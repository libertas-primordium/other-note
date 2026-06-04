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
import com.libertasprimordium.othernote.nostr.hexToBytes
import com.libertasprimordium.othernote.nostr.noteEventTags
import com.libertasprimordium.othernote.security.GeneratedIdentitySecret
import com.libertasprimordium.othernote.sync.reduceNoteEvents
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoReadinessTests {
    private val relayStyleBody =
        "Other Note disposable relay integration test payload\nQuote: \"hello\"\nUnicode: test\nTab:\tvalue\n```text\nrelay\n```"

    @Test
    fun productionCryptoFactoryIsEitherReliableOrPreciselyUnavailable() {
        val crypto = ProductionNostrCryptoFactory.createOrNull()
        if (crypto == null) {
            assertTrue(ProductionNostrCryptoFactory.unavailableReason.contains("Nostr crypto"))
        } else {
            assertTrue(crypto.productionReady)
        }
    }

    @Test
    fun offlineCryptoRoundTripWithThrowawayKeypair() {
        val crypto = ProductionNostrCryptoFactory.createOrNull()
            ?: return
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

    @Test
    fun generatedIdentitySecretRoundTripsAndRedactsDebugOutput() {
        val crypto = ProductionNostrCryptoFactory.createOrNull()
            ?: return

        val first = GeneratedIdentitySecret.generate(crypto).getOrThrow()
        val second = GeneratedIdentitySecret.generate(crypto).getOrThrow()
        val decoded = crypto.decodeNsec(first.revealNsec())
        assertTrue(decoded is KeyDecodeResult.Valid)
        assertEquals(first.privateKeyHex, decoded.privateKey.hex)
        val publicKey = crypto.derivePublicKey(decoded.privateKey).getOrThrow()
        assertEquals(first.publicKeyHex, publicKey.hex)
        assertEquals(first.npub, crypto.encodeNpub(publicKey).getOrThrow())
        assertFalse(first.revealNsec() == second.revealNsec())
        assertFalse(first.privateKeyHex == second.privateKeyHex)
        assertFalse(first.toString().contains(first.revealNsec()))
        assertFalse(first.toString().contains(first.privateKeyHex))
    }

    @Test
    fun generatedIdentitySecretSupportsNip44SelfRoundTrip() {
        val crypto = ProductionNostrCryptoFactory.createOrNull()
            ?: return

        val secret = GeneratedIdentitySecret.generate(crypto).getOrThrow()
        val privateKey = NostrPrivateKey(secret.privateKeyHex)
        val publicKey = NostrPublicKey(secret.publicKeyHex, secret.npub)
        val plaintext = """{"body_markdown":"generated direct signer plaintext should not persist"}"""

        val ciphertext = crypto.encryptToSelf(plaintext, privateKey, publicKey).getOrThrow()
        assertFalse(ciphertext.contains(plaintext))
        assertFalse(ciphertext.contains("generated direct signer plaintext"))
        assertFalse(ciphertext.contains("body_markdown"))
        assertEquals(plaintext, crypto.decryptFromSelf(ciphertext, privateKey, publicKey).getOrThrow())
    }

    @Test
    fun repeatedRelayStyleNip44SelfRoundTrips() {
        val crypto = ProductionNostrCryptoFactory.createOrNull()
            ?: return
        repeat(100) { iteration ->
            val privateKey = crypto.generatePrivateKey().getOrThrow()
            val publicKey = crypto.derivePublicKey(privateKey).getOrThrow()
            val nsec = crypto.encodeNsec(privateKey).getOrThrow()
            val npub = crypto.encodeNpub(publicKey).getOrThrow()
            assertTrue(nsec.startsWith("nsec1"), "nsec prefix failed at iteration $iteration")
            assertTrue(npub.startsWith("npub1"), "npub prefix failed at iteration $iteration")
            val decodedPrivateKey = crypto.decodeNsec(nsec)
            assertTrue(decodedPrivateKey is KeyDecodeResult.Valid, "nsec decode failed at iteration $iteration")
            assertEquals(privateKey, decodedPrivateKey.privateKey, "nsec round trip failed at iteration $iteration")
            val decodedNpub = Nip19.decode(npub)
            assertEquals(publicKey.hex, decodedNpub?.data?.toHexString(), "npub round trip failed at iteration $iteration")

            val now = System.currentTimeMillis()
            val payloads = listOf(
                NotePayload(
                    noteId = "relay-test-${UUID.randomUUID()}",
                    createdAtMs = now,
                    updatedAtMs = now,
                    bodyMarkdown = relayStyleBody,
                    deleted = false,
                ),
                NotePayload(
                    noteId = "relay-test-${UUID.randomUUID()}",
                    createdAtMs = now,
                    updatedAtMs = now + 1,
                    bodyMarkdown = "",
                    deleted = true,
                ),
            )
            payloads.forEachIndexed { payloadIndex, payload ->
                assertPayloadRoundTrip(
                    crypto = crypto,
                    privateKey = privateKey,
                    publicKey = publicKey,
                    payload = payload,
                    createdAt = now / 1000 + payloadIndex,
                    context = "iteration=$iteration payload=$payloadIndex",
                )
            }
        }
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

    private fun assertPayloadRoundTrip(
        crypto: NostrCrypto,
        privateKey: NostrPrivateKey,
        publicKey: NostrPublicKey,
        payload: NotePayload,
        createdAt: Long,
        context: String,
    ) {
        val plaintext = JsonNotePayloadCodec.encode(payload)
        val safeContext = "$context bytes=${plaintext.encodeToByteArray().size}"
        val encryptedInfo = Nip44.v2.encrypt(plaintext, privateKey.hex.hexToBytes(), publicKey.hex.hexToBytes())
        val objectDecrypted = runCatching {
            Nip44.v2.decrypt(
                encryptedInfo,
                privateKey.hex.hexToBytes(),
                publicKey.hex.hexToBytes(),
            )
        }.getOrElse { error("direct object decrypt threw ${it.safeCryptoFailure()} at $safeContext") }
        assertEquals(payload, JsonNotePayloadCodec.decode(objectDecrypted).getOrThrow(), "direct object decrypt failed at $safeContext")
        val roundTrippedInfo = Nip44v2.EncryptedInfo.decodePayload(encryptedInfo.encodePayload())
        assertTrue(encryptedInfo.nonce.contentEquals(roundTrippedInfo.nonce), "NIP-44 payload nonce changed at $safeContext")
        assertTrue(encryptedInfo.ciphertext.contentEquals(roundTrippedInfo.ciphertext), "NIP-44 payload ciphertext changed at $safeContext")
        assertTrue(encryptedInfo.mac.contentEquals(roundTrippedInfo.mac), "NIP-44 payload MAC changed at $safeContext")
        val ciphertext = crypto.encryptToSelf(plaintext, privateKey, publicKey).getOrThrow()
        assertFalse(ciphertext.contains(plaintext), "ciphertext contains payload JSON at $safeContext")
        if (payload.bodyMarkdown.isNotEmpty()) {
            assertFalse(ciphertext.contains(payload.bodyMarkdown), "ciphertext contains note body at $safeContext")
        }
        val decodedObjectDecrypted = runCatching {
            Nip44.v2.decrypt(
                Nip44v2.EncryptedInfo.decodePayload(ciphertext),
                privateKey.hex.hexToBytes(),
                publicKey.hex.hexToBytes(),
            )
        }.getOrElse { error("decoded object decrypt threw ${it.safeCryptoFailure()} at $safeContext") }
        assertEquals(payload, JsonNotePayloadCodec.decode(decodedObjectDecrypted).getOrThrow(), "decoded object decrypt failed at $safeContext")
        val directDecrypted = crypto.decryptFromSelf(ciphertext, privateKey, publicKey).getOrThrow()
        assertEquals(payload, JsonNotePayloadCodec.decode(directDecrypted).getOrThrow(), "direct decrypt failed at $safeContext")

        val event = crypto.sign(
            UnsignedNostrEvent(
                pubkey = publicKey.hex,
                createdAt = createdAt,
                kind = NoteKind,
                tags = noteEventTags(noteDTag(payload.noteId)),
                content = ciphertext,
            ),
            privateKey,
        ).getOrThrow()
        assertEquals(ciphertext, event.content, "signing changed event content at $safeContext")
        assertTrue(crypto.validate(event).getOrThrow(), "event validation failed at $safeContext")
        val eventDecrypted = crypto.decryptFromSelf(event.content, privateKey, publicKey).getOrThrow()
        assertEquals(payload, JsonNotePayloadCodec.decode(eventDecrypted).getOrThrow(), "event content decrypt failed at $safeContext")
    }

    private fun ByteArray.toHexString(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    private fun Throwable.safeCryptoFailure(): String =
        "${this::class.simpleName}:${message?.replace(Regex("[0-9a-fA-F]{24,}"), "<hex-redacted>")?.take(160) ?: ""}"
}
