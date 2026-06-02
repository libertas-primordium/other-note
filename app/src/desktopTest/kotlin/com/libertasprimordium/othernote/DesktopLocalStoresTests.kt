package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.PendingWriteStatus
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.RelayPublishResult
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.AppState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.name
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
    fun firstPendingWriteCreatesMissingDirectoryAndFile() = runBlocking {
        val root = Files.createTempDirectory("other-note-pending-first-root")
        val dir = root.resolve("pending-writes")
        val store = DesktopPendingWriteStore(dir)
        val event = event("event-first")

        assertFalse(dir.exists())

        store.enqueuePendingWrite(AccountPubkey, event, listOf("wss://relay.example.com"))

        assertTrue(dir.exists())
        assertEquals(listOf(event), store.loadPendingWrites(AccountPubkey).map { it.event })
        val raw = Files.list(dir).use { files -> files.findFirst().orElseThrow().readText() }
        assertTrue(raw.contains(event.id))
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
        firstStore.recordRetry(event.id, "wss://retry.example.com")

        val secondStore = DesktopPendingWriteStore(dir)
        val pending = secondStore.loadPendingWrites(AccountPubkey).single()
        val raw = Files.list(dir).use { files -> files.findFirst().orElseThrow().readText() }

        assertEquals(event, pending.event)
        assertEquals(PendingWriteStatus.Accepted, pending.relayStatuses["wss://accepted.example.com"])
        assertEquals(PendingWriteStatus.Pending, pending.relayStatuses["wss://retry.example.com"])
        assertEquals(1, pending.retryCounts["wss://retry.example.com"])
        assertEquals(listOf("wss://retry.example.com"), pending.unfinishedRelays)
        assertFalse(raw.contains(TestNsec))
        assertFalse(raw.contains(TestPrivateKey))
        assertFalse(raw.contains(TestPlaintextBody))
        assertFalse(raw.contains(TestPlaintextJson))
    }

    @Test
    fun existingEmptyPendingFileCanUpdateFromEmptyToOneRow() = runBlocking {
        val dir = Files.createTempDirectory("other-note-pending-empty-to-one-test")
        val accountFile = pendingFile(dir)
        Files.writeString(accountFile, """{"writes":[]}""")
        val store = DesktopPendingWriteStore(dir)
        val event = event("event-empty-to-one")

        store.enqueuePendingWrite(AccountPubkey, event, listOf("wss://relay.example.com"))

        val pending = store.loadPendingWrites(AccountPubkey).single()
        val raw = accountFile.readText()
        assertEquals(event, pending.event)
        assertTrue(raw.contains(event.id))
        assertFalse(raw.contains(TestNsec))
        assertFalse(raw.contains(TestPrivateKey))
        assertFalse(raw.contains(TestPlaintextBody))
    }

    @Test
    fun existingPendingFileCanUpdateFromOneRowToEmptyRows() = runBlocking {
        val dir = Files.createTempDirectory("other-note-pending-one-to-empty-test")
        val store = DesktopPendingWriteStore(dir)
        val event = event("event-one-to-empty")

        store.enqueuePendingWrite(AccountPubkey, event, listOf("wss://relay.example.com"))
        store.markRelayAccepted(event.id, "wss://relay.example.com")
        store.removeCompletedWrite(event.id)

        val raw = pendingFile(dir).readText()
        assertTrue(store.loadPendingWrites(AccountPubkey).isEmpty())
        assertEquals("""{"writes":[]}""", raw)
    }

    @Test
    fun existingEmptyPendingFileCanUpdateFromEmptyToEmptyWithoutMovingMissingTempFile() = runBlocking {
        val dir = Files.createTempDirectory("other-note-pending-empty-to-empty-test")
        val accountFile = pendingFile(dir)
        Files.writeString(accountFile, """{"writes":[]}""")
        val store = DesktopPendingWriteStore(dir)

        store.markRelayAccepted("missing-event", "wss://relay.example.com")
        store.removeCompletedWrite("missing-event")

        assertTrue(store.loadPendingWrites(AccountPubkey).isEmpty())
        assertEquals("""{"writes":[]}""", accountFile.readText())
        val tempFiles = Files.list(dir).use { files -> files.filter { it.name.endsWith(".tmp") }.toList() }
        assertTrue(tempFiles.isEmpty())
    }

    @Test
    fun pendingWritesAreLoadedOnlyForMatchingAccount() = runBlocking {
        val dir = Files.createTempDirectory("other-note-pending-account-test")
        val store = DesktopPendingWriteStore(dir)

        store.enqueuePendingWrite(AccountPubkey, event("event-one"), listOf("wss://relay.example.com"))

        assertTrue(store.loadPendingWrites("aa".repeat(32)).isEmpty())
        assertEquals(1, store.loadPendingWrites(AccountPubkey).size)
    }

    @Test
    fun completedPendingWritesAreRemovedFromDisk() = runBlocking {
        val dir = Files.createTempDirectory("other-note-pending-remove-test")
        val store = DesktopPendingWriteStore(dir)
        val event = event("event-remove")

        store.enqueuePendingWrite(AccountPubkey, event, listOf("wss://relay.example.com"))
        store.markRelayAccepted(event.id, "wss://relay.example.com")
        store.removeCompletedWrite(event.id)

        assertTrue(store.loadPendingWrites(AccountPubkey).isEmpty())
        val raw = Files.list(dir).use { files -> files.findFirst().orElseThrow().readText() }
        assertTrue(raw.contains("\"writes\":[]"))
        assertFalse(raw.contains(event.id))
    }

    @Test
    fun missingPendingWriteDirectoryLoadsAsEmpty() = runBlocking {
        val root = Files.createTempDirectory("other-note-pending-missing-root")
        val dir = root.resolve("pending-writes")
        val store = DesktopPendingWriteStore(dir)

        assertFalse(dir.exists())
        assertTrue(store.loadPendingWrites(AccountPubkey).isEmpty())
    }

    @Test
    fun corruptPendingWriteFileIsIgnoredSafely() = runBlocking {
        val dir = Files.createTempDirectory("other-note-pending-corrupt-test")
        val accountFile = dir.resolve("$AccountPubkey.pending.json")
        Files.createDirectories(dir)
        Files.writeString(accountFile, "{not-json")
        val store = DesktopPendingWriteStore(dir)

        assertTrue(store.loadPendingWrites(AccountPubkey).isEmpty())
    }

    @Test
    fun repeatedPendingWriteSaveUpdateRemoveCyclesRemainReadable() = runBlocking {
        val dir = Files.createTempDirectory("other-note-pending-cycle-test")
        val store = DesktopPendingWriteStore(dir)

        repeat(5) { index ->
            val event = event("event-cycle-$index")
            store.enqueuePendingWrite(AccountPubkey, event, listOf("wss://relay.example.com"))
            assertEquals(event, store.loadPendingWrites(AccountPubkey).single().event)
            store.markRelayAccepted(event.id, "wss://relay.example.com")
            store.removeCompletedWrite(event.id)
            assertTrue(store.loadPendingWrites(AccountPubkey).isEmpty())
        }

        val raw = Files.list(dir).use { files -> files.findFirst().orElseThrow().readText() }
        assertTrue(raw.contains("\"writes\":[]"))
    }

    @Test
    fun concurrentPendingWriteStatusUpdatesDoNotRaceTempFiles() = runBlocking {
        val dir = Files.createTempDirectory("other-note-pending-concurrent-test")
        val store = DesktopPendingWriteStore(dir)
        val event = event("event-concurrent")
        val relays = (1..12).map { index -> "wss://relay-$index.example.com" }

        store.enqueuePendingWrite(AccountPubkey, event, relays)
        coroutineScope {
            relays.forEach { relay ->
                launch {
                    store.markRelayAccepted(event.id, relay)
                }
            }
        }

        val pending = store.loadPendingWrites(AccountPubkey).single()
        assertEquals(relays.toSet(), pending.acceptedRelays)
        val tempFiles = Files.list(dir).use { files -> files.filter { it.name.endsWith(".tmp") }.toList() }
        assertTrue(tempFiles.isEmpty())
    }

    @Test
    fun desktopSaveWithMissingPendingDirectoryCreatesVisibleNoteAndPendingFile() = runBlocking {
        val root = Files.createTempDirectory("other-note-desktop-save-root")
        val pendingDir = root.resolve("pending-writes")
        val crypto = ProductionNostrCryptoFactory.createOrNull() ?: error(ProductionNostrCryptoFactory.unavailableReason)
        val nsec = crypto.encodeNsec(crypto.generatePrivateKey().getOrThrow()).getOrThrow()
        val client = AcceptingDesktopSaveClient()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopRelay,
                crypto = crypto,
                client = client,
                pendingWriteStore = DesktopPendingWriteStore(pendingDir),
            ),
        )

        assertTrue(state.login(nsec))
        assertTrue(state.save(existing = null, markdown = "desktop pending write first save"))

        assertTrue(pendingDir.exists())
        assertEquals("desktop pending write first save", state.notes.notes.value.single().bodyMarkdown)
        assertEquals(1, client.published.size)
        val raw = Files.list(pendingDir).use { files -> files.findFirst().orElseThrow().readText() }
        assertTrue(raw.contains("\"writes\":[]"))
        assertFalse(raw.contains(client.published.single().id))
        assertFalse(raw.contains("desktop pending write first save"))
        assertFalse(raw.contains("body_markdown"))
    }

    @Test
    fun desktopRelaySettingsPersistenceRoundTripsSafeRelayJson() = runBlocking {
        val file = Files.createTempDirectory("other-note-relay-settings-test").resolve("relay-settings.json")
        val store = DesktopRelaySettingsPersistence(file)
        val relays = listOf("wss://relay.example.com", "wss://relay.example.com/nostr")

        store.saveRelayUrls(relays)

        assertEquals(relays, store.loadRelayUrls())
        val raw = file.readText()
        assertTrue(raw.contains("wss://relay.example.com"))
        assertFalse(raw.contains(TestNsec))
        assertFalse(raw.contains(TestPrivateKey))
        assertFalse(raw.contains(TestPlaintextBody))
        assertFalse(raw.contains(TestPlaintextJson))
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

    private fun pendingFile(dir: java.nio.file.Path): java.nio.file.Path =
        dir.resolve("${com.libertasprimordium.othernote.data.DurableRelayStoreCodec.safeFileName(AccountPubkey)}.pending.json")
}

private class AcceptingDesktopSaveClient : NostrClient {
    val published = mutableListOf<NostrEvent>()

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(emptyList(), relays.map { RelayStatus(it, readable = true, message = "test read") })

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult {
        published += event
        return RelayPublishResult(relays.map { RelayStatus(it, writable = true, message = "accepted") })
    }

    override suspend fun fetchProfile(
        relays: List<String>,
        pubkey: String,
    ): com.libertasprimordium.othernote.nostr.ProfileMetadata? = null
}

private val AccountPubkey = "02".repeat(32)
private const val TestNsec = "nsec1do-not-store"
private const val TestPrivateKey = "private-key-do-not-store"
private const val TestPlaintextBody = "plain note body must not be stored"
private const val TestPlaintextJson = "\"body_markdown\":\"plain note body must not be stored\""
