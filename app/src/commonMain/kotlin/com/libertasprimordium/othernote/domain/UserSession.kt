package com.libertasprimordium.othernote.domain

data class UserSession(
    val nsec: String,
    val privateKeyHex: String,
    val npub: String,
    val publicKeyHex: String,
)

fun UserSession.abbreviatedNpub(): String =
    if (npub.length <= 18) npub else "${npub.take(10)}...${npub.takeLast(6)}"
