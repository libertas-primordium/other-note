package com.libertasprimordium.othernote.sync

import com.libertasprimordium.othernote.data.LocalEventCache
import com.libertasprimordium.othernote.data.NoteRepository
import com.libertasprimordium.othernote.data.PendingWriteMaxRetryCount
import com.libertasprimordium.othernote.data.PendingWriteStore
import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.OtherNoteTag
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.SyncState
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

class SyncNotesUseCase(
    private val notes: NoteRepository,
    private val nostr: NostrRepository,
    private val crypto: NostrCrypto,
    private val localEventCache: LocalEventCache = com.libertasprimordium.othernote.data.InMemoryLocalEventCache(),
    private val pendingWriteStore: PendingWriteStore = com.libertasprimordium.othernote.data.InMemoryPendingWriteStore(),
    private val publishScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    suspend fun sync(
        session: UserSession?,
        relays: List<String>,
        onPartialState: suspend (SyncState) -> Unit = {},
    ): SyncState {
        val totalStart = TimeSource.Monotonic.markNow()
        if (session == null) return SyncState(errors = listOf("Log in before syncing relays"))
        if (!crypto.productionReady) {
            return SyncState(warnings = listOf("Production Nostr crypto is not wired; relay sync is disabled"))
        }
        val distinctRelays = relays.distinct()
        logSafeSync("start account=${session.publicKeyHex.take(12)} relays=${distinctRelays.size} note_count_before=${notes.notes.value.size}")
        val aggregateEvents = mutableListOf<com.libertasprimordium.othernote.nostr.NostrEvent>()
        val aggregateStatuses = linkedMapOf<String, com.libertasprimordium.othernote.domain.RelayStatus>()
        var lastAppliedEventIds = emptySet<String>()
        var lastAppliedState: SyncState? = null
        val fetchStart = TimeSource.Monotonic.markNow()

        suspend fun applyOrReuseFetchedEvents(final: Boolean): SyncState {
            val statuses = aggregateStatuses.values.toList()
            val uniqueEventIds = aggregateEvents.mapTo(linkedSetOf()) { it.id }
            val previous = lastAppliedState
            val shouldApply = previous == null ||
                uniqueEventIds != lastAppliedEventIds ||
                (previous.errors.isNotEmpty() && statuses.any { it.readable })
            val state = if (shouldApply) {
                applyFetchedEvents(
                    session = session,
                    events = aggregateEvents,
                    statuses = statuses,
                    fetchDurationMs = fetchStart.elapsedNow().inWholeMilliseconds,
                    totalStart = totalStart,
                    final = final,
                ).also {
                    lastAppliedEventIds = uniqueEventIds
                    lastAppliedState = it
                }
            } else {
                previous.withSameFetchedEvents(
                    statuses = statuses,
                    totalStart = totalStart,
                    final = final,
                ).also {
                    lastAppliedState = it
                }
            }
            logSafeSync(
                "state final=$final applied=$shouldApply fetched_events=${uniqueEventIds.size} " +
                    "note_count=${notes.notes.value.size} relay_statuses=${statuses.safeRelayLogSummary()}",
            )
            return state
        }

        val cachedEvents = localEventCache.loadEvents(session.publicKeyHex)
        if (cachedEvents.isNotEmpty()) {
            aggregateEvents += cachedEvents
            logSafeSync("cache_load account=${session.publicKeyHex.take(12)} cached_events=${cachedEvents.distinctBy { it.id }.size}")
            val cachedState = applyOrReuseFetchedEvents(final = false)
            onPartialState(cachedState.copy(warnings = listOf("Loaded cached notes") + cachedState.warnings))
        }
        if (pendingWriteStore.loadPendingWrites(session.publicKeyHex).isNotEmpty()) {
            onPartialState(SyncState(warnings = listOf("Retrying pending writes...")))
        }
        retryPendingWrites(session)
        val fetch = nostr.fetchIncrementally(distinctRelays, session.publicKeyHex) { partial ->
            aggregateEvents += partial.events
            partial.statuses.forEach { aggregateStatuses[it.url] = it }
            val cacheablePartialEvents = validatedCacheableEvents(session.publicKeyHex, partial.events)
            localEventCache.upsertEvents(session.publicKeyHex, cacheablePartialEvents)
            logSafeSync(
                "relay_result events=${partial.events.distinctBy { it.id }.size} cacheable_events=${cacheablePartialEvents.size} " +
                    "statuses=${partial.statuses.safeRelayLogSummary()}",
            )
            val partialState = applyOrReuseFetchedEvents(final = false)
            onPartialState(partialState)
        }
        aggregateEvents += fetch.events.filterNot { fetched -> aggregateEvents.any { it.id == fetched.id } }
        fetch.statuses.forEach { aggregateStatuses[it.url] = it }
        val finalState = applyOrReuseFetchedEvents(final = true)
        val cacheableEvents = validatedCacheableEvents(session.publicKeyHex, aggregateEvents)
        localEventCache.upsertEvents(session.publicKeyHex, cacheableEvents)
        logSafeSync(
            "cache_upsert final=true received_events=${aggregateEvents.distinctBy { it.id }.size} " +
                "cacheable_events=${cacheableEvents.size}",
        )
        notes.pendingEvents.value.forEach { pending ->
            val publish = nostr.publishBestEffort(distinctRelays, pending, publishScope) {}
            publishScope.launchWhenComplete(publish.complete) { complete ->
                if (complete.allSucceeded) notes.markPublished(pending.id)
            }
        }
        logSafeSync(
            "end account=${session.publicKeyHex.take(12)} relays=${distinctRelays.size} " +
                "note_count_after=${notes.notes.value.size} relay_statuses=${finalState.relayStatuses.safeRelayLogSummary()}",
        )
        return finalState
    }

    private fun retryPendingWrites(session: UserSession) {
        publishScope.launch {
            val pendingWrites = pendingWriteStore.loadPendingWrites(session.publicKeyHex)
            pendingWrites.forEach { pending ->
                val retryRelays = pending.unfinishedRelays
                    .filter { relay -> (pending.retryCounts[relay] ?: 0) < PendingWriteMaxRetryCount }
                retryRelays.forEach { relay -> pendingWriteStore.recordRetry(pending.event.id, relay) }
                if (retryRelays.isNotEmpty()) {
                    val publish = nostr.publishBestEffort(retryRelays, pending.event, publishScope) { statuses ->
                        publishScope.launch {
                            updatePendingWriteStatuses(session.publicKeyHex, pending.event.id, statuses)
                        }
                    }
                    publish.complete.await()
                    val updated = pendingWriteStore.loadPendingWrites(session.publicKeyHex).firstOrNull { it.event.id == pending.event.id }
                    if (updated?.isComplete == true || updated?.isTerminallyExhausted(PendingWriteMaxRetryCount) == true) {
                        pendingWriteStore.removeCompletedWrite(pending.event.id)
                    }
                } else if (pending.isTerminallyExhausted(PendingWriteMaxRetryCount)) {
                    pendingWriteStore.removeCompletedWrite(pending.event.id)
                }
            }
        }
    }

    private suspend fun updatePendingWriteStatuses(accountPubkey: String, eventId: String, statuses: List<RelayStatus>) {
        statuses.forEach { status ->
            if (status.writable) {
                pendingWriteStore.markRelayAccepted(eventId, status.url)
            } else {
                pendingWriteStore.markRelayRejectedOrFailed(eventId, status.url, status.message)
            }
        }
        val pending = pendingWriteStore.loadPendingWrites(accountPubkey).firstOrNull { it.event.id == eventId }
        if (pending?.isComplete == true || pending?.isTerminallyExhausted(PendingWriteMaxRetryCount) == true) {
            pendingWriteStore.removeCompletedWrite(eventId)
        }
    }

    private fun validatedCacheableEvents(accountPubkey: String, events: List<NostrEvent>): List<NostrEvent> =
        events.distinctBy { it.id }.filter { event ->
            event.pubkey == accountPubkey &&
                event.kind == NoteKind &&
                event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == OtherNoteTag } &&
                event.dTag() != null &&
                crypto.validate(event).getOrDefault(false)
        }

    private suspend fun applyFetchedEvents(
        session: UserSession,
        events: List<com.libertasprimordium.othernote.nostr.NostrEvent>,
        statuses: List<com.libertasprimordium.othernote.domain.RelayStatus>,
        fetchDurationMs: Long,
        totalStart: TimeSource.Monotonic.ValueTimeMark,
        final: Boolean,
    ): SyncState {
        if (statuses.isNotEmpty() && statuses.none { it.readable }) {
            return SyncState(
                relayStatuses = statuses,
                errors = listOf(
                    "Sync ${if (final) "failed" else "waiting"}: all completed relays failed or timed out. fetched_events=0 valid_events=0 total_ms=${totalStart.elapsedNow().inWholeMilliseconds}; local notes were preserved",
                ),
            )
        }
        val privateKey = NostrPrivateKey(session.privateKeyHex)
        val publicKey = NostrPublicKey(session.publicKeyHex, session.npub)
        var wrongAuthorCount = 0
        var wrongKindCount = 0
        var missingTTagCount = 0
        var missingDTagCount = 0
        var invalidSignatureCount = 0
        val validateStart = TimeSource.Monotonic.markNow()
        val uniqueEvents = events.distinctBy { it.id }
        val candidateEvents = uniqueEvents.mapNotNull { event ->
            when {
                event.pubkey != session.publicKeyHex -> {
                    wrongAuthorCount++
                    null
                }
                event.kind != NoteKind -> {
                    wrongKindCount++
                    null
                }
                !event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == OtherNoteTag } -> {
                    missingTTagCount++
                    null
                }
                event.dTag() == null -> {
                    missingDTagCount++
                    null
                }
                !crypto.validate(event).getOrDefault(false) -> {
                    invalidSignatureCount++
                    null
                }
                else -> event
            }
        }
        val validateDurationMs = validateStart.elapsedNow().inWholeMilliseconds
        val reduceStart = TimeSource.Monotonic.markNow()
        val reduced = reduceNoteEvents(candidateEvents) { event ->
            crypto.decryptFromSelf(event.content, privateKey, publicKey)
        }
        val reduceDurationMs = reduceStart.elapsedNow().inWholeMilliseconds
        notes.replaceFromSync(mergeReducedNotesWithCurrent(notes.notes.value, reduced))
        val appliedNoteCount = notes.notes.value.size
        val fetchedCount = uniqueEvents.size
        val preDecryptRejectedCount = wrongAuthorCount + wrongKindCount + missingTTagCount + missingDTagCount + invalidSignatureCount
        val rejectedCount = preDecryptRejectedCount + reduced.rejectedCount
        val warnings = buildList {
            add(
                "Sync complete total_ms=${totalStart.elapsedNow().inWholeMilliseconds} fetch_ms=$fetchDurationMs " +
                    "validate_ms=$validateDurationMs decrypt_reduce_ms=$reduceDurationMs fetched_events=$fetchedCount " +
                    "candidate_events=${candidateEvents.size} selected_events=${reduced.selectedEvents.size} visible_notes=${reduced.notes.size} applied_notes=$appliedNoteCount " +
                    "rejected_wrong_author=$wrongAuthorCount rejected_wrong_kind=$wrongKindCount rejected_missing_t=$missingTTagCount " +
                    "rejected_missing_d=$missingDTagCount rejected_validation=$invalidSignatureCount rejected_decrypt=${reduced.decryptRejectedCount} " +
                    "rejected_payload=${reduced.payloadRejectedCount} rejected_dtag=${reduced.dTagRejectedCount}",
            )
            when {
                fetchedCount == 0 -> add("No relay returned Other Note events")
                fetchedCount > 0 && reduced.selectedEvents.isEmpty() -> add("Relays returned events, but all were rejected")
                reduced.selectedEvents.isNotEmpty() && reduced.notes.isEmpty() -> add("Latest selected events are tombstones; matching notes are hidden")
            }
            if (rejectedCount > 0) add("Rejected $rejectedCount invalid, malformed, or undecryptable events")
            if (statuses.any { !it.readable }) add("Partial relay read failure; local notes were preserved")
        }
        return SyncState(lastSyncMs = if (final) nowMs() else null, relayStatuses = statuses, warnings = warnings)
    }

    private fun SyncState.withSameFetchedEvents(
        statuses: List<RelayStatus>,
        totalStart: TimeSource.Monotonic.ValueTimeMark,
        final: Boolean,
    ): SyncState {
        if (statuses.isNotEmpty() && statuses.none { it.readable }) {
            return SyncState(
                relayStatuses = statuses,
                errors = listOf(
                    "Sync ${if (final) "failed" else "waiting"}: all completed relays failed or timed out. fetched_events=0 valid_events=0 total_ms=${totalStart.elapsedNow().inWholeMilliseconds}; local notes were preserved",
                ),
            )
        }
        return copy(
            lastSyncMs = if (final) nowMs() else null,
            relayStatuses = statuses,
            warnings = warnings.withPartialRelayReadWarning(statuses),
            errors = emptyList(),
        )
    }

    private fun List<String>.withPartialRelayReadWarning(statuses: List<RelayStatus>): List<String> {
        val warning = "Partial relay read failure; local notes were preserved"
        return if (statuses.any { !it.readable } && warning !in this) this + warning else this
    }
}
