package com.libertasprimordium.othernote.ui

import com.libertasprimordium.othernote.data.InMemoryNoteRepository
import com.libertasprimordium.othernote.data.RelaySettingsStore
import com.libertasprimordium.othernote.domain.DefaultRelays
import com.libertasprimordium.othernote.domain.RelayConfig
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.security.DefaultKeyManagementPolicy
import com.libertasprimordium.othernote.security.KeyManagementPolicy
import com.libertasprimordium.othernote.security.NostrSignerProvider
import com.libertasprimordium.othernote.security.SecureSecretStore
import com.libertasprimordium.othernote.security.UnavailableExternalSignerProvider
import com.libertasprimordium.othernote.security.UnavailableSecureSecretStore

enum class AppRuntimeMode {
    Offline,
    DesktopDevRelay,
}

data class AppServices(
    val mode: AppRuntimeMode,
    val crypto: NostrCrypto,
    val client: NostrClient,
    val showRelayDiagnostics: Boolean = false,
    val keyManagementPolicy: KeyManagementPolicy = DefaultKeyManagementPolicy,
    val secureSecretStore: SecureSecretStore = UnavailableSecureSecretStore(),
    val externalSignerProvider: NostrSignerProvider = UnavailableExternalSignerProvider(),
    val notes: InMemoryNoteRepository = InMemoryNoteRepository(),
    val relaySettings: RelaySettingsStore = RelaySettingsStore(
        if (mode == AppRuntimeMode.DesktopDevRelay) DesktopDevRelayDefaults else DefaultRelays,
    ),
    val startupWarnings: List<String> = emptyList(),
)

val DesktopDevRelayDefaults = listOf(
    RelayConfig("wss://relay.damus.io"),
    RelayConfig("wss://relay.primal.net"),
    RelayConfig("wss://relay.nostr.net"),
    RelayConfig("wss://nos.lol"),
    RelayConfig("wss://relay.ditto.pub"),
)

fun defaultAppServices(): AppServices = AppServices(
    mode = AppRuntimeMode.Offline,
    crypto = NonProductionNostrCrypto(),
    client = OfflineNostrClient(),
    startupWarnings = listOf("Production relay runtime is disabled"),
)
