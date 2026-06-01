package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.nostr.ProfileMetadata
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.RelayPublishResult
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.security.NostrSignerEventSigner
import com.libertasprimordium.othernote.security.NostrSignerNip44Operator
import com.libertasprimordium.othernote.security.NostrSignerProvider
import com.libertasprimordium.othernote.security.NostrSignerPublicKeyRequester
import com.libertasprimordium.othernote.security.SignEventRequestResult
import com.libertasprimordium.othernote.security.SignerMode
import com.libertasprimordium.othernote.security.SignerNip44OperationResult
import com.libertasprimordium.othernote.security.SignerPublicKeyRequestResult
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.AppState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignerLocalSaveTests {
    private val crypto = ProductionNostrCryptoFactory.createOrNull()
        ?: error(ProductionNostrCryptoFactory.unavailableReason)

    @Test
    fun externalSignerSaveCreatesVisibleLocalNoteAndEncryptedPendingEvent() = runBlocking {
        val fixture = fixture()
        val nip44 = LocalSaveNip44Operator()
        val state = state(fixture, nip44 = nip44, signer = TestEventSigner(fixture.privateKey))
        val body = "normal editor note"

        state.requestExternalSignerPublicKey()
        val saved = state.save(existing = null, markdown = body)

        assertTrue(saved)
        assertEquals(body, state.notes.notes.value.single().bodyMarkdown)
        assertEquals("Saved locally", state.message.value.substringBefore(" ("))
        assertTrue(state.message.value.contains("Not synced to relays"))
        val pendingEvent = state.notes.pendingEvents.value.single()
        assertEquals(30078, pendingEvent.kind)
        assertTrue(pendingEvent.tags.any { it == listOf("t", "other-note") })
        assertTrue(pendingEvent.tags.any { it.firstOrNull() == "d" && it.getOrNull(1)?.startsWith("other-note:note:") == true })
        assertEquals(nip44.ciphertext, pendingEvent.content)
        assertTrue(crypto.validate(pendingEvent).getOrThrow())
        assertFalse(state.message.value.contains(body))
        assertFalse(state.message.value.contains(nip44.ciphertext))
    }

    @Test
    fun externalSignerSaveRejectsWrongPubkeyAndDoesNotCreateNote() = runBlocking {
        val fixture = fixture()
        val wrongKey = crypto.generatePrivateKey().getOrThrow()
        val state = state(fixture, signer = TestEventSigner(wrongKey))

        state.requestExternalSignerPublicKey()
        val saved = state.save(existing = null, markdown = "not saved")

        assertFalse(saved)
        assertTrue(state.notes.notes.value.isEmpty())
        assertTrue(state.message.value.contains("different pubkey"))
    }

    @Test
    fun externalSignerSaveCancellationDoesNotCreateNote() = runBlocking {
        val fixture = fixture()
        val state = state(
            fixture,
            nip44 = LocalSaveNip44Operator(encryptResult = SignerNip44OperationResult.Cancelled),
            signer = TestEventSigner(fixture.privateKey),
        )

        state.requestExternalSignerPublicKey()
        val saved = state.save(existing = null, markdown = "not saved")

        assertFalse(saved)
        assertTrue(state.notes.notes.value.isEmpty())
        assertEquals("Signer note save cancelled", state.message.value)
    }

    @Test
    fun externalSignerSaveRejectsDecryptMismatchAndDoesNotCreateNote() = runBlocking {
        val fixture = fixture()
        val state = state(
            fixture,
            nip44 = LocalSaveNip44Operator(decryptOverride = """{"schema":"wrong"}"""),
            signer = TestEventSigner(fixture.privateKey),
        )

        state.requestExternalSignerPublicKey()
        val saved = state.save(existing = null, markdown = "not saved")

        assertFalse(saved)
        assertTrue(state.notes.notes.value.isEmpty())
        assertTrue(state.message.value.contains("payload"))
    }

    @Test
    fun externalSignerEditPreservesNoteIdLocally() = runBlocking {
        val fixture = fixture()
        val state = state(fixture, signer = TestEventSigner(fixture.privateKey))

        state.requestExternalSignerPublicKey()
        assertTrue(state.save(existing = null, markdown = "first"))
        val original = state.notes.notes.value.single()
        assertTrue(state.save(existing = original, markdown = "edited"))

        val edited = state.notes.notes.value.single()
        assertEquals(original.id, edited.id)
        assertEquals("edited", edited.bodyMarkdown)
    }

    @Test
    fun externalSignerDeleteReportsUnsupportedAndKeepsNoteVisible() = runBlocking {
        val fixture = fixture()
        val state = state(fixture, signer = TestEventSigner(fixture.privateKey))
        val body = "keep this signer note"

        state.requestExternalSignerPublicKey()
        assertTrue(state.save(existing = null, markdown = body))
        val note = state.notes.notes.value.single()
        val pendingEventsBeforeDelete = state.notes.pendingEvents.value.toList()

        val deleted = state.delete(note)

        assertFalse(deleted)
        assertEquals("Signer-backed delete is not implemented yet.", state.message.value)
        assertEquals(body, state.notes.notes.value.single().bodyMarkdown)
        assertFalse(state.notes.notes.value.single().deleted)
        assertEquals(pendingEventsBeforeDelete, state.notes.pendingEvents.value)
        assertFalse(state.message.value.contains(body))
        assertFalse(state.message.value.contains("nsec1"))
    }

    @Test
    fun directNsecDeleteStillUsesExistingSignedDeletePath() = runBlocking {
        val privateKey = crypto.generatePrivateKey().getOrThrow()
        val nsec = crypto.encodeNsec(privateKey).getOrThrow()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = crypto,
                client = AcceptingNostrClient(),
            ),
        )

        assertTrue(state.login(nsec))
        assertTrue(state.save(existing = null, markdown = "direct nsec note"))
        val note = state.notes.notes.value.single()
        assertTrue(state.delete(note))

        assertTrue(state.notes.notes.value.isEmpty())
        assertTrue(state.message.value.startsWith("Delete saved to"))
    }

    private fun state(
        fixture: Fixture,
        nip44: LocalSaveNip44Operator = LocalSaveNip44Operator(),
        signer: NostrSignerEventSigner,
    ): AppState = AppState(
        AppServices(
            mode = AppRuntimeMode.Offline,
            crypto = crypto,
            client = OfflineNostrClient(),
            externalSignerProvider = AvailableSignerProvider,
            externalSignerPublicKeyRequester = TestPublicKeyRequester(
                SignerPublicKeyRequestResult.Success(
                    pubkeyHex = fixture.publicKeyHex,
                    npub = fixture.npub,
                    signerPackage = "com.example.signer",
                ),
            ),
            externalSignerEventSigner = signer,
            externalSignerNip44Operator = nip44,
        ),
    )

    private fun fixture(): Fixture {
        val privateKey = crypto.generatePrivateKey().getOrThrow()
        val publicKey = crypto.derivePublicKey(privateKey).getOrThrow()
        return Fixture(privateKey, publicKey.hex, publicKey.npub)
    }

    private data class Fixture(
        val privateKey: com.libertasprimordium.othernote.nostr.NostrPrivateKey,
        val publicKeyHex: String,
        val npub: String,
    )

    private inner class TestEventSigner(
        private val privateKey: com.libertasprimordium.othernote.nostr.NostrPrivateKey,
    ) : NostrSignerEventSigner {
        override fun signEvent(
            unsignedEvent: NostrEvent,
            currentUserPubkey: String,
            signerPackage: String?,
            onResult: (SignEventRequestResult) -> Unit,
        ) {
            val signed = crypto.sign(
                UnsignedNostrEvent(
                    unsignedEvent.pubkey,
                    unsignedEvent.createdAt,
                    unsignedEvent.kind,
                    unsignedEvent.tags,
                    unsignedEvent.content,
                ),
                privateKey,
            ).getOrThrow()
            onResult(SignEventRequestResult.Success(signed, signerPackage))
        }
    }
}

private class TestPublicKeyRequester(
    private val result: SignerPublicKeyRequestResult,
) : NostrSignerPublicKeyRequester {
    override fun requestPublicKey(onResult: (SignerPublicKeyRequestResult) -> Unit) {
        onResult(result)
    }
}

private class LocalSaveNip44Operator(
    val ciphertext: String = "encrypted-local-save-test",
    private val encryptResult: SignerNip44OperationResult? = null,
    private val decryptOverride: String? = null,
) : NostrSignerNip44Operator {
    override fun encryptToSelf(
        plaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult =
        encryptResult ?: SignerNip44OperationResult.Encrypted(ciphertext, signerPackage)

    override fun decryptFromSelf(
        ciphertext: String,
        expectedPlaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult =
        SignerNip44OperationResult.Decrypted(decryptOverride ?: expectedPlaintext, signerPackage)
}

private object AvailableSignerProvider : NostrSignerProvider {
    override val mode: SignerMode = SignerMode.ExternalSigner
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null
    override val displayName: String = "Test NIP-55 Signer"
    override val canGetPublicKey: Boolean = true
    override val canSignEvent: Boolean = true
    override val canNip44EncryptDecrypt: Boolean = true
    override val safeDiagnostics: List<String> = listOf("safe test signer available")
}

private class AcceptingNostrClient : NostrClient {
    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(emptyList(), relays.map { RelayStatus(it, readable = true, message = "test read") })

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult =
        RelayPublishResult(relays.map { RelayStatus(it, writable = true, message = "accepted") })

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}
