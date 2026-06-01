package com.libertasprimordium.othernote.nostr

interface NostrCrypto {
    val productionReady: Boolean
    fun generatePrivateKey(): Result<NostrPrivateKey>
    fun encodeNsec(privateKey: NostrPrivateKey): Result<String>
    fun encodeNpub(publicKey: NostrPublicKey): Result<String>
    fun decodeNsec(nsec: String): KeyDecodeResult
    fun derivePublicKey(privateKey: NostrPrivateKey): Result<NostrPublicKey>
    fun encryptToSelf(plaintext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String>
    fun decryptFromSelf(ciphertext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String>
    fun computeEventId(unsigned: UnsignedNostrEvent): Result<String>
    fun sign(unsigned: UnsignedNostrEvent, privateKey: NostrPrivateKey): Result<NostrEvent>
    fun validate(event: NostrEvent): Result<Boolean>
}

class NonProductionNostrCrypto : NostrCrypto {
    override val productionReady: Boolean = false
    private val unavailable = UnsupportedOperationException(
        "Production Nostr crypto is not wired. Add a compatible adapter for secp256k1, NIP-01 event ids/signatures, and NIP-44 v2.",
    )

    override fun generatePrivateKey(): Result<NostrPrivateKey> = Result.failure(unavailable)

    override fun encodeNsec(privateKey: NostrPrivateKey): Result<String> = Result.failure(unavailable)

    override fun encodeNpub(publicKey: NostrPublicKey): Result<String> = Result.failure(unavailable)

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

    override fun computeEventId(unsigned: UnsignedNostrEvent): Result<String> =
        Result.failure(UnsupportedOperationException("NIP-01 event id hashing is not wired yet."))

    override fun sign(unsigned: UnsignedNostrEvent, privateKey: NostrPrivateKey): Result<NostrEvent> =
        Result.failure(UnsupportedOperationException("Secp256k1 event signing is not wired yet."))

    override fun validate(event: NostrEvent): Result<Boolean> =
        Result.failure(UnsupportedOperationException("NIP-01 event id/signature validation is not wired yet."))
}

fun ByteArray.toHex(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
