package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.security.KeyManagementPolicy
import com.libertasprimordium.othernote.security.NostrSignerProvider
import com.libertasprimordium.othernote.security.SecureSecretStore
import com.libertasprimordium.othernote.security.SecureSecretStoreResult
import com.libertasprimordium.othernote.security.SignerSignEventRequestBuilder
import com.libertasprimordium.othernote.security.SignerPublicKeyRequestResult
import com.libertasprimordium.othernote.security.SignerPublicKeyResponseParser
import com.libertasprimordium.othernote.security.SignerMode
import com.libertasprimordium.othernote.security.SignEventRequestShape
import com.libertasprimordium.othernote.security.UnavailableExternalSignerProvider
import com.libertasprimordium.othernote.security.UnavailableSecureSecretStore
import com.libertasprimordium.othernote.nostr.Nip19
import com.libertasprimordium.othernote.nostr.NostrEvent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeyManagementPolicyTests {
    private val suppliedNsec = "nsec1testsecretmustnotappear"

    @Test
    fun unavailableSecureSecretStoreDoesNotPersistNsec() = runBlocking {
        val store = UnavailableSecureSecretStore("No secure store")

        val save = store.saveNsec("account", suppliedNsec)
        val load = store.loadNsec("account")

        assertIs<SecureSecretStoreResult.Unavailable>(save)
        assertFalse(load is SecureSecretStoreResult.Loaded)
        assertFalse(store.isAvailable)
    }

    @Test
    fun unavailableStoreDiagnosticsDoNotIncludeNsec() = runBlocking {
        val store = UnavailableSecureSecretStore("OS keyring unavailable")

        val save = store.saveNsec("account", suppliedNsec)

        assertFalse(save.safeText().contains(suppliedNsec))
        assertFalse(store.unavailableReason.orEmpty().contains(suppliedNsec))
    }

    @Test
    fun sessionOnlyNsecDoesNotImplySavedKeyMode() {
        val policy = KeyManagementPolicy()
        val modes = policy.resolveAvailableModes(
            store = UnavailableSecureSecretStore(),
            externalSigner = UnavailableExternalSignerProvider(),
        )

        assertTrue(SignerMode.SessionOnlyNsec in modes)
        assertFalse(SignerMode.SavedDeviceNsec in modes)
    }

    @Test
    fun savedDeviceNsecRequiresAvailableSecureSecretStore() {
        val policy = KeyManagementPolicy()

        assertFalse(policy.savedDeviceNsecAllowed(UnavailableSecureSecretStore()))
        assertTrue(policy.savedDeviceNsecAllowed(TestOnlySecureSecretStore()))
    }

    @Test
    fun externalSignerDependsOnProviderAvailability() {
        val policy = KeyManagementPolicy()
        val unavailable = policy.resolveAvailableModes(
            store = TestOnlySecureSecretStore(),
            externalSigner = UnavailableExternalSignerProvider(),
        )
        val available = policy.resolveAvailableModes(
            store = TestOnlySecureSecretStore(),
            externalSigner = TestOnlySignerProvider(isAvailable = true),
        )

        assertFalse(SignerMode.ExternalSigner in unavailable)
        assertTrue(SignerMode.ExternalSigner in available)
    }

    @Test
    fun externalSignerAvailabilityDoesNotEnableSavedDeviceNsec() {
        val policy = KeyManagementPolicy()
        val modes = policy.resolveAvailableModes(
            store = UnavailableSecureSecretStore(),
            externalSigner = TestOnlySignerProvider(isAvailable = true),
        )

        assertTrue(SignerMode.ExternalSigner in modes)
        assertTrue(SignerMode.SessionOnlyNsec in modes)
        assertFalse(SignerMode.SavedDeviceNsec in modes)
    }

    @Test
    fun externalSignerMetadataAndDiagnosticsAreSafe() {
        val provider = TestOnlySignerProvider(isAvailable = true, displayName = "Test Signer")

        assertEquals("Test Signer", provider.displayName)
        assertTrue(provider.canGetPublicKey)
        assertFalse(provider.canSignEvent)
        assertFalse(provider.canNip44EncryptDecrypt)
        assertFalse(provider.safeDiagnostics.joinToString(" ").contains(suppliedNsec))
    }

    @Test
    fun signerPublicKeyParserDerivesNpubFromHexPubkey() {
        val pubkeyHex = "03".repeat(32)
        val expectedNpub = Nip19.encode("npub", ByteArray(32) { 0x03 }) ?: error("npub encode failed")

        val result = SignerPublicKeyResponseParser.parse(result = pubkeyHex, signerPackage = "com.example.signer")

        val success = assertIs<SignerPublicKeyRequestResult.Success>(result)
        assertEquals(pubkeyHex, success.pubkeyHex)
        assertEquals(expectedNpub, success.npub)
        assertEquals("com.example.signer", success.signerPackage)
    }

    @Test
    fun signerPublicKeyParserDecodesNpubResult() {
        val pubkeyHex = "04".repeat(32)
        val npub = Nip19.encode("npub", ByteArray(32) { 0x04 }) ?: error("npub encode failed")

        val result = SignerPublicKeyResponseParser.parse(result = npub)

        val success = assertIs<SignerPublicKeyRequestResult.Success>(result)
        assertEquals(pubkeyHex, success.pubkeyHex)
        assertEquals(npub, success.npub)
    }

    @Test
    fun signerPublicKeyParserRejectsMismatchedHexAndNpub() {
        val hex = "05".repeat(32)
        val mismatchedNpub = Nip19.encode("npub", ByteArray(32) { 0x06 }) ?: error("npub encode failed")

        val result = SignerPublicKeyResponseParser.parse(result = hex, npub = mismatchedNpub)

        assertIs<SignerPublicKeyRequestResult.InvalidResponse>(result)
    }

    @Test
    fun signerPublicKeyParserRejectsMalformedResponseWithoutLeakingNsecLikeInput() {
        val result = SignerPublicKeyResponseParser.parse(result = suppliedNsec)

        val invalid = assertIs<SignerPublicKeyRequestResult.InvalidResponse>(result)
        assertFalse(invalid.safeReason.contains(suppliedNsec))
    }

    @Test
    fun signEventRequestDefaultsToFullUnsignedEventWithoutIdOrSig() {
        val pubkey = "07".repeat(32)
        val request = SignerSignEventRequestBuilder.build(
            requestedEvent = testSignEvent(pubkey),
            currentUserPubkey = pubkey,
            signerPackage = "com.example.signer",
        ).getOrThrow()
        val payload = assertNotNull(SignerSignEventRequestBuilder.parseEventPayload(request.eventJson))

        assertTrue(request.uriString.startsWith("nostrsigner:"))
        assertTrue(request.uriString.removePrefix("nostrsigner:").isNotBlank())
        assertEquals(1, payload.kind)
        assertEquals("Other Note signer test", payload.content)
        assertEquals(emptyList(), payload.tags)
        assertEquals(pubkey, payload.pubkey)
        assertEquals(SignEventRequestShape.FullUnsignedEventNoIdSig, request.shape)
        assertTrue(request.eventJson.contains("\"pubkey\""))
        assertFalse(request.eventJson.contains("\"id\""))
        assertFalse(request.eventJson.contains("\"sig\""))
    }

    @Test
    fun signEventRequestSupportsBlankIdSigShapeExplicitly() {
        val pubkey = "08".repeat(32)
        val request = SignerSignEventRequestBuilder.build(
            requestedEvent = testSignEvent(pubkey),
            currentUserPubkey = pubkey,
            signerPackage = null,
            shape = SignEventRequestShape.FullEventBlankIdSig,
        ).getOrThrow()
        val payload = assertNotNull(SignerSignEventRequestBuilder.parseEventPayload(request.eventJson))

        assertEquals(pubkey, payload.pubkey)
        assertEquals("", payload.id)
        assertEquals("", payload.sig)
        assertTrue(request.eventJson.contains("\"id\":\"\""))
        assertTrue(request.eventJson.contains("\"sig\":\"\""))
    }

    @Test
    fun signEventPartialPayloadShapeIsExplicitOnly() {
        val pubkey = "09".repeat(32)
        val defaultRequest = SignerSignEventRequestBuilder.build(
            requestedEvent = testSignEvent(pubkey),
            currentUserPubkey = pubkey,
            signerPackage = null,
        ).getOrThrow()
        val partialRequest = SignerSignEventRequestBuilder.build(
            requestedEvent = testSignEvent(pubkey),
            currentUserPubkey = pubkey,
            signerPackage = null,
            shape = SignEventRequestShape.PartialPayload,
        ).getOrThrow()
        val partialPayload = assertNotNull(SignerSignEventRequestBuilder.parseEventPayload(partialRequest.eventJson))

        assertEquals(SignEventRequestShape.FullUnsignedEventNoIdSig, defaultRequest.shape)
        assertEquals(SignEventRequestShape.PartialPayload, partialRequest.shape)
        assertEquals(null, partialPayload.pubkey)
        assertFalse(partialRequest.eventJson.contains("\"pubkey\""))
    }

    @Test
    fun signEventRequestKeepsExpectedIdExtraSeparateFromPayload() {
        val pubkey = "0b".repeat(32)
        val event = testSignEvent(pubkey)
        val request = SignerSignEventRequestBuilder.build(
            requestedEvent = event,
            currentUserPubkey = pubkey,
            signerPackage = null,
        ).getOrThrow()

        assertEquals(event.id, request.expectedEventId)
        assertFalse(request.eventJson.contains(event.id))
    }

    @Test
    fun signEventRequestRequiresSignerSessionPubkey() {
        val result = SignerSignEventRequestBuilder.build(
            requestedEvent = testSignEvent("0c".repeat(32)),
            currentUserPubkey = "0d".repeat(32),
            signerPackage = null,
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun signEventRequestDiagnosticsAreSanitized() {
        val pubkey = "0e".repeat(32)
        val event = testSignEvent(pubkey)
        val request = SignerSignEventRequestBuilder.build(
            requestedEvent = event,
            currentUserPubkey = pubkey,
            signerPackage = "com.example.signer",
        ).getOrThrow()
        val diagnostics = request.safeDiagnostics.joinToString(" ")

        assertFalse(diagnostics.contains(pubkey))
        assertFalse(diagnostics.contains(event.id))
        assertFalse(diagnostics.contains(request.eventJson))
        assertFalse(diagnostics.contains(suppliedNsec))
        assertTrue(diagnostics.contains("event_json_length="))
        assertTrue(diagnostics.contains("request_shape=full_unsigned_event_no_id_sig"))
        assertTrue(diagnostics.contains("event_contains_pubkey=true"))
        assertTrue(diagnostics.contains("event_contains_id=false"))
        assertTrue(diagnostics.contains("event_contains_sig=false"))
        assertTrue(diagnostics.contains("data_uri_has_payload=true"))
    }

    @Test
    fun plaintextPersistenceIsNeverAllowedByDefault() {
        val policy = KeyManagementPolicy()

        assertFalse(policy.plaintextPersistenceAllowed)
        assertEquals(
            listOf(SignerMode.ExternalSigner, SignerMode.SessionOnlyNsec, SignerMode.SavedDeviceNsec),
            policy.preferredModes,
        )
    }

    @Test
    fun savedDeviceNsecUnavailableWhenPlaintextPersistenceFlagWouldBeTrue() {
        val policy = KeyManagementPolicy(plaintextPersistenceAllowed = true)

        assertFalse(policy.savedDeviceNsecAllowed(TestOnlySecureSecretStore()))
    }

    @Test
    fun testOnlyStoreCanModelAvailabilityWithoutProductionFallback() = runBlocking {
        val store = TestOnlySecureSecretStore()

        assertTrue(store.isAvailable)
        assertIs<SecureSecretStoreResult.Saved>(store.saveNsec("account", suppliedNsec))
        assertIs<SecureSecretStoreResult.Loaded>(store.loadNsec("account"))
        assertIs<SecureSecretStoreResult.Deleted>(store.deleteNsec("account"))
        assertFalse(store.safeDiagnostics.any { it.contains(suppliedNsec) })
    }
}

private fun testSignEvent(pubkey: String): NostrEvent = NostrEvent(
    id = "ab".repeat(32),
    pubkey = pubkey,
    createdAt = 1_700_000_000,
    kind = 1,
    tags = emptyList(),
    content = "Other Note signer test",
    sig = "",
)

private fun SecureSecretStoreResult.safeText(): String = when (this) {
    SecureSecretStoreResult.Deleted -> "Deleted"
    is SecureSecretStoreResult.Failed -> safeMessage
    is SecureSecretStoreResult.Loaded -> "Loaded"
    SecureSecretStoreResult.Saved -> "Saved"
    SecureSecretStoreResult.Unavailable -> "Unavailable"
}

private class TestOnlySecureSecretStore : SecureSecretStore {
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null
    val safeDiagnostics = mutableListOf<String>()
    private var stored: String? = null

    override suspend fun saveNsec(accountId: String, nsec: String): SecureSecretStoreResult {
        stored = nsec
        safeDiagnostics += "saved account"
        return SecureSecretStoreResult.Saved
    }

    override suspend fun loadNsec(accountId: String): SecureSecretStoreResult {
        safeDiagnostics += "loaded account"
        return stored?.let { SecureSecretStoreResult.Loaded(it) }
            ?: SecureSecretStoreResult.Failed("No secret saved")
    }

    override suspend fun deleteNsec(accountId: String): SecureSecretStoreResult {
        stored = null
        safeDiagnostics += "deleted account"
        return SecureSecretStoreResult.Deleted
    }
}

private class TestOnlySignerProvider(
    override val isAvailable: Boolean,
    override val displayName: String? = null,
) : NostrSignerProvider {
    override val mode: SignerMode = SignerMode.ExternalSigner
    override val unavailableReason: String? = if (isAvailable) null else "Unavailable in test"
    override val canGetPublicKey: Boolean = isAvailable
    override val canSignEvent: Boolean = false
    override val canNip44EncryptDecrypt: Boolean = false
    override val safeDiagnostics: List<String> = listOf("safe signer diagnostic")
}
