package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.ui.AppMode
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.AppState
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.Nip19
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.domain.DefaultRelays
import com.libertasprimordium.othernote.security.NostrSignerProvider
import com.libertasprimordium.othernote.security.NostrSignerEventSigner
import com.libertasprimordium.othernote.security.NostrSignerNip44Operator
import com.libertasprimordium.othernote.security.NostrSignerPublicKeyRequester
import com.libertasprimordium.othernote.security.SignEventRequestResult
import com.libertasprimordium.othernote.security.SignerNip44OperationResult
import com.libertasprimordium.othernote.security.SignerPublicKeyRequestResult
import com.libertasprimordium.othernote.security.SignerMode
import com.libertasprimordium.othernote.security.UnavailableExternalSignerProvider
import com.libertasprimordium.othernote.domain.SessionAuthMethod
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppModeTests {
    @Test
    fun sharedDefaultRelaysUseMaintainedDevSet() {
        val urls = DefaultRelays.map { it.url }

        assertTrue("wss://nos.lol" in urls)
        assertTrue("wss://relay.ditto.pub" in urls)
        assertFalse(urls.any { it.contains("relay.nostr.band") })
    }

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
        assertTrue(state.message.value.contains("relay sync can run with signer prompts"))
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
    fun signerLoginStatusDoesNotExposeDevelopmentTestActions() {
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

        assertFalse(state.message.value.contains("Test signer"))
        assertFalse(state.message.value.contains("test signer"))
        assertTrue(state.message.value.contains("relay sync can run"))
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

    @Test
    fun signerTestSignatureSuccessLeavesSignerSessionAndShowsVerifiedStatus() {
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
                externalSignerEventSigner = TestSignerEventSigner { unsigned ->
                    SignEventRequestResult.Success(unsigned.copy(sig = "valid"), "com.example.signer")
                },
            ),
        )

        state.requestExternalSignerPublicKey()
        state.requestExternalSignerTestSignature()

        assertEquals(AppMode.Authenticated, state.mode.value)
        assertEquals(SessionAuthMethod.ExternalSigner, state.session.value?.authMethod)
        assertTrue(state.message.value.startsWith("Signer test event signed and verified"))
    }

    @Test
    fun signerTestSignatureRejectsWrongPubkeyAndKeepsSession() {
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
                externalSignerEventSigner = TestSignerEventSigner {
                    SignEventRequestResult.InvalidResponse("Signer returned event for a different pubkey")
                },
            ),
        )

        state.requestExternalSignerPublicKey()
        state.requestExternalSignerTestSignature()

        assertEquals(AppMode.Authenticated, state.mode.value)
        assertTrue(state.message.value.contains("different pubkey"))
    }

    @Test
    fun signerTestSigningCancelledLeavesSignerSession() {
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
                externalSignerEventSigner = TestSignerEventSigner { SignEventRequestResult.Cancelled },
            ),
        )

        state.requestExternalSignerPublicKey()
        state.requestExternalSignerTestSignature()

        assertEquals(AppMode.Authenticated, state.mode.value)
        assertEquals("Signer signing cancelled", state.message.value)
        assertNotNull(state.session.value)
    }

    @Test
    fun signerTestSigningDiagnosticsAreVisibleOnlyWhenEnabledAndSanitized() {
        val pubkeyHex = "02".repeat(32)
        val npub = Nip19.encode("npub", ByteArray(32) { 0x02 }) ?: error("npub encode failed")
        val disabledState = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
                externalSignerEventSigner = TestSignerEventSigner { SignEventRequestResult.Cancelled },
            ),
        )
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                showNip55Diagnostics = true,
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
                externalSignerEventSigner = TestSignerEventSigner { SignEventRequestResult.Cancelled },
            ),
        )

        disabledState.requestExternalSignerPublicKey()
        disabledState.requestExternalSignerTestSignature()
        state.requestExternalSignerPublicKey()
        state.requestExternalSignerTestSignature()

        assertEquals("", disabledState.diagnosticMessage.value)
        val diagnostics = state.diagnosticMessage.value
        assertTrue(diagnostics.contains("request_shape=full_unsigned_event_no_id_sig"))
        assertTrue(diagnostics.contains("event_contains_pubkey=true"))
        assertFalse(diagnostics.contains(pubkeyHex))
        assertFalse(diagnostics.contains("nsec1"))
    }

    @Test
    fun signerNip44RoundTripSuccessShowsVerifiedStatus() {
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
                externalSignerNip44Operator = TestNip44Operator(),
            ),
        )

        state.requestExternalSignerPublicKey()
        state.requestExternalSignerNip44Test()

        assertEquals(AppMode.Authenticated, state.mode.value)
        assertEquals("Signer decrypted and verified test payload", state.message.value)
    }

    @Test
    fun signerNip44RoundTripUnavailableDoesNotBreakSession() {
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
                externalSignerNip44Operator = TestNip44Operator(
                    encryptResult = SignerNip44OperationResult.InvalidResponse("Signer returned invalid encryption result"),
                ),
            ),
        )

        state.requestExternalSignerPublicKey()
        state.requestExternalSignerNip44Test()

        assertEquals(AppMode.Authenticated, state.mode.value)
        assertEquals("Signer returned invalid encryption result", state.message.value)
    }
}

private class TestSignerPublicKeyRequester(
    private val result: SignerPublicKeyRequestResult,
) : NostrSignerPublicKeyRequester {
    override fun requestPublicKey(onResult: (SignerPublicKeyRequestResult) -> Unit) {
        onResult(result)
    }
}

private class TestSignerEventSigner(
    private val sign: (NostrEvent) -> SignEventRequestResult,
) : NostrSignerEventSigner {
    override fun signEvent(
        unsignedEvent: NostrEvent,
        currentUserPubkey: String,
        signerPackage: String?,
        onResult: (SignEventRequestResult) -> Unit,
    ) {
        onResult(sign(unsignedEvent))
    }
}

private class TestNip44Operator(
    private val encryptResult: SignerNip44OperationResult? = null,
) : NostrSignerNip44Operator {
    override fun encryptToSelf(
        plaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult =
        encryptResult ?: SignerNip44OperationResult.Encrypted("nip44-ciphertext", signerPackage)

    override fun decryptFromSelf(
        ciphertext: String,
        expectedPlaintext: String?,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult =
        SignerNip44OperationResult.Decrypted(expectedPlaintext.orEmpty(), signerPackage)
}

private object AvailableTestSignerProvider : NostrSignerProvider {
    override val mode: SignerMode = SignerMode.ExternalSigner
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null
    override val displayName: String = "Test NIP-55 Signer"
    override val canGetPublicKey: Boolean = true
    override val canSignEvent: Boolean = true
    override val canNip44EncryptDecrypt: Boolean = true
    override val safeDiagnostics: List<String> = listOf("safe test signer available")
}
