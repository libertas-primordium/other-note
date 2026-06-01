package com.libertasprimordium.othernote.nostr

interface NostrCrypto {
    val productionReady: Boolean
    fun decodeNsec(nsec: String): KeyDecodeResult
    fun derivePublicKey(privateKey: NostrPrivateKey): Result<NostrPublicKey>
    fun encryptToSelf(plaintext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String>
    fun decryptFromSelf(ciphertext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String>
    fun sign(unsigned: UnsignedNostrEvent, privateKey: NostrPrivateKey): Result<NostrEvent>
}

class NonProductionNostrCrypto : NostrCrypto {
    override val productionReady: Boolean = false

    override fun decodeNsec(nsec: String): KeyDecodeResult {
        val trimmed = nsec.trim()
        if (!trimmed.startsWith("nsec1")) return KeyDecodeResult.Invalid("Key must start with nsec1")
        val decoded = Nip19.decode(trimmed) ?: return KeyDecodeResult.Invalid("Invalid bech32 nsec")
        if (decoded.hrp != "nsec") return KeyDecodeResult.Invalid("Expected nsec key")
        if (decoded.data.size != 32) return KeyDecodeResult.Invalid("nsec payload must be 32 bytes")
        return KeyDecodeResult.Valid(NostrPrivateKey(decoded.data.toHex()))
    }

    override fun derivePublicKey(privateKey: NostrPrivateKey): Result<NostrPublicKey> =
        Result.failure(UnsupportedOperationException("Secp256k1 public key derivation is not wired yet. Do not publish with this adapter."))

    override fun encryptToSelf(plaintext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String> =
        Result.failure(UnsupportedOperationException("NIP-44 v2 encryption is not wired yet. Plaintext was not emitted."))

    override fun decryptFromSelf(ciphertext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String> =
        Result.failure(UnsupportedOperationException("NIP-44 v2 decryption is not wired yet."))

    override fun sign(unsigned: UnsignedNostrEvent, privateKey: NostrPrivateKey): Result<NostrEvent> =
        Result.failure(UnsupportedOperationException("Secp256k1 event signing is not wired yet."))
}

fun ByteArray.toHex(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
