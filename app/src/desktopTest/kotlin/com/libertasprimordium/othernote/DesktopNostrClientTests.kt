package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.nostr.NostrEvent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopNostrClientTests {
    private val client = DesktopNostrClient()

    @Test
    fun publishReturnsFailureStatusForInvalidRelayUrl() = runBlocking {
        val result = client.publish(listOf("https://relay.example.com"), event)
        assertEquals(1, result.statuses.size)
        assertFalse(result.statuses.single().writable)
    }

    @Test
    fun fetchReturnsFailureStatusForInvalidRelayUrl() = runBlocking {
        val result = client.fetchNotes(listOf("https://relay.example.com"), authorPubkey = "pub")
        assertEquals(0, result.events.size)
        assertEquals(1, result.statuses.size)
        assertFalse(result.statuses.single().readable)
    }

    private val event = NostrEvent(
        id = "id",
        pubkey = "pub",
        createdAt = 1,
        kind = 30078,
        tags = listOf(listOf("d", "other-note:note:test"), listOf("t", "other-note")),
        content = "ciphertext",
        sig = "sig",
    )
}
