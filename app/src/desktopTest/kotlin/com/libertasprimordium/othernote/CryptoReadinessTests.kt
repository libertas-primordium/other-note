package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.NotePayload
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.Nip19
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.nostr.noteEventTags
import com.libertasprimordium.othernote.sync.reduceNoteEvents
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoReadinessTests {
    @Test
    fun offlineCryptoRoundTripWithThrowawayKeypair() {
        val crypto = ProductionNostrCryptoFactory.createOrNull()
            ?: error(ProductionNostrCryptoFactory.unavailableReason)
        val privateKey = crypto.generatePrivateKey().getOrThrow()
        val publicKey = crypto.derivePublicKey(privateKey).getOrThrow()
        val nsec = crypto.encodeNsec(privateKey).getOrThrow()
        val npub = crypto.encodeNpub(publicKey).getOrThrow()
        assertTrue(nsec.startsWith("nsec1"))
        assertTrue(npub.startsWith("npub1"))
        val decodedPrivateKey = crypto.decodeNsec(nsec)
        assertTrue(decodedPrivateKey is KeyDecodeResult.Valid)
        assertEquals(privateKey, decodedPrivateKey.privateKey)
        val decodedNpub = Nip19.decode(npub)
        assertEquals("npub", decodedNpub?.hrp)
        assertEquals(publicKey.hex, decodedNpub?.data?.joinToString("") { it.toUByte().toString(16).padStart(2, '0') })

        val payload = NotePayload(
            noteId = "throwaway-test-note",
            createdAtMs = 1,
            updatedAtMs = 2,
            bodyMarkdown = "Heading\nQuote: \"hello\"\nUnicode: こんにちは\nTab:\tvalue\n```kotlin\nval x = \"secret-ish test body\"\n```",
            deleted = false,
        )
        val plaintext = JsonNotePayloadCodec.encode(payload)
        val ciphertext = crypto.encryptToSelf(plaintext, privateKey, publicKey).getOrThrow()
        assertFalse(ciphertext.contains(plaintext))
        assertFalse(ciphertext.contains(payload.bodyMarkdown))

        val event = signPayload(crypto, privateKey, publicKey, payload, createdAt = 2)
        assertEquals(
            event.id,
            crypto.computeEventId(
                UnsignedNostrEvent(
                    pubkey = event.pubkey,
                    createdAt = event.createdAt,
                    kind = event.kind,
                    tags = event.tags,
                    content = event.content,
                ),
            ).getOrThrow(),
        )
        assertTrue(crypto.validate(event).getOrThrow())

        val decrypted = crypto.decryptFromSelf(event.content, privateKey, publicKey).getOrThrow()
        assertEquals(payload, JsonNotePayloadCodec.decode(decrypted).getOrThrow())

        val olderPayload = payload.copy(updatedAtMs = 1, bodyMarkdown = "older")
        val newerPayload = payload.copy(updatedAtMs = 3, bodyMarkdown = "newer")
        val olderEvent = signPayload(crypto, privateKey, publicKey, olderPayload, createdAt = 1)
        val newerEvent = signPayload(crypto, privateKey, publicKey, newerPayload, createdAt = 3)
        val reduced = reduceNoteEvents(listOf(newerEvent, olderEvent)) {
            crypto.decryptFromSelf(it.content, privateKey, publicKey)
        }
        assertEquals("newer", reduced.notes.single().bodyMarkdown)

        val tombstonePayload = newerPayload.copy(updatedAtMs = 4, bodyMarkdown = "", deleted = true)
        val tombstoneEvent = signPayload(crypto, privateKey, publicKey, tombstonePayload, createdAt = 4)
        val reducedWithTombstone = reduceNoteEvents(listOf(olderEvent, newerEvent, tombstoneEvent)) {
            crypto.decryptFromSelf(it.content, privateKey, publicKey)
        }
        assertTrue(reducedWithTombstone.notes.isEmpty())
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
}
