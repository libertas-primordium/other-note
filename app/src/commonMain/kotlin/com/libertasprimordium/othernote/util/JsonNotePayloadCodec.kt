package com.libertasprimordium.othernote.util

import com.libertasprimordium.othernote.domain.NotePayload
import com.libertasprimordium.othernote.domain.NotePayloadSchema

object JsonNotePayloadCodec {
    fun encode(payload: NotePayload): String = buildString {
        append("{")
        appendJsonString("schema", payload.schema)
        append(',')
        appendJsonString("note_id", payload.noteId)
        append(',')
        appendJsonNumber("created_at_ms", payload.createdAtMs)
        append(',')
        appendJsonNumber("updated_at_ms", payload.updatedAtMs)
        append(',')
        appendJsonString("body_markdown", payload.bodyMarkdown)
        append(',')
        append("\"deleted\":${payload.deleted}")
        append("}")
    }

    fun decode(json: String): Result<NotePayload> = runCatching {
        val fields = parseFlatJsonObject(json)
        val schema = fields["schema"] as? String ?: error("Missing schema")
        require(schema == NotePayloadSchema) { "Unsupported note payload schema: $schema" }
        NotePayload(
            schema = schema,
            noteId = fields["note_id"] as? String ?: error("Missing note_id"),
            createdAtMs = (fields["created_at_ms"] as? Long) ?: error("Missing created_at_ms"),
            updatedAtMs = (fields["updated_at_ms"] as? Long) ?: error("Missing updated_at_ms"),
            bodyMarkdown = fields["body_markdown"] as? String ?: error("Missing body_markdown"),
            deleted = fields["deleted"] as? Boolean ?: error("Missing deleted"),
        )
    }

    private fun StringBuilder.appendJsonString(name: String, value: String) {
        append('"').append(name).append("\":\"").append(escape(value)).append('"')
    }

    private fun StringBuilder.appendJsonNumber(name: String, value: Long) {
        append('"').append(name).append("\":").append(value)
    }

    private fun escape(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private fun parseFlatJsonObject(json: String): Map<String, Any> {
        val trimmed = json.trim()
        require(trimmed.startsWith('{') && trimmed.endsWith('}')) { "Expected JSON object" }
        val fields = mutableMapOf<String, Any>()
        var index = 1
        while (index < trimmed.lastIndex) {
            index = skipWhitespaceAndComma(trimmed, index)
            if (index >= trimmed.lastIndex) break
            val key = readString(trimmed, index)
            index = key.next
            index = skipWhitespace(trimmed, index)
            require(trimmed.getOrNull(index) == ':') { "Expected ':'" }
            index = skipWhitespace(trimmed, index + 1)
            val value = when (trimmed.getOrNull(index)) {
                '"' -> readString(trimmed, index).also { index = it.next }.value
                't' -> true.also { index += 4 }
                'f' -> false.also { index += 5 }
                else -> {
                    val start = index
                    while (trimmed.getOrNull(index)?.isDigit() == true) index++
                    trimmed.substring(start, index).toLong()
                }
            }
            fields[key.value] = value
        }
        return fields
    }

    private data class StringRead(val value: String, val next: Int)

    private fun readString(input: String, start: Int): StringRead {
        require(input[start] == '"') { "Expected string" }
        val out = StringBuilder()
        var index = start + 1
        while (index < input.length) {
            val ch = input[index++]
            when (ch) {
                '"' -> return StringRead(out.toString(), index)
                '\\' -> {
                    val escaped = input[index++]
                    out.append(
                        when (escaped) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            '"', '\\' -> escaped
                            else -> escaped
                        },
                    )
                }
                else -> out.append(ch)
            }
        }
        error("Unterminated string")
    }

    private fun skipWhitespace(input: String, start: Int): Int {
        var index = start
        while (input.getOrNull(index)?.isWhitespace() == true) index++
        return index
    }

    private fun skipWhitespaceAndComma(input: String, start: Int): Int {
        var index = start
        while (input.getOrNull(index)?.let { it.isWhitespace() || it == ',' } == true) index++
        return index
    }
}
