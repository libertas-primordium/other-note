package com.libertasprimordium.othernote.nostr

import com.libertasprimordium.othernote.domain.RelayStatus

interface NostrClient {
    suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult
    suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult
    suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata?
}

data class RelayFetchResult(
    val events: List<NostrEvent>,
    val statuses: List<RelayStatus>,
)

data class RelayPublishResult(
    val statuses: List<RelayStatus>,
) {
    val allSucceeded: Boolean get() = statuses.isNotEmpty() && statuses.all { it.writable }
    val anySucceeded: Boolean get() = statuses.any { it.writable }
}

data class ProfileMetadata(
    val pubkey: String,
    val name: String?,
    val displayName: String?,
    val pictureUrl: String?,
) {
    val bestName: String? get() = displayName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
}

class OfflineNostrClient : NostrClient {
    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(
            events = emptyList(),
            statuses = relays.map { RelayStatus(it, readable = false, message = "Network client not wired yet") },
        )

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult =
        RelayPublishResult(relays.map { RelayStatus(it, writable = false, message = "Publishing not wired yet") })

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}
