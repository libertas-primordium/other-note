package com.libertasprimordium.othernote.domain

data class UserSession(
    val nsec: String,
    val privateKeyHex: String,
    val npub: String,
    val publicKeyHex: String,
    val authMethod: SessionAuthMethod = SessionAuthMethod.SessionOnlyNsec,
    val signerPackage: String? = null,
)

enum class SessionAuthMethod {
    SessionOnlyNsec,
    ExternalSigner,
    RemoteSigner,
}

fun UserSession.abbreviatedNpub(): String =
    if (npub.length <= 18) npub else "${npub.take(10)}...${npub.takeLast(6)}"

fun UserSession.hasSessionPrivateKey(): Boolean =
    authMethod == SessionAuthMethod.SessionOnlyNsec && privateKeyHex.length == 64
