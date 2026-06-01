package com.libertasprimordium.othernote.security

interface NostrSignerProvider {
    val mode: SignerMode
    val isAvailable: Boolean
    val unavailableReason: String?
    val displayName: String? get() = null
    val canGetPublicKey: Boolean get() = false
    val canSignEvent: Boolean get() = false
    val canNip44Encrypt: Boolean get() = canNip44EncryptDecrypt
    val canNip44Decrypt: Boolean get() = canNip44EncryptDecrypt
    val canNip44RoundTrip: Boolean get() = canNip44Encrypt && canNip44Decrypt
    val canNip44EncryptDecrypt: Boolean get() = false
    val safeDiagnostics: List<String> get() = emptyList()
}

class UnavailableExternalSignerProvider(
    override val unavailableReason: String = "External signer integration is not implemented for this runtime.",
) : NostrSignerProvider {
    override val mode: SignerMode = SignerMode.ExternalSigner
    override val isAvailable: Boolean = false
}
