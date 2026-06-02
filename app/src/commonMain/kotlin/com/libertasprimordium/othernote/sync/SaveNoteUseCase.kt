package com.libertasprimordium.othernote.sync

import com.libertasprimordium.othernote.data.LocalEventCache
import com.libertasprimordium.othernote.data.NoteRepository
import com.libertasprimordium.othernote.data.PendingWriteStore
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.domain.nextNoteVersionUpdatedAtMs
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

class SaveNoteUseCase(
    private val notes: NoteRepository,
    private val nostr: NostrRepository,
    private val localEventCache: LocalEventCache = com.libertasprimordium.othernote.data.InMemoryLocalEventCache(),
    private val pendingWriteStore: PendingWriteStore = com.libertasprimordium.othernote.data.InMemoryPendingWriteStore(),
    private val publishScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onPublishStatus: (List<RelayStatus>) -> Unit = {},
) {
    suspend fun save(
        existing: Note?,
        bodyMarkdown: String,
        session: UserSession?,
        relays: List<String>,
    ): SaveResult {
        val totalStart = TimeSource.Monotonic.markNow()
        val now = nowMs()
        val note = existing?.copy(
            bodyMarkdown = bodyMarkdown,
            updatedAtMs = nextNoteVersionUpdatedAtMs(existing.updatedAtMs, now),
            deleted = false,
        )
            ?: Note(createdAtMs = now, updatedAtMs = now, bodyMarkdown = bodyMarkdown)
        if (session == null) {
            notes.upsertLocal(note)
            return SaveResult.LocalOnly("Saved locally. Log in with an nsec to publish.")
        }
        val eventResult = nostr.buildSignedNoteEventWithDiagnostics(note, session)
        val build = eventResult.getOrNull()
        if (build == null) {
            notes.upsertLocal(note)
            return SaveResult.LocalOnly(eventResult.exceptionOrNull()?.message ?: "Crypto adapter refused publishing")
        }
        val diagnostics = build.diagnostics.toMutableList()
        val localControl = nostr.validateSignedNoteEventWithDiagnostics(note, build.event, session)
        val localControlDiagnostics = localControl.getOrNull()
        if (localControlDiagnostics == null) {
            return SaveResult.Failed(localControl.exceptionOrNull()?.message ?: "Signed event failed local validation")
        }
        diagnostics += localControlDiagnostics
        val publishStart = TimeSource.Monotonic.markNow()
        val targetRelays = relays.distinct()
        pendingWriteStore.enqueuePendingWrite(session.publicKeyHex, build.event, targetRelays)
        val publish = nostr.publishBestEffort(relays, build.event, publishScope) { statuses ->
            publishScope.launch {
                statuses.forEach { status ->
                    if (status.writable) {
                        pendingWriteStore.markRelayAccepted(build.event.id, status.url)
                    } else {
                        pendingWriteStore.markRelayRejectedOrFailed(build.event.id, status.url, status.message)
                    }
                }
                val pending = pendingWriteStore.loadPendingWrites(session.publicKeyHex).firstOrNull { it.event.id == build.event.id }
                if (pending?.isComplete == true) pendingWriteStore.removeCompletedWrite(build.event.id)
            }
            onPublishStatus(statuses)
        }
        val firstAccepted = publish.firstAccepted.await()
        diagnostics += "publish_total_ms=${publishStart.elapsedNow().inWholeMilliseconds}"
        diagnostics += "save_total_ms=${totalStart.elapsedNow().inWholeMilliseconds}"
        if (!firstAccepted.anySucceeded) {
            return SaveResult.Failed("Save failed: No relay accepted write. ${diagnostics.joinToString(" ")} ${firstAccepted.statuses.toSafeMessages().joinToString("; ")}")
        }
        firstAccepted.statuses.forEach { status ->
            if (status.writable) {
                pendingWriteStore.markRelayAccepted(build.event.id, status.url)
            } else {
                pendingWriteStore.markRelayRejectedOrFailed(build.event.id, status.url, status.message)
            }
        }
        notes.upsertLocal(note, build.event)
        localEventCache.upsertEvents(session.publicKeyHex, listOf(build.event))
        val pendingAfterFirstAccepted = pendingWriteStore.loadPendingWrites(session.publicKeyHex).firstOrNull { it.event.id == build.event.id }
        if (pendingAfterFirstAccepted?.isComplete == true) pendingWriteStore.removeCompletedWrite(build.event.id)
        publishScope.launchWhenComplete(publish.complete) { complete ->
            if (complete.allSucceeded) notes.markPublished(build.event.id)
        }
        val acceptedCount = firstAccepted.statuses.count { it.writable }
        val totalCount = relays.distinct().size
        val compactStatus = if (firstAccepted.statuses.size < totalCount) {
            "Saved to $acceptedCount/$totalCount relays; syncing others..."
        } else {
            "Saved to $acceptedCount/$totalCount relays"
        }
        return SaveResult.Published(
            listOf(compactStatus) +
                listOf("Save accepted by at least one relay ${diagnostics.joinToString(" ")}") +
                firstAccepted.statuses.toSafeMessages(),
        )
    }
}

internal fun CoroutineScope.launchWhenComplete(
    complete: kotlinx.coroutines.Deferred<com.libertasprimordium.othernote.nostr.RelayPublishResult>,
    onComplete: suspend (com.libertasprimordium.othernote.nostr.RelayPublishResult) -> Unit,
) {
    this.launch {
        runCatching { onComplete(complete.await()) }
    }
}

sealed class SaveResult {
    data class LocalOnly(val reason: String) : SaveResult()
    data class Published(val relayMessages: List<String>) : SaveResult()
    data class Failed(val reason: String) : SaveResult()
}

fun List<com.libertasprimordium.othernote.domain.RelayStatus>.toSafeMessages(): List<String> =
    map { "${it.url}: read=${it.readable} write=${it.writable} ${it.message.take(180)}" }
