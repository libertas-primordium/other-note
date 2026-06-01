package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.InMemoryNoteRepository
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.domain.toPayload
import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
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
import kotlinx.coroutines.runBlocking
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
        assertTrue(state.warnings.single().contains("Rejected 3"))
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
) : NostrClient {
    val published = mutableListOf<NostrEvent>()

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(events, statuses)

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult {
        published += event
        return RelayPublishResult(publishStatuses)
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
