package com.libertasprimordium.othernote.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val ProfileMetadataKind = 0

private const val ProfileNameMaxLength = 80
private const val ProfileAboutMaxLength = 280
private const val ProfileUrlMaxLength = 240

private val profileJson = Json { ignoreUnknownKeys = true }

fun profileMetadataFilter(pubkey: String, limit: Int = 20): NostrFilter =
    NostrFilter(
        authors = listOf(pubkey),
        kinds = listOf(ProfileMetadataKind),
        tTags = emptyList(),
        limit = limit,
    )

fun parseProfileMetadata(pubkey: String, content: String, createdAt: Long? = null): ProfileMetadata? {
    val obj = runCatching { profileJson.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
    return ProfileMetadata(
        pubkey = pubkey,
        name = obj.safeString("name", ProfileNameMaxLength),
        displayName = obj.safeString("display_name", ProfileNameMaxLength),
        pictureUrl = obj.safeString("picture", ProfileUrlMaxLength),
        about = obj.safeString("about", ProfileAboutMaxLength),
        nip05 = obj.safeString("nip05", ProfileNameMaxLength),
        website = obj.safeString("website", ProfileUrlMaxLength),
        createdAt = createdAt,
    )
}

fun selectLatestProfileMetadata(events: List<NostrEvent>, pubkey: String): ProfileMetadata? =
    events
        .asSequence()
        .filter { it.kind == ProfileMetadataKind && it.pubkey == pubkey }
        .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenByDescending { it.id })
        .mapNotNull { parseProfileMetadata(pubkey = it.pubkey, content = it.content, createdAt = it.createdAt) }
        .firstOrNull()

private fun JsonObject.safeString(key: String, maxLength: Int): String? {
    val value = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.trim() ?: return null
    if (value.isBlank()) return null
    return value.take(maxLength)
}
