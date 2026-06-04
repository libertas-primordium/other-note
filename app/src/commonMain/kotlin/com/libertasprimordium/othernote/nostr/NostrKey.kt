package com.libertasprimordium.othernote.nostr

data class NostrPrivateKey(
    val hex: String,
)

data class NostrPublicKey(
    val hex: String,
    val npub: String,
)

sealed class KeyDecodeResult {
    data class Valid(val privateKey: NostrPrivateKey) : KeyDecodeResult()
    data class Invalid(val reason: String) : KeyDecodeResult()
}
