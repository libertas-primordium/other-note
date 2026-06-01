package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import kotlin.test.Test
import kotlin.test.fail

class RelayIntegrationTests {
    @Test
    fun relayRoundTripIsExplicitlyOptIn() {
        if (System.getenv("OTHER_NOTE_RELAY_TESTS") != "1") return
        val relays = System.getenv("OTHER_NOTE_TEST_RELAYS")
            ?.split(',')
            ?.mapNotNull { normalizeRelayUrl(it).getOrNull() }
            ?.distinct()
            .orEmpty()
        if (relays.isEmpty()) return

        val crypto = ProductionNostrCryptoFactory.createOrNull()
            ?: fail(ProductionNostrCryptoFactory.unavailableReason)

        fail(
            "Relay integration transport is intentionally not active until a production crypto adapter is wired. " +
                "Adapter readiness: ${crypto.productionReady}. Relays requested: ${relays.joinToString()}.",
        )
    }
}
