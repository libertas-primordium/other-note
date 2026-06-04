package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.data.InMemoryPendingWriteStore
import com.libertasprimordium.othernote.data.PendingWriteStore
import com.libertasprimordium.othernote.domain.NotePayload
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.nostr.FanoutNostrClient
import com.libertasprimordium.othernote.nostr.IncrementalNostrClient
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.ProfileMetadata
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.PublishBestEffortHandle
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
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        val client = AcceptingNostrClient()
        val state = state(fixture, nip44 = nip44, signer = TestEventSigner(fixture.privateKey), client = client)
        val body = "normal editor note"

        state.requestExternalSignerPublicKey()
        val saved = state.save(existing = null, markdown = body)

        assertTrue(saved)
        assertEquals(body, state.notes.notes.value.single().bodyMarkdown)
        assertTrue(state.message.value.startsWith("Saved to"))
        assertTrue(state.message.value.contains("relays"))
        val pendingEvent = state.notes.pendingEvents.value.single()
        assertEquals(30078, pendingEvent.kind)
        assertTrue(pendingEvent.tags.any { it == listOf("t", "other-note") })
        assertTrue(pendingEvent.tags.any { it.firstOrNull() == "d" && it.getOrNull(1)?.startsWith("other-note:note:") == true })
        assertEquals(nip44.latestCiphertext, pendingEvent.content)
        assertTrue(crypto.validate(pendingEvent).getOrThrow())
        assertEquals(pendingEvent, client.published.single())
        assertFalse(state.message.value.contains(body))
        assertFalse(state.message.value.contains(nip44.latestCiphertext))
    }

    @Test
    fun externalSignerSaveDoesNotCreateNoteWhenNoRelayAccepts() = runBlocking {
        val fixture = fixture()
        val state = state(
            fixture,
            signer = TestEventSigner(fixture.privateKey),
            client = AcceptingNostrClient(
                publishStatuses = listOf(RelayStatus("wss://relay.example.com", writable = false, message = "rejected")),
            ),
        )

        state.requestExternalSignerPublicKey()
        val saved = state.save(existing = null, markdown = "not accepted")

        assertFalse(saved)
        assertTrue(state.notes.notes.value.isEmpty())
        assertTrue(state.message.value.contains("no relay accepted"))
    }

    @Test
    fun externalSignerSaveUsesFanoutAndKeepsLateRelayStatuses() = runBlocking {
        val fixture = fixture()
        val client = AcceptingNostrClient(
            firstAcceptedStatuses = listOf(RelayStatus("wss://fast.example.com", writable = true, message = "accepted")),
            completeStatuses = listOf(
                RelayStatus("wss://fast.example.com", writable = true, message = "accepted"),
                RelayStatus("wss://slow.example.com", writable = true, message = "accepted late"),
            ),
            autoCompletePublish = false,
        )
        val state = state(fixture, signer = TestEventSigner(fixture.privateKey), client = client)

        state.requestExternalSignerPublicKey()
        val saved = state.save(existing = null, markdown = "fanout note")

        assertTrue(saved)
        assertEquals("fanout note", state.notes.notes.value.single().bodyMarkdown)
        assertEquals(1, client.publishBestEffortCalls)
        assertTrue(state.message.value.startsWith("Saved to 1/5 relays"))
        client.completePendingPublish()
        assertTrue(client.statusUpdates.flatten().none { it.message.contains("skipped") })
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
    fun externalSignerDeleteCreatesLocalTombstoneAndHidesNote() = runBlocking {
        val fixture = fixture()
        val nip44 = LocalSaveNip44Operator()
        val client = AcceptingNostrClient()
        val state = state(fixture, nip44 = nip44, signer = TestEventSigner(fixture.privateKey), client = client)
        val body = "keep this signer note"

        state.requestExternalSignerPublicKey()
        assertTrue(state.save(existing = null, markdown = body))
        val note = state.notes.notes.value.single()
        val savedEvent = state.notes.pendingEvents.value.single()

        val deleted = state.delete(note)

        assertTrue(deleted)
        assertTrue(state.notes.notes.value.isEmpty())
        assertTrue(state.message.value.startsWith("Deleted locally and sent to"))
        assertTrue(state.message.value.contains("relays"))
        val tombstoneEvent = state.notes.pendingEvents.value.single { it.id != savedEvent.id }
        assertEquals(30078, tombstoneEvent.kind)
        assertEquals(savedEvent.tags.first { it.firstOrNull() == "d" }, tombstoneEvent.tags.first { it.firstOrNull() == "d" })
        assertTrue(tombstoneEvent.tags.any { it == listOf("t", "other-note") })
        assertTrue(crypto.validate(tombstoneEvent).getOrThrow())
        assertEquals(tombstoneEvent, client.published.last())
        val tombstonePayload = JsonNotePayloadCodec.decode(nip44.lastPlaintext ?: error("missing tombstone plaintext")).getOrThrow()
        assertEquals(note.id, tombstonePayload.noteId)
        assertEquals("", tombstonePayload.bodyMarkdown)
        assertTrue(tombstonePayload.deleted)
        assertFalse(state.message.value.contains(body))
        assertFalse(state.message.value.contains("nsec1"))
    }

    @Test
    fun externalSignerDeleteDoesNotHideNoteWhenNoRelayAccepts() = runBlocking {
        val fixture = fixture()
        val client = AcceptingNostrClient()
        val state = state(fixture, signer = TestEventSigner(fixture.privateKey), client = client)
        state.requestExternalSignerPublicKey()
        assertTrue(state.save(existing = null, markdown = "keep"))
        val note = state.notes.notes.value.single()
        val rejected = listOf(RelayStatus("wss://relay.example.com", writable = false, message = "rejected"))
        client.publishStatuses = rejected
        client.firstAcceptedStatuses = rejected
        client.completeStatuses = rejected

        val deleted = state.delete(note)

        assertFalse(deleted)
        assertEquals(note, state.notes.notes.value.single())
        assertTrue(state.message.value.contains("no relay accepted"))
    }

    @Test
    fun externalSignerSyncRecoversFetchedRelayEvent() = runBlocking {
        val fixture = fixture()
        val nip44 = LocalSaveNip44Operator()
        val saveClient = AcceptingNostrClient()
        val firstState = state(fixture, nip44 = nip44, signer = TestEventSigner(fixture.privateKey), client = saveClient)
        firstState.requestExternalSignerPublicKey()
        assertTrue(firstState.save(existing = null, markdown = "recover signer note"))
        val savedEvent = saveClient.published.single()
        val secondNip44 = LocalSaveNip44Operator().also { it.plaintextByCiphertext += nip44.plaintextByCiphertext }
        val fetchClient = AcceptingNostrClient(
            fetchEvents = listOf(savedEvent),
            fetchStatuses = listOf(RelayStatus("wss://relay.example.com", readable = true, message = "ok")),
        )
        val secondState = state(fixture, nip44 = secondNip44, signer = TestEventSigner(fixture.privateKey), client = fetchClient)

        secondState.requestExternalSignerPublicKey()
        secondState.sync()

        assertEquals("recover signer note", secondState.notes.notes.value.single().bodyMarkdown)
        assertTrue(fetchClient.fetchAuthorPubkeys.single() == fixture.publicKeyHex)
        assertFalse(secondState.message.value.contains("recover signer note"))
    }

    @Test
    fun externalSignerSyncAppliesFastRelayBeforeSlowRelayCompletes() = runBlocking {
        val fixture = fixture()
        val nip44 = LocalSaveNip44Operator()
        val saveClient = AcceptingNostrClient()
        val firstState = state(fixture, nip44 = nip44, signer = TestEventSigner(fixture.privateKey), client = saveClient)
        firstState.requestExternalSignerPublicKey()
        assertTrue(firstState.save(existing = null, markdown = "fast signer relay"))
        val savedEvent = saveClient.published.single()
        val secondNip44 = LocalSaveNip44Operator().also { it.plaintextByCiphertext += nip44.plaintextByCiphertext }
        val fetchClient = AcceptingNostrClient(
            incrementalResults = listOf(
                RelayFetchResult(listOf(savedEvent), listOf(RelayStatus("wss://fast.example.com", readable = true, message = "ok"))),
                RelayFetchResult(emptyList(), listOf(RelayStatus("wss://slow.example.com", readable = false, message = "timeout"))),
            ),
            incrementalDelayAfterFirstMs = 1_000,
        )
        val secondState = state(fixture, nip44 = secondNip44, signer = TestEventSigner(fixture.privateKey), client = fetchClient)

        secondState.requestExternalSignerPublicKey()
        val syncJob = launch { secondState.sync() }
        withTimeout(500) {
            while (secondState.notes.notes.value.none { it.bodyMarkdown == "fast signer relay" }) delay(10)
        }
        syncJob.join()

        assertEquals("fast signer relay", secondState.notes.notes.value.single().bodyMarkdown)
    }

    @Test
    fun externalSignerSyncRetriesPendingSignedWritesWithoutSignerRoundTrip() = runBlocking {
        val fixture = fixture()
        val pendingStore = InMemoryPendingWriteStore()
        val pendingEvent = crypto.sign(
            UnsignedNostrEvent(
                pubkey = fixture.publicKeyHex,
                createdAt = 123,
                kind = 30078,
                tags = listOf(listOf("d", "other-note:note:retry-signer"), listOf("t", "other-note")),
                content = "encrypted-signer-retry-ciphertext",
            ),
            fixture.privateKey,
        ).getOrThrow()
        pendingStore.enqueuePendingWrite(
            accountPubkey = fixture.publicKeyHex,
            event = pendingEvent,
            targetRelays = listOf("wss://done.example.com", "wss://retry.example.com"),
        )
        pendingStore.markRelayAccepted(pendingEvent.id, "wss://done.example.com")
        val client = AcceptingNostrClient(
            firstAcceptedStatuses = listOf(RelayStatus("wss://retry.example.com", writable = true, message = "accepted")),
            completeStatuses = listOf(RelayStatus("wss://retry.example.com", writable = true, message = "accepted")),
        )
        val state = state(
            fixture = fixture,
            signer = TestEventSigner(fixture.privateKey),
            client = client,
            pendingWriteStore = pendingStore,
        )

        state.requestExternalSignerPublicKey()
        state.sync()
        withTimeout(1_000) {
            while (client.published.isEmpty() || pendingStore.loadPendingWrites(fixture.publicKeyHex).isNotEmpty()) delay(10)
        }

        assertEquals(pendingEvent, client.published.single())
        assertTrue(pendingStore.loadPendingWrites(fixture.publicKeyHex).isEmpty())
    }

    @Test
    fun externalSignerDeleteCancellationKeepsNoteVisible() = runBlocking {
        val fixture = fixture()
        val nip44 = LocalSaveNip44Operator()
        val state = state(fixture, nip44 = nip44, signer = TestEventSigner(fixture.privateKey))
        state.requestExternalSignerPublicKey()
        assertTrue(state.save(existing = null, markdown = "keep"))
        val note = state.notes.notes.value.single()
        val pendingEventsBeforeDelete = state.notes.pendingEvents.value.toList()
        nip44.encryptResult = SignerNip44OperationResult.Cancelled

        val deleted = state.delete(note)

        assertFalse(deleted)
        assertEquals("Signer note delete cancelled", state.message.value)
        assertEquals(note, state.notes.notes.value.single())
        assertEquals(pendingEventsBeforeDelete, state.notes.pendingEvents.value)
    }

    @Test
    fun externalSignerDeleteRejectsWrongPubkeyAndKeepsNoteVisible() = runBlocking {
        val fixture = fixture()
        val wrongKey = crypto.generatePrivateKey().getOrThrow()
        val signer = TestEventSigner(fixture.privateKey)
        val state = state(fixture, signer = signer)
        state.requestExternalSignerPublicKey()
        assertTrue(state.save(existing = null, markdown = "keep"))
        val note = state.notes.notes.value.single()
        val pendingEventsBeforeDelete = state.notes.pendingEvents.value.toList()
        signer.privateKey = wrongKey

        val deleted = state.delete(note)

        assertFalse(deleted)
        assertTrue(state.message.value.contains("signature") || state.message.value.contains("different pubkey"))
        assertEquals(note, state.notes.notes.value.single())
        assertEquals(pendingEventsBeforeDelete, state.notes.pendingEvents.value)
    }

    @Test
    fun externalSignerDeleteRejectsPayloadMismatchAndKeepsNoteVisible() = runBlocking {
        val fixture = fixture()
        val nip44 = LocalSaveNip44Operator()
        val state = state(fixture, nip44 = nip44, signer = TestEventSigner(fixture.privateKey))
        state.requestExternalSignerPublicKey()
        assertTrue(state.save(existing = null, markdown = "keep"))
        val note = state.notes.notes.value.single()
        val pendingEventsBeforeDelete = state.notes.pendingEvents.value.toList()
        val badPayload = JsonNotePayloadCodec.encode(
            NotePayload(
                noteId = "wrong-note-id",
                createdAtMs = note.createdAtMs,
                updatedAtMs = note.updatedAtMs + 1,
                bodyMarkdown = "",
                deleted = true,
            ),
        )
        nip44.decryptOverride = badPayload

        val deleted = state.delete(note)

        assertFalse(deleted)
        assertTrue(state.message.value.contains("payload"))
        assertEquals(note, state.notes.notes.value.single())
        assertEquals(pendingEventsBeforeDelete, state.notes.pendingEvents.value)
    }

    @Test
    fun externalSignerDeleteRejectsDeletedFalsePayloadAndKeepsNoteVisible() = runBlocking {
        val fixture = fixture()
        val nip44 = LocalSaveNip44Operator()
        val state = state(fixture, nip44 = nip44, signer = TestEventSigner(fixture.privateKey))
        state.requestExternalSignerPublicKey()
        assertTrue(state.save(existing = null, markdown = "keep"))
        val note = state.notes.notes.value.single()
        val pendingEventsBeforeDelete = state.notes.pendingEvents.value.toList()
        val badPayload = JsonNotePayloadCodec.encode(
            NotePayload(
                noteId = note.id,
                createdAtMs = note.createdAtMs,
                updatedAtMs = note.updatedAtMs + 1,
                bodyMarkdown = "",
                deleted = false,
            ),
        )
        nip44.decryptOverride = badPayload

        val deleted = state.delete(note)

        assertFalse(deleted)
        assertTrue(state.message.value.contains("payload"))
        assertEquals(note, state.notes.notes.value.single())
        assertEquals(pendingEventsBeforeDelete, state.notes.pendingEvents.value)
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
        client: NostrClient = AcceptingNostrClient(),
        pendingWriteStore: PendingWriteStore = InMemoryPendingWriteStore(),
    ): AppState {
        val services = AppServices(
            mode = AppRuntimeMode.Offline,
            crypto = crypto,
            client = client,
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
            pendingWriteStore = pendingWriteStore,
        )
        return AppState(services)
    }

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
        var privateKey: com.libertasprimordium.othernote.nostr.NostrPrivateKey,
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
    private val ciphertextPrefix: String = "encrypted-local-save-test",
    var encryptResult: SignerNip44OperationResult? = null,
    var decryptOverride: String? = null,
) : NostrSignerNip44Operator {
    val plaintextByCiphertext = mutableMapOf<String, String>()
    var lastPlaintext: String? = null
    var latestCiphertext: String = ""
        private set
    private var encryptCount = 0

    override fun encryptToSelf(
        plaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult {
        lastPlaintext = plaintext
        latestCiphertext = "$ciphertextPrefix-${++encryptCount}"
        plaintextByCiphertext[latestCiphertext] = plaintext
        return encryptResult ?: SignerNip44OperationResult.Encrypted(latestCiphertext, signerPackage)
    }

    override fun decryptFromSelf(
        ciphertext: String,
        expectedPlaintext: String?,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult =
        SignerNip44OperationResult.Decrypted(
            decryptOverride ?: expectedPlaintext ?: plaintextByCiphertext[ciphertext].orEmpty(),
            signerPackage,
        )
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

private class AcceptingNostrClient(
    private val fetchEvents: List<NostrEvent> = emptyList(),
    private val fetchStatuses: List<RelayStatus> = emptyList(),
    var publishStatuses: List<RelayStatus> = emptyList(),
    var firstAcceptedStatuses: List<RelayStatus> = publishStatuses,
    var completeStatuses: List<RelayStatus> = publishStatuses,
    private val autoCompletePublish: Boolean = true,
    private val incrementalResults: List<RelayFetchResult> = emptyList(),
    private val incrementalDelayAfterFirstMs: Long = 0,
) : NostrClient, FanoutNostrClient, IncrementalNostrClient {
    val published = mutableListOf<NostrEvent>()
    val statusUpdates = mutableListOf<List<RelayStatus>>()
    val fetchAuthorPubkeys = mutableListOf<String>()
    var publishBestEffortCalls = 0
    private var pendingComplete: CompletableDeferred<RelayPublishResult>? = null
    private var pendingOnStatus: ((List<RelayStatus>) -> Unit)? = null

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(fetchEvents, fetchStatuses.ifEmpty { relays.map { RelayStatus(it, readable = true, message = "test read") } })

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult {
        published += event
        return RelayPublishResult(publishStatuses.ifEmpty { relays.map { RelayStatus(it, writable = true, message = "accepted") } })
    }

    override fun publishBestEffort(
        relays: List<String>,
        event: NostrEvent,
        scope: CoroutineScope,
        onStatus: (List<RelayStatus>) -> Unit,
    ): PublishBestEffortHandle {
        publishBestEffortCalls++
        published += event
        val first = firstAcceptedStatuses.ifEmpty { relays.map { RelayStatus(it, writable = true, message = "accepted") } }
        val complete = completeStatuses.ifEmpty { first }
        pendingOnStatus = onStatus
        statusUpdates += first
        onStatus(first)
        val firstAccepted = CompletableDeferred(RelayPublishResult(first))
        val completeDeferred = CompletableDeferred<RelayPublishResult>()
        pendingComplete = completeDeferred
        if (autoCompletePublish) completePendingPublish()
        return PublishBestEffortHandle(firstAccepted, completeDeferred)
    }

    fun completePendingPublish() {
        val complete = completeStatuses.ifEmpty { statusUpdates.lastOrNull() ?: emptyList() }
        statusUpdates += complete
        pendingOnStatus?.invoke(complete)
        pendingComplete?.complete(RelayPublishResult(complete))
    }

    override suspend fun fetchNotesIncrementally(
        relays: List<String>,
        authorPubkey: String,
        onRelayResult: suspend (RelayFetchResult) -> Unit,
    ): RelayFetchResult {
        fetchAuthorPubkeys += authorPubkey
        if (incrementalResults.isEmpty()) {
            return fetchNotes(relays, authorPubkey).also { onRelayResult(it) }
        }
        val events = mutableListOf<NostrEvent>()
        val statuses = mutableListOf<RelayStatus>()
        incrementalResults.forEachIndexed { index, result ->
            if (index > 0 && incrementalDelayAfterFirstMs > 0) delay(incrementalDelayAfterFirstMs)
            events += result.events
            statuses += result.statuses
            onRelayResult(result)
        }
        return RelayFetchResult(events, statuses)
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}
