package com.libertasprimordium.othernote.security

class DesktopSecureSecretStore : SecureSecretStore {
    override val isAvailable: Boolean = false
    override val unavailableReason: String = "OS keyring storage not implemented yet."

    override suspend fun saveNsec(accountId: String, nsec: String): SecureSecretStoreResult =
        SecureSecretStoreResult.Unavailable

    override suspend fun loadNsec(accountId: String): SecureSecretStoreResult =
        SecureSecretStoreResult.Unavailable

    override suspend fun deleteNsec(accountId: String): SecureSecretStoreResult =
        SecureSecretStoreResult.Unavailable
}
