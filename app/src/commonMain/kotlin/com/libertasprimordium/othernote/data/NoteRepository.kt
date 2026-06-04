package com.libertasprimordium.othernote.data

import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.nostr.NostrEvent
import kotlinx.coroutines.flow.StateFlow

interface NoteRepository {
    val notes: StateFlow<List<Note>>
    val pendingEvents: StateFlow<List<NostrEvent>>
    suspend fun upsertLocal(note: Note, pendingEvent: NostrEvent? = null)
    suspend fun replaceFromSync(notes: List<Note>)
    suspend fun markPublished(eventId: String)
    suspend fun clear()
}
