package com.libertasprimordium.othernote.nostr

object ProductionNostrCryptoFactory {
    const val unavailableReason =
        "No production Nostr crypto adapter is wired for the current Kotlin 1.9.24 toolchain. Quartz is MIT/GPL-compatible but current artifacts require Kotlin 2.x metadata."

    fun createOrNull(): NostrCrypto? = null
}
