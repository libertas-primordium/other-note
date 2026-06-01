package com.libertasprimordium.othernote.domain

data class RelayConfig(
    val url: String,
    val readEnabled: Boolean = true,
    val writeEnabled: Boolean = true,
)

data class RelayStatus(
    val url: String,
    val readable: Boolean = false,
    val writable: Boolean = false,
    val message: String = "Not checked",
)

val DefaultRelays = listOf(
    RelayConfig("wss://relay.damus.io"),
    RelayConfig("wss://relay.primal.net"),
    RelayConfig("wss://relay.nostr.net"),
)
