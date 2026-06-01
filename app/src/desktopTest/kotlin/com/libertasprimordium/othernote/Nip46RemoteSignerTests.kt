package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.InMemoryLocalEventCache
import com.libertasprimordium.othernote.data.InMemoryPendingWriteStore
import com.libertasprimordium.othernote.domain.RelayConfig
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.SessionAuthMethod
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrWireJson
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.ProfileMetadata
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.RelayPublishResult
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.security.Nip46ConnectResult
import com.libertasprimordium.othernote.security.Nip46Method
import com.libertasprimordium.othernote.security.Nip46RemoteSigner
import com.libertasprimordium.othernote.security.Nip46RequestPayload
import com.libertasprimordium.othernote.security.Nip46RequestTransport
import com.libertasprimordium.othernote.security.Nip46Response
import com.libertasprimordium.othernote.security.Nip46TransportSession
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip46RemoteSignerTests {
    private val crypto = ProductionNostrCryptoFactory.createOrNull()
        ?: error(ProductionNostrCryptoFactory.unavailableReason)

    @Test
    fun connectValidatesSecretAndSetsUserPubkey() = runBlocking {
        val fixture = fixture()
        val transport = FakeNip46Transport(crypto, fixture, expectedSecret = "secret")
        val signer = Nip46RemoteSigner(transport, crypto)

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=secret")

        val connected = assertIs<Nip46ConnectResult.Connected>(result)
        assertEquals(fixture.userPubkey, connected.userPubkey)
        assertEquals(fixture.remotePubkey, transport.connectParams.single()[0])
        assertEquals("secret", transport.connectParams.single()[1])
        assertNotEquals(transport.connectClientPubkeys.single(), transport.connectParams.single()[0])
        assertTrue(transport.connectParams.single()[2].contains("sign_event:30078"))
        assertTrue(transport.connectParams.single()[2].contains("nip44_encrypt"))
        assertTrue(signer.canSignEvent)
        assertTrue(signer.canNip44RoundTrip)
    }

    @Test
    fun connectWithoutSecretStillSendsRemoteSignerPubkeyFirst() = runBlocking {
        val fixture = fixture()
        val transport = FakeNip46Transport(crypto, fixture)
        val signer = Nip46RemoteSigner(transport, crypto)

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com")

        assertIs<Nip46ConnectResult.Connected>(result)
        val params = transport.connectParams.single()
        assertEquals(fixture.remotePubkey, params[0])
        assertEquals("", params[1])
        assertTrue(params[2].contains("get_public_key"))
        assertTrue(params[2].contains("switch_relays"))
        assertNotEquals(transport.connectClientPubkeys.single(), params[0])
    }

    @Test
    fun connectFailureDoesNotExposeSecret() = runBlocking {
        val fixture = fixture()
        val signer = Nip46RemoteSigner(FakeNip46Transport(crypto, fixture, expectedSecret = "expected"), crypto)

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=wrong-secret")

        val failed = assertIs<Nip46ConnectResult.Failed>(result)
        assertFalse(failed.safeReason.contains("wrong-secret"))
        assertFalse(failed.safeReason.contains("expected"))
    }

    @Test
    fun requestTimeoutMapsToCleanConnectResult() = runBlocking {
        val fixture = fixture()
        val signer = Nip46RemoteSigner(FakeNip46Transport(crypto, fixture, timeout = true), crypto)

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=secret")

        assertEquals(Nip46ConnectResult.TimedOut, result)
        assertTrue(signer.safeDiagnostics.joinToString("\n").contains("timed out"))
        assertFalse(signer.safeDiagnostics.joinToString("\n").contains("must-not-appear"))
    }

    @Test
    fun requestFailureDiagnosticsAreSafeAndStageAware() = runBlocking {
        val fixture = fixture()
        val signer = Nip46RemoteSigner(
            FakeNip46Transport(
                crypto,
                fixture,
                throwMessage = "stage=relay_publish_failed statuses=wss://relay.example.com writable=false outcome=rejected secret=must-not-appear",
            ),
            crypto,
        )

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=must-not-appear")

        val failed = assertIs<Nip46ConnectResult.Failed>(result)
        assertTrue(failed.safeReason.contains("relay_publish_failed"))
        assertFalse(failed.safeReason.contains("must-not-appear"))
    }

    @Test
    fun responseDecryptAndIdMismatchDiagnosticsAreDistinct() = runBlocking {
        val fixture = fixture()
        val decryptSigner = Nip46RemoteSigner(
            FakeNip46Transport(crypto, fixture, throwMessage = "stage=response_decrypt_failed events=1 count=1 secret=must-not-appear"),
            crypto,
        )
        val mismatchSigner = Nip46RemoteSigner(
            FakeNip46Transport(crypto, fixture, throwMessage = "stage=response_id_mismatch events=1 count=1 secret=must-not-appear"),
            crypto,
        )

        val decryptFailure = assertIs<Nip46ConnectResult.Failed>(
            decryptSigner.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=must-not-appear"),
        )
        val mismatchFailure = assertIs<Nip46ConnectResult.Failed>(
            mismatchSigner.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=must-not-appear"),
        )

        assertTrue(decryptFailure.safeReason.contains("response_decrypt_failed"))
        assertTrue(mismatchFailure.safeReason.contains("response_id_mismatch"))
        assertFalse(decryptFailure.safeReason.contains("must-not-appear"))
        assertFalse(mismatchFailure.safeReason.contains("must-not-appear"))
    }

    @Test
    fun switchRelaysSendsNoParams() = runBlocking {
        val fixture = fixture()
        val transport = FakeNip46Transport(crypto, fixture, switchRelayResult = """["wss://switched.example.com"]""")
        val signer = Nip46RemoteSigner(transport, crypto)

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com")

        val connected = assertIs<Nip46ConnectResult.Connected>(result)
        assertEquals(listOf(emptyList()), transport.switchRelayParams)
        assertEquals(listOf("wss://switched.example.com"), connected.relays)
    }

    @Test
    fun signEventRejectsMismatchedPubkeyAndContent() {
        runBlocking {
            val fixture = fixture()
            val requested = unsignedEvent(fixture.userPubkey, "encrypted-content")
            val wrongPubkeySigner = connectedSigner(fixture, FakeNip46Transport(crypto, fixture, wrongSignedPubkey = true))
            val wrongContentSigner = connectedSigner(fixture, FakeNip46Transport(crypto, fixture, wrongSignedContent = true))

            val wrongPubkeyResult = wrongPubkeySigner.signEventAsync(requested, fixture.userPubkey, "nip46")
            val wrongContentResult = wrongContentSigner.signEventAsync(requested, fixture.userPubkey, "nip46")

            assertIs<com.libertasprimordium.othernote.security.SignEventRequestResult.InvalidResponse>(wrongPubkeyResult)
            assertIs<com.libertasprimordium.othernote.security.SignEventRequestResult.InvalidResponse>(wrongContentResult)
        }
    }

    @Test
    fun signEventSendsSpecCompatibleUnsignedEventPayload() = runBlocking {
        val fixture = fixture()
        val transport = FakeNip46Transport(crypto, fixture)
        val signer = connectedSigner(fixture, transport)
        val requested = unsignedEvent(fixture.userPubkey, "encrypted-content")

        val signResult = signer.signEventAsync(requested, fixture.userPubkey, "nip46")

        assertIs<com.libertasprimordium.othernote.security.SignEventRequestResult.Success>(signResult)
        val payload = Json.parseToJsonElement(transport.signEventPayloads.single()).jsonObject
        assertNull(payload["pubkey"])
        assertNull(payload["id"])
        assertNull(payload["sig"])
        assertEquals(30078, payload["kind"]?.jsonPrimitive?.content?.toInt())
        assertEquals("encrypted-content", payload["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun nip44EncryptAndDecryptDelegateToRemoteSigner() {
        runBlocking {
            val fixture = fixture()
            val signer = connectedSigner(fixture, FakeNip46Transport(crypto, fixture))

            val encrypted = signer.encryptToSelfAsync("plain note payload", fixture.userPubkey, "nip46")
            val ciphertext = assertIs<com.libertasprimordium.othernote.security.SignerNip44OperationResult.Encrypted>(encrypted).payload
            val decrypted = signer.decryptFromSelfAsync(ciphertext, "plain note payload", fixture.userPubkey, "nip46")

            assertIs<com.libertasprimordium.othernote.security.SignerNip44OperationResult.Decrypted>(decrypted)
        }
    }

    @Test
    fun appCanSaveEncryptedNoteWithNip46Signer() = runBlocking {
        val fixture = fixture()
        val remoteSigner = Nip46RemoteSigner(FakeNip46Transport(crypto, fixture), crypto)
        val cache = InMemoryLocalEventCache()
        val pending = InMemoryPendingWriteStore()
        val relayClient = CapturingNostrClient()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = crypto,
                client = relayClient,
                remoteSigner = remoteSigner,
                localEventCache = cache,
                pendingWriteStore = pending,
                relaySettings = com.libertasprimordium.othernote.data.RelaySettingsStore(
                    listOf(RelayConfig("wss://relay.example.com")),
                ),
            ),
        )

        assertTrue(state.connectRemoteSigner("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com"))
        assertEquals(SessionAuthMethod.RemoteSigner, state.session.value?.authMethod)
        assertTrue(state.save(existing = null, markdown = "nip46 private note"))

        val published = relayClient.published.single()
        assertEquals(30078, published.kind)
        assertEquals(fixture.userPubkey, published.pubkey)
        assertFalse(published.content.contains("nip46 private note"))
        assertEquals(listOf(published), cache.loadEvents(fixture.userPubkey))
    }

    @Test
    fun remoteSignerLoginStartsAsyncAndDoesNotBlockCaller() = runBlocking {
        val fixture = fixture()
        val remoteSigner = Nip46RemoteSigner(FakeNip46Transport(crypto, fixture, delayMs = 150), crypto)
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = crypto,
                client = CapturingNostrClient(),
                remoteSigner = remoteSigner,
            ),
        )

        val accepted = state.startRemoteSignerConnection("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com")

        assertTrue(accepted)
        assertEquals(null, state.session.value)
        assertTrue(state.message.value.contains("Connecting"))
        withTimeout(1_500) {
            while (state.session.value?.authMethod != SessionAuthMethod.RemoteSigner) delay(10)
        }
    }

    private fun connectedSigner(fixture: Fixture, transport: FakeNip46Transport): Nip46RemoteSigner {
        val signer = Nip46RemoteSigner(transport, crypto)
        runBlocking {
            assertIs<Nip46ConnectResult.Connected>(signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com"))
        }
        return signer
    }

    private fun fixture(): Fixture {
        val userPrivateKey = crypto.generatePrivateKey().getOrThrow()
        val userPublicKey = crypto.derivePublicKey(userPrivateKey).getOrThrow()
        val remotePrivateKey = crypto.generatePrivateKey().getOrThrow()
        val remotePublicKey = crypto.derivePublicKey(remotePrivateKey).getOrThrow()
        return Fixture(userPrivateKey, userPublicKey.hex, userPublicKey.npub, remotePublicKey.hex)
    }

    private fun unsignedEvent(pubkey: String, content: String): NostrEvent {
        val unsigned = UnsignedNostrEvent(
            pubkey = pubkey,
            createdAt = 123,
            kind = 30078,
            tags = listOf(listOf("d", "other-note:note:nip46"), listOf("t", "other-note")),
            content = content,
        )
        return NostrEvent(
            id = crypto.computeEventId(unsigned).getOrThrow(),
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = "",
        )
    }
}

private data class Fixture(
    val userPrivateKey: NostrPrivateKey,
    val userPubkey: String,
    val userNpub: String,
    val remotePubkey: String,
)

private class FakeNip46Transport(
    private val crypto: NostrCrypto,
    private val fixture: Fixture,
    private val expectedSecret: String? = null,
    private val wrongSignedPubkey: Boolean = false,
    private val wrongSignedContent: Boolean = false,
    private val timeout: Boolean = false,
    private val throwMessage: String? = null,
    private val switchRelayResult: String = "null",
    private val delayMs: Long = 0,
) : Nip46RequestTransport {
    private val plaintextByCiphertext = mutableMapOf<String, String>()
    val connectParams = mutableListOf<List<String>>()
    val connectClientPubkeys = mutableListOf<String>()
    val switchRelayParams = mutableListOf<List<String>>()
    val signEventPayloads = mutableListOf<String>()

    override suspend fun sendRequest(session: Nip46TransportSession, request: Nip46RequestPayload): Result<Nip46Response> = runCatching {
        if (delayMs > 0) delay(delayMs)
        throwMessage?.let { error(it) }
        if (timeout) error("request timed out secret=must-not-appear")
        when (request.method) {
            Nip46Method.Connect.wireName -> {
                connectParams += request.params
                connectClientPubkeys += session.clientPubkey
                if (request.params.getOrNull(0) != fixture.remotePubkey || request.params.getOrNull(0) == session.clientPubkey) {
                    Nip46Response.Error(request.id, "Remote signer rejected connect params")
                } else if (request.params.getOrNull(2)?.contains("sign_event:30078") != true) {
                    Nip46Response.Error(request.id, "Remote signer rejected missing permissions")
                } else if (request.params.getOrNull(2)?.contains("nip44_decrypt") != true) {
                    Nip46Response.Error(request.id, "Remote signer rejected missing permissions")
                } else if (expectedSecret != null && request.params.getOrNull(1) != expectedSecret) {
                    Nip46Response.Error(request.id, "Remote signer rejected connect secret")
                } else {
                    Nip46Response.Success(request.id, "ack")
                }
            }
            Nip46Method.Ping.wireName -> Nip46Response.Success(request.id, "pong")
            Nip46Method.GetPublicKey.wireName -> Nip46Response.Success(request.id, fixture.userPubkey)
            Nip46Method.SwitchRelays.wireName -> {
                switchRelayParams += request.params
                if (request.params.isNotEmpty()) {
                    Nip46Response.Error(request.id, "Remote signer rejected switch_relays params")
                } else {
                    Nip46Response.Success(request.id, switchRelayResult)
                }
            }
            Nip46Method.SignEvent.wireName -> {
                val payload = request.params.single()
                signEventPayloads += payload
                val requested = parseUnsignedSignEventPayload(payload)
                val unsigned = UnsignedNostrEvent(
                    pubkey = fixture.userPubkey,
                    createdAt = requested.createdAt,
                    kind = requested.kind,
                    tags = requested.tags,
                    content = requested.content,
                )
                val signed = crypto.sign(unsigned, fixture.userPrivateKey).getOrThrow()
                    .let { if (wrongSignedPubkey) it.copy(pubkey = fixture.remotePubkey) else it }
                    .let { if (wrongSignedContent) it.copy(content = it.content + "-changed") else it }
                Nip46Response.Success(request.id, NostrWireJson.eventJson(signed))
            }
            Nip46Method.Nip44Encrypt.wireName -> {
                val plaintext = request.params.getOrNull(1) ?: error("missing plaintext")
                val ciphertext = "encrypted-nip46-${plaintext.hashCode()}-${plaintext.length}"
                plaintextByCiphertext[ciphertext] = plaintext
                Nip46Response.Success(request.id, ciphertext)
            }
            Nip46Method.Nip44Decrypt.wireName -> {
                val ciphertext = request.params.getOrNull(1) ?: error("missing ciphertext")
                Nip46Response.Success(request.id, plaintextByCiphertext[ciphertext] ?: error("missing plaintext"))
            }
            else -> Nip46Response.Error(request.id, "unsupported method")
        }
    }

    private data class UnsignedSignRequest(
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
    )

    private fun parseUnsignedSignEventPayload(raw: String): UnsignedSignRequest {
        val obj = Json.parseToJsonElement(raw).jsonObject
        require(obj["pubkey"] == null) { "NIP-46 sign_event request must not include pubkey" }
        require(obj["id"] == null) { "NIP-46 sign_event request must not include id" }
        require(obj["sig"] == null) { "NIP-46 sign_event request must not include sig" }
        val tags = obj["tags"]?.jsonArray?.map { tag ->
            tag.jsonArray.map { it.jsonPrimitive.content }
        } ?: emptyList()
        return UnsignedSignRequest(
            createdAt = obj["created_at"]?.jsonPrimitive?.content?.toLong() ?: error("missing created_at"),
            kind = obj["kind"]?.jsonPrimitive?.content?.toInt() ?: error("missing kind"),
            tags = tags,
            content = obj["content"]?.jsonPrimitive?.content ?: error("missing content"),
        )
    }
}

private class CapturingNostrClient : NostrClient {
    val published = mutableListOf<NostrEvent>()

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(emptyList(), relays.map { RelayStatus(it, readable = true, message = "ok") })

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult {
        published += event
        return RelayPublishResult(relays.map { RelayStatus(it, writable = true, message = "accepted") })
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}
