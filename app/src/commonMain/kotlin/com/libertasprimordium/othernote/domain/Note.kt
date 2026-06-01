package com.libertasprimordium.othernote.domain

import com.libertasprimordium.othernote.util.stableRandomId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val NotePayloadSchema = "com.libertasprimordium.othernote.note.v1"
const val OtherNoteTag = "other-note"
const val NoteKind = 30078

data class Note(
    val id: String = stableRandomId(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val bodyMarkdown: String,
    val deleted: Boolean = false,
    val sourceEventId: String? = null,
) {
    val dTag: String get() = noteDTag(id)
}

@Serializable
data class NotePayload(
    val schema: String = NotePayloadSchema,
    @SerialName("note_id")
    val noteId: String,
    @SerialName("created_at_ms")
    val createdAtMs: Long,
    @SerialName("updated_at_ms")
    val updatedAtMs: Long,
    @SerialName("body_markdown")
    val bodyMarkdown: String,
    val deleted: Boolean,
)

fun noteDTag(noteId: String): String = "other-note:note:$noteId"

fun Note.toPayload(): NotePayload = NotePayload(
    noteId = id,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    bodyMarkdown = bodyMarkdown,
    deleted = deleted,
)

fun NotePayload.toNote(sourceEventId: String? = null): Note = Note(
    id = noteId,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    bodyMarkdown = bodyMarkdown,
    deleted = deleted,
    sourceEventId = sourceEventId,
)
