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
        val next = notes
            .filterNot { it.deleted }
            .latestVersionByNoteId()
            .sortedByDescending { it.updatedAtMs }
        if (_notes.value != next) {
            _notes.value = next
        }
    }

    override suspend fun markPublished(eventId: String) {
        _pendingEvents.update { events -> events.filterNot { it.id == eventId } }
    }

    override suspend fun clear() {
        _notes.value = emptyList()
        _pendingEvents.value = emptyList()
    }
}

private fun List<Note>.latestVersionByNoteId(): List<Note> =
    groupBy { it.id }.values.map { versions ->
        versions.maxWith(noteVersionComparator)
    }

private val noteVersionComparator: Comparator<Note> = Comparator { left, right ->
    when {
        left.updatedAtMs != right.updatedAtMs -> left.updatedAtMs.compareTo(right.updatedAtMs)
        left.sourceEventId == right.sourceEventId -> 0
        left.sourceEventId == null -> -1
        right.sourceEventId == null -> 1
        else -> right.sourceEventId.compareTo(left.sourceEventId)
    }
}
