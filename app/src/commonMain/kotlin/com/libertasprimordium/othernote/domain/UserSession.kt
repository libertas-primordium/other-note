package com.libertasprimordium.othernote.domain

data class UserSession(
    val nsec: String,
    val privateKeyHex: String,
    val npub: String,
    val publicKeyHex: String,
    val authMethod: SessionAuthMethod = SessionAuthMethod.SessionOnlyNsec,
    val signerPackage: String? = null,
) {
    override fun toString(): String =
        "UserSession(nsec=redacted, privateKeyHex=redacted, npub=${npub.safePrefix()}, publicKeyHex=${publicKeyHex.safePrefix()}, authMethod=$authMethod, signerPackage=$signerPackage)"
}

enum class SessionAuthMethod {
    SessionOnlyNsec,
    ExternalSigner,
    RemoteSigner,
}

fun UserSession.abbreviatedNpub(): String =
    if (npub.length <= 18) npub else "${npub.take(10)}...${npub.takeLast(6)}"

fun UserSession.hasSessionPrivateKey(): Boolean =
    authMethod == SessionAuthMethod.SessionOnlyNsec && privateKeyHex.length == 64

private fun String.safePrefix(): String =
    if (length <= 12) this else take(12)
