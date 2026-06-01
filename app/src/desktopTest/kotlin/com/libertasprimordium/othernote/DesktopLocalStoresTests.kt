package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.PendingWriteStatus
import com.libertasprimordium.othernote.nostr.NostrEvent
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLocalStoresTests {
    @Test
    fun fileEventCacheStoresSignedEncryptedEventsWithoutSecretsOrPlaintext() = runBlocking {
        val dir = Files.createTempDirectory("other-note-event-cache-test")
        val cache = DesktopLocalEventCache(dir)
        val event = event(content = "encrypted-public-event-content")

        cache.upsertEvents(AccountPubkey, listOf(event))

        assertEquals(listOf(event), cache.loadEvents(AccountPubkey))
        val raw = Files.list(dir).use { files -> files.findFirst().orElseThrow().readText() }
        assertTrue(raw.contains("encrypted-public-event-content"))
        assertFalse(raw.contains(TestNsec))
        assertFalse(raw.contains(TestPrivateKey))
        assertFalse(raw.contains(TestPlaintextBody))
        assertFalse(raw.contains(TestPlaintextJson))
    }

    @Test
    fun corruptEventCacheFileIsIgnoredSafely() = runBlocking {
        val dir = Files.createTempDirectory("other-note-event-cache-corrupt-test")
        val accountFile = dir.resolve("$AccountPubkey.events.json")
        Files.createDirectories(dir)
        Files.writeString(accountFile, "{not-json")
        val cache = DesktopLocalEventCache(dir)

        assertTrue(cache.loadEvents(AccountPubkey).isEmpty())
    }

    @Test
    fun pendingWriteStorePersistsUnfinishedRelayStatusesAcrossInstances() = runBlocking {
        val dir = Files.createTempDirectory("other-note-pending-write-test")
        val firstStore = DesktopPendingWriteStore(dir)
        val event = event(content = "encrypted-pending-event")

        firstStore.enqueuePendingWrite(
            accountPubkey = AccountPubkey,
            event = event,
            targetRelays = listOf("wss://accepted.example.com", "wss://retry.example.com"),
        )
        firstStore.markRelayAccepted(event.id, "wss://accepted.example.com")
        firstStore.markRelayRejectedOrFailed(event.id, "wss://retry.example.com", "timeout without secrets")

        val secondStore = DesktopPendingWriteStore(dir)
        val pending = secondStore.loadPendingWrites(AccountPubkey).single()
        val raw = Files.list(dir).use { files -> files.findFirst().orElseThrow().readText() }

        assertEquals(event, pending.event)
        assertEquals(PendingWriteStatus.Accepted, pending.relayStatuses["wss://accepted.example.com"])
        assertEquals(PendingWriteStatus.Failed, pending.relayStatuses["wss://retry.example.com"])
        assertEquals(listOf("wss://retry.example.com"), pending.unfinishedRelays)
        assertFalse(raw.contains(TestNsec))
        assertFalse(raw.contains(TestPrivateKey))
        assertFalse(raw.contains(TestPlaintextBody))
        assertFalse(raw.contains(TestPlaintextJson))
    }

    @Test
    fun pendingWritesAreLoadedOnlyForMatchingAccount() = runBlocking {
        val dir = Files.createTempDirectory("other-note-pending-account-test")
        val store = DesktopPendingWriteStore(dir)

        store.enqueuePendingWrite(AccountPubkey, event("event-one"), listOf("wss://relay.example.com"))

        assertTrue(store.loadPendingWrites("aa".repeat(32)).isEmpty())
        assertEquals(1, store.loadPendingWrites(AccountPubkey).size)
    }

    private fun event(
        id: String = "11".repeat(32),
        content: String = "encrypted-content",
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
private const val TestNsec = "nsec1do-not-store"
private const val TestPrivateKey = "private-key-do-not-store"
private const val TestPlaintextBody = "plain note body must not be stored"
private const val TestPlaintextJson = "\"body_markdown\":\"plain note body must not be stored\""
