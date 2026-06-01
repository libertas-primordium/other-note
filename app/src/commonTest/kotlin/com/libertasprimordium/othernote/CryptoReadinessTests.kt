package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.NotePayload
import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.noteDTag
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
    fun offlineCryptoRoundTripWhenProductionAdapterIsAvailable() {
        val crypto = ProductionNostrCryptoFactory.createOrNull() ?: return
        val privateKey = crypto.generatePrivateKey().getOrThrow()
        val publicKey = crypto.derivePublicKey(privateKey).getOrThrow()
        val nsec = crypto.encodeNsec(privateKey).getOrThrow()
        val npub = crypto.encodeNpub(publicKey).getOrThrow()
        assertTrue(nsec.startsWith("nsec1"))
        assertTrue(npub.startsWith("npub1"))

        val payload = NotePayload(
            noteId = "throwaway-test-note",
            createdAtMs = 1,
            updatedAtMs = 2,
            bodyMarkdown = "throwaway test note",
            deleted = false,
        )
        val plaintext = JsonNotePayloadCodec.encode(payload)
        val ciphertext = crypto.encryptToSelf(plaintext, privateKey, publicKey).getOrThrow()
        assertFalse(ciphertext.contains(payload.bodyMarkdown))

        val unsigned = UnsignedNostrEvent(
            pubkey = publicKey.hex,
            createdAt = 2,
            kind = NoteKind,
            tags = noteEventTags(noteDTag(payload.noteId)),
            content = ciphertext,
        )
        val event = crypto.sign(unsigned, privateKey).getOrThrow()
        assertEquals(event.id, crypto.computeEventId(unsigned).getOrThrow())
        assertTrue(crypto.validate(event).getOrThrow())

        val decrypted = crypto.decryptFromSelf(event.content, privateKey, publicKey).getOrThrow()
        assertEquals(payload, JsonNotePayloadCodec.decode(decrypted).getOrThrow())
        val reduced = reduceNoteEvents(listOf(event)) { crypto.decryptFromSelf(it.content, privateKey, publicKey) }
        assertEquals(payload.noteId, reduced.notes.single().id)
    }
}
