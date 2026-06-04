package com.libertasprimordium.othernote.data

import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class InMemoryNoteRepository : NoteRepository {
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    override val notes: StateFlow<List<Note>> = _notes

    private val _pendingEvents = MutableStateFlow<List<NostrEvent>>(emptyList())
    override val pendingEvents: StateFlow<List<NostrEvent>> = _pendingEvents

    override suspend fun upsertLocal(note: Note, pendingEvent: NostrEvent?) {
        _notes.update { current ->
            (current.filterNot { it.id == note.id } + note)
                .filterNot { it.deleted }
                .sortedByDescending { it.updatedAtMs }
        }
        if (pendingEvent != null) {
            _pendingEvents.update { events -> (events.filterNot { it.id == pendingEvent.id } + pendingEvent) }
        }
    }

    override suspend fun replaceFromSync(notes: List<Note>) {
        _notes.value = notes.filterNot { it.deleted }.sortedByDescending { it.updatedAtMs }
    }

    override suspend fun markPublished(eventId: String) {
        _pendingEvents.update { events -> events.filterNot { it.id == eventId } }
    }

    override suspend fun clear() {
        _notes.value = emptyList()
        _pendingEvents.value = emptyList()
    }
}
