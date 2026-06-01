package com.libertasprimordium.othernote.security

interface SecureSecretStore {
    val isAvailable: Boolean
    val unavailableReason: String?
    suspend fun saveNsec(accountId: String, nsec: String): SecureSecretStoreResult
    suspend fun loadNsec(accountId: String): SecureSecretStoreResult
    suspend fun deleteNsec(accountId: String): SecureSecretStoreResult
}

sealed class SecureSecretStoreResult {
    data object Unavailable : SecureSecretStoreResult()
    data object Saved : SecureSecretStoreResult()
    data object Deleted : SecureSecretStoreResult()
    data class Loaded(val nsec: String) : SecureSecretStoreResult()
    data class Failed(val safeMessage: String) : SecureSecretStoreResult()
}

class UnavailableSecureSecretStore(
    override val unavailableReason: String = "Secure secret storage is not implemented for this runtime.",
) : SecureSecretStore {
    override val isAvailable: Boolean = false

    override suspend fun saveNsec(accountId: String, nsec: String): SecureSecretStoreResult =
        SecureSecretStoreResult.Unavailable

    override suspend fun loadNsec(accountId: String): SecureSecretStoreResult =
        SecureSecretStoreResult.Unavailable

    override suspend fun deleteNsec(accountId: String): SecureSecretStoreResult =
        SecureSecretStoreResult.Unavailable
}
