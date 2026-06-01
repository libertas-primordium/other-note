package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.NotePayload
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.nostr.NostrEventSerialization
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.Nip19
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.sync.planRelayMigration
import com.libertasprimordium.othernote.sync.reduceNoteEvents
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import com.libertasprimordium.othernote.util.MediaType
import com.libertasprimordium.othernote.util.detectUrls
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import com.libertasprimordium.othernote.util.truncateMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UtilityTests {
    @Test
    fun relayUrlsNormalizeAndRejectInvalidSchemes() {
        assertEquals("wss://relay.example.com", normalizeRelayUrl("relay.example.com/").getOrThrow())
        assertTrue(normalizeRelayUrl("https://relay.example.com").isFailure)
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
        assertTrue(plan.shouldFetchBeforeRemoval)
        assertTrue(plan.shouldRepublishCurrentEvents)
    }

    @Test
    fun markdownTruncationStripsCommonMarkup() {
        val truncated = truncateMarkdown("# Heading\n\n**bold** text", maxChars = 20)
        assertFalse(truncated.contains("#"))
        assertTrue(truncated.startsWith("Heading"))
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
