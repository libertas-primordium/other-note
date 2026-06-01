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
    val decryptRejectedCount: Int = 0,
    val payloadRejectedCount: Int = 0,
    val dTagRejectedCount: Int = 0,
)

fun reduceNoteEvents(events: List<NostrEvent>, decrypt: (NostrEvent) -> Result<String>): ReducedNoteState {
    val accepted = mutableListOf<Pair<NostrEvent, Note>>()
    var decryptRejected = 0
    var payloadRejected = 0
    var dTagRejected = 0
    events.filter { it.isOtherNoteEvent() && it.dTag() != null }.forEach { event ->
        val decrypted = decrypt(event).getOrNull()
        if (decrypted == null) {
            decryptRejected++
            return@forEach
        }
        val payload = JsonNotePayloadCodec.decode(decrypted).getOrNull()
        if (payload == null) {
            payloadRejected++
            return@forEach
        }
        val note = payload.toNote(event.id)
        if (event.dTag() != note.dTag) {
            dTagRejected++
            return@forEach
        }
        accepted += event to note
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
        rejectedCount = decryptRejected + payloadRejected + dTagRejected,
        decryptRejectedCount = decryptRejected,
        payloadRejectedCount = payloadRejected,
        dTagRejectedCount = dTagRejected,
    )
}
