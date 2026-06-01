package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.InMemoryNoteRepository
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.domain.toPayload
import com.libertasprimordium.othernote.nostr.FanoutNostrClient
import com.libertasprimordium.othernote.nostr.IncrementalNostrClient
import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.PublishBestEffortHandle
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.nostr.ProfileMetadata
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.RelayPublishResult
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.nostr.noteEventTags
import com.libertasprimordium.othernote.sync.DeleteNoteUseCase
import com.libertasprimordium.othernote.sync.SaveNoteUseCase
import com.libertasprimordium.othernote.sync.SaveResult
import com.libertasprimordium.othernote.sync.SyncNotesUseCase
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncSafetyTests {
    @Test
    fun disabledCryptoSyncDoesNotDeleteLocalNotes() = runBlocking {
        val notes = InMemoryNoteRepository()
        val local = Note(id = "local", createdAtMs = 1, updatedAtMs = 1, bodyMarkdown = "local only")
        notes.upsertLocal(local)
        val sync = SyncNotesUseCase(notes, NostrRepository(FakeCrypto(productionReady = false), FakeClient()), FakeCrypto(productionReady = false))

        sync.sync(session(), listOf("wss://relay.example.com"))

        assertEquals(listOf(local), notes.notes.value)
    }

    @Test
    fun failedRelayReadDoesNotDeleteLocalNotes() = runBlocking {
        val notes = InMemoryNoteRepository()
        val local = Note(id = "local", createdAtMs = 1, updatedAtMs = 1, bodyMarkdown = "local only")
        notes.upsertLocal(local)
        val crypto = FakeCrypto(productionReady = true)
        val sync = SyncNotesUseCase(
            notes,
            NostrRepository(crypto, FakeClient(statuses = listOf(RelayStatus("wss://relay.example.com", readable = false, message = "failed")))),
            crypto,
        )

        sync.sync(session(), listOf("wss://relay.example.com"))

        assertEquals(listOf(local), notes.notes.value)
    }

    @Test
    fun emptySuccessfulReadPreservesLocalOnlyNotes() = runBlocking {
        val notes = InMemoryNoteRepository()
        val local = Note(id = "local", createdAtMs = 1, updatedAtMs = 1, bodyMarkdown = "local only")
        notes.upsertLocal(local)
        val crypto = FakeCrypto(productionReady = true)
        val sync = SyncNotesUseCase(
            notes,
            NostrRepository(crypto, FakeClient(statuses = listOf(RelayStatus("wss://relay.example.com", readable = true, message = "ok")))),
            crypto,
        )

        sync.sync(session(), listOf("wss://relay.example.com"))

        assertEquals(listOf(local), notes.notes.value)
    }

    @Test
    fun validFetchedEventsDecryptAndReduceIntoNotes() = runBlocking {
        val notes = InMemoryNoteRepository()
        val crypto = FakeCrypto(productionReady = true)
        val older = signedEvent(crypto, note("remote", "older", updatedAtMs = 2), createdAt = 2)
        val newer = signedEvent(crypto, note("remote", "newer", updatedAtMs = 3), createdAt = 3)
        val sync = SyncNotesUseCase(
            notes,
            NostrRepository(
                crypto,
                FakeClient(
                    events = listOf(older, newer),
                    statuses = listOf(RelayStatus("wss://relay.example.com", readable = true, message = "ok")),
                ),
            ),
            crypto,
        )

        sync.sync(session(), listOf("wss://relay.example.com"))

        assertEquals("newer", notes.notes.value.single().bodyMarkdown)
    }

    @Test
    fun invalidFetchedEventsAreRejectedBeforeDecryptOrDecode() = runBlocking {
        val notes = InMemoryNoteRepository()
        val local = note("local", "preserve", updatedAtMs = 1)
        notes.upsertLocal(local)
        val crypto = FakeCrypto(productionReady = true)
        val valid = signedEvent(crypto, note("remote", "accepted", updatedAtMs = 2), createdAt = 2)
        val wrongAuthor = valid.copy(id = "wrong-author", pubkey = "ff".repeat(32), sig = "valid")
        val invalidSignature = valid.copy(id = "bad-signature", sig = "invalid")
        val undecryptable = valid.copy(id = "undecryptable", content = "missing-ciphertext", sig = "valid")
        val sync = SyncNotesUseCase(
            notes,
            NostrRepository(
                crypto,
                FakeClient(
                    events = listOf(wrongAuthor, invalidSignature, undecryptable, valid),
                    statuses = listOf(RelayStatus("wss://relay.example.com", readable = true, message = "ok")),
                ),
            ),
            crypto,
        )

        val state = sync.sync(session(), listOf("wss://relay.example.com"))

        assertEquals(setOf("local", "remote"), notes.notes.value.map { it.id }.toSet())
        assertTrue(state.warnings.any { it.contains("Rejected 3") })
    }

    @Test
    fun savePublishesEncryptedSignedEventWithExpectedTags() = runBlocking {
        val notes = InMemoryNoteRepository()
        val crypto = FakeCrypto(productionReady = true)
        val client = FakeClient(publishStatuses = listOf(RelayStatus("wss://relay.example.com", writable = true, message = "accepted")))
        val save = SaveNoteUseCase(notes, NostrRepository(crypto, client))

        val result = save.save(existing = null, bodyMarkdown = "body", session = session(), relays = listOf("wss://relay.example.com"))

        assertIs<SaveResult.Published>(result)
        val event = client.published.single()
        val note = notes.notes.value.single()
        assertEquals(NoteKind, event.kind)
        assertEquals(noteDTag(note.id), event.dTag())
        assertTrue(event.isOtherNoteEvent())
        assertFalse(event.content.contains("body"))
    }

    @Test
    fun editPreservesNoteIdAndDTag() = runBlocking {
        val notes = InMemoryNoteRepository()
        val crypto = FakeCrypto(productionReady = true)
        val client = FakeClient(publishStatuses = listOf(RelayStatus("wss://relay.example.com", writable = true, message = "accepted")))
        val save = SaveNoteUseCase(notes, NostrRepository(crypto, client))
        val existing = note("same-id", "old", updatedAtMs = 1)

        save.save(existing = existing, bodyMarkdown = "new", session = session(), relays = listOf("wss://relay.example.com"))

        assertEquals("same-id", notes.notes.value.single().id)
        assertEquals(noteDTag("same-id"), client.published.single().dTag())
    }

    @Test
    fun deletePublishesTombstoneAndHidesAfterAcceptedWrite() = runBlocking {
        val notes = InMemoryNoteRepository()
        val note = note("delete-me", "body", updatedAtMs = 1)
        notes.upsertLocal(note)
        val crypto = FakeCrypto(productionReady = true)
        val client = FakeClient(publishStatuses = listOf(RelayStatus("wss://relay.example.com", writable = true, message = "accepted")))
        val delete = DeleteNoteUseCase(notes, NostrRepository(crypto, client))

        val result = delete.delete(note, session(), listOf("wss://relay.example.com"))

        assertIs<SaveResult.Published>(result)
        assertTrue(notes.notes.value.isEmpty())
        val decoded = crypto.decryptFromSelf(client.published.single().content, NostrPrivateKey(session().privateKeyHex), NostrPublicKey(session().publicKeyHex, session().npub))
            .mapCatching { JsonNotePayloadCodec.decode(it).getOrThrow() }
            .getOrThrow()
        assertTrue(decoded.deleted)
        assertEquals("", decoded.bodyMarkdown)
    }

    @Test
    fun allWriteFailedDoesNotClaimSuccessOrChangeNotes() = runBlocking {
        val notes = InMemoryNoteRepository()
        val crypto = FakeCrypto(productionReady = true)
        val client = FakeClient(publishStatuses = listOf(RelayStatus("wss://relay.example.com", writable = false, message = "rejected")))
        val save = SaveNoteUseCase(notes, NostrRepository(crypto, client))

        val result = save.save(existing = null, bodyMarkdown = "body", session = session(), relays = listOf("wss://relay.example.com"))

        assertIs<SaveResult.Failed>(result)
        assertTrue(notes.notes.value.isEmpty())
    }

    @Test
    fun saveUpdatesAfterFirstAcceptedRelayWithoutWaitingForSlowFailures() = runBlocking {
        val notes = InMemoryNoteRepository()
        val crypto = FakeCrypto(productionReady = true)
        val statusUpdates = mutableListOf<List<RelayStatus>>()
        val client = FakeClient(
            firstAcceptedStatuses = listOf(
                RelayStatus("wss://fast.example.com", writable = true, message = "accepted"),
            ),
            completeStatuses = listOf(
                RelayStatus("wss://fast.example.com", writable = true, message = "accepted"),
                RelayStatus("wss://slow.example.com", writable = false, message = "timeout"),
            ),
            autoCompletePublish = false,
        )
        val save = SaveNoteUseCase(notes, NostrRepository(crypto, client), this) { statusUpdates += it }

        val result = withTimeout(500) {
            save.save(existing = null, bodyMarkdown = "body", session = session(), relays = listOf("wss://fast.example.com", "wss://slow.example.com"))
        }

        assertIs<SaveResult.Published>(result)
        assertEquals("body", notes.notes.value.single().bodyMarkdown)
        assertEquals(1, client.publishBestEffortCalls)
        client.completePendingPublish()
        assertTrue(statusUpdates.flatten().none { it.message.contains("skipped") })
        assertTrue(statusUpdates.last().any { it.url == "wss://slow.example.com" && !it.writable })
    }

    @Test
    fun deleteHidesAfterFirstAcceptedTombstoneWhileOtherWritesContinue() = runBlocking {
        val notes = InMemoryNoteRepository()
        val target = note("delete-fanout", "body", updatedAtMs = 1)
        notes.upsertLocal(target)
        val crypto = FakeCrypto(productionReady = true)
        val statusUpdates = mutableListOf<List<RelayStatus>>()
        val client = FakeClient(
            firstAcceptedStatuses = listOf(RelayStatus("wss://fast.example.com", writable = true, message = "accepted")),
            completeStatuses = listOf(
                RelayStatus("wss://fast.example.com", writable = true, message = "accepted"),
                RelayStatus("wss://slow.example.com", writable = true, message = "accepted late"),
            ),
            autoCompletePublish = false,
        )
        val delete = DeleteNoteUseCase(notes, NostrRepository(crypto, client), this) { statusUpdates += it }

        val result = withTimeout(500) {
            delete.delete(target, session(), listOf("wss://fast.example.com", "wss://slow.example.com"))
        }

        assertIs<SaveResult.Published>(result)
        assertTrue(notes.notes.value.isEmpty())
        client.completePendingPublish()
        assertTrue(statusUpdates.last().all { it.writable })
        assertTrue(statusUpdates.flatten().none { it.message.contains("skipped") })
    }

    @Test
    fun incrementalSyncAppliesValidFastRelayBeforeSlowRelayCompletes() = runBlocking {
        val notes = InMemoryNoteRepository()
        val crypto = FakeCrypto(productionReady = true)
        val valid = signedEvent(crypto, note("remote", "fast", updatedAtMs = 2), createdAt = 2)
        val client = FakeClient(
            incrementalResults = listOf(
                RelayFetchResult(
                    events = listOf(valid),
                    statuses = listOf(RelayStatus("wss://fast.example.com", readable = true, message = "stage=fetch outcome=complete duration_ms=1 EOSE with 1 event(s)")),
                ),
                RelayFetchResult(
                    events = emptyList(),
                    statuses = listOf(RelayStatus("wss://slow.example.com", readable = false, message = "stage=fetch outcome=timeout duration_ms=5000")),
                ),
            ),
            incrementalDelayAfterFirstMs = 1_000,
        )
        val sync = SyncNotesUseCase(notes, NostrRepository(crypto, client), crypto)
        var noteVisibleAfterFirstPartial = false

        val state = sync.sync(session(), listOf("wss://fast.example.com", "wss://slow.example.com")) {
            if (notes.notes.value.any { it.id == "remote" }) noteVisibleAfterFirstPartial = true
        }

        assertTrue(noteVisibleAfterFirstPartial)
        assertEquals("fast", notes.notes.value.single().bodyMarkdown)
        assertTrue(state.warnings.any { it.contains("Partial relay read failure") })
    }

    @Test
    fun laterIncrementalReplacementUpdatesVisibleNoteAndTombstoneHidesIt() = runBlocking {
        val notes = InMemoryNoteRepository()
        val crypto = FakeCrypto(productionReady = true)
        val older = signedEvent(crypto, note("remote", "older", updatedAtMs = 2), createdAt = 2)
        val newer = signedEvent(crypto, note("remote", "newer", updatedAtMs = 3), createdAt = 3)
        val tombstone = signedEvent(crypto, note("remote", "", updatedAtMs = 4).copy(deleted = true), createdAt = 4)
        val client = FakeClient(
            incrementalResults = listOf(
                RelayFetchResult(listOf(older), listOf(RelayStatus("wss://one.example.com", readable = true, message = "ok"))),
                RelayFetchResult(listOf(newer), listOf(RelayStatus("wss://two.example.com", readable = true, message = "ok"))),
                RelayFetchResult(listOf(tombstone), listOf(RelayStatus("wss://three.example.com", readable = true, message = "ok"))),
            ),
        )
        val sync = SyncNotesUseCase(notes, NostrRepository(crypto, client), crypto)
        val observedBodies = mutableListOf<String>()

        sync.sync(session(), listOf("wss://one.example.com", "wss://two.example.com", "wss://three.example.com")) {
            observedBodies += notes.notes.value.singleOrNull()?.bodyMarkdown.orEmpty()
        }

        assertTrue("older" in observedBodies)
        assertTrue("newer" in observedBodies)
        assertTrue(notes.notes.value.isEmpty())
    }

    @Test
    fun savedEventCanBeFetchedOnNextSyncAndDisplayed() = runBlocking {
        val firstNotes = InMemoryNoteRepository()
        val secondNotes = InMemoryNoteRepository()
        val crypto = FakeCrypto(productionReady = true)
        val saveClient = FakeClient(publishStatuses = listOf(RelayStatus("wss://relay.example.com", writable = true, message = "accepted")))
        val save = SaveNoteUseCase(firstNotes, NostrRepository(crypto, saveClient))

        val saveResult = save.save(existing = null, bodyMarkdown = "recover me", session = session(), relays = listOf("wss://relay.example.com"))

        assertIs<SaveResult.Published>(saveResult)
        val fetchClient = FakeClient(
            events = saveClient.published.toList(),
            statuses = listOf(RelayStatus("wss://relay.example.com", readable = true, message = "ok")),
        )
        val sync = SyncNotesUseCase(secondNotes, NostrRepository(crypto, fetchClient), crypto)
        sync.sync(session(), listOf("wss://relay.example.com"))

        assertEquals("recover me", secondNotes.notes.value.single().bodyMarkdown)
        assertTrue(saveClient.published.single().isOtherNoteEvent())
        assertEquals(session().publicKeyHex, saveClient.published.single().pubkey)
    }

    @Test
    fun rejectedReturnedEventsProduceSpecificDiagnosticCounts() = runBlocking {
        val notes = InMemoryNoteRepository()
        val crypto = FakeCrypto(productionReady = true)
        val valid = signedEvent(crypto, note("remote", "accepted", updatedAtMs = 2), createdAt = 2)
        val wrongKind = valid.copy(id = "wrong-kind", kind = 1, sig = "valid")
        val missingT = valid.copy(id = "missing-t", tags = valid.tags.filterNot { it.firstOrNull() == "t" }, sig = "valid")
        val missingD = valid.copy(id = "missing-d", tags = valid.tags.filterNot { it.firstOrNull() == "d" }, sig = "valid")
        val sync = SyncNotesUseCase(
            notes,
            NostrRepository(
                crypto,
                FakeClient(
                    events = listOf(wrongKind, missingT, missingD, valid),
                    statuses = listOf(RelayStatus("wss://relay.example.com", readable = true, message = "ok")),
                ),
            ),
            crypto,
        )

        val state = sync.sync(session(), listOf("wss://relay.example.com"))
        val diagnostic = state.warnings.first()

        assertTrue(diagnostic.contains("rejected_wrong_kind=1"))
        assertTrue(diagnostic.contains("rejected_missing_t=1"))
        assertTrue(diagnostic.contains("rejected_missing_d=1"))
        assertTrue(diagnostic.contains("applied_notes=1"))
    }

    private fun session(): UserSession = UserSession(
        nsec = "nsec-redacted",
        privateKeyHex = "01".repeat(32),
        npub = "npub-test",
        publicKeyHex = "02".repeat(32),
    )

    private fun note(id: String, body: String, updatedAtMs: Long): Note =
        Note(id = id, createdAtMs = 1, updatedAtMs = updatedAtMs, bodyMarkdown = body)

    private fun signedEvent(crypto: FakeCrypto, note: Note, createdAt: Long): NostrEvent {
        val session = session()
        val plaintext = JsonNotePayloadCodec.encode(note.toPayload())
        val ciphertext = crypto.encryptToSelf(plaintext, NostrPrivateKey(session.privateKeyHex), NostrPublicKey(session.publicKeyHex, session.npub)).getOrThrow()
        return crypto.sign(
            UnsignedNostrEvent(
                pubkey = session.publicKeyHex,
                createdAt = createdAt,
                kind = NoteKind,
                tags = noteEventTags(noteDTag(note.id)),
                content = ciphertext,
            ),
            NostrPrivateKey(session.privateKeyHex),
        ).getOrThrow()
    }
}

private class FakeClient(
    private val events: List<NostrEvent> = emptyList(),
    private val statuses: List<RelayStatus> = emptyList(),
    private val publishStatuses: List<RelayStatus> = emptyList(),
    private val firstAcceptedStatuses: List<RelayStatus> = publishStatuses,
    private val completeStatuses: List<RelayStatus> = publishStatuses,
    private val autoCompletePublish: Boolean = true,
    private val incrementalResults: List<RelayFetchResult> = emptyList(),
    private val incrementalDelayAfterFirstMs: Long = 0,
) : IncrementalNostrClient, FanoutNostrClient {
    val published = mutableListOf<NostrEvent>()
    var publishBestEffortCalls = 0
    private var pendingComplete: CompletableDeferred<RelayPublishResult>? = null
    private var pendingOnStatus: ((List<RelayStatus>) -> Unit)? = null

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(events, statuses)

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult {
        published += event
        return RelayPublishResult(publishStatuses)
    }

    override fun publishBestEffort(
        relays: List<String>,
        event: NostrEvent,
        scope: CoroutineScope,
        onStatus: (List<RelayStatus>) -> Unit,
    ): PublishBestEffortHandle {
        publishBestEffortCalls++
        published += event
        pendingOnStatus = onStatus
        onStatus(firstAcceptedStatuses)
        val firstAccepted = CompletableDeferred(RelayPublishResult(firstAcceptedStatuses))
        val complete = CompletableDeferred<RelayPublishResult>()
        pendingComplete = complete
        if (autoCompletePublish) completePendingPublish()
        return PublishBestEffortHandle(firstAccepted, complete)
    }

    fun completePendingPublish() {
        pendingOnStatus?.invoke(completeStatuses)
        pendingComplete?.complete(RelayPublishResult(completeStatuses))
    }

    override suspend fun fetchNotesIncrementally(
        relays: List<String>,
        authorPubkey: String,
        onRelayResult: suspend (RelayFetchResult) -> Unit,
    ): RelayFetchResult {
        if (incrementalResults.isEmpty()) {
            return fetchNotes(relays, authorPubkey).also { onRelayResult(it) }
        }
        val allEvents = mutableListOf<NostrEvent>()
        val allStatuses = mutableListOf<RelayStatus>()
        incrementalResults.forEachIndexed { index, result ->
            if (index > 0 && incrementalDelayAfterFirstMs > 0) delay(incrementalDelayAfterFirstMs)
            allEvents += result.events
            allStatuses += result.statuses
            onRelayResult(result)
        }
        return RelayFetchResult(allEvents, allStatuses)
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}

private class FakeCrypto(
    override val productionReady: Boolean,
) : NostrCrypto {
    private val plaintextByCiphertext = mutableMapOf<String, String>()

    override fun generatePrivateKey(): Result<NostrPrivateKey> = Result.failure(UnsupportedOperationException())
    override fun encodeNsec(privateKey: NostrPrivateKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun encodeNpub(publicKey: NostrPublicKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun decodeNsec(nsec: String): KeyDecodeResult = KeyDecodeResult.Invalid("unused")
    override fun derivePublicKey(privateKey: NostrPrivateKey): Result<NostrPublicKey> = Result.failure(UnsupportedOperationException())
    override fun encryptToSelf(plaintext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String> = runCatching {
        val ciphertext = "cipher-${plaintext.hashCode()}-${plaintext.length}"
        plaintextByCiphertext[ciphertext] = plaintext
        ciphertext
    }
    override fun decryptFromSelf(ciphertext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String> =
        plaintextByCiphertext[ciphertext]?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException("Undecryptable test event"))
    override fun computeEventId(unsigned: UnsignedNostrEvent): Result<String> = Result.success(
        "id-${unsigned.pubkey.take(6)}-${unsigned.createdAt}-${unsigned.dTagForTest()}",
    )
    override fun sign(unsigned: UnsignedNostrEvent, privateKey: NostrPrivateKey): Result<NostrEvent> =
        computeEventId(unsigned).map {
            NostrEvent(
                id = it,
                pubkey = unsigned.pubkey,
                createdAt = unsigned.createdAt,
                kind = unsigned.kind,
                tags = unsigned.tags,
                content = unsigned.content,
                sig = "valid",
            )
        }
    override fun validate(event: NostrEvent): Result<Boolean> = Result.success(event.sig == "valid")

    private fun UnsignedNostrEvent.dTagForTest(): String = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1).orEmpty()
}
