package com.libertasprimordium.othernote.security

class AndroidExternalSignerProvider : NostrSignerProvider {
    override val mode: SignerMode = SignerMode.ExternalSigner
    override val isAvailable: Boolean = false
    override val unavailableReason: String = "NIP-55 signer integration not implemented yet."
}
