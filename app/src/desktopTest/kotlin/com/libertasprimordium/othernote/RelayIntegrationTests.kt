package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.NotePayload
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.nostr.noteEventTags
import com.libertasprimordium.othernote.sync.reduceNoteEvents
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class RelayIntegrationTests {
    @Test
    fun encryptedNoteRelayRoundTripIsExplicitlyOptIn() = runBlocking {
        if (System.getenv("OTHER_NOTE_RELAY_TESTS") != "1") return@runBlocking
        val relays = System.getenv("OTHER_NOTE_TEST_RELAYS")
            ?.split(',')
            ?.mapNotNull { normalizeRelayUrl(it).getOrNull() }
            ?.distinct()
            .orEmpty()
        if (relays.isEmpty()) return@runBlocking

        val crypto = ProductionNostrCryptoFactory.createOrNull()
            ?: fail(ProductionNostrCryptoFactory.unavailableReason)

        val client = DesktopNostrClient()
        val privateKey = crypto.generatePrivateKey().getOrThrow()
        val publicKey = crypto.derivePublicKey(privateKey).getOrThrow()
        val noteId = "relay-test-${UUID.randomUUID()}"
        val createdAtMs = System.currentTimeMillis()
        val basePayload = NotePayload(
            noteId = noteId,
            createdAtMs = createdAtMs,
            updatedAtMs = createdAtMs,
            bodyMarkdown = "Other Note disposable relay integration test",
            deleted = false,
        )

        val initialEvent = signPayload(crypto, privateKey, publicKey, basePayload, createdAt = createdAtMs / 1000)
        val initialPublish = client.publish(relays, initialEvent)
        assertTrue(
            initialPublish.statuses.any { it.writable },
            "No relay accepted initial write. Statuses: ${initialPublish.statuses.safeSummary()}",
        )

        val initialFetch = client.fetchNotes(relays, publicKey.hex)
        val validInitial = initialFetch.validTestEvents(crypto, privateKey, publicKey, noteId)
        assertTrue(
            validInitial.isNotEmpty(),
            "No valid initial test event fetched. Publish: ${initialPublish.statuses.safeSummary()}; fetch: ${initialFetch.statuses.safeSummary()}",
        )
        assertTrue(
            validInitial.any { event -> decryptPayloadOrNull(crypto, event, privateKey, publicKey) == basePayload },
            "Initial payload did not round trip. Publish: ${initialPublish.statuses.safeSummary()}; fetch: ${initialFetch.statuses.safeSummary()}",
        )

        val updatedPayload = basePayload.copy(
            updatedAtMs = createdAtMs + 2_000,
            bodyMarkdown = "Other Note disposable relay integration test updated",
        )
        val updatedEvent = signPayload(crypto, privateKey, publicKey, updatedPayload, createdAt = initialEvent.createdAt + 2)
        val updatedPublish = client.publish(relays, updatedEvent)
        assertTrue(
            updatedPublish.statuses.any { it.writable },
            "No relay accepted update write. Statuses: ${updatedPublish.statuses.safeSummary()}",
        )

        val updatedFetch = client.fetchNotes(relays, publicKey.hex)
        val validUpdated = updatedFetch.validTestEvents(crypto, privateKey, publicKey, noteId)
        val reducedUpdated = reduceNoteEvents(validUpdated) { event ->
            crypto.decryptFromSelf(event.content, privateKey, publicKey)
        }
        assertTrue(
            reducedUpdated.notes.singleOrNull { it.id == noteId }?.bodyMarkdown == updatedPayload.bodyMarkdown,
            "Reducer did not select updated note. Publish: ${updatedPublish.statuses.safeSummary()}; fetch: ${updatedFetch.statuses.safeSummary()}",
        )

        val tombstonePayload = updatedPayload.copy(
            updatedAtMs = createdAtMs + 4_000,
            bodyMarkdown = "",
            deleted = true,
        )
        val tombstoneEvent = signPayload(crypto, privateKey, publicKey, tombstonePayload, createdAt = updatedEvent.createdAt + 2)
        val tombstonePublish = client.publish(relays, tombstoneEvent)
        assertTrue(
            tombstonePublish.statuses.any { it.writable },
            "No relay accepted tombstone write. Statuses: ${tombstonePublish.statuses.safeSummary()}",
        )

        val tombstoneFetch = client.fetchNotes(relays, publicKey.hex)
        val validTombstone = tombstoneFetch.validTestEvents(crypto, privateKey, publicKey, noteId)
        val reducedTombstone = reduceNoteEvents(validTombstone) { event ->
            crypto.decryptFromSelf(event.content, privateKey, publicKey)
        }
        assertTrue(
            reducedTombstone.notes.none { it.id == noteId },
            "Reducer did not hide tombstoned note. Publish: ${tombstonePublish.statuses.safeSummary()}; fetch: ${tombstoneFetch.statuses.safeSummary()}",
        )
    }

    private fun signPayload(
        crypto: NostrCrypto,
        privateKey: NostrPrivateKey,
        publicKey: NostrPublicKey,
        payload: NotePayload,
        createdAt: Long,
    ): NostrEvent {
        val ciphertext = crypto.encryptToSelf(JsonNotePayloadCodec.encode(payload), privateKey, publicKey).getOrThrow()
        return crypto.sign(
            UnsignedNostrEvent(
                pubkey = publicKey.hex,
                createdAt = createdAt,
                kind = NoteKind,
                tags = noteEventTags(noteDTag(payload.noteId)),
                content = ciphertext,
            ),
            privateKey,
        ).getOrThrow()
    }

    private fun RelayFetchResult.validTestEvents(
        crypto: NostrCrypto,
        privateKey: NostrPrivateKey,
        publicKey: NostrPublicKey,
        noteId: String,
    ): List<NostrEvent> = events.filter { event ->
        event.pubkey == publicKey.hex &&
            event.dTag() == noteDTag(noteId) &&
            crypto.validate(event).getOrDefault(false) &&
            decryptPayloadOrNull(crypto, event, privateKey, publicKey)?.noteId == noteId
    }

    private fun decryptPayloadOrNull(
        crypto: NostrCrypto,
        event: NostrEvent,
        privateKey: NostrPrivateKey,
        publicKey: NostrPublicKey,
    ): NotePayload? =
        crypto.decryptFromSelf(event.content, privateKey, publicKey)
            .mapCatching { JsonNotePayloadCodec.decode(it).getOrThrow() }
            .getOrNull()

    private fun List<RelayStatus>.safeSummary(): String =
        joinToString("; ") { status ->
            "${status.url} read=${status.readable} write=${status.writable} message=${status.message.take(180)}"
        }
}
