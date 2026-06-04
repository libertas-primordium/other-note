package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrFilter
import com.libertasprimordium.othernote.nostr.ProfileMetadataKind
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.RelayPublishResult
import com.libertasprimordium.othernote.nostr.parseProfileMetadata
import com.libertasprimordium.othernote.nostr.selectLatestProfileMetadata
import com.libertasprimordium.othernote.data.ProfileRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileMetadataTests {
    private val pubkey = "a".repeat(64)

    @Test
    fun parsesSupportedProfileFields() {
        val profile = parseProfileMetadata(
            pubkey = pubkey,
            content = """{"name":"alice","display_name":"Alice Notes","about":"Encrypted notes","picture":"https://example.com/a.png","nip05":"alice@example.com","website":"https://example.com"}""",
            createdAt = 42,
        )

        assertEquals("Alice Notes", profile?.bestName)
        assertEquals("alice", profile?.name)
        assertEquals("Encrypted notes", profile?.about)
        assertEquals("https://example.com/a.png", profile?.pictureUrl)
        assertEquals("alice@example.com", profile?.nip05)
        assertEquals("https://example.com", profile?.website)
        assertEquals(42, profile?.createdAt)
    }

    @Test
    fun fallsBackFromDisplayNameToName() {
        val profile = parseProfileMetadata(pubkey, """{"name":"alice","display_name":"   "}""")

        assertEquals("alice", profile?.bestName)
    }

    @Test
    fun trimsBlankAndUnknownFieldsSafely() {
        val profile = parseProfileMetadata(pubkey, """{"name":"  Alice  ","about":"   ","unknown":"ignored"}""")

        assertEquals("Alice", profile?.bestName)
        assertNull(profile?.about)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(parseProfileMetadata(pubkey, """{"name":"""))
    }

    @Test
    fun capsExtremelyLongProfileText() {
        val longName = "n".repeat(200)
        val longAbout = "a".repeat(500)
        val profile = parseProfileMetadata(pubkey, """{"display_name":"$longName","about":"$longAbout"}""")

        assertEquals(80, profile?.displayName?.length)
        assertEquals(280, profile?.about?.length)
    }

    @Test
    fun selectsLatestValidProfileEventAndIgnoresMalformedEvents() {
        val oldValid = profileEvent(id = "old", createdAt = 10, content = """{"name":"Old"}""")
        val newerMalformed = profileEvent(id = "newer-bad", createdAt = 30, content = """{"name":""")
        val latestValid = profileEvent(id = "latest", createdAt = 20, content = """{"display_name":"Latest"}""")

        val profile = selectLatestProfileMetadata(listOf(oldValid, newerMalformed, latestValid), pubkey)

        assertEquals("Latest", profile?.bestName)
    }

    @Test
    fun sameTimestampTieBreaksDeterministicallyByEventId() {
        val lower = profileEvent(id = "aaa", createdAt = 10, content = """{"name":"Lower"}""")
        val higher = profileEvent(id = "bbb", createdAt = 10, content = """{"name":"Higher"}""")

        assertEquals("Higher", selectLatestProfileMetadata(listOf(lower, higher), pubkey)?.bestName)
        assertEquals("Higher", selectLatestProfileMetadata(listOf(higher, lower), pubkey)?.bestName)
    }

    @Test
    fun repositoryCachesProfileByPubkey() = runBlocking {
        val client = ProfileFetchClient(listOf(profileEvent(id = "profile", createdAt = 10, content = """{"name":"Cached"}""")))
        val repository = ProfileRepository(client)

        assertEquals("Cached", repository.loadProfile(listOf("wss://relay.example.com"), pubkey)?.bestName)
        assertEquals("Cached", repository.loadProfile(listOf("wss://relay.example.com"), pubkey)?.bestName)
        assertEquals(1, client.fetchEventCalls)
    }

    private fun profileEvent(id: String, createdAt: Long, content: String): NostrEvent =
        NostrEvent(
            id = id,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = ProfileMetadataKind,
            tags = emptyList(),
            content = content,
            sig = "sig",
        )
}

private class ProfileFetchClient(
    private val events: List<NostrEvent>,
) : NostrClient {
    var fetchEventCalls = 0

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(emptyList(), emptyList())

    override suspend fun fetchEvents(relays: List<String>, filter: NostrFilter): RelayFetchResult {
        fetchEventCalls++
        return RelayFetchResult(
            events = events.filter { event ->
                event.kind in filter.kinds && event.pubkey in filter.authors
            },
            statuses = relays.map { RelayStatus(it, readable = true, message = "ok") },
        )
    }

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult =
        RelayPublishResult(emptyList())
}
