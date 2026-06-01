package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.ui.AppMode
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.AppState
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.Nip19
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.security.NostrSignerProvider
import com.libertasprimordium.othernote.security.NostrSignerPublicKeyRequester
import com.libertasprimordium.othernote.security.SignerPublicKeyRequestResult
import com.libertasprimordium.othernote.security.SignerMode
import com.libertasprimordium.othernote.security.UnavailableExternalSignerProvider
import com.libertasprimordium.othernote.domain.SessionAuthMethod
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppModeTests {
    @Test
    fun localOnlyModeAllowsNoteEditingWithoutSession() = runBlocking {
        val state = AppState()
        state.continueLocalOnly()

        state.save(existing = null, markdown = "local note")

        assertEquals(AppMode.LocalOnly, state.mode.value)
        assertNull(state.session.value)
        assertEquals("local note", state.notes.notes.value.single().bodyMarkdown)
    }

    @Test
    fun localOnlyModeBlocksRelaySync() = runBlocking {
        val state = AppState()
        state.continueLocalOnly()

        state.sync()

        assertTrue(state.syncState.value.errors.single().contains("requires a validated nsec"))
    }

    @Test
    fun externalSignerUnavailableIsSurfacedWithoutChangingSessionMode() {
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = UnavailableExternalSignerProvider("No Android NIP-55 signer found."),
            ),
        )

        state.requestExternalSignerPublicKey()

        assertFalse(state.externalSignerAvailable)
        assertTrue(state.message.value.contains("No Android signer found"))
        assertEquals(AppMode.SignedOut, state.mode.value)
        assertNull(state.session.value)
    }

    @Test
    fun signerPublicKeySuccessCreatesExternalSignerSessionWithoutPrivateKey() {
        val pubkeyHex = "02".repeat(32)
        val npub = Nip19.encode("npub", ByteArray(32) { 0x02 }) ?: error("npub encode failed")
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
            ),
        )

        state.requestExternalSignerPublicKey()

        assertTrue(state.externalSignerAvailable)
        assertEquals("External signer detected: Test NIP-55 Signer", state.externalSignerStatus)
        assertTrue(state.message.value.contains("note sync through signer is not implemented yet"))
        assertEquals(AppMode.Authenticated, state.mode.value)
        val session = state.session.value ?: error("Missing signer session")
        assertEquals(SessionAuthMethod.ExternalSigner, session.authMethod)
        assertEquals("external-signer", session.nsec)
        assertEquals("", session.privateKeyHex)
        assertEquals(pubkeyHex, session.publicKeyHex)
        assertEquals(npub, session.npub)
        assertEquals("com.example.signer", session.signerPackage)
    }

    @Test
    fun signerCancellationLeavesUserSignedOut() {
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(SignerPublicKeyRequestResult.Cancelled),
            ),
        )

        state.requestExternalSignerPublicKey()

        assertEquals("Signer request cancelled", state.message.value)
        assertEquals(AppMode.SignedOut, state.mode.value)
        assertNull(state.session.value)
    }
}

private class TestSignerPublicKeyRequester(
    private val result: SignerPublicKeyRequestResult,
) : NostrSignerPublicKeyRequester {
    override fun requestPublicKey(onResult: (SignerPublicKeyRequestResult) -> Unit) {
        onResult(result)
    }
}

private object AvailableTestSignerProvider : NostrSignerProvider {
    override val mode: SignerMode = SignerMode.ExternalSigner
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null
    override val displayName: String = "Test NIP-55 Signer"
    override val canGetPublicKey: Boolean = true
    override val canSignEvent: Boolean = false
    override val canNip44EncryptDecrypt: Boolean = false
    override val safeDiagnostics: List<String> = listOf("safe test signer available")
}
