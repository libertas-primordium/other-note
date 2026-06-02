package com.libertasprimordium.othernote.sync

import com.libertasprimordium.othernote.data.LocalEventCache
import com.libertasprimordium.othernote.data.PendingWriteStore
import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.OtherNoteTag
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrRepository

data class RelayMigrationPlan(
    val oldRelays: List<String>,
    val newRelays: List<String>,
    val addedRelays: List<String>,
    val removedRelays: List<String>,
) {
    val unchangedRelays: List<String> get() = newRelays.filter { it in oldRelays }
    val migrationRequired: Boolean get() = addedRelays.isNotEmpty() || removedRelays.isNotEmpty()
    val shouldFetchBeforeRemoval: Boolean get() = removedRelays.isNotEmpty()
    val shouldRepublishCurrentEvents: Boolean get() = addedRelays.isNotEmpty()
}

fun planRelayMigration(oldRelays: List<String>, newRelays: List<String>): RelayMigrationPlan {
    val oldDistinct = oldRelays.distinct()
    val newDistinct = newRelays.distinct()
    return RelayMigrationPlan(
        oldRelays = oldDistinct,
        newRelays = newDistinct,
        addedRelays = newDistinct.filterNot { it in oldDistinct },
        removedRelays = oldDistinct.filterNot { it in newDistinct },
    )
}

class MigrateRelaysUseCase {
    suspend fun migrate(oldRelays: List<String>, newRelays: List<String>): RelayMigrationPlan =
        planRelayMigration(oldRelays, newRelays)
}

data class RelayMigrationExecutionResult(
    val plan: RelayMigrationPlan,
    val fetchStatuses: List<RelayStatus>,
    val fetchedEventCount: Int,
    val cacheEventCount: Int,
    val latestEvents: List<NostrEvent>,
    val publishStatusesByEventId: Map<String, List<RelayStatus>>,
    val warnings: List<String>,
) {
    val fullSuccess: Boolean get() = warnings.isEmpty()
    val selectedEventCount: Int get() = latestEvents.size
    val failedAddedRelays: List<String> get() =
        publishStatusesByEventId.values
            .flatten()
            .filter { !it.writable }
            .map { it.url }
            .distinct()

    fun safeSummary(): String =
        buildString {
            append("Relay migration ")
            append(if (fullSuccess) "completed" else "needs review")
            append(". fetched_events=$fetchedEventCount cached_events=$cacheEventCount selected_events=$selectedEventCount")
            if (plan.addedRelays.isNotEmpty()) append(" added=${plan.addedRelays.joinToString()}")
            if (plan.removedRelays.isNotEmpty()) append(" removed=${plan.removedRelays.joinToString()}")
        }

    fun safeDetails(): String =
        buildList {
            add("fetch_relays=${(plan.oldRelays).joinToString()}")
            add("fetch_statuses=${fetchStatuses.toSafeMigrationSummary()}")
            add("publish_statuses=${publishStatusesByEventId.values.flatten().toSafeMigrationSummary()}")
            warnings.forEach { add(it) }
        }.filter { it.isNotBlank() }.joinToString("\n").take(1_500)
}

class RelayMigrationUseCase(
    private val nostr: NostrRepository,
    private val crypto: NostrCrypto,
    private val localEventCache: LocalEventCache,
) {
    suspend fun execute(
        session: UserSession,
        plan: RelayMigrationPlan,
    ): RelayMigrationExecutionResult {
        val fetched = if (plan.migrationRequired && plan.oldRelays.isNotEmpty()) {
            nostr.fetch(plan.oldRelays, session.publicKeyHex)
        } else {
            com.libertasprimordium.othernote.nostr.RelayFetchResult(emptyList(), emptyList())
        }
        val cacheableFetched = fetched.events.validCacheableNoteEvents(session.publicKeyHex, crypto)
        if (cacheableFetched.isNotEmpty()) {
            localEventCache.upsertEvents(session.publicKeyHex, cacheableFetched)
        }
        val cachedEvents = localEventCache.loadEvents(session.publicKeyHex)
        val latestEvents = selectLatestSignedEncryptedNoteEvents(
            events = cachedEvents + cacheableFetched,
            accountPubkey = session.publicKeyHex,
            crypto = crypto,
        )
        val publishStatuses = linkedMapOf<String, List<RelayStatus>>()
        if (plan.shouldRepublishCurrentEvents && latestEvents.isNotEmpty()) {
            latestEvents.forEach { event ->
                publishStatuses[event.id] = nostr.publish(plan.addedRelays, event).statuses
            }
        }
        val warnings = buildList {
            val failedFetches = fetched.statuses.filter { !it.readable }
            if (failedFetches.isNotEmpty()) {
                add("Some current relays could not be fetched before migration: ${failedFetches.toSafeMigrationSummary()}")
            }
            if (plan.shouldRepublishCurrentEvents && latestEvents.isEmpty() && (cachedEvents.isNotEmpty() || cacheableFetched.isNotEmpty())) {
                add("No signed encrypted note events were available to republish to added relays.")
            }
            val failedPublishes = publishStatuses.values.flatten().filter { !it.writable }
            if (failedPublishes.isNotEmpty()) {
                add("Some added relays did not accept republished encrypted events: ${failedPublishes.toSafeMigrationSummary()}")
            }
        }
        return RelayMigrationExecutionResult(
            plan = plan,
            fetchStatuses = fetched.statuses,
            fetchedEventCount = fetched.events.distinctBy { it.id }.size,
            cacheEventCount = cachedEvents.distinctBy { it.id }.size,
            latestEvents = latestEvents,
            publishStatusesByEventId = publishStatuses,
            warnings = warnings,
        )
    }
}

fun selectLatestSignedEncryptedNoteEvents(
    events: List<NostrEvent>,
    accountPubkey: String,
    crypto: NostrCrypto,
): List<NostrEvent> =
    events.validCacheableNoteEvents(accountPubkey, crypto)
        .groupBy { event -> "${event.pubkey}|${event.kind}|${event.dTag().orEmpty()}" }
        .values
        .map { versions ->
            versions
                .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
                .first()
        }
        .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })

private fun List<NostrEvent>.validCacheableNoteEvents(accountPubkey: String, crypto: NostrCrypto): List<NostrEvent> =
    distinctBy { it.id }.filter { event ->
        event.pubkey == accountPubkey &&
            event.kind == NoteKind &&
            event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == OtherNoteTag } &&
            event.dTag() != null &&
            crypto.validate(event).getOrDefault(false)
    }

suspend fun queueRelayMigrationPendingWrites(
    accountPubkey: String,
    result: RelayMigrationExecutionResult,
    pendingWriteStore: PendingWriteStore,
) {
    result.latestEvents.forEach { event ->
        val failedRelays = result.publishStatusesByEventId[event.id].orEmpty()
            .filter { !it.writable }
            .map { it.url }
            .distinct()
        if (failedRelays.isNotEmpty()) {
            pendingWriteStore.enqueuePendingWrite(accountPubkey, event, failedRelays)
            failedRelays.forEach { relay ->
                val status = result.publishStatusesByEventId[event.id].orEmpty().firstOrNull { it.url == relay }
                pendingWriteStore.markRelayRejectedOrFailed(event.id, relay, status?.message ?: "relay migration publish failed")
            }
        }
    }
}

private fun List<RelayStatus>.toSafeMigrationSummary(): String =
    if (isEmpty()) {
        "none"
    } else {
        joinToString(" | ") { status ->
            "${status.url} read=${status.readable} write=${status.writable} ${status.message.take(160)}"
        }
    }
