package com.libertasprimordium.othernote.data

import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.ProfileMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class ProfileRepository(
    private val client: NostrClient,
) {
    private val _profiles = MutableStateFlow<Map<String, ProfileMetadata>>(emptyMap())
    val profiles: StateFlow<Map<String, ProfileMetadata>> = _profiles

    suspend fun loadProfile(relays: List<String>, pubkey: String): ProfileMetadata? {
        _profiles.value[pubkey]?.let { return it }
        val profile = client.fetchProfile(relays, pubkey) ?: return null
        _profiles.update { it + (pubkey to profile) }
        return profile
    }
}
