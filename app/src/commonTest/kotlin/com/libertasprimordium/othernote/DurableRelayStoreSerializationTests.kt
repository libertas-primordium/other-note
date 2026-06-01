package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.DurableCachedEventRecord
import com.libertasprimordium.othernote.data.DurablePendingWriteRecord
import com.libertasprimordium.othernote.data.DurableRelayStoreCodec
import com.libertasprimordium.othernote.data.PendingWriteStatus
import com.libertasprimordium.othernote.data.toDurableRecord
import com.libertasprimordium.othernote.nostr.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurableRelayStoreSerializationTests {
    @Test
    fun encryptedEventCacheRecordsRoundTripWithoutSecretsOrPlaintext() {
        val record = DurableCachedEventRecord(event = event().toDurableRecord(), cachedAtMs = 1234)

        val raw = DurableRelayStoreCodec.encodeCachedEvents(listOf(record))
        val decoded = DurableRelayStoreCodec.decodeCachedEventsOrEmpty(raw)

        assertEquals(listOf(record), decoded)
        assertEquals(event(), decoded.single().event.toEvent())
        assertSafeSerializedContent(raw)
    }

    @Test
    fun pendingWriteRecordsRoundTripWithoutSecretsOrPlaintext() {
        val record = DurablePendingWriteRecord(
            accountPubkey = AccountPubkey,
            event = event(content = "encrypted-pending-ciphertext").toDurableRecord(),
            targetRelays = listOf("wss://one.example.com", "wss://two.example.com"),
            relayStatuses = mapOf(
                "wss://one.example.com" to PendingWriteStatus.Accepted.name,
                "wss://two.example.com" to PendingWriteStatus.Failed.name,
            ),
            retryCounts = mapOf("wss://two.example.com" to 2),
            lastSafeErrorByRelay = mapOf("wss://two.example.com" to "timeout without sensitive material"),
            createdAtMs = 100,
            updatedAtMs = 200,
        )

        val raw = DurableRelayStoreCodec.encodePendingWrites(listOf(record))
        val decoded = DurableRelayStoreCodec.decodePendingWritesOrEmpty(raw)
        val pending = decoded.single().toPendingWrite()

        assertEquals(listOf(record), decoded)
        assertEquals(AccountPubkey, pending.accountPubkey)
        assertEquals(PendingWriteStatus.Accepted, pending.relayStatuses["wss://one.example.com"])
        assertEquals(PendingWriteStatus.Failed, pending.relayStatuses["wss://two.example.com"])
        assertEquals(2, pending.retryCounts["wss://two.example.com"])
        assertSafeSerializedContent(raw)
    }

    @Test
    fun malformedJsonReturnsEmptyRecords() {
        assertTrue(DurableRelayStoreCodec.decodeCachedEventsOrEmpty("{not-json").isEmpty())
        assertTrue(DurableRelayStoreCodec.decodePendingWritesOrEmpty("""{"writes": "wrong-shape"}""").isEmpty())
    }

    private fun assertSafeSerializedContent(raw: String) {
        assertTrue(raw.contains("encrypted-"))
        assertFalse(raw.contains(TestNsec))
        assertFalse(raw.contains(TestPrivateKey))
        assertFalse(raw.contains(TestPlaintextBody))
        assertFalse(raw.contains(TestPlaintextPayloadField))
        assertFalse(raw.contains("body_markdown"))
    }

    private fun event(
        id: String = "11".repeat(32),
        content: String = "encrypted-cache-ciphertext",
    ): NostrEvent = NostrEvent(
        id = id,
        pubkey = AccountPubkey,
        createdAt = 123,
        kind = 30078,
        tags = listOf(listOf("d", "other-note:note:test-note"), listOf("t", "other-note")),
        content = content,
        sig = "22".repeat(32),
    )
}

private val AccountPubkey = "02".repeat(32)
private const val TestNsec = "nsec1durable-test-secret"
private const val TestPrivateKey = "private-key-durable-test-secret"
private const val TestPlaintextBody = "durable plaintext note body"
private const val TestPlaintextPayloadField = "\"body_markdown\":\"durable plaintext note body\""
