package com.libertasprimordium.othernote.nostr

import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.core.Event as QuartzEvent
import com.vitorpamplona.quartz.nip01Core.crypto.EventAssembler
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01
import com.vitorpamplona.quartz.nip44Encryption.Nip44

object ProductionNostrCryptoFactory {
    const val unavailableReason =
        "Quartz-backed Nostr crypto is unavailable."

    fun createOrNull(): NostrCrypto? = QuartzNostrCrypto()
}

class QuartzNostrCrypto : NostrCrypto {
    override val productionReady: Boolean = true

    override fun generatePrivateKey(): Result<NostrPrivateKey> = runCatching {
        NostrPrivateKey((KeyPair().privKey ?: error("Quartz did not generate a private key")).toHexKey())
    }

    override fun encodeNsec(privateKey: NostrPrivateKey): Result<String> = runCatching {
        Nip19.encode("nsec", privateKey.hex.hexToBytes()) ?: error("Could not encode nsec")
    }

    override fun encodeNpub(publicKey: NostrPublicKey): Result<String> = runCatching {
        Nip19.encode("npub", publicKey.hex.hexToBytes()) ?: error("Could not encode npub")
    }

    override fun decodeNsec(nsec: String): KeyDecodeResult {
        val trimmed = nsec.trim()
        if (!trimmed.startsWith("nsec1") && !trimmed.startsWith("NSEC1")) {
            return KeyDecodeResult.Invalid("Key must start with nsec1")
        }
        val decoded = Nip19.decode(trimmed) ?: return KeyDecodeResult.Invalid("Invalid bech32 nsec")
        if (decoded.hrp != "nsec") return KeyDecodeResult.Invalid("Expected nsec key")
        if (decoded.data.size != 32) return KeyDecodeResult.Invalid("nsec payload must be 32 bytes")
        return KeyDecodeResult.Valid(NostrPrivateKey(decoded.data.toHex()))
    }

    override fun derivePublicKey(privateKey: NostrPrivateKey): Result<NostrPublicKey> = runCatching {
        val keyPair = keyPair(privateKey)
        val publicKeyHex = keyPair.pubKey.toHexKey()
        NostrPublicKey(publicKeyHex, encodeNpub(NostrPublicKey(publicKeyHex, "")).getOrThrow())
    }

    override fun encryptToSelf(
        plaintext: String,
        privateKey: NostrPrivateKey,
        publicKey: NostrPublicKey,
    ): Result<String> = runCatching {
        Nip44.encrypt(plaintext, privateKey.hex.hexToBytes(), publicKey.hex.hexToBytes()).encodePayload()
    }

    override fun decryptFromSelf(
        ciphertext: String,
        privateKey: NostrPrivateKey,
        publicKey: NostrPublicKey,
    ): Result<String> = runCatching {
        Nip44.decrypt(ciphertext, privateKey.hex.hexToBytes(), publicKey.hex.hexToBytes())
    }

    override fun computeEventId(unsigned: UnsignedNostrEvent): Result<String> = runCatching {
        EventHasher.hashId(unsigned.pubkey, unsigned.createdAt, unsigned.kind, unsigned.tags.toQuartzTags(), unsigned.content)
    }

    override fun sign(unsigned: UnsignedNostrEvent, privateKey: NostrPrivateKey): Result<NostrEvent> = runCatching {
        val publicKey = derivePublicKey(privateKey).getOrThrow()
        val event: QuartzEvent = EventAssembler.hashAndSign(
            publicKey.hex,
            unsigned.createdAt,
            unsigned.kind,
            unsigned.tags.toQuartzTags(),
            unsigned.content,
            privateKey.hex.hexToBytes(),
        )
        event.toOtherNoteEvent()
    }

    override fun validate(event: NostrEvent): Result<Boolean> = runCatching {
        val unsigned = UnsignedNostrEvent(
            pubkey = event.pubkey,
            createdAt = event.createdAt,
            kind = event.kind,
            tags = event.tags,
            content = event.content,
        )
        val idMatches = computeEventId(unsigned).getOrThrow() == event.id
        val signatureMatches = Nip01.verify(
            event.sig.hexToBytes(),
            event.id.hexToBytes(),
            event.pubkey.hexToBytes(),
        )
        idMatches && signatureMatches
    }

    private fun keyPair(privateKey: NostrPrivateKey): KeyPair = KeyPair(privateKey.hex.hexToBytes(), null, true)

    private fun List<List<String>>.toQuartzTags(): Array<Array<String>> =
        map { it.toTypedArray() }.toTypedArray()

    private fun QuartzEvent.toOtherNoteEvent(): NostrEvent = NostrEvent(
        id = id,
        pubkey = pubKey,
        createdAt = createdAt,
        kind = kind,
        tags = tags.map { it.toList() },
        content = content,
        sig = sig,
    )
}
