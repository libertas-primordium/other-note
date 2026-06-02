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
    val selectedNotes: List<Note> = notes,
)

fun reduceNoteEvents(events: List<NostrEvent>, decrypt: (NostrEvent) -> Result<String>): ReducedNoteState {
    return reduceDecryptedNoteEvents(events) { decrypt(it) }
}

suspend fun reduceNoteEventsAsync(events: List<NostrEvent>, decrypt: suspend (NostrEvent) -> Result<String>): ReducedNoteState {
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
    return accepted.toReducedState(decryptRejected, payloadRejected, dTagRejected)
}

private fun reduceDecryptedNoteEvents(events: List<NostrEvent>, decrypt: (NostrEvent) -> Result<String>): ReducedNoteState {
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
    return accepted.toReducedState(decryptRejected, payloadRejected, dTagRejected)
}

private fun List<Pair<NostrEvent, Note>>.toReducedState(
    decryptRejected: Int,
    payloadRejected: Int,
    dTagRejected: Int,
): ReducedNoteState {
    val selected = this
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
        selectedNotes = selected.map { it.second },
    )
}

fun mergeReducedNotesWithCurrent(currentNotes: List<Note>, reduced: ReducedNoteState): List<Note> {
    val selectedById = reduced.selectedNotes.associateBy { it.id }
    val currentById = currentNotes.associateBy { it.id }
    val currentNotesToKeep = currentNotes.filter { current ->
        val selected = selectedById[current.id] ?: return@filter true
        current.isNewerMaterializationThan(selected)
    }
    val reducedNotesToApply = reduced.notes.filter { selected ->
        val current = currentById[selected.id]
        current == null || !current.isNewerMaterializationThan(selected)
    }
    return reducedNotesToApply + currentNotesToKeep
}

private fun Note.isNewerMaterializationThan(other: Note): Boolean {
    if (updatedAtMs != other.updatedAtMs) return updatedAtMs > other.updatedAtMs
    val thisSource = sourceEventId ?: return false
    val otherSource = other.sourceEventId ?: return true
    return thisSource < otherSource
}
