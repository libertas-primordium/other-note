package com.libertasprimordium.othernote.nostr

import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.OtherNoteTag

data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun dTag(): String? = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)
    fun isOtherNoteEvent(): Boolean =
        kind == NoteKind && tags.any { it.size >= 2 && it[0] == "t" && it[1] == OtherNoteTag }
}

data class UnsignedNostrEvent(
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)

fun noteEventTags(dTag: String): List<List<String>> = listOf(
    listOf("d", dTag),
    listOf("t", OtherNoteTag),
    listOf("alt", "Encrypted Other Note note"),
    listOf("client", "Other Note"),
)
