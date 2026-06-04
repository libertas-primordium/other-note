package com.libertasprimordium.othernote.data

interface SecureKeyStore {
    val canPersistPrivateKeys: Boolean
    suspend fun saveNsec(nsec: String): Result<Unit>
    suspend fun loadNsec(): Result<String?>
    suspend fun clearNsec(): Result<Unit>
}

class DisabledSecureKeyStore : SecureKeyStore {
    override val canPersistPrivateKeys: Boolean = false
    override suspend fun saveNsec(nsec: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Secure key persistence is not implemented for this platform yet."))

    override suspend fun loadNsec(): Result<String?> = Result.success(null)
    override suspend fun clearNsec(): Result<Unit> = Result.success(Unit)
}
