package com.libertasprimordium.othernote.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

object NostrEventSerialization {
    fun canonicalPreimage(unsigned: UnsignedNostrEvent): String {
        val preimage = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(unsigned.pubkey))
            add(JsonPrimitive(unsigned.createdAt))
            add(JsonPrimitive(unsigned.kind))
            add(tagsToJson(unsigned.tags))
            add(JsonPrimitive(unsigned.content))
        }
        return preimage.toString()
    }

    private fun tagsToJson(tags: List<List<String>>): JsonArray = buildJsonArray {
        tags.forEach { tag ->
            add(
                buildJsonArray {
                    tag.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
    }
}
