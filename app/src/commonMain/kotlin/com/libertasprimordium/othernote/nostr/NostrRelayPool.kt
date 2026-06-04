package com.libertasprimordium.othernote.nostr

import com.libertasprimordium.othernote.util.normalizeRelayUrl

class NostrRelayPool(
    relays: List<String>,
) {
    val normalizedRelays: List<String> = relays.mapNotNull { normalizeRelayUrl(it).getOrNull() }.distinct()
}
