package com.libertasprimordium.othernote.nostr

import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.OtherNoteTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class NostrFilter(
    val authors: List<String> = emptyList(),
    val kinds: List<Int> = listOf(NoteKind),
    val tTags: List<String> = listOf(OtherNoteTag),
    val pTags: List<String> = emptyList(),
    val since: Long? = null,
    val limit: Int = 200,
)

sealed interface NostrRelayMessage {
    data class Event(val subscriptionId: String, val event: NostrEvent) : NostrRelayMessage
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : NostrRelayMessage
    data class Eose(val subscriptionId: String) : NostrRelayMessage
    data class Closed(val subscriptionId: String, val message: String) : NostrRelayMessage
    data class Notice(val message: String) : NostrRelayMessage
}

object NostrWireJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun publishEventMessage(event: NostrEvent): String =
        buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(eventObject(event))
        }.toString()

    fun requestMessage(subscriptionId: String, filter: NostrFilter): String =
        buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subscriptionId))
            add(filterObject(filter))
        }.toString()

    fun closeMessage(subscriptionId: String): String =
        buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(subscriptionId))
        }.toString()

    fun parseRelayMessage(raw: String): NostrRelayMessage? {
        val array = json.parseToJsonElement(raw).jsonArrayOrNull() ?: return null
        val type = array.getOrNull(0)?.stringOrNull() ?: return null
        return when (type) {
            "EVENT" -> {
                val subscriptionId = array.getOrNull(1)?.stringOrNull() ?: return null
                val event = array.getOrNull(2)?.jsonObjectOrNull()?.let(::parseEventObject) ?: return null
                NostrRelayMessage.Event(subscriptionId, event)
            }
            "OK" -> {
                val eventId = array.getOrNull(1)?.stringOrNull() ?: return null
                val accepted = array.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: return null
                val message = array.getOrNull(3)?.stringOrNull().orEmpty()
                NostrRelayMessage.Ok(eventId, accepted, message)
            }
            "EOSE" -> {
                val subscriptionId = array.getOrNull(1)?.stringOrNull() ?: return null
                NostrRelayMessage.Eose(subscriptionId)
            }
            "CLOSED" -> {
                val subscriptionId = array.getOrNull(1)?.stringOrNull() ?: return null
                val message = array.getOrNull(2)?.stringOrNull().orEmpty()
                NostrRelayMessage.Closed(subscriptionId, message)
            }
            "NOTICE" -> NostrRelayMessage.Notice(array.getOrNull(1)?.stringOrNull().orEmpty())
            else -> null
        }
    }

    fun eventJson(event: NostrEvent): String = eventObject(event).toString()

    fun parseEventJson(raw: String): NostrEvent? =
        runCatching { json.parseToJsonElement(raw).jsonObjectOrNull()?.let(::parseEventObject) }.getOrNull()

    fun eventObject(event: NostrEvent): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(event.id))
        put("pubkey", JsonPrimitive(event.pubkey))
        put("created_at", JsonPrimitive(event.createdAt))
        put("kind", JsonPrimitive(event.kind))
        put(
            "tags",
            buildJsonArray {
                event.tags.forEach { tag ->
                    add(
                        buildJsonArray {
                            tag.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }
            },
        )
        put("content", JsonPrimitive(event.content))
        put("sig", JsonPrimitive(event.sig))
    }

    fun filterObject(filter: NostrFilter): JsonObject = buildJsonObject {
        if (filter.authors.isNotEmpty()) {
            put("authors", stringArray(filter.authors))
        }
        put(
            "kinds",
            buildJsonArray {
                filter.kinds.forEach { add(JsonPrimitive(it)) }
            },
        )
        if (filter.tTags.isNotEmpty()) {
            put("#t", stringArray(filter.tTags))
        }
        if (filter.pTags.isNotEmpty()) {
            put("#p", stringArray(filter.pTags))
        }
        filter.since?.let { put("since", JsonPrimitive(it)) }
        put("limit", JsonPrimitive(filter.limit))
    }

    fun parseEventObject(obj: JsonObject): NostrEvent? {
        val tags = obj["tags"]?.jsonArrayOrNull()?.mapNotNull { tag ->
            tag.jsonArrayOrNull()?.mapNotNull { it.stringOrNull() }
        } ?: return null
        return NostrEvent(
            id = obj["id"]?.stringOrNull() ?: return null,
            pubkey = obj["pubkey"]?.stringOrNull() ?: return null,
            createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return null,
            kind = obj["kind"]?.jsonPrimitive?.intOrNull ?: return null,
            tags = tags,
            content = obj["content"]?.stringOrNull() ?: return null,
            sig = obj["sig"]?.stringOrNull() ?: return null,
        )
    }

    private fun stringArray(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.stringOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()
}
