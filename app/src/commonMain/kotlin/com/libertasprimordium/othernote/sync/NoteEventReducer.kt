package com.libertasprimordium.othernote.sync

import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.toNote
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec

data class ReducedNoteState(
    val notes: List<Note>,
    val selectedEvents: List<NostrEvent>,
    val selectedNoteIds: Set<String>,
    val rejectedCount: Int,
)

fun reduceNoteEvents(events: List<NostrEvent>, decrypt: (NostrEvent) -> Result<String>): ReducedNoteState {
    val accepted = mutableListOf<Pair<NostrEvent, Note>>()
    var rejected = 0
    events.filter { it.isOtherNoteEvent() && it.dTag() != null }.forEach { event ->
        val note = decrypt(event)
            .mapCatching { JsonNotePayloadCodec.decode(it).getOrThrow().toNote(event.id) }
            .getOrNull()
        if (note == null || event.dTag() != note.dTag) {
            rejected++
        } else {
            accepted += event to note
        }
    }

    val selected = accepted
        .groupBy { it.second.dTag }
        .values
        .map { versions ->
            versions
                .sortedWith(compareByDescending<Pair<NostrEvent, Note>> { it.first.createdAt }.thenBy { it.first.id })
                .first()
        }
    return ReducedNoteState(
        notes = selected.map { it.second }.filterNot { it.deleted }.sortedByDescending { it.updatedAtMs },
        selectedEvents = selected.map { it.first },
        selectedNoteIds = selected.map { it.second.id }.toSet(),
        rejectedCount = rejected,
    )
}
