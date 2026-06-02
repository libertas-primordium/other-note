package com.libertasprimordium.othernote.ui

import com.libertasprimordium.othernote.data.InMemoryNoteRepository
import com.libertasprimordium.othernote.data.InMemoryLocalEventCache
import com.libertasprimordium.othernote.data.InMemoryPendingWriteStore
import com.libertasprimordium.othernote.data.LocalEventCache
import com.libertasprimordium.othernote.data.PendingWriteStore
import com.libertasprimordium.othernote.data.RelaySettingsStore
import com.libertasprimordium.othernote.domain.DefaultRelays
import com.libertasprimordium.othernote.domain.RelayConfig
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.security.DefaultKeyManagementPolicy
import com.libertasprimordium.othernote.security.KeyManagementPolicy
import com.libertasprimordium.othernote.security.Nip46RemoteSigner
import com.libertasprimordium.othernote.security.NostrSignerProvider
import com.libertasprimordium.othernote.security.NostrSignerEventSigner
import com.libertasprimordium.othernote.security.NostrSignerNip44Operator
import com.libertasprimordium.othernote.security.NostrSignerPublicKeyRequester
import com.libertasprimordium.othernote.security.SecureSecretStore
import com.libertasprimordium.othernote.security.UnavailableSignerEventSigner
import com.libertasprimordium.othernote.security.UnavailableExternalSignerProvider
import com.libertasprimordium.othernote.security.UnavailableSignerNip44Operator
import com.libertasprimordium.othernote.security.UnavailableSecureSecretStore
import com.libertasprimordium.othernote.security.UnavailableSignerPublicKeyRequester

enum class AppRuntimeMode {
    Offline,
    DesktopDevRelay,
}

enum class AppPlatform {
    Generic,
    Android,
    Desktop,
}

data class AppServices(
    val mode: AppRuntimeMode,
    val platform: AppPlatform = AppPlatform.Generic,
    val crypto: NostrCrypto,
    val client: NostrClient,
    val showRelayDiagnostics: Boolean = false,
    val showNip55Diagnostics: Boolean = false,
    val keyManagementPolicy: KeyManagementPolicy = DefaultKeyManagementPolicy,
    val secureSecretStore: SecureSecretStore = UnavailableSecureSecretStore(),
    val externalSignerProvider: NostrSignerProvider = UnavailableExternalSignerProvider(),
    val externalSignerPublicKeyRequester: NostrSignerPublicKeyRequester = UnavailableSignerPublicKeyRequester(),
    val externalSignerEventSigner: NostrSignerEventSigner = UnavailableSignerEventSigner(),
    val externalSignerNip44Operator: NostrSignerNip44Operator = UnavailableSignerNip44Operator(),
    val remoteSigner: Nip46RemoteSigner? = null,
    val localEventCache: LocalEventCache = InMemoryLocalEventCache(),
    val pendingWriteStore: PendingWriteStore = InMemoryPendingWriteStore(),
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

fun defaultAppServices(platform: AppPlatform = AppPlatform.Generic): AppServices = AppServices(
    mode = AppRuntimeMode.Offline,
    platform = platform,
    crypto = NonProductionNostrCrypto(),
    client = OfflineNostrClient(),
    startupWarnings = listOf("Production relay runtime is disabled"),
)
