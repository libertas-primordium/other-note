package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.security.DesktopSecureSecretStore
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopRuntimeModeTests {
    @AfterTest
    fun clearFlag() {
        System.clearProperty("othernote.devRelayRuntime")
        System.clearProperty("othernote.showRelayDiagnostics")
    }

    @Test
    fun defaultDesktopRuntimeUsesOfflineServices() {
        System.clearProperty("othernote.devRelayRuntime")

        val services = DesktopAppServicesFactory.create()

        assertEquals(AppRuntimeMode.Offline, services.mode)
        assertIs<NonProductionNostrCrypto>(services.crypto)
        assertIs<OfflineNostrClient>(services.client)
    }

    @Test
    fun devFlagRuntimeUsesProductionCryptoAndDesktopRelayClient() {
        System.setProperty("othernote.devRelayRuntime", "true")

        val services = DesktopAppServicesFactory.create()

        assertNotNull(ProductionNostrCryptoFactory.createOrNull())
        assertEquals(AppRuntimeMode.DesktopDevRelay, services.mode)
        assertEquals(false, services.showRelayDiagnostics)
        assertTrue(services.crypto.productionReady)
        assertIs<DesktopNostrClient>(services.client)
        assertIs<DesktopSecureSecretStore>(services.secureSecretStore)
        assertEquals(false, services.secureSecretStore.isAvailable)
        assertEquals(
            listOf(
                "wss://relay.damus.io",
                "wss://relay.primal.net",
                "wss://relay.nostr.net",
                "wss://nos.lol",
                "wss://relay.ditto.pub",
            ),
            services.relaySettings.normalizedUrls(),
        )
    }

    @Test
    fun relayDiagnosticsRequireExplicitFlag() {
        System.setProperty("othernote.devRelayRuntime", "true")
        System.clearProperty("othernote.showRelayDiagnostics")

        assertEquals(false, DesktopAppServicesFactory.create().showRelayDiagnostics)

        System.setProperty("othernote.showRelayDiagnostics", "true")

        assertEquals(true, DesktopAppServicesFactory.create().showRelayDiagnostics)
    }

    @Test
    fun devRuntimeLoginDerivesNpubAndDoesNotStoreNsecText() {
        System.setProperty("othernote.devRelayRuntime", "true")
        val crypto = ProductionNostrCryptoFactory.createOrNull() ?: error(ProductionNostrCryptoFactory.unavailableReason)
        val nsec = crypto.encodeNsec(crypto.generatePrivateKey().getOrThrow()).getOrThrow()
        val state = AppState(DesktopAppServicesFactory.create())

        assertTrue(state.login(nsec))

        val session = state.session.value ?: error("Missing session")
        assertEquals("nsec-redacted", session.nsec)
        assertTrue(session.npub.startsWith("npub1"))
        assertEquals(64, session.publicKeyHex.length)
    }
}
