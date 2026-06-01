package com.libertasprimordium.othernote.sync

import com.libertasprimordium.othernote.data.NoteRepository
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.util.nowMs

class SaveNoteUseCase(
    private val notes: NoteRepository,
    private val nostr: NostrRepository,
) {
    suspend fun save(
        existing: Note?,
        bodyMarkdown: String,
        session: UserSession?,
        relays: List<String>,
    ): SaveResult {
        val now = nowMs()
        val note = existing?.copy(bodyMarkdown = bodyMarkdown, updatedAtMs = now, deleted = false)
            ?: Note(createdAtMs = now, updatedAtMs = now, bodyMarkdown = bodyMarkdown)
        if (session == null) {
            notes.upsertLocal(note)
            return SaveResult.LocalOnly("Saved locally. Log in with an nsec to publish.")
        }
        val eventResult = nostr.buildSignedNoteEvent(note, session)
        val event = eventResult.getOrNull()
        if (event == null) {
            notes.upsertLocal(note)
            return SaveResult.LocalOnly(eventResult.exceptionOrNull()?.message ?: "Crypto adapter refused publishing")
        }
        val localControl = nostr.validateSignedNoteEvent(note, event, session)
        if (localControl.isFailure) {
            return SaveResult.Failed(localControl.exceptionOrNull()?.message ?: "Signed event failed local validation")
        }
        val publish = nostr.publish(relays, event)
        if (!publish.anySucceeded) {
            return SaveResult.Failed("No relay accepted write. ${publish.statuses.toSafeMessages().joinToString("; ")}")
        }
        notes.upsertLocal(note, event)
        if (publish.allSucceeded) notes.markPublished(event.id)
        return SaveResult.Published(publish.statuses.toSafeMessages())
    }
}

sealed class SaveResult {
    data class LocalOnly(val reason: String) : SaveResult()
    data class Published(val relayMessages: List<String>) : SaveResult()
    data class Failed(val reason: String) : SaveResult()
}

fun List<com.libertasprimordium.othernote.domain.RelayStatus>.toSafeMessages(): List<String> =
    map { "${it.url}: read=${it.readable} write=${it.writable} ${it.message.take(180)}" }
