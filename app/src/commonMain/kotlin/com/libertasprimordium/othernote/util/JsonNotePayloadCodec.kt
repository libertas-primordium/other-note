package com.libertasprimordium.othernote.util

import com.libertasprimordium.othernote.domain.NotePayload
import com.libertasprimordium.othernote.domain.NotePayloadSchema
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object JsonNotePayloadCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(payload: NotePayload): String = json.encodeToString(payload)

    fun decode(json: String): Result<NotePayload> = runCatching {
        val payload = this.json.decodeFromString<NotePayload>(json)
        require(payload.schema == NotePayloadSchema) { "Unsupported note payload schema: ${payload.schema}" }
        payload
    }
}
