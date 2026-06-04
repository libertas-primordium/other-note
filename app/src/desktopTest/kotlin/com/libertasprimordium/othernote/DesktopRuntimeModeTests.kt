package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.security.DesktopSecureSecretStore
import com.libertasprimordium.othernote.security.SecureSecretStoreResult
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppState
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopRuntimeModeTests {
    @AfterTest
    fun clearFlag() {
        System.clearProperty("othernote.devRelayRuntime")
        System.clearProperty("othernote.showRelayDiagnostics")
    }

    @Test
    fun defaultDesktopRuntimeUsesProductionCryptoAndDesktopRelayClient() {
        System.clearProperty("othernote.devRelayRuntime")

        val services = DesktopAppServicesFactory.create()

        assertNotNull(ProductionNostrCryptoFactory.createOrNull())
        assertEquals(AppRuntimeMode.DesktopRelay, services.mode)
        assertEquals(false, services.showRelayDiagnostics)
        assertTrue(services.crypto.productionReady)
        assertIs<DesktopNostrClient>(services.client)
        assertIs<DesktopSecureSecretStore>(services.secureSecretStore)
        if (!services.secureSecretStore.isAvailable) {
            assertTrue(services.secureSecretStore.unavailableReason.orEmpty().contains("Desktop keyring"))
        }
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
    fun devFlagKeepsDesktopRelayClientAndMarksDeveloperMode() {
        System.setProperty("othernote.devRelayRuntime", "true")

        val services = DesktopAppServicesFactory.create()

        assertNotNull(ProductionNostrCryptoFactory.createOrNull())
        assertEquals(AppRuntimeMode.DesktopDevRelay, services.mode)
        assertEquals(false, services.showRelayDiagnostics)
        assertTrue(services.crypto.productionReady)
        assertIs<DesktopNostrClient>(services.client)
        assertIs<DesktopSecureSecretStore>(services.secureSecretStore)
        if (!services.secureSecretStore.isAvailable) {
            assertTrue(services.secureSecretStore.unavailableReason.orEmpty().contains("Desktop keyring"))
        }
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
    fun desktopKeyringTreatsBlankSuccessfulSearchAsAvailableEmptyList() = runBlocking {
        val dir = Files.createTempDirectory("other-note-secret-tool-test")
        val helper = dir.resolve("secret-tool")
        Files.writeString(
            helper,
            """
            #!/bin/sh
            if [ "${'$'}1" = "search" ]; then
              exit 0
            fi
            exit 1
            """.trimIndent(),
        )
        helper.toFile().setExecutable(true)
        val store = DesktopSecureSecretStore(secretToolPath = helper.toString())

        val result = store.listSavedNsecs()

        assertTrue(store.isAvailable)
        assertNull(store.unavailableReason)
        val listed = assertIs<SecureSecretStoreResult.Listed>(result)
        assertTrue(listed.identities.isEmpty())
    }

    @Test
    fun desktopKeyringUsesStableSecretToolAttributesForSaveListLoadAndDelete() = runBlocking {
        val dir = Files.createTempDirectory("other-note-secret-tool-attrs-test")
        val helper = dir.resolve("secret-tool")
        val invocations = dir.resolve("invocations.log")
        val accountPubkey = "1".repeat(64)
        Files.writeString(
            helper,
            """
            #!/bin/sh
            printf '%s\n' "$*" >> "${invocations}"
            case "$1" in
              store)
                cat >/dev/null
                if [ "$4" = "application" ] && [ "$5" = "other-note" ] && [ "$6" = "key_type" ] && [ "$7" = "nostr_nsec" ] && [ "$8" = "account_pubkey" ] && [ "$9" = "$accountPubkey" ]; then
                  exit 0
                fi
                echo "bad store attributes" >&2
                exit 2
                ;;
              search)
                if [ "$2" = "application" ] && [ "$3" = "other-note" ] && [ "$4" = "key_type" ] && [ "$5" = "nostr_nsec" ]; then
                  printf 'label = Other Note identity\n'
                  printf 'attribute.account_pubkey = %s\n' "$accountPubkey"
                  printf 'attribute.application = other-note\n'
                  printf 'attribute.key_type = nostr_nsec\n'
                  exit 0
                fi
                echo "bad search attributes" >&2
                exit 3
                ;;
              lookup)
                if [ "$2" = "application" ] && [ "$3" = "other-note" ] && [ "$4" = "key_type" ] && [ "$5" = "nostr_nsec" ] && [ "$6" = "account_pubkey" ] && [ "$7" = "$accountPubkey" ]; then
                  printf 'nsec-test-value\n'
                  exit 0
                fi
                echo "bad lookup attributes" >&2
                exit 4
                ;;
              clear)
                if [ "$2" = "application" ] && [ "$3" = "other-note" ] && [ "$4" = "key_type" ] && [ "$5" = "nostr_nsec" ] && [ "$6" = "account_pubkey" ] && [ "$7" = "$accountPubkey" ]; then
                  exit 0
                fi
                echo "bad clear attributes" >&2
                exit 5
                ;;
            esac
            exit 9
            """.trimIndent(),
        )
        helper.toFile().setExecutable(true)
        val store = DesktopSecureSecretStore(secretToolPath = helper.toString(), crypto = null)

        assertIs<SecureSecretStoreResult.Saved>(store.saveNsec(accountPubkey, "nsec-test-value"))
        val listed = assertIs<SecureSecretStoreResult.Listed>(store.listSavedNsecs())
        assertEquals(accountPubkey, listed.identities.single().accountPubkey)
        assertIs<SecureSecretStoreResult.Loaded>(store.loadNsec(accountPubkey))
        assertIs<SecureSecretStoreResult.Deleted>(store.deleteNsec(accountPubkey))

        val commands = Files.readString(invocations)
        assertTrue(commands.contains("store --label Other Note identity"))
        assertTrue(commands.contains("application other-note key_type nostr_nsec account_pubkey $accountPubkey"))
        assertTrue(commands.contains("search application other-note key_type nostr_nsec"))
        assertTrue(commands.contains("lookup application other-note key_type nostr_nsec account_pubkey $accountPubkey"))
        assertTrue(commands.contains("clear application other-note key_type nostr_nsec account_pubkey $accountPubkey"))
        assertFalse(commands.lineSequence().any { it.contains(" application other-note type nostr_nsec") })
    }

    @Test
    fun desktopKeyringDoesNotReportSaveSuccessWhenSecretToolStoreFails() = runBlocking {
        val dir = Files.createTempDirectory("other-note-secret-tool-save-fail-test")
        val helper = dir.resolve("secret-tool")
        Files.writeString(
            helper,
            """
            #!/bin/sh
            if [ "$1" = "store" ]; then
              cat >/dev/null
              echo "store failed" >&2
              exit 12
            fi
            exit 0
            """.trimIndent(),
        )
        helper.toFile().setExecutable(true)
        val store = DesktopSecureSecretStore(secretToolPath = helper.toString(), crypto = null)

        val result = store.saveNsec("2".repeat(64), "nsec-test-value")

        assertIs<SecureSecretStoreResult.Failed>(result)
        Unit
    }

    @Test
    fun devRuntimeLoginDerivesNpubAndDoesNotStoreNsecText() {
        System.clearProperty("othernote.devRelayRuntime")
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
