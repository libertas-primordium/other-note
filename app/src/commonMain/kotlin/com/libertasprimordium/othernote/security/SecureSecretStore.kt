package com.libertasprimordium.othernote.security

interface SecureSecretStore {
    val isAvailable: Boolean
    val unavailableReason: String?
    suspend fun listSavedNsecs(): SecureSecretStoreResult
    suspend fun saveNsec(accountId: String, nsec: String): SecureSecretStoreResult
    suspend fun loadNsec(accountId: String): SecureSecretStoreResult
    suspend fun deleteNsec(accountId: String): SecureSecretStoreResult
}

data class SavedNsecIdentity(
    val accountPubkey: String,
    val npub: String,
    val label: String? = null,
) {
    override fun toString(): String =
        "SavedNsecIdentity(accountPubkey=${accountPubkey.safePrefix()}, npub=${npub.safePrefix()}, labelPresent=${label != null})"
}

sealed class SecureSecretStoreResult {
    data object Unavailable : SecureSecretStoreResult()
    data object Saved : SecureSecretStoreResult()
    data object Deleted : SecureSecretStoreResult()
    data class Listed(val identities: List<SavedNsecIdentity>) : SecureSecretStoreResult()
    data class Loaded(val nsec: String) : SecureSecretStoreResult() {
        override fun toString(): String = "Loaded(nsec=redacted)"
    }
    data class Failed(val safeMessage: String) : SecureSecretStoreResult()
}

class UnavailableSecureSecretStore(
    override val unavailableReason: String = "Secure secret storage is not implemented for this runtime.",
) : SecureSecretStore {
    override val isAvailable: Boolean = false

    override suspend fun listSavedNsecs(): SecureSecretStoreResult =
        SecureSecretStoreResult.Unavailable

    override suspend fun saveNsec(accountId: String, nsec: String): SecureSecretStoreResult =
        SecureSecretStoreResult.Unavailable

    override suspend fun loadNsec(accountId: String): SecureSecretStoreResult =
        SecureSecretStoreResult.Unavailable

    override suspend fun deleteNsec(accountId: String): SecureSecretStoreResult =
        SecureSecretStoreResult.Unavailable
}

private fun String.safePrefix(): String =
    if (length <= 12) this else take(12)
