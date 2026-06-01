package com.libertasprimordium.othernote.sync

import com.libertasprimordium.othernote.data.NoteRepository
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.util.nowMs

class DeleteNoteUseCase(
    private val notes: NoteRepository,
    private val nostr: NostrRepository,
) {
    suspend fun delete(note: Note, session: UserSession?, relays: List<String>): SaveResult {
        val tombstone = note.copy(bodyMarkdown = "", deleted = true, updatedAtMs = nowMs())
        if (session == null) {
            notes.upsertLocal(tombstone)
            return SaveResult.LocalOnly("Deleted locally. Relay tombstone requires a signed session.")
        }
        val eventResult = nostr.buildSignedNoteEvent(tombstone, session)
        val event = eventResult.getOrNull()
        if (event == null) {
            notes.upsertLocal(tombstone)
            return SaveResult.LocalOnly(eventResult.exceptionOrNull()?.message ?: "Crypto adapter refused tombstone publish")
        }
        val localControl = nostr.validateSignedNoteEvent(tombstone, event, session)
        if (localControl.isFailure) {
            return SaveResult.Failed(localControl.exceptionOrNull()?.message ?: "Signed tombstone failed local validation")
        }
        val publish = nostr.publish(relays, event)
        if (!publish.anySucceeded) {
            return SaveResult.Failed("No relay accepted tombstone. ${publish.statuses.toSafeMessages().joinToString("; ")}")
        }
        notes.upsertLocal(tombstone, event)
        if (publish.allSucceeded) notes.markPublished(event.id)
        return SaveResult.Published(publish.statuses.toSafeMessages())
    }
}
