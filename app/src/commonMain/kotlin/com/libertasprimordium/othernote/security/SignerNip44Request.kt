package com.libertasprimordium.othernote.security

sealed class SignerNip44OperationResult {
    data class Encrypted(val payload: String, val signerPackage: String?) : SignerNip44OperationResult()
    data class Decrypted(val plaintext: String, val signerPackage: String?) : SignerNip44OperationResult()
    data object Cancelled : SignerNip44OperationResult()
    data class Unavailable(val safeReason: String) : SignerNip44OperationResult()
    data class Failed(val safeReason: String) : SignerNip44OperationResult()
    data class InvalidResponse(val safeReason: String) : SignerNip44OperationResult()
}

interface NostrSignerNip44Operator {
    fun encryptToSelf(
        plaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult

    fun decryptFromSelf(
        ciphertext: String,
        expectedPlaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult
}

class UnavailableSignerNip44Operator(
    private val safeReason: String = "External signer NIP-44 operations are not implemented for this runtime.",
) : NostrSignerNip44Operator {
    override fun encryptToSelf(
        plaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult = SignerNip44OperationResult.Unavailable(safeReason)

    override fun decryptFromSelf(
        ciphertext: String,
        expectedPlaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult = SignerNip44OperationResult.Unavailable(safeReason)
}

enum class SignerNip44Operation(
    val diagnosticName: String,
    val contentProviderMethod: String,
) {
    Encrypt("nip44_encrypt", "NIP44_ENCRYPT"),
    Decrypt("nip44_decrypt", "NIP44_DECRYPT"),
}

data class SignerNip44Request(
    val operation: SignerNip44Operation,
    val payload: String,
    val peerPubkey: String,
    val currentUserPubkey: String,
    val signerPackage: String?,
    val safeDiagnostics: List<String>,
)

object SignerNip44TestPayload {
    const val Plaintext = "Other Note NIP-44 signer test"
}

object SignerNip44RequestBuilder {
    fun build(
        operation: SignerNip44Operation,
        payload: String,
        peerPubkey: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): Result<SignerNip44Request> = runCatching {
        require(payload.isNotBlank()) { "NIP-44 signer payload must not be blank" }
        require(peerPubkey.isValidHexPubkey()) { "Peer pubkey must be 32-byte hex" }
        require(currentUserPubkey.isValidHexPubkey()) { "Current user pubkey must be 32-byte hex" }
        SignerNip44Request(
            operation = operation,
            payload = payload,
            peerPubkey = peerPubkey,
            currentUserPubkey = currentUserPubkey,
            signerPackage = signerPackage?.takeIf { it.isNotBlank() },
            safeDiagnostics = listOf(
                "operation=${operation.diagnosticName}",
                "request_path=content_resolver",
                "target_signer_package_present=${!signerPackage.isNullOrBlank()}",
                "peer_pubkey=${peerPubkey.abbreviatedHex()}",
                "current_user=${currentUserPubkey.abbreviatedHex()}",
                "payload_length=${payload.length}",
                "content_provider_method=${operation.contentProviderMethod}",
            ),
        )
    }

    private fun String.isValidHexPubkey(): Boolean =
        length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun String.abbreviatedHex(): String = take(12)
}

object SignerNip44ResponseParser {
    fun parseEncryptResult(
        result: String?,
        plaintext: String,
        signerPackage: String?,
    ): SignerNip44OperationResult {
        val encrypted = result?.takeIf { it.isNotBlank() }
            ?: return SignerNip44OperationResult.InvalidResponse("Signer returned invalid encryption result")
        if (encrypted.contains(plaintext)) {
            return SignerNip44OperationResult.InvalidResponse("Signer returned invalid encryption result")
        }
        return SignerNip44OperationResult.Encrypted(
            payload = encrypted,
            signerPackage = signerPackage?.takeIf { it.isNotBlank() },
        )
    }

    fun parseDecryptResult(
        result: String?,
        expectedPlaintext: String,
        signerPackage: String?,
    ): SignerNip44OperationResult {
        val decrypted = result?.takeIf { it.isNotBlank() }
            ?: return SignerNip44OperationResult.InvalidResponse("Signer decryption failed")
        if (decrypted != expectedPlaintext) {
            return SignerNip44OperationResult.InvalidResponse("Signer decryption failed")
        }
        return SignerNip44OperationResult.Decrypted(
            plaintext = decrypted,
            signerPackage = signerPackage?.takeIf { it.isNotBlank() },
        )
    }
}
