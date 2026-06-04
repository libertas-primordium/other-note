package com.libertasprimordium.othernote.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.libertasprimordium.othernote.data.InMemoryNoteRepository
import com.libertasprimordium.othernote.data.InMemoryLocalEventCache
import com.libertasprimordium.othernote.data.InMemoryPendingWriteStore
import com.libertasprimordium.othernote.data.LocalEventCache
import com.libertasprimordium.othernote.data.NoopNoteListPreferenceStore
import com.libertasprimordium.othernote.data.PendingWriteStore
import com.libertasprimordium.othernote.data.RelaySettingsStore
import com.libertasprimordium.othernote.data.NoopThemePreferenceStore
import com.libertasprimordium.othernote.data.NoteListPreferenceStore
import com.libertasprimordium.othernote.data.ThemePreferenceStore
import com.libertasprimordium.othernote.domain.DefaultRelays
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.DefaultRelayTester
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.nostr.RelayTester
import com.libertasprimordium.othernote.security.DefaultKeyManagementPolicy
import com.libertasprimordium.othernote.security.KeyManagementPolicy
import com.libertasprimordium.othernote.security.Nip46SessionStore
import com.libertasprimordium.othernote.security.Nip46RemoteSigner
import com.libertasprimordium.othernote.security.Nip55SessionStore
import com.libertasprimordium.othernote.security.NostrSignerProvider
import com.libertasprimordium.othernote.security.NostrSignerEventSigner
import com.libertasprimordium.othernote.security.NostrSignerNip44Operator
import com.libertasprimordium.othernote.security.NostrSignerPublicKeyRequester
import com.libertasprimordium.othernote.security.SecureSecretStore
import com.libertasprimordium.othernote.security.UnavailableNip46SessionStore
import com.libertasprimordium.othernote.security.UnavailableNip55SessionStore
import com.libertasprimordium.othernote.security.UnavailableSignerEventSigner
import com.libertasprimordium.othernote.security.UnavailableExternalSignerProvider
import com.libertasprimordium.othernote.security.UnavailableSignerNip44Operator
import com.libertasprimordium.othernote.security.UnavailableSecureSecretStore
import com.libertasprimordium.othernote.security.UnavailableSignerPublicKeyRequester

enum class AppRuntimeMode {
    Offline,
    DesktopRelay,
    DesktopDevRelay,
}

enum class AppPlatform {
    Generic,
    Android,
    Desktop,
}

interface ExternalUrlOpener {
    fun open(url: String): Boolean
}

object UnavailableExternalUrlOpener : ExternalUrlOpener {
    override fun open(url: String): Boolean = false
}

sealed interface DirectNsecCredentialSaveResult {
    data object Saved : DirectNsecCredentialSaveResult
    data object Canceled : DirectNsecCredentialSaveResult
    data object Unavailable : DirectNsecCredentialSaveResult
    data class Failed(val safeMessage: String) : DirectNsecCredentialSaveResult
}

interface DirectNsecCredentialSaver {
    suspend fun saveDirectNsecCredential(accountIdentifier: String, nsec: String): DirectNsecCredentialSaveResult
}

object UnavailableDirectNsecCredentialSaver : DirectNsecCredentialSaver {
    override suspend fun saveDirectNsecCredential(
        accountIdentifier: String,
        nsec: String,
    ): DirectNsecCredentialSaveResult = DirectNsecCredentialSaveResult.Unavailable
}

sealed interface NoteImageLoadResult {
    data class Loaded(val image: ImageBitmap) : NoteImageLoadResult
    data object Failed : NoteImageLoadResult
}

interface NoteImageLoader {
    suspend fun load(url: String): NoteImageLoadResult
}

object UnavailableNoteImageLoader : NoteImageLoader {
    override suspend fun load(url: String): NoteImageLoadResult = NoteImageLoadResult.Failed
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
    val nip55SessionStore: Nip55SessionStore = UnavailableNip55SessionStore(),
    val remoteSigner: Nip46RemoteSigner? = null,
    val nip46SessionStore: Nip46SessionStore = UnavailableNip46SessionStore(),
    val localEventCache: LocalEventCache = InMemoryLocalEventCache(),
    val pendingWriteStore: PendingWriteStore = InMemoryPendingWriteStore(),
    val relayTester: RelayTester = DefaultRelayTester(client, crypto),
    val themePreferenceStore: ThemePreferenceStore = NoopThemePreferenceStore,
    val noteListPreferenceStore: NoteListPreferenceStore = NoopNoteListPreferenceStore,
    val externalUrlOpener: ExternalUrlOpener = UnavailableExternalUrlOpener,
    val directNsecCredentialSaver: DirectNsecCredentialSaver = UnavailableDirectNsecCredentialSaver,
    val noteImageLoader: NoteImageLoader = UnavailableNoteImageLoader,
    val notes: InMemoryNoteRepository = InMemoryNoteRepository(),
    val relaySettings: RelaySettingsStore = RelaySettingsStore(
        if (mode == AppRuntimeMode.DesktopRelay || mode == AppRuntimeMode.DesktopDevRelay) DesktopRelayDefaults else DefaultRelays,
    ),
    val startupWarnings: List<String> = emptyList(),
)

val DesktopRelayDefaults = DefaultRelays

val DesktopDevRelayDefaults = DesktopRelayDefaults

fun defaultAppServices(platform: AppPlatform = AppPlatform.Generic): AppServices = AppServices(
    mode = AppRuntimeMode.Offline,
    platform = platform,
    crypto = NonProductionNostrCrypto(),
    client = OfflineNostrClient(),
    startupWarnings = listOf("Production relay runtime is disabled"),
)
