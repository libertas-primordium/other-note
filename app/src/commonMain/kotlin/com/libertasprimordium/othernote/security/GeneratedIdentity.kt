package com.libertasprimordium.othernote.security

import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.NostrCrypto

class GeneratedIdentitySecret private constructor(
    private val nsecValue: String,
    val privateKeyHex: String,
    val publicKeyHex: String,
    val npub: String,
) {
    fun revealNsec(): String = nsecValue

    override fun toString(): String = "GeneratedIdentitySecret(nsec=redacted, privateKey=redacted, npub=${npub.safePrefix()})"

    companion object {
        fun generate(crypto: NostrCrypto): Result<GeneratedIdentitySecret> = runCatching {
            val privateKey = crypto.generatePrivateKey().getOrThrow()
            val publicKey = crypto.derivePublicKey(privateKey).getOrThrow()
            val nsec = crypto.encodeNsec(privateKey).getOrThrow()
            val npub = crypto.encodeNpub(publicKey).getOrThrow()
            val decoded = crypto.decodeNsec(nsec)
            require(decoded is KeyDecodeResult.Valid && decoded.privateKey == privateKey) {
                "Generated identity failed private-key verification"
            }
            GeneratedIdentitySecret(
                nsecValue = nsec,
                privateKeyHex = privateKey.hex,
                publicKeyHex = publicKey.hex,
                npub = npub,
            )
        }
    }
}

private fun String.safePrefix(): String =
    if (length <= 12) this else take(12)
