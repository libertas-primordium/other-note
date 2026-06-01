package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.InMemoryNoteRepository
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.UserSession
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
import com.libertasprimordium.othernote.sync.SyncNotesUseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun session(): UserSession = UserSession(
        nsec = "nsec-redacted",
        privateKeyHex = "01".repeat(32),
        npub = "npub-test",
        publicKeyHex = "02".repeat(32),
    )
}

private class FakeClient(
    private val events: List<NostrEvent> = emptyList(),
    private val statuses: List<RelayStatus> = emptyList(),
) : NostrClient {
    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(events, statuses)

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult =
        RelayPublishResult(emptyList())

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}

private class FakeCrypto(
    override val productionReady: Boolean,
) : NostrCrypto {
    override fun generatePrivateKey(): Result<NostrPrivateKey> = Result.failure(UnsupportedOperationException())
    override fun encodeNsec(privateKey: NostrPrivateKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun encodeNpub(publicKey: NostrPublicKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun decodeNsec(nsec: String): KeyDecodeResult = KeyDecodeResult.Invalid("unused")
    override fun derivePublicKey(privateKey: NostrPrivateKey): Result<NostrPublicKey> = Result.failure(UnsupportedOperationException())
    override fun encryptToSelf(plaintext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun decryptFromSelf(ciphertext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun computeEventId(unsigned: UnsignedNostrEvent): Result<String> = Result.failure(UnsupportedOperationException())
    override fun sign(unsigned: UnsignedNostrEvent, privateKey: NostrPrivateKey): Result<NostrEvent> = Result.failure(UnsupportedOperationException())
    override fun validate(event: NostrEvent): Result<Boolean> = Result.failure(UnsupportedOperationException())
}
