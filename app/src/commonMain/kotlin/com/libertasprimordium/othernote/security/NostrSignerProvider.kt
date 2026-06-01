package com.libertasprimordium.othernote.security

interface NostrSignerProvider {
    val mode: SignerMode
    val isAvailable: Boolean
    val unavailableReason: String?
}

class UnavailableExternalSignerProvider(
    override val unavailableReason: String = "External signer integration is not implemented for this runtime.",
) : NostrSignerProvider {
    override val mode: SignerMode = SignerMode.ExternalSigner
    override val isAvailable: Boolean = false
}
