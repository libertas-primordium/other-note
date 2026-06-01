package com.libertasprimordium.othernote.sync

import com.libertasprimordium.othernote.data.NoteRepository
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.util.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.TimeSource

class DeleteNoteUseCase(
    private val notes: NoteRepository,
    private val nostr: NostrRepository,
    private val publishScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onPublishStatus: (List<RelayStatus>) -> Unit = {},
) {
    suspend fun delete(note: Note, session: UserSession?, relays: List<String>): SaveResult {
        val totalStart = TimeSource.Monotonic.markNow()
        val tombstone = note.copy(bodyMarkdown = "", deleted = true, updatedAtMs = nowMs())
        if (session == null) {
            notes.upsertLocal(tombstone)
            return SaveResult.LocalOnly("Deleted locally. Relay tombstone requires a signed session.")
        }
        val eventResult = nostr.buildSignedNoteEventWithDiagnostics(tombstone, session)
        val build = eventResult.getOrNull()
        if (build == null) {
            notes.upsertLocal(tombstone)
            return SaveResult.LocalOnly(eventResult.exceptionOrNull()?.message ?: "Crypto adapter refused tombstone publish")
        }
        val diagnostics = build.diagnostics.toMutableList()
        val localControl = nostr.validateSignedNoteEventWithDiagnostics(tombstone, build.event, session)
        val localControlDiagnostics = localControl.getOrNull()
        if (localControlDiagnostics == null) {
            return SaveResult.Failed(localControl.exceptionOrNull()?.message ?: "Signed tombstone failed local validation")
        }
        diagnostics += localControlDiagnostics
        val publishStart = TimeSource.Monotonic.markNow()
        val publish = nostr.publishBestEffort(relays, build.event, publishScope, onPublishStatus)
        val firstAccepted = publish.firstAccepted.await()
        diagnostics += "publish_total_ms=${publishStart.elapsedNow().inWholeMilliseconds}"
        diagnostics += "delete_total_ms=${totalStart.elapsedNow().inWholeMilliseconds}"
        if (!firstAccepted.anySucceeded) {
            return SaveResult.Failed("Delete failed: No relay accepted tombstone. ${diagnostics.joinToString(" ")} ${firstAccepted.statuses.toSafeMessages().joinToString("; ")}")
        }
        notes.upsertLocal(tombstone, build.event)
        publishScope.launchWhenComplete(publish.complete) { complete ->
            if (complete.allSucceeded) notes.markPublished(build.event.id)
        }
        return SaveResult.Published(listOf("Delete accepted by at least one relay ${diagnostics.joinToString(" ")}") + firstAccepted.statuses.toSafeMessages())
    }
}
