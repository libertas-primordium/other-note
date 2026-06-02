package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.NotePayload
import com.libertasprimordium.othernote.domain.DefaultRelays
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.data.RelaySettingsCodec
import com.libertasprimordium.othernote.data.RelaySettingsPersistence
import com.libertasprimordium.othernote.data.RelaySettingsStore
import com.libertasprimordium.othernote.nostr.NostrEventSerialization
import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.Nip19
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.sync.planRelayMigration
import com.libertasprimordium.othernote.sync.reduceNoteEvents
import com.libertasprimordium.othernote.sync.selectLatestSignedEncryptedNoteEvents
import com.libertasprimordium.othernote.ui.noteGridColumnCount
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import com.libertasprimordium.othernote.util.MediaType
import com.libertasprimordium.othernote.util.detectUrls
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import com.libertasprimordium.othernote.util.truncateMarkdown
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UtilityTests {
    @Test
    fun relayUrlsNormalizeAndRejectInvalidSchemes() {
        assertEquals("wss://relay.primal.net", normalizeRelayUrl("relay.primal.net").getOrThrow())
        assertEquals("wss://relay.example.com/path", normalizeRelayUrl("relay.example.com/path").getOrThrow())
        assertEquals("wss://relay.example.com", normalizeRelayUrl(" WSS://Relay.Example.com/ ").getOrThrow())
        assertEquals("wss://relay.example.com/nostr", normalizeRelayUrl("wss://relay.example.com/nostr/").getOrThrow())
        assertEquals("ws://localhost:7000", normalizeRelayUrl("ws://localhost:7000/").getOrThrow())
        assertTrue(normalizeRelayUrl("https://relay.example.com").isFailure)
        assertTrue(normalizeRelayUrl("http://relay.example.com").isFailure)
        assertTrue(normalizeRelayUrl("wss://relay.example.com/?token=secret").isFailure)
        assertTrue(normalizeRelayUrl("wss://relay.example.com/#fragment").isFailure)
        assertTrue(normalizeRelayUrl("wss://relay example.com").isFailure)
        assertTrue(normalizeRelayUrl("not a relay").isFailure)
        assertTrue(normalizeRelayUrl("ws://relay.example.com").isFailure)
    }

    @Test
    fun relaySettingsDeduplicateAndRejectEmptyLists() {
        val store = RelaySettingsStore()

        val preview = store.previewChange(
            listOf(
                " WSS://Relay.Example.com/ ",
                "relay.example.com",
                "wss://relay.example.com/nostr/",
            ),
        ).getOrThrow()

        assertEquals(listOf("wss://relay.example.com", "wss://relay.example.com/nostr"), preview.map { it.url })
        assertTrue(store.previewChange(emptyList()).isFailure)
    }

    @Test
    fun relaySettingsPersistenceRoundTripsSafeRelayUrls() = runBlocking {
        val persistence = MemoryRelaySettingsPersistence()
        val store = RelaySettingsStore(persistence = persistence)
        val custom = store.previewChange(listOf("wss://relay.example.com", "wss://relay.example.com/nostr")).getOrThrow()

        store.commitAndPersist(custom)
        val restored = RelaySettingsStore(persistence = persistence)
        restored.loadPersisted()

        assertEquals(custom.map { it.url }, restored.normalizedUrls())
        val serialized = persistence.raw ?: error("Missing serialized relay settings")
        assertTrue(serialized.contains("wss://relay.example.com"))
        assertFalse(serialized.contains("nsec"))
        assertFalse(serialized.contains("privateKey"))
        assertFalse(serialized.contains("body_markdown"))
    }

    @Test
    fun relaySettingsRestoreDefaultsPersistsExpectedDefaultSet() = runBlocking {
        val persistence = MemoryRelaySettingsPersistence()
        val store = RelaySettingsStore(persistence = persistence)
        val custom = store.previewChange(listOf("wss://relay.example.com")).getOrThrow()
        store.commitAndPersist(custom)

        store.restoreDefaultsAndPersist()

        assertEquals(DefaultRelays.map { it.url }, store.normalizedUrls())
        assertEquals(DefaultRelays.map { it.url }, RelaySettingsCodec.decodeOrNull(persistence.raw.orEmpty()))
    }

    @Test
    fun urlDetectionClassifiesSimpleMediaExtensions() {
        val urls = detectUrls("See https://example.com/a.png and https://example.com/watch.mp4")
        assertEquals(MediaType.Image, urls[0].type)
        assertEquals(MediaType.Video, urls[1].type)
    }

    @Test
    fun payloadJsonRoundTripsMarkdown() {
        val payload = NotePayload(
            noteId = "note-1",
            createdAtMs = 1,
            updatedAtMs = 2,
            bodyMarkdown = "# Title\nbody \"quoted\"",
            deleted = false,
        )
        assertEquals(payload, JsonNotePayloadCodec.decode(JsonNotePayloadCodec.encode(payload)).getOrThrow())
    }

    @Test
    fun payloadJsonRoundTripsEscapedUnicodeAndCodeBlocks() {
        val payload = NotePayload(
            noteId = "note-json",
            createdAtMs = 100,
            updatedAtMs = 200,
            bodyMarkdown = "Quote: \"hello\"\nBackslash: \\\nTab:\t\nUnicode: こんにちは\n```kotlin\nprintln(\"x\")\n```",
            deleted = false,
        )
        val encoded = JsonNotePayloadCodec.encode(payload)
        assertTrue(encoded.contains("body_markdown"))
        assertEquals(payload, JsonNotePayloadCodec.decode(encoded).getOrThrow())
    }

    @Test
    fun nip01PreimageDoesNotUseNotePayloadCodec() {
        val preimage = NostrEventSerialization.canonicalPreimage(
            UnsignedNostrEvent(
                pubkey = "pub",
                createdAt = 123,
                kind = 30078,
                tags = listOf(listOf("d", "other-note:note:abc"), listOf("t", "other-note")),
                content = "encrypted-content",
            ),
        )
        assertEquals("""[0,"pub",123,30078,[["d","other-note:note:abc"],["t","other-note"]],"encrypted-content"]""", preimage)
    }

    @Test
    fun nip19RejectsMixedCaseAndAcceptsLowercase() {
        val encoded = Nip19.encode("npub", ByteArray(32) { it.toByte() }) ?: error("npub encode failed")
        assertEquals("npub", Nip19.decode(encoded)?.hrp)
        val mixedCase = encoded.take(8).uppercase() + encoded.drop(8)
        assertEquals(null, Nip19.decode(mixedCase))
    }

    @Test
    fun reducerSelectsNewestPerDTagAndExcludesTombstones() {
        val old = event("a", 10, "note-1", deleted = false, body = "old")
        val newest = event("b", 20, "note-1", deleted = false, body = "new")
        val tombstone = event("c", 30, "note-2", deleted = true, body = "")
        val reduced = reduceNoteEvents(listOf(old, newest, tombstone)) { Result.success(it.content) }
        assertEquals(1, reduced.notes.size)
        assertEquals("new", reduced.notes.single().bodyMarkdown)
        assertEquals("b", reduced.selectedEvents.first { it.dTag() == noteDTag("note-1") }.id)
    }

    @Test
    fun reducerSelectsNewestPerDTagIndependentOfInputOrder() {
        val old = event("a", 10, "note-1", deleted = false, body = "old")
        val edited = event("b", 20, "note-1", deleted = false, body = "edited")
        val other = event("c", 15, "note-2", deleted = false, body = "other")

        val reduced = reduceNoteEvents(listOf(edited, other, old)) { Result.success(it.content) }

        assertEquals(setOf("edited", "other"), reduced.notes.map { it.bodyMarkdown }.toSet())
        assertEquals("b", reduced.selectedEvents.first { it.dTag() == noteDTag("note-1") }.id)
    }

    @Test
    fun reducerUsesEventIdTieBreakerForSameCreatedAt() {
        val low = event("aaa", 20, "note-1", deleted = false, body = "low")
        val high = event("zzz", 20, "note-1", deleted = false, body = "high")
        val reduced = reduceNoteEvents(listOf(high, low)) { Result.success(it.content) }
        assertEquals("low", reduced.notes.single().bodyMarkdown)
        assertEquals("aaa", reduced.selectedEvents.single().id)
    }

    @Test
    fun relayMigrationIdentifiesAddsAndRemovals() {
        val plan = planRelayMigration(listOf("wss://a.test", "wss://b.test"), listOf("wss://b.test", "wss://c.test"))
        assertEquals(listOf("wss://c.test"), plan.addedRelays)
        assertEquals(listOf("wss://a.test"), plan.removedRelays)
        assertEquals(listOf("wss://b.test"), plan.unchangedRelays)
        assertTrue(plan.migrationRequired)
        assertTrue(plan.shouldFetchBeforeRemoval)
        assertTrue(plan.shouldRepublishCurrentEvents)
    }

    @Test
    fun relayMigrationPlansNoOpAndEmptyRequestedLists() {
        val noOp = planRelayMigration(listOf("wss://a.test"), listOf("wss://a.test"))
        assertFalse(noOp.migrationRequired)
        assertFalse(noOp.shouldFetchBeforeRemoval)
        assertFalse(noOp.shouldRepublishCurrentEvents)

        val empty = planRelayMigration(listOf("wss://a.test"), emptyList())
        assertEquals(listOf("wss://a.test"), empty.removedRelays)
        assertTrue(empty.shouldFetchBeforeRemoval)
        assertFalse(empty.shouldRepublishCurrentEvents)
    }

    @Test
    fun relayMigrationLatestSelectionUsesReplaceableEventOrderingWithoutDecrypting() {
        val old = event("b-old", 10, "note-1", deleted = false, body = "cipher-old").copy(content = "cipher-old", sig = "valid")
        val edited = event("c-edited", 20, "note-1", deleted = false, body = "cipher-edited").copy(content = "cipher-edited", sig = "valid")
        val tombstone = event("a-tombstone", 20, "note-1", deleted = true, body = "").copy(content = "cipher-tombstone", sig = "valid")
        val other = event("d-other", 15, "note-2", deleted = false, body = "cipher-other").copy(content = "cipher-other", sig = "valid")

        val selected = selectLatestSignedEncryptedNoteEvents(
            events = listOf(old, edited, other, tombstone),
            accountPubkey = "pub",
            crypto = AcceptingValidationCrypto,
        )

        assertEquals(listOf("a-tombstone", "d-other"), selected.map { it.id })
        assertEquals("cipher-tombstone", selected.first().content)
    }

    @Test
    fun relayMigrationLatestSelectionRejectsInvalidOrWrongAccountEvents() {
        val valid = event("valid", 10, "note-1", deleted = false, body = "cipher").copy(content = "cipher", sig = "valid")
        val invalid = event("invalid", 20, "note-1", deleted = false, body = "cipher-new").copy(content = "cipher-new", sig = "invalid")
        val wrongAccount = valid.copy(id = "wrong", pubkey = "other-pub", createdAt = 30, sig = "valid")

        val selected = selectLatestSignedEncryptedNoteEvents(
            events = listOf(valid, invalid, wrongAccount),
            accountPubkey = "pub",
            crypto = AcceptingValidationCrypto,
        )

        assertEquals(listOf("valid"), selected.map { it.id })
    }

    @Test
    fun markdownTruncationStripsCommonMarkup() {
        val truncated = truncateMarkdown("# Heading\n\n**bold** text", maxChars = 20)
        assertFalse(truncated.contains("#"))
        assertTrue(truncated.startsWith("Heading"))
    }

    @Test
    fun noteGridColumnPolicyUsesTwoColumnsForNormalPhoneWidths() {
        assertEquals(1, noteGridColumnCount(300))
        assertEquals(2, noteGridColumnCount(336))
        assertEquals(2, noteGridColumnCount(600))
    }

    @Test
    fun noteGridColumnPolicyAdaptsForDesktopWidths() {
        assertEquals(3, noteGridColumnCount(840))
        assertEquals(5, noteGridColumnCount(1_440))
        assertEquals(6, noteGridColumnCount(2_400))
    }

    private fun event(id: String, createdAt: Long, noteId: String, deleted: Boolean, body: String): NostrEvent {
        val payload = NotePayload(noteId = noteId, createdAtMs = 1, updatedAtMs = createdAt * 1000, bodyMarkdown = body, deleted = deleted)
        return NostrEvent(
            id = id,
            pubkey = "pub",
            createdAt = createdAt,
            kind = 30078,
            tags = listOf(listOf("d", noteDTag(noteId)), listOf("t", "other-note")),
            content = JsonNotePayloadCodec.encode(payload),
            sig = "sig",
        )
    }
}

private object AcceptingValidationCrypto : NostrCrypto {
    override val productionReady: Boolean = true
    override fun generatePrivateKey(): Result<NostrPrivateKey> = Result.failure(UnsupportedOperationException())
    override fun encodeNsec(privateKey: NostrPrivateKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun encodeNpub(publicKey: NostrPublicKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun decodeNsec(nsec: String): KeyDecodeResult = KeyDecodeResult.Invalid("unused")
    override fun derivePublicKey(privateKey: NostrPrivateKey): Result<NostrPublicKey> = Result.failure(UnsupportedOperationException())
    override fun encryptToSelf(plaintext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun decryptFromSelf(ciphertext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun computeEventId(unsigned: UnsignedNostrEvent): Result<String> = Result.failure(UnsupportedOperationException())
    override fun sign(unsigned: UnsignedNostrEvent, privateKey: NostrPrivateKey): Result<NostrEvent> = Result.failure(UnsupportedOperationException())
    override fun validate(event: NostrEvent): Result<Boolean> = Result.success(event.sig == "valid")
}

private class MemoryRelaySettingsPersistence : RelaySettingsPersistence {
    var raw: String? = null

    override suspend fun loadRelayUrls(): List<String>? =
        raw?.let { RelaySettingsCodec.decodeOrNull(it) }

    override suspend fun saveRelayUrls(urls: List<String>) {
        raw = RelaySettingsCodec.encode(urls)
    }
}
