package com.libertasprimordium.othernote.security

import com.libertasprimordium.othernote.nostr.Nip19
import com.libertasprimordium.othernote.nostr.hexToBytes
import com.libertasprimordium.othernote.nostr.toHex

sealed class SignerPublicKeyRequestResult {
    data class Success(
        val pubkeyHex: String,
        val npub: String,
        val signerPackage: String?,
    ) : SignerPublicKeyRequestResult()

    data object Cancelled : SignerPublicKeyRequestResult()
    data class Unavailable(val safeReason: String) : SignerPublicKeyRequestResult()
    data class Failed(val safeReason: String) : SignerPublicKeyRequestResult()
    data class InvalidResponse(val safeReason: String) : SignerPublicKeyRequestResult()
}

interface NostrSignerPublicKeyRequester {
    fun requestPublicKey(onResult: (SignerPublicKeyRequestResult) -> Unit)
}

interface TargetedNostrSignerPublicKeyRequester : NostrSignerPublicKeyRequester {
    fun requestPublicKeyForSigner(signerPackage: String, onResult: (SignerPublicKeyRequestResult) -> Unit)
}

interface TargetedNostrSignerAvailability {
    fun isSignerPackageAvailable(signerPackage: String): Boolean
}

class UnavailableSignerPublicKeyRequester(
    private val safeReason: String = "External signer public-key request is not implemented for this runtime.",
) : NostrSignerPublicKeyRequester {
    override fun requestPublicKey(onResult: (SignerPublicKeyRequestResult) -> Unit) {
        onResult(SignerPublicKeyRequestResult.Unavailable(safeReason))
    }
}

object SignerPublicKeyResponseParser {
    fun parse(
        result: String?,
        pubkey: String? = null,
        npub: String? = null,
        signerPackage: String? = null,
    ): SignerPublicKeyRequestResult {
        val resultValue = result?.trim().orEmpty()
        val pubkeyValue = pubkey?.trim().orEmpty()
        val npubValue = npub?.trim().orEmpty()
        val candidates = listOf(resultValue, pubkeyValue, npubValue).filter { it.isNotBlank() }
        if (candidates.isEmpty()) {
            return SignerPublicKeyRequestResult.InvalidResponse("Signer did not return a public key")
        }
        val decoded = candidates.map { decodePublicKeyCandidate(it) }
        val invalid = decoded.firstOrNull { it is ParsedPublicKey.Invalid }
        if (invalid != null && decoded.none { it is ParsedPublicKey.Valid }) {
            return SignerPublicKeyRequestResult.InvalidResponse("Signer returned malformed public key data")
        }
        val validKeys = decoded.filterIsInstance<ParsedPublicKey.Valid>()
        val first = validKeys.firstOrNull()
            ?: return SignerPublicKeyRequestResult.InvalidResponse("Signer returned malformed public key data")
        if (validKeys.any { it.pubkeyHex != first.pubkeyHex || it.npub != first.npub }) {
            return SignerPublicKeyRequestResult.InvalidResponse("Signer returned mismatched public key values")
        }
        return SignerPublicKeyRequestResult.Success(
            pubkeyHex = first.pubkeyHex,
            npub = first.npub,
            signerPackage = signerPackage?.takeIf { it.isNotBlank() },
        )
    }

    private fun decodePublicKeyCandidate(value: String): ParsedPublicKey =
        when {
            value.startsWith("npub1", ignoreCase = false) -> decodeNpub(value)
            value.isHexPublicKey() -> decodeHex(value.lowercase())
            else -> ParsedPublicKey.Invalid
        }

    private fun decodeNpub(value: String): ParsedPublicKey {
        val decoded = Nip19.decode(value) ?: return ParsedPublicKey.Invalid
        if (decoded.hrp != "npub" || decoded.data.size != 32) return ParsedPublicKey.Invalid
        val hex = decoded.data.toHex()
        return ParsedPublicKey.Valid(hex, value.lowercase())
    }

    private fun decodeHex(value: String): ParsedPublicKey {
        val bytes = runCatching { value.hexToBytes() }.getOrNull() ?: return ParsedPublicKey.Invalid
        if (bytes.size != 32) return ParsedPublicKey.Invalid
        val npub = Nip19.encode("npub", bytes) ?: return ParsedPublicKey.Invalid
        return ParsedPublicKey.Valid(value, npub)
    }
}

private sealed class ParsedPublicKey {
    data class Valid(val pubkeyHex: String, val npub: String) : ParsedPublicKey()
    data object Invalid : ParsedPublicKey()
}

private fun String.isHexPublicKey(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
