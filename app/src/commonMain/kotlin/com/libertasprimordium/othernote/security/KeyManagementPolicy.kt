package com.libertasprimordium.othernote.security

enum class SignerMode {
    ExternalSigner,
    SessionOnlyNsec,
    SavedDeviceNsec,
}

data class KeyManagementPolicy(
    val preferredModes: List<SignerMode> = listOf(
        SignerMode.ExternalSigner,
        SignerMode.SessionOnlyNsec,
        SignerMode.SavedDeviceNsec,
    ),
    val plaintextPersistenceAllowed: Boolean = false,
) {
    fun savedDeviceNsecAllowed(store: SecureSecretStore): Boolean =
        !plaintextPersistenceAllowed && store.isAvailable

    fun resolveAvailableModes(
        store: SecureSecretStore,
        externalSigner: NostrSignerProvider,
    ): List<SignerMode> = preferredModes.filter { mode ->
        when (mode) {
            SignerMode.ExternalSigner -> externalSigner.isAvailable
            SignerMode.SessionOnlyNsec -> true
            SignerMode.SavedDeviceNsec -> savedDeviceNsecAllowed(store)
        }
    }
}

val DefaultKeyManagementPolicy = KeyManagementPolicy()
