package com.libertasprimordium.othernote.ui

import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.OtherNoteTag
import com.libertasprimordium.othernote.domain.DefaultRelays
import com.libertasprimordium.othernote.domain.RelayConfig
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.SessionAuthMethod
import com.libertasprimordium.othernote.domain.SyncState
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.domain.hasSessionPrivateKey
import com.libertasprimordium.othernote.data.PendingWriteMaxRetryCount
import com.libertasprimordium.othernote.data.ProfileRepository
import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.nostr.ProfileMetadata
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.RelayPublishResult
import com.libertasprimordium.othernote.nostr.RelayTestResult
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.security.GeneratedIdentitySecret
import com.libertasprimordium.othernote.security.Nip46SessionStoreResult
import com.libertasprimordium.othernote.security.Nip55SessionStoreResult
import com.libertasprimordium.othernote.security.SavedNip55Session
import com.libertasprimordium.othernote.security.SavedNsecIdentity
import com.libertasprimordium.othernote.security.SavedNip46SessionMetadata
import com.libertasprimordium.othernote.security.SavedNip55SessionMetadata
import com.libertasprimordium.othernote.security.SecureSecretStoreResult
import com.libertasprimordium.othernote.security.SignerPublicKeyRequestResult
import com.libertasprimordium.othernote.security.SignEventRequestResult
import com.libertasprimordium.othernote.security.SignerNip44Operation
import com.libertasprimordium.othernote.security.SignerNip44OperationResult
import com.libertasprimordium.othernote.security.SignerNip44RequestBuilder
import com.libertasprimordium.othernote.security.SignerNip44TestPayload
import com.libertasprimordium.othernote.security.SignerNoteEventBuildResult
import com.libertasprimordium.othernote.security.SignerNoteEventBuilder
import com.libertasprimordium.othernote.security.SignerNoteEventBuildStage
import com.libertasprimordium.othernote.security.Nip46ConnectResult
import com.libertasprimordium.othernote.security.Nip46ConnectionTokenParser
import com.libertasprimordium.othernote.security.SignerSignEventRequestBuilder
import com.libertasprimordium.othernote.security.SignerTestEventFactory
import com.libertasprimordium.othernote.security.TargetedNostrSignerAvailability
import com.libertasprimordium.othernote.sync.DeleteNoteUseCase
import com.libertasprimordium.othernote.sync.MigrateRelaysUseCase
import com.libertasprimordium.othernote.sync.RelayMigrationExecutionResult
import com.libertasprimordium.othernote.sync.RelayMigrationUseCase
import com.libertasprimordium.othernote.sync.RelayListPublishResult
import com.libertasprimordium.othernote.sync.RelayListSyncUseCase
import com.libertasprimordium.othernote.sync.SaveNoteUseCase
import com.libertasprimordium.othernote.sync.SaveResult
import com.libertasprimordium.othernote.sync.SyncNotesUseCase
import com.libertasprimordium.othernote.sync.mergeReducedNotesWithCurrent
import com.libertasprimordium.othernote.sync.planManualRelaySync
import com.libertasprimordium.othernote.sync.queueRelayMigrationPendingWrites
import com.libertasprimordium.othernote.sync.reduceNoteEvents
import com.libertasprimordium.othernote.sync.reduceNoteEventsAsync
import com.libertasprimordium.othernote.util.BuiltInNoteSortOptions
import com.libertasprimordium.othernote.util.DefaultNoteSortOption
import com.libertasprimordium.othernote.util.NoteSortOption
import com.libertasprimordium.othernote.util.noteSortOptionForId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class AppMode {
    SignedOut,
    LocalOnly,
    Authenticated,
}

private enum class SignerWriteAction {
    Save,
    Delete,
}

data class EditorSaveState(
    val inProgress: Boolean = false,
    val message: String = "",
    val error: String? = null,
)

enum class GeneratedIdentityStep {
    Idle,
    Explanation,
    Generated,
}

data class GeneratedIdentityState(
    val step: GeneratedIdentityStep = GeneratedIdentityStep.Idle,
    val npub: String = "",
    val nsecRevealed: Boolean = false,
    val savedAcknowledged: Boolean = false,
    val lossAcknowledged: Boolean = false,
    val error: String? = null,
    internal val secret: GeneratedIdentitySecret? = null,
) {
    val canUseForSession: Boolean get() =
        step == GeneratedIdentityStep.Generated && secret != null && savedAcknowledged && lossAcknowledged

    fun nsecForDisplay(): String =
        if (nsecRevealed) {
            secret?.revealNsec().orEmpty()
        } else {
            "nsec hidden until you reveal it"
        }
}

data class SavedIdentityState(
    val loading: Boolean = false,
    val identities: List<SavedNsecIdentity> = emptyList(),
    val error: String? = null,
)

enum class RemoteSignerPairingStage {
    Idle,
    CheckingToken,
    WaitingForSigner,
    FetchingAccount,
    AwaitingApproval,
    Connected,
    Failed,
}

data class RemoteSignerPairingState(
    val stage: RemoteSignerPairingStage = RemoteSignerPairingStage.Idle,
    val title: String = "Remote signer pairing",
    val message: String = "Paste a bunker link from your signer to begin.",
    val inProgress: Boolean = false,
    val authUrlAvailable: Boolean = false,
) {
    override fun toString(): String =
        "RemoteSignerPairingState(stage=$stage, title=$title, message=$message, inProgress=$inProgress, authUrlAvailable=$authUrlAvailable)"
}

data class SavedRemoteSignerState(
    val loading: Boolean = false,
    val sessions: List<SavedNip46SessionMetadata> = emptyList(),
    val error: String? = null,
)

data class SavedAndroidSignerState(
    val loading: Boolean = false,
    val sessions: List<SavedNip55SessionMetadata> = emptyList(),
    val error: String? = null,
)

data class ProfileUiState(
    val loading: Boolean = false,
    val pubkey: String? = null,
    val metadata: ProfileMetadata? = null,
)

enum class KeyringSaveConfirmationSource {
    ExistingNsec,
    GeneratedIdentity,
}

data class KeyringSaveConfirmationState(
    val source: KeyringSaveConfirmationSource? = null,
) {
    val visible: Boolean
        get() = source != null
}

object KeyringSaveWarningCopy {
    const val title: String = "Save this key to your desktop keyring?"
    const val body: String =
        "Other Note will store this nsec in the desktop keyring, not in Other Note app files. " +
            "The keyring protects the key at rest and behind your desktop/keyring unlock. " +
            "After the keyring is unlocked, authorized local apps or keyring tools may be able to request and display the saved nsec. " +
            "This is device-local convenience storage, not a backup. Keep a separate secure copy of the nsec or import it into a signer."
    const val description: String =
        "Stores this nsec in your desktop keyring. This is device-local convenience storage, not a backup. " +
            "Apps or tools allowed to access your unlocked keyring may be able to retrieve it."

    fun titleFor(platform: AppPlatform): String = when (platform) {
        AppPlatform.Android -> "Save this key to Android secure storage?"
        AppPlatform.Desktop -> title
        AppPlatform.Generic -> "Save this key to secure storage?"
    }

    fun bodyFor(platform: AppPlatform): String = when (platform) {
        AppPlatform.Android ->
            "Other Note will encrypt this nsec with an Android Keystore-backed key and store only the encrypted record on this device. " +
                "This is device-local convenience storage, not a backup. If this device is lost, reset, or the secure key becomes unavailable, you still need a separate secure copy or signer import. " +
                "Android signer or a trusted password manager is preferred for many users. " +
                "Only save this key on a device you trust. Logout stops using the saved identity, and Forget removes it from Other Note's secure storage."
        AppPlatform.Desktop -> body
        AppPlatform.Generic ->
            "Other Note will store this nsec only through OS-backed secure storage. This is device-local convenience storage, not a backup. " +
                "Keep a separate secure copy of the nsec or import it into a signer."
    }

    fun descriptionFor(platform: AppPlatform): String = when (platform) {
        AppPlatform.Android ->
            "Stores this nsec as an Android Keystore-encrypted device-local record. This is convenience storage, not a backup."
        AppPlatform.Desktop -> description
        AppPlatform.Generic ->
            "Stores this nsec through OS-backed secure storage. This is device-local convenience storage, not a backup."
    }

    fun labelFor(platform: AppPlatform): String = when (platform) {
        AppPlatform.Android -> "Android secure storage"
        AppPlatform.Desktop -> "Desktop keyring"
        AppPlatform.Generic -> "Secure storage"
    }

    fun saveButtonLabelFor(platform: AppPlatform): String = when (platform) {
        AppPlatform.Android -> "Save this identity to this device"
        AppPlatform.Desktop -> "Save to this device's keyring"
        AppPlatform.Generic -> "Save to secure storage"
    }

    fun confirmButtonLabelFor(platform: AppPlatform): String = when (platform) {
        AppPlatform.Android -> "Save to Android secure storage"
        AppPlatform.Desktop -> "Save to keyring"
        AppPlatform.Generic -> "Save"
    }
}

data class RelayAddTestState(
    val inProgress: Boolean = false,
    val warning: RelayAddWarning? = null,
)

data class RelayAddWarning(
    val relayUrl: String,
    val safeReason: String,
)

data class RelayMigrationUiState(
    val inProgress: Boolean = false,
    val warning: RelayMigrationWarning? = null,
)

data class RelayMigrationWarning(
    val title: String,
    val body: String,
    val details: String,
    val summary: String = title,
)

data class RelayMigrationUserWarningText(
    val title: String,
    val body: String,
)

private data class PendingRelayMigrationDecision(
    val requestedConfigs: List<RelayConfig>,
    val result: RelayMigrationExecutionResult?,
    val sessionPubkey: String?,
    val summary: String,
    val details: String,
    val successMessage: String = "Relay settings saved after migration warning.",
)

private data class ValidatedNsec(
    val nsec: String,
    val privateKey: NostrPrivateKey,
    val publicKey: NostrPublicKey,
) {
    override fun toString(): String = "ValidatedNsec(nsec=redacted, privateKey=redacted, publicKey=${publicKey.hex.take(12)})"
}

sealed interface RelayAddResult {
    data class Added(val relayUrl: String) : RelayAddResult
    data class ValidationFailed(val message: String) : RelayAddResult
    data class Duplicate(val relayUrl: String) : RelayAddResult
    data object WaitingForUserChoice : RelayAddResult
    data object InProgress : RelayAddResult
}

sealed interface RelaySettingsRefreshResult {
    data class PublishedListAvailable(val relays: List<String>, val safeSummary: String) : RelaySettingsRefreshResult
    data class Skipped(val safeReason: String) : RelaySettingsRefreshResult
    data class NoChange(val safeReason: String) : RelaySettingsRefreshResult
    data class Failed(val safeReason: String) : RelaySettingsRefreshResult
}

class AppState(private val services: AppServices = defaultAppServices()) {
    private val crypto = services.crypto
    private val signerVerifier = ProductionNostrCryptoFactory.createOrNull()
    private val client = services.client
    private val nostr = NostrRepository(crypto, client)
    private val profiles = ProfileRepository(client)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runtimeMode: AppRuntimeMode = services.mode
    val platform: AppPlatform = services.platform
    val directRelayRuntimeAvailable: Boolean = crypto.productionReady && services.client !is OfflineNostrClient
    val notes = services.notes
    val relaySettings = services.relaySettings
    private val saveNote = SaveNoteUseCase(
        notes,
        nostr,
        services.localEventCache,
        services.pendingWriteStore,
        appScope,
        ::updatePublishStatuses,
    )
    private val deleteNote = DeleteNoteUseCase(
        notes,
        nostr,
        services.localEventCache,
        services.pendingWriteStore,
        appScope,
        ::updatePublishStatuses,
    )

    fun openExternalUrl(url: String): Boolean {
        val opened = services.externalUrlOpener.open(url)
        if (!opened) {
            _message.value = "Could not open link with the system browser."
        }
        return opened
    }

    suspend fun loadNoteImage(url: String): NoteImageLoadResult = services.noteImageLoader.load(url)
    private val syncNotes = SyncNotesUseCase(
        notes,
        nostr,
        crypto,
        services.localEventCache,
        services.pendingWriteStore,
        appScope,
    )
    private val migrateRelays = MigrateRelaysUseCase()
    private val relayMigration = RelayMigrationUseCase(nostr, crypto, services.localEventCache)
    private val relayListSync = RelayListSyncUseCase(nostr, crypto)
    private val signerNoteEventBuilder = SignerNoteEventBuilder(
        nip44 = services.externalSignerNip44Operator,
        eventSigner = services.externalSignerEventSigner,
    )

    private fun signerNoteEventBuilderFor(session: UserSession): SignerNoteEventBuilder? = when (session.authMethod) {
        SessionAuthMethod.ExternalSigner -> signerNoteEventBuilder
        SessionAuthMethod.RemoteSigner -> services.remoteSigner?.let { SignerNoteEventBuilder(nip44 = it, eventSigner = it) }
        SessionAuthMethod.SessionOnlyNsec -> null
    }

    private fun signerNip44For(session: UserSession) = when (session.authMethod) {
        SessionAuthMethod.ExternalSigner -> services.externalSignerNip44Operator
        SessionAuthMethod.RemoteSigner -> services.remoteSigner
        SessionAuthMethod.SessionOnlyNsec -> null
    }

    private fun signerCanSignEvent(session: UserSession): Boolean = when (session.authMethod) {
        SessionAuthMethod.ExternalSigner -> externalSignerCanSignEvent
        SessionAuthMethod.RemoteSigner -> services.remoteSigner?.canSignEvent == true
        SessionAuthMethod.SessionOnlyNsec -> false
    }

    private fun signerCanNip44RoundTrip(session: UserSession): Boolean = when (session.authMethod) {
        SessionAuthMethod.ExternalSigner -> externalSignerCanNip44RoundTrip
        SessionAuthMethod.RemoteSigner -> services.remoteSigner?.canNip44RoundTrip == true
        SessionAuthMethod.SessionOnlyNsec -> false
    }

    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session

    private val _mode = MutableStateFlow(AppMode.SignedOut)
    val mode: StateFlow<AppMode> = _mode

    private val _syncState = MutableStateFlow(SyncState(warnings = services.startupWarnings))
    val syncState: StateFlow<SyncState> = _syncState

    private val _message = MutableStateFlow(startupMessage())
    val message: StateFlow<String> = _message

    private val _diagnosticMessage = MutableStateFlow("")
    val diagnosticMessage: StateFlow<String> = _diagnosticMessage

    private val _editorSaveState = MutableStateFlow(EditorSaveState())
    val editorSaveState: StateFlow<EditorSaveState> = _editorSaveState

    private val _generatedIdentityState = MutableStateFlow(GeneratedIdentityState())
    val generatedIdentityState: StateFlow<GeneratedIdentityState> = _generatedIdentityState

    private val _savedIdentityState = MutableStateFlow(
        if (services.secureSecretStore.isAvailable) {
            SavedIdentityState(loading = true)
        } else {
            SavedIdentityState(error = services.secureSecretStore.unavailableReason)
        },
    )
    val savedIdentityState: StateFlow<SavedIdentityState> = _savedIdentityState
    private val savedIdentityRefreshMutex = Mutex()

    private val _remoteSignerPairingState = MutableStateFlow(RemoteSignerPairingState())
    val remoteSignerPairingState: StateFlow<RemoteSignerPairingState> = _remoteSignerPairingState

    private val _savedRemoteSignerState = MutableStateFlow(
        if (services.nip46SessionStore.isAvailable) {
            SavedRemoteSignerState(loading = true)
        } else {
            SavedRemoteSignerState(error = services.nip46SessionStore.unavailableReason)
        },
    )
    val savedRemoteSignerState: StateFlow<SavedRemoteSignerState> = _savedRemoteSignerState
    private val savedRemoteSignerRefreshMutex = Mutex()

    private val _savedAndroidSignerState = MutableStateFlow(
        if (services.nip55SessionStore.isAvailable) {
            SavedAndroidSignerState(loading = true)
        } else {
            SavedAndroidSignerState(error = services.nip55SessionStore.unavailableReason)
        },
    )
    val savedAndroidSignerState: StateFlow<SavedAndroidSignerState> = _savedAndroidSignerState
    private val savedAndroidSignerRefreshMutex = Mutex()

    private val _profileState = MutableStateFlow(ProfileUiState())
    val profileState: StateFlow<ProfileUiState> = _profileState

    private val _selectedThemeId = MutableStateFlow(NostrClassicTheme.id)
    val selectedThemeId: StateFlow<String> = _selectedThemeId
    val availableThemes: List<OtherNoteThemeDefinition> = BuiltInOtherNoteThemes
    private var themeSelectionChangedInSession = false

    private val _selectedNoteSortId = MutableStateFlow(DefaultNoteSortOption.id)
    val selectedNoteSortId: StateFlow<String> = _selectedNoteSortId
    val availableNoteSortOptions: List<NoteSortOption> = BuiltInNoteSortOptions
    private var noteSortChangedInSession = false

    private val _keyringSaveConfirmationState = MutableStateFlow(KeyringSaveConfirmationState())
    val keyringSaveConfirmationState: StateFlow<KeyringSaveConfirmationState> = _keyringSaveConfirmationState

    private val _relayAddTestState = MutableStateFlow(RelayAddTestState())
    val relayAddTestState: StateFlow<RelayAddTestState> = _relayAddTestState

    private val _relayMigrationState = MutableStateFlow(RelayMigrationUiState())
    val relayMigrationState: StateFlow<RelayMigrationUiState> = _relayMigrationState
    private var pendingRelayMigrationDecision: PendingRelayMigrationDecision? = null
    private var relayListImportedForPubkey: String? = null

    val showRelayDiagnostics: Boolean = services.showRelayDiagnostics
    val showNip55Diagnostics: Boolean = services.showNip55Diagnostics
    val externalSignerAvailable: Boolean = services.externalSignerProvider.isAvailable
    val externalSignerDisplayName: String? = services.externalSignerProvider.displayName
    val externalSignerCanSignEvent: Boolean = services.externalSignerProvider.canSignEvent
    val externalSignerCanNip44RoundTrip: Boolean = services.externalSignerProvider.canNip44RoundTrip
    val remoteSignerAvailable: Boolean = services.remoteSigner?.isAvailable == true
    val savedRemoteSignerAvailable: Boolean = services.nip46SessionStore.isAvailable
    val savedRemoteSignerStatus: String =
        services.nip46SessionStore.unavailableReason ?: "Saved remote signer sessions available"
    val savedAndroidSignerAvailable: Boolean = platform == AppPlatform.Android && services.nip55SessionStore.isAvailable
    val savedAndroidSignerStatus: String =
        services.nip55SessionStore.unavailableReason ?: "Saved Android signer sessions available"
    val secureSecretStoreAvailable: Boolean = services.secureSecretStore.isAvailable
    val secureSecretStoreStatus: String =
        services.secureSecretStore.unavailableReason ?: "${KeyringSaveWarningCopy.labelFor(platform)} available"
    val remoteSignerStatus: String = when {
        services.remoteSigner == null -> "NIP-46 remote signer unavailable in this runtime"
        services.remoteSigner.isAvailable -> "NIP-46 remote signer available"
        else -> services.remoteSigner.unavailableReason ?: "NIP-46 remote signer unavailable"
    }
    val externalSignerStatus: String = if (services.externalSignerProvider.isAvailable) {
        "External signer detected: ${services.externalSignerProvider.displayName ?: "NIP-55 signer"}"
    } else {
        services.externalSignerProvider.unavailableReason ?: "External signer unavailable"
    }
    val defaultRelayUrls: List<String> = DefaultRelays.map { it.url }
    val signInOptions: List<SignInOptionUi>
        get() = buildSignInOptions(
            platform = platform,
            externalSignerAvailable = externalSignerAvailable,
            remoteSignerAvailable = remoteSignerAvailable,
        )

    init {
        appScope.launch {
            loadThemePreference()
        }
        appScope.launch {
            loadNoteListPreference()
        }
        appScope.launch {
            runCatching { relaySettings.loadPersisted() }
                .onFailure { _message.value = "Relay settings could not be loaded. ${it.safePersistenceMessage()}" }
        }
        appScope.launch {
            refreshSavedIdentities()
        }
        appScope.launch {
            refreshSavedRemoteSigners()
        }
        appScope.launch {
            refreshSavedAndroidSigners()
            restoreActiveSavedAndroidSigner()
        }
    }

    private suspend fun loadThemePreference() {
        val stored = withContext(Dispatchers.IO) {
            runCatching { services.themePreferenceStore.loadThemeId() }.getOrNull()
        }
        if (!themeSelectionChangedInSession) {
            _selectedThemeId.value = otherNoteThemeForId(stored).id
        }
    }

    fun selectTheme(themeId: String) {
        val selected = otherNoteThemeForId(themeId)
        themeSelectionChangedInSession = true
        _selectedThemeId.value = selected.id
        appScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { services.themePreferenceStore.saveThemeId(selected.id) }
            }
        }
    }

    private suspend fun loadNoteListPreference() {
        val stored = withContext(Dispatchers.IO) {
            runCatching { services.noteListPreferenceStore.loadSortId() }.getOrNull()
        }
        if (!noteSortChangedInSession) {
            _selectedNoteSortId.value = noteSortOptionForId(stored).id
        }
    }

    fun selectNoteSort(sortId: String) {
        val selected = noteSortOptionForId(sortId)
        noteSortChangedInSession = true
        _selectedNoteSortId.value = selected.id
        appScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { services.noteListPreferenceStore.saveSortId(selected.id) }
            }
        }
    }

    fun login(rawNsec: String): Boolean {
        return when (val decoded = crypto.decodeNsec(rawNsec)) {
            is KeyDecodeResult.Valid -> {
                if (!crypto.productionReady) {
                    _message.value = "Validated nsec format only. Relay sync is disabled in offline runtime."
                    _session.value = UserSession(
                        nsec = "nsec-redacted",
                        privateKeyHex = decoded.privateKey.hex,
                        npub = "npub unavailable in offline runtime",
                        publicKeyHex = "unavailable",
                        authMethod = SessionAuthMethod.SessionOnlyNsec,
                    )
                    _mode.value = AppMode.Authenticated
                    return true
                }
                val publicKey = crypto.derivePublicKey(decoded.privateKey).getOrElse {
                    _message.value = it.message ?: "Could not derive public key"
                    return false
                }
                activateValidatedNsecSession(
                    ValidatedNsec(
                        nsec = rawNsec.trim(),
                        privateKey = decoded.privateKey,
                        publicKey = publicKey,
                    ),
                    if (directRelayRuntimeAvailable) {
                        "nsec active for this session only. Other Note did not store it."
                    } else {
                        "nsec validated. Relay sync is disabled in offline runtime."
                    },
                )
            }
            is KeyDecodeResult.Invalid -> {
                _message.value = decoded.reason
                false
            }
        }
    }

    suspend fun saveDirectNsecCredentialWithPasswordManager(
        accountIdentifier: String,
        rawNsec: String,
    ): DirectNsecCredentialSaveResult =
        services.directNsecCredentialSaver.saveDirectNsecCredential(accountIdentifier, rawNsec.trim())

    fun applyDirectNsecCredentialSaveResult(result: DirectNsecCredentialSaveResult) {
        _message.value = when (result) {
            DirectNsecCredentialSaveResult.Saved ->
                "Password-manager save was accepted. nsec active for this session only."
            DirectNsecCredentialSaveResult.Canceled ->
                "Password-manager save was canceled. nsec active for this session only."
            DirectNsecCredentialSaveResult.Unavailable ->
                "Android Password Manager save is unavailable. nsec active for this session only."
            is DirectNsecCredentialSaveResult.Failed ->
                result.safeMessage.toUserFacingMessage()
        }
    }

    suspend fun refreshSavedIdentities(): Boolean {
        if (!services.secureSecretStore.isAvailable) {
            _savedIdentityState.value = SavedIdentityState(error = services.secureSecretStore.unavailableReason)
            return false
        }
        return savedIdentityRefreshMutex.withLock {
            _savedIdentityState.value = _savedIdentityState.value.copy(loading = true, error = null)
            val result = withContext(Dispatchers.IO) {
                services.secureSecretStore.listSavedNsecs()
            }
            when (result) {
                is SecureSecretStoreResult.Listed -> {
                    _savedIdentityState.value = SavedIdentityState(
                        identities = result.identities.map { it.withDerivedNpub() },
                    )
                    true
                }
                SecureSecretStoreResult.Unavailable -> {
                    _savedIdentityState.value = SavedIdentityState(error = services.secureSecretStore.unavailableReason)
                    false
                }
                is SecureSecretStoreResult.Failed -> {
                    _savedIdentityState.value = SavedIdentityState(error = result.safeMessage.toUserFacingMessage())
                    false
                }
                SecureSecretStoreResult.Deleted,
                SecureSecretStoreResult.Saved,
                is SecureSecretStoreResult.Loaded,
                -> {
                    _savedIdentityState.value = SavedIdentityState(error = "Could not load saved identities.")
                    false
                }
            }
        }
    }

    fun startRefreshSavedIdentities(): Job = appScope.launch {
        refreshSavedIdentities()
    }

    suspend fun refreshSavedRemoteSigners(): Boolean {
        if (!services.nip46SessionStore.isAvailable) {
            _savedRemoteSignerState.value = SavedRemoteSignerState(error = services.nip46SessionStore.unavailableReason)
            return false
        }
        return savedRemoteSignerRefreshMutex.withLock {
            _savedRemoteSignerState.value = _savedRemoteSignerState.value.copy(loading = true, error = null)
            val result = withContext(Dispatchers.IO) {
                services.nip46SessionStore.listSessions()
            }
            when (result) {
                is Nip46SessionStoreResult.Listed -> {
                    _savedRemoteSignerState.value = SavedRemoteSignerState(sessions = result.sessions.map { it.withDerivedNpub() })
                    true
                }
                Nip46SessionStoreResult.Unavailable -> {
                    _savedRemoteSignerState.value = SavedRemoteSignerState(error = services.nip46SessionStore.unavailableReason)
                    false
                }
                is Nip46SessionStoreResult.Failed -> {
                    _savedRemoteSignerState.value = SavedRemoteSignerState(error = result.safeMessage.toUserFacingMessage())
                    false
                }
                Nip46SessionStoreResult.Deleted,
                is Nip46SessionStoreResult.Loaded,
                Nip46SessionStoreResult.Saved,
                -> {
                    _savedRemoteSignerState.value = SavedRemoteSignerState(error = "Saved remote signer sessions could not be loaded.")
                    false
                }
            }
        }
    }

    fun startRefreshSavedRemoteSigners(): Job = appScope.launch {
        refreshSavedRemoteSigners()
    }

    suspend fun refreshSavedAndroidSigners(): Boolean {
        if (!services.nip55SessionStore.isAvailable) {
            _savedAndroidSignerState.value = SavedAndroidSignerState(error = services.nip55SessionStore.unavailableReason)
            return false
        }
        return savedAndroidSignerRefreshMutex.withLock {
            _savedAndroidSignerState.value = _savedAndroidSignerState.value.copy(loading = true, error = null)
            val result = withContext(Dispatchers.IO) {
                services.nip55SessionStore.listSessions()
            }
            when (result) {
                is Nip55SessionStoreResult.Listed -> {
                    _savedAndroidSignerState.value = SavedAndroidSignerState(sessions = result.sessions.map { it.withDerivedNpub() })
                    true
                }
                Nip55SessionStoreResult.Unavailable -> {
                    _savedAndroidSignerState.value = SavedAndroidSignerState(error = services.nip55SessionStore.unavailableReason)
                    false
                }
                is Nip55SessionStoreResult.Failed -> {
                    _savedAndroidSignerState.value = SavedAndroidSignerState(error = result.safeMessage.toUserFacingMessage())
                    false
                }
                Nip55SessionStoreResult.Deleted,
                is Nip55SessionStoreResult.Loaded,
                Nip55SessionStoreResult.Saved,
                -> {
                    _savedAndroidSignerState.value = SavedAndroidSignerState(error = "Saved Android signer sessions could not be loaded.")
                    false
                }
            }
        }
    }

    fun startRefreshSavedAndroidSigners(): Job = appScope.launch {
        refreshSavedAndroidSigners()
    }

    private suspend fun restoreActiveSavedAndroidSigner(): Boolean {
        if (platform != AppPlatform.Android || !services.nip55SessionStore.isAvailable || _mode.value != AppMode.SignedOut) {
            return false
        }
        val listed = withContext(Dispatchers.IO) {
            services.nip55SessionStore.listSessions()
        }
        val active = (listed as? Nip55SessionStoreResult.Listed)
            ?.sessions
            ?.firstOrNull { it.active }
            ?: return false
        return restoreSavedAndroidSignerSession(active.userPubkey, requireActive = true, message = "Signed in with saved Android signer.")
    }

    fun requestExistingNsecKeyringSaveConfirmation() {
        if (!services.secureSecretStore.isAvailable) {
            _message.value = "${KeyringSaveWarningCopy.labelFor(platform)} is not available. You can still sign in for this session."
            return
        }
        _keyringSaveConfirmationState.value =
            KeyringSaveConfirmationState(KeyringSaveConfirmationSource.ExistingNsec)
    }

    fun requestGeneratedIdentityKeyringSaveConfirmation() {
        val current = _generatedIdentityState.value
        if (!current.canUseForSession || current.secret == null) {
            _message.value = "Save the generated nsec and acknowledge the recovery warning before saving it to secure storage."
            return
        }
        if (!services.secureSecretStore.isAvailable) {
            _message.value = "${KeyringSaveWarningCopy.labelFor(platform)} is not available. You can still use this identity for this session."
            return
        }
        _keyringSaveConfirmationState.value =
            KeyringSaveConfirmationState(KeyringSaveConfirmationSource.GeneratedIdentity)
    }

    fun cancelKeyringSaveConfirmation() {
        _keyringSaveConfirmationState.value = KeyringSaveConfirmationState()
    }

    suspend fun confirmExistingNsecKeyringSave(rawNsec: String): Boolean {
        if (_keyringSaveConfirmationState.value.source != KeyringSaveConfirmationSource.ExistingNsec) return false
        return saveNsecToKeyring(rawNsec).also {
            _keyringSaveConfirmationState.value = KeyringSaveConfirmationState()
        }
    }

    suspend fun confirmGeneratedIdentityKeyringSave(): Boolean {
        if (_keyringSaveConfirmationState.value.source != KeyringSaveConfirmationSource.GeneratedIdentity) return false
        return saveGeneratedIdentityToKeyring().also {
            _keyringSaveConfirmationState.value = KeyringSaveConfirmationState()
        }
    }

    suspend fun saveNsecToKeyring(rawNsec: String): Boolean {
        if (!services.secureSecretStore.isAvailable) {
            _message.value = "${KeyringSaveWarningCopy.labelFor(platform)} is not available. You can still sign in for this session."
            return false
        }
        val validated = validateNsecForSavedIdentity(rawNsec).getOrElse {
            _message.value = it.message?.toUserFacingMessage() ?: "Could not save this identity to ${KeyringSaveWarningCopy.labelFor(platform)}."
            return false
        }
        val result = withContext(Dispatchers.IO) {
            services.secureSecretStore.saveNsec(validated.publicKey.hex, validated.nsec)
        }
        return when (result) {
            SecureSecretStoreResult.Saved -> {
                refreshSavedIdentities()
                ensureSavedIdentityVisible(validated.publicKey)
                activateValidatedNsecSession(
                    validated,
                    "Identity saved to ${KeyringSaveWarningCopy.labelFor(platform)} and signed in. This device storage is not a backup.",
                ).also { signedIn ->
                    if (!signedIn) {
                        _message.value = "Identity saved to ${KeyringSaveWarningCopy.labelFor(platform)}, but Other Note could not sign in. Use the saved identity to try again."
                    }
                }
            }
            SecureSecretStoreResult.Unavailable -> {
                _message.value = "${KeyringSaveWarningCopy.labelFor(platform)} is not available. You can still sign in for this session."
                false
            }
            is SecureSecretStoreResult.Failed -> {
                _message.value = result.safeMessage.toUserFacingMessage()
                false
            }
            SecureSecretStoreResult.Deleted,
            is SecureSecretStoreResult.Listed,
            is SecureSecretStoreResult.Loaded,
            -> {
                _message.value = "Could not save this identity to ${KeyringSaveWarningCopy.labelFor(platform)}."
                false
            }
        }
    }

    suspend fun saveGeneratedIdentityToKeyring(): Boolean {
        val current = _generatedIdentityState.value
        val secret = current.secret
        if (!current.canUseForSession || secret == null) {
            _message.value = "Save the generated nsec and acknowledge the recovery warning before saving it to secure storage."
            return false
        }
        if (!services.secureSecretStore.isAvailable) {
            _message.value = "${KeyringSaveWarningCopy.labelFor(platform)} is not available. You can still use this identity for this session."
            return false
        }
        val result = withContext(Dispatchers.IO) {
            services.secureSecretStore.saveNsec(secret.publicKeyHex, secret.revealNsec())
        }
        return when (result) {
            SecureSecretStoreResult.Saved -> {
                refreshSavedIdentities()
                ensureSavedIdentityVisible(NostrPublicKey(secret.publicKeyHex, secret.npub))
                activateValidatedNsecSession(
                    ValidatedNsec(
                        nsec = secret.revealNsec(),
                        privateKey = NostrPrivateKey(secret.privateKeyHex),
                        publicKey = NostrPublicKey(secret.publicKeyHex, secret.npub),
                    ),
                    "Identity saved to ${KeyringSaveWarningCopy.labelFor(platform)} and signed in. This device storage is not a backup.",
                ).also { signedIn ->
                    if (signedIn) {
                        _generatedIdentityState.value = GeneratedIdentityState()
                    } else {
                        _message.value = "Identity saved to ${KeyringSaveWarningCopy.labelFor(platform)}, but Other Note could not sign in. Use the saved identity to try again."
                    }
                }
            }
            SecureSecretStoreResult.Unavailable -> {
                _message.value = "${KeyringSaveWarningCopy.labelFor(platform)} is not available. You can still use this identity for this session."
                false
            }
            is SecureSecretStoreResult.Failed -> {
                _message.value = result.safeMessage.toUserFacingMessage()
                false
            }
            SecureSecretStoreResult.Deleted,
            is SecureSecretStoreResult.Listed,
            is SecureSecretStoreResult.Loaded,
            -> {
                _message.value = "Could not save this identity to ${KeyringSaveWarningCopy.labelFor(platform)}."
                false
            }
        }
    }

    suspend fun loginWithSavedIdentity(accountPubkey: String): Boolean {
        if (!services.secureSecretStore.isAvailable) {
            _message.value = "${KeyringSaveWarningCopy.labelFor(platform)} is not available. You can still sign in for this session."
            return false
        }
        val result = withContext(Dispatchers.IO) {
            services.secureSecretStore.loadNsec(accountPubkey)
        }
        val nsec = when (result) {
            is SecureSecretStoreResult.Loaded -> result.nsec
            SecureSecretStoreResult.Unavailable -> {
                _message.value = "${KeyringSaveWarningCopy.labelFor(platform)} is not available. You can still sign in for this session."
                return false
            }
            is SecureSecretStoreResult.Failed -> {
                _message.value = result.safeMessage.toUserFacingMessage()
                return false
            }
            SecureSecretStoreResult.Deleted,
            is SecureSecretStoreResult.Listed,
            SecureSecretStoreResult.Saved,
            -> {
                _message.value = "Could not load this saved identity."
                return false
            }
        }
        val validated = validateNsecForSavedIdentity(nsec).getOrElse {
            _message.value = it.message?.toUserFacingMessage() ?: "This saved identity is invalid. Remove it and sign in again."
            return false
        }
        if (!validated.publicKey.hex.equals(accountPubkey, ignoreCase = true)) {
            _message.value = "This saved identity does not match its saved account metadata. Remove it and sign in again."
            return false
        }
        _session.value = UserSession(
            nsec = "nsec-redacted",
            privateKeyHex = validated.privateKey.hex,
            npub = validated.publicKey.npub,
            publicKeyHex = validated.publicKey.hex,
            authMethod = SessionAuthMethod.SessionOnlyNsec,
        )
        _mode.value = AppMode.Authenticated
        _message.value = "Signed in with a saved identity from ${KeyringSaveWarningCopy.labelFor(platform)}."
        startProfileLoad()
        return true
    }

    suspend fun forgetSavedIdentity(accountPubkey: String): Boolean {
        if (!services.secureSecretStore.isAvailable) {
            _message.value = "${KeyringSaveWarningCopy.labelFor(platform)} is not available."
            return false
        }
        val result = withContext(Dispatchers.IO) {
            services.secureSecretStore.deleteNsec(accountPubkey)
        }
        return when (result) {
            SecureSecretStoreResult.Deleted -> {
                refreshSavedIdentities()
                removeSavedIdentityFromState(accountPubkey)
                val activeSavedIdentity = _session.value?.publicKeyHex.equals(accountPubkey, ignoreCase = true)
                if (activeSavedIdentity && platform == AppPlatform.Android) {
                    logout()
                    _message.value = "Saved key removed from Android secure storage. Current direct-key session was cleared."
                } else if (activeSavedIdentity) {
                    _message.value = "Saved key removed. Current session remains active until logout."
                } else {
                    _message.value = "Saved key removed from this device."
                }
                true
            }
            SecureSecretStoreResult.Unavailable -> {
                _message.value = "${KeyringSaveWarningCopy.labelFor(platform)} is not available."
                false
            }
            is SecureSecretStoreResult.Failed -> {
                _message.value = result.safeMessage.toUserFacingMessage()
                false
            }
            SecureSecretStoreResult.Saved,
            is SecureSecretStoreResult.Listed,
            is SecureSecretStoreResult.Loaded,
            -> {
                _message.value = "Could not forget this saved identity."
                false
            }
        }
    }

    fun startGeneratedIdentityFlow() {
        _generatedIdentityState.value = GeneratedIdentityState(step = GeneratedIdentityStep.Explanation)
        _message.value = "Create a new identity only if you are ready to save the nsec outside Other Note."
    }

    fun generateFreshIdentity(): Boolean {
        val generator = identityGenerationCrypto()
        if (generator == null) {
            _generatedIdentityState.value = GeneratedIdentityState(
                step = GeneratedIdentityStep.Explanation,
                error = ProductionNostrCryptoFactory.unavailableReason,
            )
            _message.value = "Fresh identity generation is unavailable in this runtime."
            return false
        }
        val secret = GeneratedIdentitySecret.generate(generator).getOrElse {
            _generatedIdentityState.value = GeneratedIdentityState(
                step = GeneratedIdentityStep.Explanation,
                error = "Could not generate a fresh identity.",
            )
            _message.value = "Could not generate a fresh identity."
            return false
        }
        _generatedIdentityState.value = GeneratedIdentityState(
            step = GeneratedIdentityStep.Generated,
            npub = secret.npub,
            secret = secret,
        )
        _message.value = "Fresh identity generated. Save the nsec before continuing."
        return true
    }

    fun toggleGeneratedIdentityNsecReveal() {
        val current = _generatedIdentityState.value
        if (current.step == GeneratedIdentityStep.Generated) {
            _generatedIdentityState.value = current.copy(nsecRevealed = !current.nsecRevealed)
        }
    }

    fun acknowledgeGeneratedIdentitySaved(acknowledged: Boolean) {
        val current = _generatedIdentityState.value
        _generatedIdentityState.value = current.copy(savedAcknowledged = acknowledged)
    }

    fun acknowledgeGeneratedIdentityLossRisk(acknowledged: Boolean) {
        val current = _generatedIdentityState.value
        _generatedIdentityState.value = current.copy(lossAcknowledged = acknowledged)
    }

    fun cancelGeneratedIdentityFlow() {
        _generatedIdentityState.value = GeneratedIdentityState()
        _message.value = "Fresh identity generation cancelled."
    }

    fun useGeneratedIdentityForSession(): Boolean {
        val current = _generatedIdentityState.value
        val secret = current.secret
        if (!current.canUseForSession || secret == null) {
            _message.value = "Save the generated nsec and acknowledge the recovery warning before continuing."
            return false
        }
        _session.value = UserSession(
            nsec = "nsec-redacted",
            privateKeyHex = secret.privateKeyHex,
            npub = secret.npub,
            publicKeyHex = secret.publicKeyHex,
            authMethod = SessionAuthMethod.SessionOnlyNsec,
        )
        _mode.value = AppMode.Authenticated
        startProfileLoad()
        _generatedIdentityState.value = GeneratedIdentityState()
        _message.value = if (directRelayRuntimeAvailable) {
            "New identity active for this session only. Other Note did not store the nsec."
        } else {
            "New identity active for this session only. Direct generated-key use remains local/offline in this runtime."
        }
        return true
    }

    private fun identityGenerationCrypto() =
        if (crypto.productionReady) crypto else ProductionNostrCryptoFactory.createOrNull()

    private fun validateNsecForSavedIdentity(rawNsec: String): Result<ValidatedNsec> = runCatching {
        if (!crypto.productionReady) {
            throw IllegalStateException("Saved direct-key sign-in requires production Nostr crypto.")
        }
        val trimmed = rawNsec.trim()
        val privateKey = when (val decoded = crypto.decodeNsec(trimmed)) {
            is KeyDecodeResult.Valid -> decoded.privateKey
            is KeyDecodeResult.Invalid -> throw IllegalArgumentException("This saved identity is invalid. Remove it and sign in again.")
        }
        val publicKey = crypto.derivePublicKey(privateKey).getOrElse {
            throw IllegalArgumentException("This saved identity is invalid. Remove it and sign in again.")
        }
        ValidatedNsec(nsec = trimmed, privateKey = privateKey, publicKey = publicKey)
    }

    private fun activateValidatedNsecSession(validated: ValidatedNsec, successMessage: String): Boolean =
        runCatching {
            _session.value = UserSession(
                nsec = "nsec-redacted",
                privateKeyHex = validated.privateKey.hex,
                npub = validated.publicKey.npub,
                publicKeyHex = validated.publicKey.hex,
                authMethod = SessionAuthMethod.SessionOnlyNsec,
            )
            _mode.value = AppMode.Authenticated
            _message.value = successMessage
            startProfileLoad()
            true
        }.getOrElse {
            false
        }

    private fun SavedNsecIdentity.withDerivedNpub(): SavedNsecIdentity {
        if (npub.isNotBlank()) return this
        val derived = if (accountPubkey.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            crypto.encodeNpub(NostrPublicKey(accountPubkey.lowercase(), "")).getOrNull()
        } else {
            null
        }
        return copy(npub = derived.orEmpty())
    }

    private fun SavedNip46SessionMetadata.withDerivedNpub(): SavedNip46SessionMetadata {
        if (userNpub.isNotBlank()) return this
        val derived = if (userPubkey.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            crypto.encodeNpub(NostrPublicKey(userPubkey.lowercase(), "")).getOrNull()
        } else {
            null
        }
        return copy(userNpub = derived.orEmpty())
    }

    private fun SavedNip55SessionMetadata.withDerivedNpub(): SavedNip55SessionMetadata {
        if (userNpub.isNotBlank()) return this
        val derived = if (userPubkey.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            crypto.encodeNpub(NostrPublicKey(userPubkey.lowercase(), "")).getOrNull()
        } else {
            null
        }
        return copy(userNpub = derived.orEmpty())
    }

    private fun ensureSavedIdentityVisible(publicKey: NostrPublicKey) {
        val current = _savedIdentityState.value
        if (current.identities.any { it.accountPubkey.equals(publicKey.hex, ignoreCase = true) }) return
        _savedIdentityState.value = current.copy(
            loading = false,
            error = null,
            identities = current.identities + SavedNsecIdentity(
                accountPubkey = publicKey.hex,
                npub = publicKey.npub,
                label = "${KeyringSaveWarningCopy.labelFor(platform)} identity",
            ),
        )
    }

    private fun removeSavedIdentityFromState(accountPubkey: String) {
        val current = _savedIdentityState.value
        _savedIdentityState.value = current.copy(
            loading = false,
            identities = current.identities.filterNot { it.accountPubkey.equals(accountPubkey, ignoreCase = true) },
        )
    }

    private fun ensureSavedRemoteSignerVisible(session: SavedNip46SessionMetadata) {
        val current = _savedRemoteSignerState.value
        if (current.sessions.any { it.userPubkey.equals(session.userPubkey, ignoreCase = true) }) return
        _savedRemoteSignerState.value = current.copy(
            loading = false,
            error = null,
            sessions = current.sessions + session.withDerivedNpub(),
        )
    }

    private fun removeSavedRemoteSignerFromState(userPubkey: String) {
        val current = _savedRemoteSignerState.value
        _savedRemoteSignerState.value = current.copy(
            loading = false,
            sessions = current.sessions.filterNot { it.userPubkey.equals(userPubkey, ignoreCase = true) },
        )
    }

    private fun ensureSavedAndroidSignerVisible(session: SavedNip55SessionMetadata) {
        val current = _savedAndroidSignerState.value
        if (current.sessions.any { it.userPubkey.equals(session.userPubkey, ignoreCase = true) }) return
        _savedAndroidSignerState.value = current.copy(
            loading = false,
            error = null,
            sessions = current.sessions + session.withDerivedNpub(),
        )
    }

    private fun removeSavedAndroidSignerFromState(userPubkey: String) {
        val current = _savedAndroidSignerState.value
        _savedAndroidSignerState.value = current.copy(
            loading = false,
            sessions = current.sessions.filterNot { it.userPubkey.equals(userPubkey, ignoreCase = true) },
        )
    }

    private fun SavedNip55Session.hasRequiredLocalFields(): Boolean =
        userPubkey.matches(Regex("^[0-9a-fA-F]{64}$")) &&
            signerPackage.isNotBlank()

    private fun SavedNip55Session.restorableNpub(): String =
        userNpub.takeIf { it.isNotBlank() }
            ?: crypto.encodeNpub(NostrPublicKey(userPubkey.lowercase(), "")).getOrNull()
            ?: ""

    private fun isSavedAndroidSignerAvailable(signerPackage: String): Boolean {
        if (signerPackage.isBlank()) return false
        val targeted = services.externalSignerProvider as? TargetedNostrSignerAvailability
        return targeted?.isSignerPackageAvailable(signerPackage) ?: externalSignerAvailable
    }

    fun requestExternalSignerPublicKey() {
        if (!externalSignerAvailable) {
            "No Android signer found. Install a NIP-55 signer such as Amber, or paste an nsec for this session."
                .also { _message.value = it }
            return
        }
        _message.value = "Waiting for signer..."
        services.externalSignerPublicKeyRequester.requestPublicKey(::handleSignerPublicKeyResult)
    }

    suspend fun loginWithSavedAndroidSigner(userPubkey: String): Boolean {
        return restoreSavedAndroidSignerSession(
            userPubkey = userPubkey,
            requireActive = false,
            message = "Signed in with saved Android signer.",
        )
    }

    private suspend fun restoreSavedAndroidSignerSession(
        userPubkey: String,
        requireActive: Boolean,
        message: String,
    ): Boolean {
        if (!services.nip55SessionStore.isAvailable) {
            _message.value = "Saved Android signer session could not be loaded."
            return false
        }
        val loaded = withContext(Dispatchers.IO) {
            services.nip55SessionStore.loadSession(userPubkey)
        }
        val savedSession = when (loaded) {
            is Nip55SessionStoreResult.Loaded -> loaded.session
            Nip55SessionStoreResult.Unavailable -> {
                _message.value = "Saved Android signer session could not be loaded."
                return false
            }
            is Nip55SessionStoreResult.Failed -> {
                _message.value = loaded.safeMessage.toUserFacingMessage()
                return false
            }
            Nip55SessionStoreResult.Deleted,
            is Nip55SessionStoreResult.Listed,
            Nip55SessionStoreResult.Saved,
            -> {
                _message.value = "Saved Android signer session could not be loaded."
                return false
            }
        }
        if (requireActive && !savedSession.active) {
            return false
        }
        if (!savedSession.hasRequiredLocalFields()) {
            _message.value = "Saved Android signer session is corrupted."
            return false
        }
        if (!isSavedAndroidSignerAvailable(savedSession.signerPackage)) {
            _message.value = "The saved Android signer is not installed or is no longer available."
            return false
        }
        val updated = savedSession.copy(
            active = true,
            lastUsedAtMs = com.libertasprimordium.othernote.util.nowMs(),
        )
        withContext(Dispatchers.IO) {
            services.nip55SessionStore.saveSession(updated)
        }
        refreshSavedAndroidSigners()
        activateExternalSignerSession(updated.userPubkey, updated.restorableNpub(), updated.signerPackage, message)
        return true
    }

    suspend fun forgetSavedAndroidSigner(userPubkey: String): Boolean {
        if (!services.nip55SessionStore.isAvailable) {
            _message.value = "Saved Android signer session could not be forgotten."
            return false
        }
        val result = withContext(Dispatchers.IO) {
            services.nip55SessionStore.deleteSession(userPubkey)
        }
        return when (result) {
            Nip55SessionStoreResult.Deleted -> {
                removeSavedAndroidSignerFromState(userPubkey)
                _message.value = if (_session.value?.authMethod == SessionAuthMethod.ExternalSigner &&
                    _session.value?.publicKeyHex.equals(userPubkey, ignoreCase = true)
                ) {
                    _session.value = null
                    _mode.value = AppMode.SignedOut
                    _profileState.value = ProfileUiState()
                    notes.clear()
                    "Saved Android signer forgotten. Current signer session was cleared."
                } else {
                    "Saved Android signer forgotten."
                }
                true
            }
            Nip55SessionStoreResult.Unavailable -> {
                _message.value = "Saved Android signer session could not be forgotten."
                false
            }
            is Nip55SessionStoreResult.Failed -> {
                _message.value = result.safeMessage.toUserFacingMessage()
                false
            }
            is Nip55SessionStoreResult.Listed,
            is Nip55SessionStoreResult.Loaded,
            Nip55SessionStoreResult.Saved,
            -> {
                _message.value = "Saved Android signer session could not be forgotten."
                false
            }
        }
    }

    fun startRemoteSignerConnection(rawToken: String): Boolean {
        if (_remoteSignerPairingState.value.inProgress) {
            _message.value = "Remote signer pairing is already in progress."
            return false
        }
        _remoteSignerPairingState.value = RemoteSignerPairingState(
            stage = RemoteSignerPairingStage.CheckingToken,
            title = "Checking bunker link",
            message = "Other Note is checking the remote signer link before sending a pairing request.",
            inProgress = true,
        )
        if (!validateRemoteSignerConnectionInput(rawToken)) return false
        _remoteSignerPairingState.value = RemoteSignerPairingState(
            stage = RemoteSignerPairingStage.WaitingForSigner,
            title = "Waiting for signer approval",
            message = "Approve the connection in your signer. Your private key stays there; Other Note asks to read your public key, encrypt/decrypt notes, and sign note events.",
            inProgress = true,
        )
        _message.value = "Waiting for remote signer approval..."
        appScope.launch {
            connectRemoteSigner(rawToken)
        }
        return true
    }

    suspend fun connectRemoteSigner(rawToken: String): Boolean {
        if (!_remoteSignerPairingState.value.inProgress) {
            _remoteSignerPairingState.value = RemoteSignerPairingState(
                stage = RemoteSignerPairingStage.CheckingToken,
                title = "Checking bunker link",
                message = "Other Note is checking the remote signer link before sending a pairing request.",
                inProgress = true,
            )
        }
        if (!validateRemoteSignerConnectionInput(rawToken)) return false
        _remoteSignerPairingState.value = RemoteSignerPairingState(
            stage = RemoteSignerPairingStage.WaitingForSigner,
            title = "Waiting for signer approval",
            message = "Approve the connection in your signer. Your private key stays there; Other Note is sending the request through the signer relays in your bunker link.",
            inProgress = true,
        )
        _message.value = "Waiting for remote signer approval..."
        val result = withContext(Dispatchers.IO) {
            services.remoteSigner?.connectAsync(rawToken) ?: Nip46ConnectResult.Unavailable
        }
        if (result is Nip46ConnectResult.Connected) {
            _remoteSignerPairingState.value = RemoteSignerPairingState(
                stage = RemoteSignerPairingStage.FetchingAccount,
                title = "Reading account public key",
                message = "The signer approved the connection. Other Note is reading your account public key from the signer.",
                inProgress = true,
            )
        }
        return handleRemoteSignerConnectResult(result)
    }

    private fun validateRemoteSignerConnectionInput(rawToken: String): Boolean {
        val remoteSigner = services.remoteSigner
        if (remoteSigner == null || !remoteSigner.isAvailable) {
            val message = "NIP-46 remote signer is unavailable in this runtime."
            _message.value = message
            _remoteSignerPairingState.value = RemoteSignerPairingState(
                stage = RemoteSignerPairingStage.Failed,
                title = "Remote signer unavailable",
                message = message.toUserFacingMessage(),
            )
            return false
        }
        val parsed = Nip46ConnectionTokenParser.parse(rawToken)
        if (parsed.isFailure) {
            val rawMessage = parsed.exceptionOrNull()?.message ?: "Remote signer token is invalid"
            val userMessage = rawMessage.toUserFacingMessage()
            _message.value = userMessage
            _remoteSignerPairingState.value = RemoteSignerPairingState(
                stage = RemoteSignerPairingStage.Failed,
                title = "Bunker link is invalid",
                message = userMessage,
            )
            return false
        }
        return true
    }

    suspend fun loginWithSavedRemoteSigner(userPubkey: String): Boolean {
        if (_remoteSignerPairingState.value.inProgress) {
            _message.value = "Remote signer pairing is already in progress."
            return false
        }
        val remoteSigner = services.remoteSigner
        if (remoteSigner == null || !remoteSigner.isAvailable) {
            val message = "NIP-46 remote signer is unavailable in this runtime."
            _message.value = message
            _remoteSignerPairingState.value = RemoteSignerPairingState(
                stage = RemoteSignerPairingStage.Failed,
                title = "Remote signer unavailable",
                message = message.toUserFacingMessage(),
            )
            return false
        }
        if (!services.nip46SessionStore.isAvailable) {
            _message.value = "Saved remote signer session could not be loaded."
            return false
        }
        _remoteSignerPairingState.value = RemoteSignerPairingState(
            stage = RemoteSignerPairingStage.FetchingAccount,
            title = "Resuming remote signer",
            message = "Other Note is using the saved remote-signer session and checking the account public key.",
            inProgress = true,
        )
        val loaded = withContext(Dispatchers.IO) {
            services.nip46SessionStore.loadSession(userPubkey)
        }
        val session = when (loaded) {
            is Nip46SessionStoreResult.Loaded -> loaded.session
            Nip46SessionStoreResult.Unavailable -> {
                _message.value = "Saved remote signer session could not be loaded."
                _remoteSignerPairingState.value = RemoteSignerPairingState(
                    stage = RemoteSignerPairingStage.Failed,
                    title = "Saved remote signer unavailable",
                    message = "Saved remote signer session could not be loaded.",
                )
                return false
            }
            is Nip46SessionStoreResult.Failed -> {
                val userMessage = loaded.safeMessage.toUserFacingMessage()
                _message.value = userMessage
                _remoteSignerPairingState.value = RemoteSignerPairingState(
                    stage = RemoteSignerPairingStage.Failed,
                    title = "Could not load saved remote signer",
                    message = userMessage,
                )
                return false
            }
            Nip46SessionStoreResult.Deleted,
            is Nip46SessionStoreResult.Listed,
            Nip46SessionStoreResult.Saved,
            -> {
                _message.value = "Saved remote signer session could not be loaded."
                _remoteSignerPairingState.value = RemoteSignerPairingState(
                    stage = RemoteSignerPairingStage.Failed,
                    title = "Could not load saved remote signer",
                    message = "Saved remote signer session could not be loaded.",
                )
                return false
            }
        }
        val result = withContext(Dispatchers.IO) {
            remoteSigner.resumeAsync(session)
        }
        return handleRemoteSignerConnectResult(result, resumed = true)
    }

    suspend fun forgetSavedRemoteSigner(userPubkey: String): Boolean {
        if (!services.nip46SessionStore.isAvailable) {
            _message.value = "Saved remote signer session could not be forgotten."
            return false
        }
        val result = withContext(Dispatchers.IO) {
            services.nip46SessionStore.deleteSession(userPubkey)
        }
        return when (result) {
            Nip46SessionStoreResult.Deleted -> {
                removeSavedRemoteSignerFromState(userPubkey)
                val active = _session.value
                if (active?.authMethod == SessionAuthMethod.RemoteSigner &&
                    active.publicKeyHex.equals(userPubkey, ignoreCase = true)
                ) {
                    services.remoteSigner?.disconnect()
                    _session.value = null
                    _mode.value = AppMode.SignedOut
                    _profileState.value = ProfileUiState()
                    _remoteSignerPairingState.value = RemoteSignerPairingState()
                    notes.clear()
                    _message.value = "Saved remote signer forgotten. Current remote-signer session was cleared."
                } else {
                    _message.value = "Saved remote signer forgotten."
                }
                true
            }
            Nip46SessionStoreResult.Unavailable -> {
                _message.value = "Saved remote signer session could not be forgotten."
                false
            }
            is Nip46SessionStoreResult.Failed -> {
                _message.value = result.safeMessage.toUserFacingMessage()
                false
            }
            is Nip46SessionStoreResult.Listed,
            is Nip46SessionStoreResult.Loaded,
            Nip46SessionStoreResult.Saved,
            -> {
                _message.value = "Saved remote signer session could not be forgotten."
                false
            }
        }
    }

    private suspend fun handleRemoteSignerConnectResult(result: Nip46ConnectResult, resumed: Boolean = false): Boolean =
        when (result) {
            is Nip46ConnectResult.Connected -> {
                _session.value = UserSession(
                    nsec = "remote-signer",
                    privateKeyHex = "",
                    npub = result.userNpub,
                    publicKeyHex = result.userPubkey,
                    authMethod = SessionAuthMethod.RemoteSigner,
                    signerPackage = "nip46:${result.remoteSignerPubkey.take(12)}",
                )
                _mode.value = AppMode.Authenticated
                val sessionSaved = if (resumed) true else persistActiveRemoteSignerSession()
                _message.value = if (resumed) {
                    "Remote signer session resumed; relay sync can run with signer approval."
                } else if (sessionSaved) {
                    "Remote signer connected; relay sync can run with signer approval."
                } else {
                    "Remote signer connected for this app session, but the reusable session could not be saved."
                }
                _remoteSignerPairingState.value = RemoteSignerPairingState(
                    stage = RemoteSignerPairingStage.Connected,
                    title = if (resumed) "Remote signer resumed" else "Remote signer connected",
                    message = when {
                        resumed -> "Other Note will ask the signer to encrypt/decrypt notes and sign events when needed."
                        sessionSaved -> "Other Note will ask the signer to encrypt/decrypt notes and sign events when needed. The reusable remote-signer session is stored on this device."
                        else -> "Other Note will ask the signer to encrypt/decrypt notes and sign events when needed. The reusable remote-signer session could not be saved, so you may need to pair again after restart."
                    },
                )
                startProfileLoad()
                true
            }
            is Nip46ConnectResult.AwaitingApproval -> {
                val message = "Open the signer approval page if your signer requires it, then retry pairing after approving."
                _message.value = "Remote signer approval required."
                _diagnosticMessage.value = "auth_url_present=${result.safeUrl.isNotBlank()}"
                _remoteSignerPairingState.value = RemoteSignerPairingState(
                    stage = RemoteSignerPairingStage.AwaitingApproval,
                    title = "Signer approval required",
                    message = message,
                    authUrlAvailable = result.safeUrl.isNotBlank(),
                )
                false
            }
            is Nip46ConnectResult.Failed -> {
                val userMessage = result.safeReason.toUserFacingMessage()
                _message.value = userMessage
                _diagnosticMessage.value = result.safeReason
                _remoteSignerPairingState.value = RemoteSignerPairingState(
                    stage = RemoteSignerPairingStage.Failed,
                    title = userFacingErrorFor(result.safeReason).title,
                    message = userMessage,
                )
                false
            }
            Nip46ConnectResult.TimedOut -> {
                val raw = "Remote signer did not respond method=connect"
                _message.value = raw.toUserFacingMessage()
                _remoteSignerPairingState.value = RemoteSignerPairingState(
                    stage = RemoteSignerPairingStage.Failed,
                    title = "Remote signer did not respond",
                    message = raw.toUserFacingMessage(),
                )
                false
            }
            Nip46ConnectResult.Unavailable -> {
                val message = "NIP-46 remote signer is unavailable in this runtime."
                _message.value = message
                _remoteSignerPairingState.value = RemoteSignerPairingState(
                    stage = RemoteSignerPairingStage.Failed,
                    title = "Remote signer unavailable",
                    message = message.toUserFacingMessage(),
                )
                false
            }
        }

    private suspend fun persistActiveRemoteSignerSession(): Boolean {
        val store = services.nip46SessionStore
        val remoteSigner = services.remoteSigner ?: return false
        if (!store.isAvailable) return false
        val now = com.libertasprimordium.othernote.util.nowMs()
        val exported = remoteSigner.exportSession(createdAtMs = now, lastUsedAtMs = now) ?: return false
        when (val result = withContext(Dispatchers.IO) { store.saveSession(exported) }) {
            Nip46SessionStoreResult.Saved -> {
                refreshSavedRemoteSigners()
                ensureSavedRemoteSignerVisible(exported.metadata())
                return true
            }
            is Nip46SessionStoreResult.Failed -> {
                _diagnosticMessage.value = result.safeMessage
            }
            Nip46SessionStoreResult.Unavailable,
            Nip46SessionStoreResult.Deleted,
            is Nip46SessionStoreResult.Listed,
            is Nip46SessionStoreResult.Loaded,
            -> Unit
        }
        return false
    }

    private fun handleSignerPublicKeyResult(result: SignerPublicKeyRequestResult) {
        when (result) {
            is SignerPublicKeyRequestResult.Success -> {
                activateExternalSignerSession(result.pubkeyHex, result.npub, result.signerPackage)
                appScope.launch {
                    persistActiveAndroidSignerSession(result)
                }
            }
            SignerPublicKeyRequestResult.Cancelled -> {
                _message.value = "Signer request cancelled"
            }
            is SignerPublicKeyRequestResult.Unavailable -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
            is SignerPublicKeyRequestResult.Failed -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
            is SignerPublicKeyRequestResult.InvalidResponse -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
        }
    }

    private fun activateExternalSignerSession(
        pubkeyHex: String,
        npub: String,
        signerPackage: String?,
        message: String = "Signer login ready; relay sync can run with signer prompts.",
    ) {
        _session.value = UserSession(
            nsec = "external-signer",
            privateKeyHex = "",
            npub = npub,
            publicKeyHex = pubkeyHex,
            authMethod = SessionAuthMethod.ExternalSigner,
            signerPackage = signerPackage,
        )
        _mode.value = AppMode.Authenticated
        _message.value = message
        startProfileLoad()
    }

    private suspend fun persistActiveAndroidSignerSession(result: SignerPublicKeyRequestResult.Success): Boolean {
        val store = services.nip55SessionStore
        if (!store.isAvailable) return false
        val signerPackage = result.signerPackage?.takeIf { it.isNotBlank() } ?: return false
        val now = com.libertasprimordium.othernote.util.nowMs()
        val session = SavedNip55Session(
            userPubkey = result.pubkeyHex,
            userNpub = result.npub,
            signerPackage = signerPackage,
            signerLabel = services.externalSignerProvider.displayName,
            active = true,
            createdAtMs = now,
            lastUsedAtMs = now,
        )
        return when (val save = withContext(Dispatchers.IO) { store.saveSession(session) }) {
            Nip55SessionStoreResult.Saved -> {
                refreshSavedAndroidSigners()
                ensureSavedAndroidSignerVisible(session.metadata())
                true
            }
            is Nip55SessionStoreResult.Failed -> {
                _diagnosticMessage.value = save.safeMessage
                false
            }
            Nip55SessionStoreResult.Unavailable,
            Nip55SessionStoreResult.Deleted,
            is Nip55SessionStoreResult.Listed,
            is Nip55SessionStoreResult.Loaded,
            -> false
        }
    }

    fun requestExternalSignerTestSignature() {
        val session = _session.value
        if (session?.authMethod != SessionAuthMethod.ExternalSigner) {
            _message.value = "Use Android signer before testing signer event signing."
            return
        }
        if (!externalSignerCanSignEvent) {
            _message.value = "External signer event signing is unavailable."
            return
        }
        val testEvent = SignerTestEventFactory.build(session.publicKeyHex).getOrElse {
            _message.value = "Could not build signer test event."
            return
        }
        if (showNip55Diagnostics) {
            _diagnosticMessage.value = SignerSignEventRequestBuilder.build(
                requestedEvent = testEvent,
                currentUserPubkey = session.publicKeyHex,
                signerPackage = session.signerPackage,
            ).map { it.safeDiagnostics.joinToString("\n") }
                .getOrElse { "Could not build signer test event diagnostics." }
        }
        _message.value = "Waiting for signer..."
        services.externalSignerEventSigner.signEvent(
            unsignedEvent = testEvent,
            currentUserPubkey = session.publicKeyHex,
            signerPackage = session.signerPackage,
            onResult = ::handleSignerSignEventResult,
        )
    }

    private fun handleSignerSignEventResult(result: SignEventRequestResult) {
        when (result) {
            is SignEventRequestResult.Success -> {
                _message.value = "Signer test event signed and verified (${result.signedEvent.id.abbreviatedId()})"
            }
            SignEventRequestResult.Cancelled -> {
                _message.value = "Signer signing cancelled"
            }
            is SignEventRequestResult.Unavailable -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
            is SignEventRequestResult.Failed -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
            is SignEventRequestResult.InvalidResponse -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
        }
    }

    fun requestExternalSignerNip44Test() {
        val session = _session.value
        if (session?.authMethod != SessionAuthMethod.ExternalSigner) {
            _message.value = "Use Android signer before testing signer encryption."
            return
        }
        if (!externalSignerCanNip44RoundTrip) {
            _message.value = "External signer NIP-44 encryption is unavailable."
            return
        }
        val plaintext = SignerNip44TestPayload.Plaintext
        if (showNip55Diagnostics) {
            _diagnosticMessage.value = SignerNip44RequestBuilder.build(
                operation = SignerNip44Operation.Encrypt,
                payload = plaintext,
                peerPubkey = session.publicKeyHex,
                currentUserPubkey = session.publicKeyHex,
                signerPackage = session.signerPackage,
            ).map { it.safeDiagnostics.joinToString("\n") }
                .getOrElse { "Could not build signer encryption diagnostics." }
        }
        _message.value = "Waiting for signer..."
        val encrypted = services.externalSignerNip44Operator.encryptToSelf(
            plaintext = plaintext,
            currentUserPubkey = session.publicKeyHex,
            signerPackage = session.signerPackage,
        )
        when (encrypted) {
            is SignerNip44OperationResult.Encrypted -> {
                _message.value = "Signer encrypted test payload"
                val decrypted = services.externalSignerNip44Operator.decryptFromSelf(
                    ciphertext = encrypted.payload,
                    expectedPlaintext = plaintext,
                    currentUserPubkey = session.publicKeyHex,
                    signerPackage = session.signerPackage,
                )
                handleSignerNip44DecryptResult(decrypted)
            }
            SignerNip44OperationResult.Cancelled -> {
                _message.value = "Signer encryption cancelled"
            }
            is SignerNip44OperationResult.Unavailable -> {
                _message.value = encrypted.safeReason.toUserFacingMessage()
            }
            is SignerNip44OperationResult.Failed -> {
                _message.value = encrypted.safeReason.toUserFacingMessage()
            }
            is SignerNip44OperationResult.InvalidResponse -> {
                _message.value = encrypted.safeReason.toUserFacingMessage()
            }
            is SignerNip44OperationResult.Decrypted -> {
                _message.value = "Signer returned invalid encryption result"
            }
        }
    }

    private fun handleSignerNip44DecryptResult(result: SignerNip44OperationResult) {
        when (result) {
            is SignerNip44OperationResult.Decrypted -> {
                _message.value = "Signer decrypted and verified test payload"
            }
            SignerNip44OperationResult.Cancelled -> {
                _message.value = "Signer decryption cancelled"
            }
            is SignerNip44OperationResult.Unavailable -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
            is SignerNip44OperationResult.Failed -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
            is SignerNip44OperationResult.InvalidResponse -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
            is SignerNip44OperationResult.Encrypted -> {
                _message.value = "Signer decryption failed"
            }
        }
    }

    fun requestExternalSignerNoteEventTest() {
        val session = _session.value
        if (session?.authMethod != SessionAuthMethod.ExternalSigner) {
            _message.value = "Use Android signer before building signer note event."
            return
        }
        if (!externalSignerCanSignEvent || !externalSignerCanNip44RoundTrip) {
            _message.value = "External signer note event build is unavailable."
            return
        }
        _message.value = "Building signer note event..."
        if (showNip55Diagnostics) {
            _diagnosticMessage.value = listOf(
                "operation_stage=payload_encode",
                "operation_stage=nip44_encrypt",
                "operation_stage=event_build",
                "operation_stage=sign_event",
                "operation_stage=validate",
                "operation_stage=nip44_decrypt",
                "operation_stage=payload_decode",
                "kind=30078",
                "plaintext_length=${SignerNoteEventBuilder.TestBodyMarkdown.length}",
                "pubkey=${session.publicKeyHex.take(12)}",
            ).joinToString("\n")
        }
        _message.value = "Waiting for signer..."
        when (val result = signerNoteEventBuilder.buildTestNoteEvent(session, session.signerPackage)) {
            is SignerNoteEventBuildResult.Success -> {
                _message.value = result.safeSummary
            }
            SignerNoteEventBuildResult.Cancelled -> {
                _message.value = "Signer note event build cancelled"
            }
            is SignerNoteEventBuildResult.Unavailable -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
            is SignerNoteEventBuildResult.Failed -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
            is SignerNoteEventBuildResult.InvalidResponse -> {
                _message.value = result.safeReason.toUserFacingMessage()
            }
        }
    }

    fun continueLocalOnly() {
        _session.value = null
        _profileState.value = ProfileUiState()
        _mode.value = AppMode.LocalOnly
        _message.value = "Local-only mode. Notes stay on this device and relay sync is disabled."
    }

    suspend fun logout() {
        val currentSession = _session.value
        if (currentSession?.authMethod == SessionAuthMethod.ExternalSigner && services.nip55SessionStore.isAvailable) {
            val loaded = withContext(Dispatchers.IO) {
                services.nip55SessionStore.loadSession(currentSession.publicKeyHex)
            }
            if (loaded is Nip55SessionStoreResult.Loaded) {
                withContext(Dispatchers.IO) {
                    services.nip55SessionStore.saveSession(
                        loaded.session.copy(
                            active = false,
                            lastUsedAtMs = com.libertasprimordium.othernote.util.nowMs(),
                        ),
                    )
                }
                refreshSavedAndroidSigners()
            }
        }
        services.remoteSigner?.disconnect()
        _session.value = null
        _mode.value = AppMode.SignedOut
        _profileState.value = ProfileUiState()
        _remoteSignerPairingState.value = RemoteSignerPairingState()
        notes.clear()
        _message.value = "Session cleared. Local in-memory notes were removed. Saved signers remain available."
    }

    suspend fun save(existing: Note?, markdown: String): Boolean {
        val session = _session.value
        if (session?.isSignerBacked() == true) {
            return saveWithExternalSigner(existing, markdown, session)
        }
        if (session != null && !session.hasSessionPrivateKey()) {
            _message.value = "Saving requires a session key or Android signer."
            return false
        }
        val result = withContext(Dispatchers.IO) {
            saveNote.save(existing, markdown, _session.value, relaySettings.normalizedUrls())
        }
        _message.value = result.toCompactMessage()
        _diagnosticMessage.value = result.toDiagnosticMessage()
        return result !is SaveResult.Failed
    }

    suspend fun saveFromEditor(existing: Note?, markdown: String): Boolean {
        if (_editorSaveState.value.inProgress) return false
        _editorSaveState.value = EditorSaveState(inProgress = true, message = "Saving...")
        val saved = save(existing, markdown)
        val safeMessage = _message.value.ifBlank { if (saved) "Saved" else "Save failed" }
        _editorSaveState.value = if (saved) {
            EditorSaveState(message = safeMessage)
        } else {
            EditorSaveState(error = safeMessage)
        }
        return saved
    }

    fun clearEditorSaveState() {
        _editorSaveState.value = EditorSaveState()
    }

    private suspend fun saveWithExternalSigner(existing: Note?, markdown: String, session: UserSession): Boolean {
        val builder = signerNoteEventBuilderFor(session)
        if (builder == null || !signerCanSignEvent(session) || !signerCanNip44RoundTrip(session)) {
            _message.value = "External signer local save is unavailable."
            return false
        }
        setSaveProgress("Waiting for signer...")
        val result = if (session.authMethod == SessionAuthMethod.RemoteSigner) {
            withContext(Dispatchers.IO) {
                if (existing == null) {
                    builder.buildNewLocalNoteEventAsync(session, session.signerPackage, markdown, ::handleSignerNoteSaveStage)
                } else {
                    builder.buildReplacementLocalNoteEventAsync(session, session.signerPackage, existing, markdown, ::handleSignerNoteSaveStage)
                }
            }
        } else if (existing == null) {
            withContext(Dispatchers.IO) {
                builder.buildNewLocalNoteEvent(session, session.signerPackage, markdown, ::handleSignerNoteSaveStage)
            }
        } else {
            withContext(Dispatchers.IO) {
                builder.buildReplacementLocalNoteEvent(session, session.signerPackage, existing, markdown, ::handleSignerNoteSaveStage)
            }
        }
        return when (result) {
            is SignerNoteEventBuildResult.Success -> {
                val publish = publishSignerEvent(session, result.signedEvent, SignerWriteAction.Save)
                if (!publish.anySucceeded) {
                    _message.value = "Save failed: no relay accepted write."
                    return false
                }
                notes.upsertLocal(result.note, result.signedEvent)
                services.localEventCache.upsertEvents(session.publicKeyHex, listOf(result.signedEvent))
                _message.value = publish.signerWriteMessage(SignerWriteAction.Save)
                true
            }
            SignerNoteEventBuildResult.Cancelled -> {
                _message.value = "Signer note save cancelled"
                false
            }
            is SignerNoteEventBuildResult.Unavailable -> {
                _message.value = result.safeReason.toUserFacingMessage()
                _diagnosticMessage.value = result.safeReason
                false
            }
            is SignerNoteEventBuildResult.Failed -> {
                _message.value = result.safeReason.toUserFacingMessage()
                _diagnosticMessage.value = result.safeReason
                false
            }
            is SignerNoteEventBuildResult.InvalidResponse -> {
                _message.value = result.safeReason.toUserFacingMessage()
                _diagnosticMessage.value = result.safeReason
                false
            }
        }
    }

    private fun handleSignerNoteSaveStage(stage: SignerNoteEventBuildStage) {
        setSaveProgress(when (stage) {
            SignerNoteEventBuildStage.PayloadEncoded -> "Waiting for signer..."
            SignerNoteEventBuildStage.Encrypted -> "Signer encrypted note"
            SignerNoteEventBuildStage.EventBuilt -> "Waiting for signer..."
            SignerNoteEventBuildStage.Signed -> "Signer signed note"
            SignerNoteEventBuildStage.Validated -> "Signer signed note"
            SignerNoteEventBuildStage.Decrypted -> "Signer decrypted note"
            SignerNoteEventBuildStage.PayloadDecoded -> "Saving locally..."
        })
    }

    private fun setSaveProgress(message: String) {
        _message.value = message
        if (_editorSaveState.value.inProgress) {
            _editorSaveState.value = _editorSaveState.value.copy(message = message, error = null)
        }
    }

    suspend fun delete(note: Note): Boolean {
        val session = _session.value
        if (session?.isSignerBacked() == true) {
            return deleteWithExternalSigner(note, session)
        }
        if (session != null && !session.hasSessionPrivateKey()) {
            _message.value = "Deleting requires a session key or Android signer."
            return false
        }
        val result = withContext(Dispatchers.IO) {
            deleteNote.delete(note, _session.value, relaySettings.normalizedUrls())
        }
        _message.value = result.toCompactMessage()
        _diagnosticMessage.value = result.toDiagnosticMessage()
        return result !is SaveResult.Failed
    }

    private suspend fun deleteWithExternalSigner(note: Note, session: UserSession): Boolean {
        val builder = signerNoteEventBuilderFor(session)
        if (builder == null || !signerCanSignEvent(session) || !signerCanNip44RoundTrip(session)) {
            _message.value = "External signer local delete is unavailable."
            return false
        }
        _message.value = "Waiting for signer..."
        val result = if (session.authMethod == SessionAuthMethod.RemoteSigner) {
            withContext(Dispatchers.IO) {
                builder.buildLocalTombstoneEventAsync(
                    session = session,
                    signerPackage = session.signerPackage,
                    existing = note,
                    onStage = ::handleSignerTombstoneStage,
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                builder.buildLocalTombstoneEvent(
                    session = session,
                    signerPackage = session.signerPackage,
                    existing = note,
                    onStage = ::handleSignerTombstoneStage,
                )
            }
        }
        return when (result) {
            is SignerNoteEventBuildResult.Success -> {
                val publish = publishSignerEvent(session, result.signedEvent, SignerWriteAction.Delete)
                if (!publish.anySucceeded) {
                    _message.value = "Delete failed: no relay accepted tombstone."
                    return false
                }
                notes.upsertLocal(result.note, result.signedEvent)
                services.localEventCache.upsertEvents(session.publicKeyHex, listOf(result.signedEvent))
                _message.value = publish.signerWriteMessage(SignerWriteAction.Delete)
                true
            }
            SignerNoteEventBuildResult.Cancelled -> {
                _message.value = "Signer note delete cancelled"
                false
            }
            is SignerNoteEventBuildResult.Unavailable -> {
                _message.value = result.safeReason.toUserFacingMessage()
                _diagnosticMessage.value = result.safeReason
                false
            }
            is SignerNoteEventBuildResult.Failed -> {
                _message.value = result.safeReason.toUserFacingMessage()
                _diagnosticMessage.value = result.safeReason
                false
            }
            is SignerNoteEventBuildResult.InvalidResponse -> {
                _message.value = result.safeReason.toUserFacingMessage()
                _diagnosticMessage.value = result.safeReason
                false
            }
        }
    }

    private fun handleSignerTombstoneStage(stage: SignerNoteEventBuildStage) {
        _message.value = when (stage) {
            SignerNoteEventBuildStage.PayloadEncoded -> "Waiting for signer..."
            SignerNoteEventBuildStage.Encrypted -> "Signer encrypted tombstone"
            SignerNoteEventBuildStage.EventBuilt -> "Waiting for signer..."
            SignerNoteEventBuildStage.Signed -> "Signer signed tombstone"
            SignerNoteEventBuildStage.Validated -> "Signer signed tombstone"
            SignerNoteEventBuildStage.Decrypted -> "Signer decrypted tombstone"
            SignerNoteEventBuildStage.PayloadDecoded -> "Deleting locally..."
        }
    }

    suspend fun sync() {
        val session = _session.value
        if (session?.isSignerBacked() == true) {
            syncWithExternalSigner(session)
            return
        }
        if (_session.value == null || !directRelayRuntimeAvailable || _session.value?.hasSessionPrivateKey() != true) {
            _syncState.value = SyncState(errors = listOf("Relay sync requires a validated nsec session"))
            _message.value = _syncState.value.summary
            return
        }
        _syncState.value = _syncState.value.copy(syncing = true)
        try {
            _syncState.value = withContext(Dispatchers.IO) {
                val activeSession = _session.value
                val relays = activeSession?.let { importPublishedRelayListIfNeeded(it) } ?: relaySettings.normalizedUrls()
                syncNotes.sync(activeSession, relays) { partial ->
                    _syncState.value = partial.copy(syncing = true)
                    _message.value = partial.toCompactMessage()
                    _diagnosticMessage.value = partial.toDiagnosticMessage()
                }
            }
        } finally {
            _syncState.value = _syncState.value.copy(syncing = false)
            _message.value = _syncState.value.toCompactMessage()
            _diagnosticMessage.value = _syncState.value.toDiagnosticMessage()
            runCatching { loadProfile() }
        }
    }

    fun startSync(): Job = appScope.launch { sync() }

    fun startProfileLoad(): Job = appScope.launch {
        loadProfile()
    }

    suspend fun loadProfile(): Boolean {
        val session = _session.value ?: return false
        val relays = relaySettings.normalizedUrls()
        if (relays.isEmpty()) {
            _profileState.value = ProfileUiState(pubkey = session.publicKeyHex)
            return false
        }
        val existing = _profileState.value.metadata?.takeIf { it.pubkey == session.publicKeyHex }
        _profileState.value = ProfileUiState(
            loading = existing == null,
            pubkey = session.publicKeyHex,
            metadata = existing,
        )
        val profile = runCatching {
            withContext(Dispatchers.IO) {
                profiles.loadProfile(relays, session.publicKeyHex)
            }
        }.getOrNull()
        _profileState.value = ProfileUiState(
            loading = false,
            pubkey = session.publicKeyHex,
            metadata = profile ?: existing,
        )
        return profile != null
    }

    private suspend fun publishSignerEvent(
        session: UserSession,
        event: NostrEvent,
        action: SignerWriteAction,
    ): RelayPublishResult {
        val relays = relaySettings.normalizedUrls().distinct()
        val pendingEnqueue = runCatching {
            services.pendingWriteStore.enqueuePendingWrite(session.publicKeyHex, event, relays)
        }
        if (pendingEnqueue.isFailure) {
            return RelayPublishResult(
                listOf(
                    RelayStatus(
                        url = "local-pending-write-store",
                        writable = false,
                        message = "stage=pending_write outcome=failed ${pendingEnqueue.exceptionOrNull()?.safePersistenceMessage()}",
                    ),
                ),
            )
        }
        var firstAcceptedReached = false
        val publish = nostr.publishBestEffort(relays, event, appScope) { statuses ->
            appScope.launch { updateSignerPendingStatuses(session.publicKeyHex, event.id, statuses) }
            if (!firstAcceptedReached) updateSignerPublishStatus(statuses, action)
        }
        val firstAccepted = publish.firstAccepted.await()
        firstAcceptedReached = true
        updateSignerPendingStatuses(session.publicKeyHex, event.id, firstAccepted.statuses)
        appScope.launch {
            runCatching {
                val complete = publish.complete.await()
                updateSignerPendingStatuses(session.publicKeyHex, event.id, complete.statuses)
            }
        }
        return firstAccepted
    }

    private suspend fun updateSignerPendingStatuses(accountPubkey: String, eventId: String, statuses: List<RelayStatus>) {
        statuses.forEach { status ->
            if (status.writable) {
                services.pendingWriteStore.markRelayAccepted(eventId, status.url)
            } else {
                services.pendingWriteStore.markRelayRejectedOrFailed(eventId, status.url, status.message)
            }
        }
        val pending = services.pendingWriteStore.loadPendingWrites(accountPubkey).firstOrNull { it.event.id == eventId }
        if (pending?.isComplete == true) services.pendingWriteStore.removeCompletedWrite(eventId)
    }

    private fun updateSignerPublishStatus(statuses: List<RelayStatus>, action: SignerWriteAction) {
        _syncState.value = _syncState.value.copy(relayStatuses = statuses)
        _message.value = RelayPublishResult(statuses).signerWriteMessage(action)
        _diagnosticMessage.value = statuses.toSafeSummary()
    }

    private fun RelayPublishResult.signerWriteMessage(action: SignerWriteAction): String {
        val total = relaySettings.normalizedUrls().distinct().size
        val accepted = statuses.count { it.writable }
        val pending = statuses.size < total
        return when (action) {
            SignerWriteAction.Save -> if (pending) {
                "Saved to $accepted/$total relays; syncing others..."
            } else {
                "Saved to $accepted/$total relays"
            }
            SignerWriteAction.Delete -> if (pending) {
                "Deleted locally and sent to $accepted/$total relays; syncing others..."
            } else {
                "Deleted locally and sent to $accepted/$total relays"
            }
        }
    }

    private suspend fun syncWithExternalSigner(session: UserSession) {
        val verifier = signerVerifier
        if (verifier == null) {
            _syncState.value = SyncState(errors = listOf(ProductionNostrCryptoFactory.unavailableReason))
            _message.value = _syncState.value.summary
            return
        }
        val relays = withContext(Dispatchers.IO) { importPublishedRelayListIfNeeded(session) }
        _syncState.value = _syncState.value.copy(syncing = true)
        _message.value = "Syncing..."
        val aggregateEvents = mutableListOf<NostrEvent>()
        val aggregateStatuses = linkedMapOf<String, RelayStatus>()
        val cached = services.localEventCache.loadEvents(session.publicKeyHex)
        if (cached.isNotEmpty()) {
            aggregateEvents += cached
            applySignerFetchedEvents(session, aggregateEvents, emptyList(), final = false)
        }
        if (services.pendingWriteStore.loadPendingWrites(session.publicKeyHex).isNotEmpty()) {
            _message.value = "Retrying pending writes..."
        }
        retrySignerPendingWrites(session)
        val fetch = nostr.fetchIncrementally(relays, session.publicKeyHex) { partial ->
            aggregateEvents += partial.events
            partial.statuses.forEach { aggregateStatuses[it.url] = it }
            services.localEventCache.upsertEvents(session.publicKeyHex, partial.events.filter { it.isSignerCacheable(session.publicKeyHex, verifier) })
            val partialState = applySignerFetchedEvents(session, aggregateEvents, aggregateStatuses.values.toList(), final = false)
            _syncState.value = partialState.copy(syncing = true)
            _message.value = partialState.toCompactMessage()
            _diagnosticMessage.value = partialState.toDiagnosticMessage()
        }
        aggregateEvents += fetch.events.filterNot { fetched -> aggregateEvents.any { it.id == fetched.id } }
        fetch.statuses.forEach { aggregateStatuses[it.url] = it }
        services.localEventCache.upsertEvents(session.publicKeyHex, aggregateEvents.filter { it.isSignerCacheable(session.publicKeyHex, verifier) })
        _syncState.value = applySignerFetchedEvents(session, aggregateEvents, aggregateStatuses.values.toList(), final = true).copy(syncing = false)
        _message.value = _syncState.value.toCompactMessage()
        _diagnosticMessage.value = _syncState.value.toDiagnosticMessage()
        runCatching { loadProfile() }
    }

    private suspend fun importPublishedRelayListIfNeeded(session: UserSession): List<String> {
        val currentRelays = relaySettings.normalizedUrls()
        if (relayListImportedForPubkey == session.publicKeyHex) return currentRelays
        relayListImportedForPubkey = session.publicKeyHex
        val imported = relayListSync.importPublishedWriteRelays(session, currentRelays)
        if (imported.importedRelays.isEmpty()) {
            if (imported.warning != null) {
                _syncState.value = _syncState.value.copy(warnings = _syncState.value.warnings + imported.warning)
            }
            return currentRelays
        }
        val configs = relaySettings.previewChange(imported.importedRelays).getOrNull() ?: return currentRelays
        if (configs.map { it.url } == currentRelays) return currentRelays
        val persisted = runCatching { relaySettings.commitAndPersist(configs) }
        return if (persisted.isSuccess) {
            _message.value = imported.safeSummary()
            configs.map { it.url }
        } else {
            _syncState.value = _syncState.value.copy(
                warnings = _syncState.value.warnings +
                    "Published relay list was found, but local relay settings could not be saved. ${persisted.exceptionOrNull()?.safePersistenceMessage()}",
            )
            currentRelays
        }
    }

    suspend fun refreshPublishedRelayListForSettings(): RelaySettingsRefreshResult {
        val session = _session.value ?: return RelaySettingsRefreshResult.Skipped("Relay-list refresh requires sign-in.")
        if (_relayMigrationState.value.inProgress) {
            return RelaySettingsRefreshResult.Skipped("Relay migration is already in progress.")
        }
        val addState = _relayAddTestState.value
        if (addState.inProgress || addState.warning != null) {
            return RelaySettingsRefreshResult.Skipped("Relay add/test is already in progress.")
        }
        val currentRelays = relaySettings.normalizedUrls()
        val imported = withContext(Dispatchers.IO) {
            withTimeoutOrNull(10_000) {
                relayListSync.importPublishedWriteRelays(session, currentRelays)
            }
        } ?: return RelaySettingsRefreshResult.Failed("Published relay-list refresh timed out; keeping local relay settings.")
        if (imported.importedRelays.isEmpty()) {
            return RelaySettingsRefreshResult.NoChange(imported.warning ?: "No published relay list found; keeping local relay settings.")
        }
        val configs = relaySettings.previewChange(imported.importedRelays).getOrNull()
            ?: return RelaySettingsRefreshResult.Failed("Published relay list had no usable write relays; keeping local relay settings.")
        val relays = configs.map { it.url }
        if (relays == currentRelays) {
            relayListImportedForPubkey = session.publicKeyHex
            return RelaySettingsRefreshResult.NoChange("Published relay list already matches local settings.")
        }
        return RelaySettingsRefreshResult.PublishedListAvailable(relays, imported.safeSummary())
    }

    suspend fun applyPublishedRelayListFromSettings(relays: List<String>): Boolean {
        val session = _session.value
        val configs = relaySettings.previewChange(relays).getOrNull()
        if (configs == null) {
            _message.value = "Published relay list had invalid relay settings."
            return false
        }
        val persisted = runCatching { relaySettings.commitAndPersist(configs) }
        return if (persisted.isSuccess) {
            relayListImportedForPubkey = session?.publicKeyHex
            _message.value = "Using your published relay list."
            true
        } else {
            _message.value = "Published relay list could not be saved. ${persisted.exceptionOrNull()?.safePersistenceMessage()}"
            false
        }
    }

    suspend fun syncCurrentRelays(): Boolean {
        val session = _session.value
        if (session == null) {
            _message.value = "Sign in before syncing relays."
            return false
        }
        val currentRelays = relaySettings.normalizedUrls()
        if (currentRelays.isEmpty()) {
            _message.value = "No note relays configured."
            return false
        }
        if (_relayMigrationState.value.inProgress) {
            _message.value = "Relay migration is already in progress."
            return false
        }
        val addState = _relayAddTestState.value
        if (addState.inProgress || addState.warning != null) {
            _message.value = "Finish the relay add/test dialog before syncing relays."
            return false
        }
        val configs = relaySettings.previewChange(currentRelays).getOrNull()
        if (configs == null) {
            _message.value = "Current relay settings are invalid."
            return false
        }

        pendingRelayMigrationDecision = null
        _relayMigrationState.value = RelayMigrationUiState(inProgress = true)
        _message.value = "Syncing encrypted notes across note relays..."
        val result = withContext(Dispatchers.IO) {
            relayMigration.execute(session, planManualRelaySync(currentRelays))
        }
        _relayMigrationState.value = RelayMigrationUiState()
        _diagnosticMessage.value = result.safeDetails()
        updateRelaySettingsStatusesFromMigration(result, null)
        if (result.fullSuccess) {
            _message.value = if (result.latestEvents.isEmpty()) {
                "No encrypted notes to migrate."
            } else {
                "Note relay sync completed."
            }
            return true
        }

        val userWarning = relayMigrationUserWarning(null, result)
        val details = result.safeDetails()
        pendingRelayMigrationDecision = PendingRelayMigrationDecision(
            requestedConfigs = configs,
            result = result,
            sessionPubkey = session.publicKeyHex,
            summary = userWarning.title,
            details = details,
            successMessage = "Note relay sync continued; failed relay writes were queued where possible.",
        )
        _relayMigrationState.value = RelayMigrationUiState(
            warning = RelayMigrationWarning(userWarning.title, userWarning.body, details),
        )
        _message.value = userWarning.title
        return false
    }

    private fun retrySignerPendingWrites(session: UserSession) {
        appScope.launch {
            val pendingWrites = services.pendingWriteStore.loadPendingWrites(session.publicKeyHex)
            pendingWrites.forEach { pending ->
                val retryRelays = pending.unfinishedRelays
                    .filter { relay -> (pending.retryCounts[relay] ?: 0) < PendingWriteMaxRetryCount }
                retryRelays.forEach { relay -> services.pendingWriteStore.recordRetry(pending.event.id, relay) }
                if (retryRelays.isNotEmpty()) {
                    val publish = nostr.publishBestEffort(retryRelays, pending.event, appScope) { statuses ->
                        appScope.launch {
                            updateSignerPendingStatuses(session.publicKeyHex, pending.event.id, statuses)
                        }
                    }
                    publish.complete.await()
                    val updated = services.pendingWriteStore.loadPendingWrites(session.publicKeyHex).firstOrNull { it.event.id == pending.event.id }
                    if (updated?.isComplete == true || updated?.isTerminallyExhausted(PendingWriteMaxRetryCount) == true) {
                        services.pendingWriteStore.removeCompletedWrite(pending.event.id)
                    }
                } else if (pending.isTerminallyExhausted(PendingWriteMaxRetryCount)) {
                    services.pendingWriteStore.removeCompletedWrite(pending.event.id)
                }
            }
        }
    }

    private suspend fun applySignerFetchedEvents(
        session: UserSession,
        events: List<NostrEvent>,
        statuses: List<RelayStatus>,
        final: Boolean,
    ): SyncState {
        val verifier = signerVerifier ?: return SyncState(errors = listOf(ProductionNostrCryptoFactory.unavailableReason))
        if (statuses.isNotEmpty() && statuses.none { it.readable }) {
            return SyncState(relayStatuses = statuses, errors = listOf("Sync failed: no relays reachable"))
        }
        var wrongAuthor = 0
        var wrongKind = 0
        var missingT = 0
        var missingD = 0
        var invalidSignature = 0
        val candidateEvents = events.distinctBy { it.id }.mapNotNull { event ->
            when {
                event.pubkey != session.publicKeyHex -> {
                    wrongAuthor++
                    null
                }
                event.kind != NoteKind -> {
                    wrongKind++
                    null
                }
                !event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == OtherNoteTag } -> {
                    missingT++
                    null
                }
                event.dTag() == null -> {
                    missingD++
                    null
                }
                !verifier.validate(event).getOrDefault(false) -> {
                    invalidSignature++
                    null
                }
                else -> event
            }
        }
        val reduced = if (session.authMethod == SessionAuthMethod.RemoteSigner) {
            val remoteSigner = services.remoteSigner
            reduceNoteEventsAsync(candidateEvents) { event ->
                if (remoteSigner == null) return@reduceNoteEventsAsync Result.failure(IllegalStateException("Remote signer decrypt unavailable"))
                when (val decrypted = remoteSigner.decryptFromSelfAsync(event.content, null, session.publicKeyHex, session.signerPackage)) {
                    is SignerNip44OperationResult.Decrypted -> Result.success(decrypted.plaintext)
                    else -> Result.failure(IllegalStateException("Remote signer decrypt failed"))
                }
            }
        } else {
            reduceNoteEvents(candidateEvents) { event ->
                val nip44 = signerNip44For(session) ?: return@reduceNoteEvents Result.failure(IllegalStateException("Signer decrypt unavailable"))
                when (val decrypted = nip44.decryptFromSelf(event.content, null, session.publicKeyHex, session.signerPackage)) {
                    is SignerNip44OperationResult.Decrypted -> Result.success(decrypted.plaintext)
                    else -> Result.failure(IllegalStateException("Signer decrypt failed"))
                }
            }
        }
        notes.replaceFromSync(mergeReducedNotesWithCurrent(notes.notes.value, reduced))
        val warnings = buildList {
            add("Signer sync fetched_events=${events.distinctBy { it.id }.size} candidate_events=${candidateEvents.size} selected_events=${reduced.selectedEvents.size} visible_notes=${reduced.notes.size} rejected_wrong_author=$wrongAuthor rejected_wrong_kind=$wrongKind rejected_missing_t=$missingT rejected_missing_d=$missingD rejected_validation=$invalidSignature rejected_decrypt=${reduced.decryptRejectedCount} rejected_payload=${reduced.payloadRejectedCount} rejected_dtag=${reduced.dTagRejectedCount}")
            if (reduced.selectedEvents.isNotEmpty() && reduced.notes.isEmpty()) add("Latest selected events are tombstones; matching notes are hidden")
            if (statuses.any { !it.readable }) add("Partial relay read failure; local notes were preserved")
        }
        return SyncState(lastSyncMs = if (final) com.libertasprimordium.othernote.util.nowMs() else null, relayStatuses = statuses, warnings = warnings)
    }

    private fun NostrEvent.isSignerCacheable(accountPubkey: String, verifier: com.libertasprimordium.othernote.nostr.NostrCrypto): Boolean =
        pubkey == accountPubkey &&
            kind == NoteKind &&
            tags.any { it.size >= 2 && it[0] == "t" && it[1] == OtherNoteTag } &&
            dTag() != null &&
            verifier.validate(this).getOrDefault(false)

    private fun SyncState.toDiagnosticMessage(): String =
        buildString {
            append(summary)
            val relaySummary = relayStatuses.toSafeSummary()
            if (relaySummary.isNotBlank()) append("\n").append(relaySummary)
        }

    private fun SyncState.toCompactMessage(): String {
        if (syncing) return "Syncing..."
        if (errors.isNotEmpty()) {
            return if (relayStatuses.isNotEmpty() && relayStatuses.none { it.readable }) {
                "Sync failed: no relays reachable"
            } else {
                errors.first().toUserFacingMessage()
            }
        }
        val total = relayStatuses.size
        val reachable = relayStatuses.count { it.readable }
        return when {
            total == 0 && warnings.isEmpty() -> "Not synced"
            total == 0 -> warnings.first().compactStatus().toUserFacingMessage()
            reachable == total -> "Synced"
            reachable > 0 -> "Sync partial: $reachable/$total relays reachable"
            else -> "Sync failed: no relays reachable"
        }
    }

    private fun updatePublishStatuses(statuses: List<RelayStatus>) {
        _syncState.value = _syncState.value.copy(relayStatuses = statuses)
        val total = relaySettings.normalizedUrls().distinct().size
        val accepted = statuses.count { it.writable }
        _message.value = if (statuses.size < total) {
            "Saved to $accepted/$total relays; syncing others..."
        } else {
            "Saved to $accepted/$total relays"
        }
        _diagnosticMessage.value = statuses.toSafeSummary()
    }

    suspend fun saveRelays(rawRelays: List<String>): Boolean {
        val newConfig = relaySettings.previewChange(rawRelays)
        val configs = newConfig.getOrNull()
        if (configs == null) {
            _message.value = newConfig.exceptionOrNull()?.message ?: "Invalid relay settings"
            return false
        }
        pendingRelayMigrationDecision = null
        _relayMigrationState.value = RelayMigrationUiState()
        val old = relaySettings.normalizedUrls()
        val plan = migrateRelays.migrate(old, configs.map { it.url })
        if (!plan.migrationRequired) {
            val requested = configs.map { it.url }
            val session = _session.value
            if (old != requested && session != null) {
                _relayMigrationState.value = RelayMigrationUiState(inProgress = true)
                _message.value = "Publishing updated relay list..."
                val relayListPublish = withContext(Dispatchers.IO) {
                    publishRelayListForSettingsChange(session, old, requested)
                }
                _relayMigrationState.value = RelayMigrationUiState()
                _diagnosticMessage.value = relayListPublish.safeDetails()
                updateRelaySettingsStatusesFromRelayListPublish(relayListPublish, requested)
                if (!relayListPublish.fullSuccess) {
                    val userWarning = relayMigrationUserWarning(relayListPublish, null)
                    val details = relayListPublish.safeDetails()
                    pendingRelayMigrationDecision = PendingRelayMigrationDecision(
                        requestedConfigs = configs,
                        result = null,
                        sessionPubkey = session.publicKeyHex,
                        summary = userWarning.title,
                        details = details,
                    )
                    _relayMigrationState.value = RelayMigrationUiState(
                        warning = RelayMigrationWarning(userWarning.title, userWarning.body, details),
                    )
                    _message.value = userWarning.title
                    return false
                }
            }
            return persistRelaySettings(configs, "Relay settings saved.")
        }

        val session = _session.value
        if (session == null) {
            val summary = "Relay migration requires sign-in"
            val details = "Relay settings can be saved, but Other Note cannot fetch removed relays or republish encrypted note events without an active account pubkey."
            pendingRelayMigrationDecision = PendingRelayMigrationDecision(
                requestedConfigs = configs,
                result = null,
                sessionPubkey = null,
                summary = summary,
                details = details,
            )
            _relayMigrationState.value = RelayMigrationUiState(warning = RelayMigrationWarning(summary, details, details))
            _message.value = summary
            return false
        }

        _relayMigrationState.value = RelayMigrationUiState(inProgress = true)
        _message.value = "Migrating encrypted notes across note relays..."
        val (relayListPublish, result) = withContext(Dispatchers.IO) {
            val relayListResult = publishRelayListForSettingsChange(session, old, configs.map { it.url })
            relayListResult to relayMigration.execute(session, plan)
        }
        _relayMigrationState.value = RelayMigrationUiState()
        val combinedSummary = relaySettingsMigrationSummary(relayListPublish, result)
        val combinedDetails = relaySettingsMigrationDetails(relayListPublish, result)
        _diagnosticMessage.value = combinedDetails
        updateRelaySettingsStatusesFromMigration(result, relayListPublish)
        if (result.fullSuccess && relayListPublish.fullSuccess) {
            return persistRelaySettings(configs, combinedSummary)
        }
        val userWarning = relayMigrationUserWarning(relayListPublish, result)
        pendingRelayMigrationDecision = PendingRelayMigrationDecision(
            requestedConfigs = configs,
            result = result,
            sessionPubkey = session.publicKeyHex,
            summary = userWarning.title,
            details = combinedDetails,
        )
        _relayMigrationState.value = RelayMigrationUiState(
            warning = RelayMigrationWarning(userWarning.title, userWarning.body, combinedDetails),
        )
        _message.value = userWarning.title
        return false
    }

    private suspend fun publishRelayListForSettingsChange(
        session: UserSession,
        oldRelays: List<String>,
        requestedRelays: List<String>,
    ): RelayListPublishResult {
        val targetRelays = (oldRelays + requestedRelays).distinct()
        return relayListSync.publishUpdatedRelayList(
            session = session,
            discoveryRelays = oldRelays.ifEmpty { requestedRelays },
            targetRelays = targetRelays,
            appWriteRelays = requestedRelays,
            signEvent = { event -> signRelayListEvent(session, event) },
        )
    }

    private suspend fun signRelayListEvent(session: UserSession, event: NostrEvent): SignEventRequestResult =
        when (session.authMethod) {
            SessionAuthMethod.SessionOnlyNsec -> {
                if (!session.hasSessionPrivateKey()) {
                    SignEventRequestResult.Unavailable("Relay-list publish requires a session key or signer.")
                } else {
                    runCatching {
                        val signed = crypto.sign(
                            UnsignedNostrEvent(
                                pubkey = event.pubkey,
                                createdAt = event.createdAt,
                                kind = event.kind,
                                tags = event.tags,
                                content = event.content,
                            ),
                            NostrPrivateKey(session.privateKeyHex),
                        ).getOrThrow()
                        SignEventRequestResult.Success(signed, null)
                    }.getOrElse { error ->
                        SignEventRequestResult.Failed("Relay-list signing failed: ${error::class.simpleName}")
                    }
                }
            }
            SessionAuthMethod.RemoteSigner -> {
                services.remoteSigner?.signEventAsync(event, session.publicKeyHex, session.signerPackage)
                    ?: SignEventRequestResult.Unavailable("Remote signer is unavailable for relay-list publish.")
            }
            SessionAuthMethod.ExternalSigner -> {
                val deferred = CompletableDeferred<SignEventRequestResult>()
                services.externalSignerEventSigner.signEvent(
                    unsignedEvent = event,
                    currentUserPubkey = session.publicKeyHex,
                    signerPackage = session.signerPackage,
                ) { result -> deferred.complete(result) }
                deferred.await()
            }
        }

    private fun relaySettingsMigrationSummary(
        relayListPublish: RelayListPublishResult,
        migration: RelayMigrationExecutionResult,
    ): String =
        if (relayListPublish.fullSuccess && migration.fullSuccess) {
            "Relay settings saved. Published relay list and migrated encrypted notes."
        } else {
            "Relay settings need review before saving."
        }

    private fun relaySettingsMigrationDetails(
        relayListPublish: RelayListPublishResult,
        migration: RelayMigrationExecutionResult,
    ): String =
        buildList {
            add("relay_list=${relayListPublish.safeSummary()}")
            add(relayListPublish.safeDetails())
            add("content_migration=${migration.safeSummary()}")
            add(migration.safeDetails())
        }.filter { it.isNotBlank() }.joinToString("\n").take(2_000)

    private fun relayMigrationUserWarning(
        relayListPublish: RelayListPublishResult?,
        migration: RelayMigrationExecutionResult?,
    ): RelayMigrationUserWarningText {
        val relayListFailed = relayListPublish != null && !relayListPublish.fullSuccess
        val contentFailed = migration != null && !migration.fullSuccess
        val statuses = buildList {
            relayListPublish?.statuses?.let(::addAll)
            migration?.fetchStatuses?.let(::addAll)
            migration?.publishStatusesByEventId?.values?.flatten()?.let(::addAll)
        }
        val failedMessages = statuses
            .filter { !it.readable || !it.writable }
            .map { it.message.lowercase() }
        val hasTimeout = failedMessages.any { message ->
            message.contains("timeout") ||
                message.contains("timed out") ||
                message.contains("connect") ||
                message.contains("unreachable") ||
                message.contains("offline")
        }
        val hasRejection = failedMessages.any { message ->
            message.contains("reject") ||
                message.contains("denied") ||
                message.contains("blocked") ||
                message.contains("policy")
        }
        return when {
            relayListFailed && migration?.fullSuccess == true -> RelayMigrationUserWarningText(
                title = if (hasTimeout) "Some relays did not respond" else "Some relays rejected the update",
                body = "Your encrypted notes were migrated, but Other Note could not publish the updated relay list to every relay.",
            )
            relayListPublish?.fullSuccess == true && contentFailed -> RelayMigrationUserWarningText(
                title = if (hasTimeout) "Some relays did not respond" else "Relay migration partially completed",
                body = "Your relay list was updated, but Other Note could not copy all encrypted note events to every relay.",
            )
            hasRejection -> RelayMigrationUserWarningText(
                title = "Some relays rejected the update",
                body = "Other Note could not publish your relay list or encrypted notes to every relay. The relays that accepted the update will still work. You can continue with these settings or go back and remove the failed relays.",
            )
            hasTimeout -> RelayMigrationUserWarningText(
                title = "Some relays did not respond",
                body = "One or more relays could not be reached. Your notes are still safe in local encrypted storage and on relays that accepted the update. You can continue or try different relays.",
            )
            else -> RelayMigrationUserWarningText(
                title = "Relay migration partially completed",
                body = "Your relay settings were updated on some relays, but not all. This usually means a relay is offline, rejects your key, or has a write policy. You can continue anyway or adjust your relay list.",
            )
        }
    }

    private fun updateRelaySettingsStatusesFromRelayListPublish(
        relayListPublish: RelayListPublishResult,
        displayRelays: List<String>,
    ) {
        val statusesByRelay = relayListPublish.statuses.associateBy { it.url }
        val statuses = displayRelays.distinct().map { relay ->
            val status = statusesByRelay[relay]
            when {
                status == null -> RelayStatus(relay, message = "Not checked yet")
                status.writable -> RelayStatus(relay, writable = true, message = "Published relay list")
                else -> RelayStatus(relay, writable = false, message = status.message.toReadableRelayFailure("Could not publish"))
            }
        }
        updateRelaySettingsStatuses(statuses)
    }

    private fun updateRelaySettingsStatusesFromMigration(
        migration: RelayMigrationExecutionResult,
        relayListPublish: RelayListPublishResult?,
    ) {
        val relays = (migration.plan.newRelays + migration.plan.oldRelays).distinct()
        val fetchByRelay = migration.fetchStatuses.associateBy { it.url }
        val publishStatusesByRelay = migration.publishStatusesByEventId.values
            .flatten()
            .groupBy { it.url }
        val relayListStatusesByRelay = relayListPublish?.statuses.orEmpty().groupBy { it.url }
        val eventCount = migration.latestEvents.size
        val statuses = relays.map { relay ->
            val relayListStatuses = relayListStatusesByRelay[relay].orEmpty()
            val publishStatuses = publishStatusesByRelay[relay].orEmpty()
            val failures = (relayListStatuses + publishStatuses).filter { !it.writable }
            val fetchStatus = fetchByRelay[relay]
            when {
                failures.isNotEmpty() -> {
                    val message = failures.first().message
                    val accepted = publishStatuses.count { it.writable }
                    val readable = if (accepted in 1 until eventCount) {
                        "Published $accepted of $eventCount events"
                    } else {
                        message.toReadableRelayFailure("Could not publish")
                    }
                    RelayStatus(relay, readable = fetchStatus?.readable ?: false, writable = false, message = readable)
                }
                eventCount > 0 && publishStatuses.isNotEmpty() -> {
                    val accepted = publishStatuses.count { it.writable }
                    val message = if (accepted == eventCount) {
                        "Published all events"
                    } else {
                        "Published $accepted of $eventCount events"
                    }
                    RelayStatus(relay, readable = fetchStatus?.readable ?: false, writable = accepted == eventCount, message = message)
                }
                fetchStatus != null && !fetchStatus.readable -> {
                    RelayStatus(relay, readable = false, message = fetchStatus.message.toReadableRelayFailure("Could not fetch"))
                }
                migration.fetchedEventCount > 0 -> {
                    RelayStatus(relay, readable = fetchStatus?.readable ?: true, message = "Fetched ${migration.fetchedEventCount} events")
                }
                else -> RelayStatus(relay, readable = fetchStatus?.readable ?: true, message = "No encrypted notes to migrate")
            }
        }
        updateRelaySettingsStatuses(statuses)
    }

    private fun updateRelaySettingsStatuses(statuses: List<RelayStatus>) {
        if (statuses.isEmpty()) return
        val existing = _syncState.value.relayStatuses.associateBy { it.url }
        val merged = existing.toMutableMap()
        statuses.forEach { status -> merged[status.url] = status }
        val relayOrder = relaySettings.normalizedUrls()
        _syncState.value = _syncState.value.copy(
            relayStatuses = relayOrder.mapNotNull { merged[it] } +
                merged.values.filterNot { it.url in relayOrder },
        )
    }

    private fun String.toReadableRelayFailure(defaultMessage: String): String {
        val lower = lowercase()
        return when {
            lower.contains("reject") || lower.contains("denied") || lower.contains("blocked") || lower.contains("policy") -> "Rejected writes"
            lower.contains("timeout") || lower.contains("timed out") -> "Timed out"
            lower.contains("fetch") -> "Could not fetch"
            lower.contains("connect") || lower.contains("offline") || lower.contains("unreachable") -> "Could not reach relay"
            else -> defaultMessage
        }
    }

    suspend fun testRelayBeforeAdd(rawRelay: String, existingRelays: List<String>): RelayAddResult {
        if (_relayAddTestState.value.inProgress) return RelayAddResult.InProgress
        val parsed = relaySettings.previewChange(listOf(rawRelay))
        val normalized = parsed.getOrNull()?.singleOrNull()?.url
        if (normalized == null) {
            return RelayAddResult.ValidationFailed(parsed.exceptionOrNull()?.message ?: "Invalid relay URL")
        }
        if (normalized in existingRelays) {
            return RelayAddResult.Duplicate(normalized)
        }
        _relayAddTestState.value = RelayAddTestState(inProgress = true)
        val result = withContext(Dispatchers.IO) {
            services.relayTester.testAppRelay(normalized, _session.value)
        }
        return when (result) {
            is RelayTestResult.Success -> {
                _relayAddTestState.value = RelayAddTestState()
                RelayAddResult.Added(normalized)
            }
            is RelayTestResult.Failure -> {
                _relayAddTestState.value = RelayAddTestState(
                    warning = RelayAddWarning(
                        relayUrl = normalized,
                        safeReason = result.userMessage.toRelayTestWarningMessage(),
                    ),
                )
                RelayAddResult.WaitingForUserChoice
            }
        }
    }

    suspend fun testConfiguredRelay(rawRelay: String): Boolean {
        if (_relayAddTestState.value.inProgress) {
            _message.value = "Relay test already in progress."
            return false
        }
        val parsed = relaySettings.previewChange(listOf(rawRelay))
        val normalized = parsed.getOrNull()?.singleOrNull()?.url
        if (normalized == null) {
            _message.value = parsed.exceptionOrNull()?.message ?: "Invalid relay URL."
            return false
        }
        _relayAddTestState.value = RelayAddTestState(inProgress = true)
        val result = withContext(Dispatchers.IO) {
            services.relayTester.testAppRelay(normalized, _session.value)
        }
        _relayAddTestState.value = RelayAddTestState()
        return when (result) {
            is RelayTestResult.Success -> {
                val writable = result.mode == "signed_write_fetch"
                updateRelaySettingsStatuses(
                    listOf(
                        RelayStatus(
                            url = normalized,
                            readable = true,
                            writable = writable,
                            message = if (writable) {
                                "Relay reachable for note reads and writes"
                            } else {
                                "Relay reachable for note reads"
                            },
                        ),
                    ),
                )
                _message.value = "Relay test passed."
                true
            }
            is RelayTestResult.Failure -> {
                val safeMessage = result.userMessage.toRelayTestWarningMessage()
                updateRelaySettingsStatuses(
                    listOf(
                        RelayStatus(
                            url = normalized,
                            readable = false,
                            writable = false,
                            message = safeMessage.toReadableRelayFailure("Could not test relay"),
                        ),
                    ),
                )
                _message.value = "Relay test failed. $safeMessage"
                false
            }
        }
    }

    fun cancelFailedRelayAdd() {
        _relayAddTestState.value = RelayAddTestState()
    }

    fun continueFailedRelayAdd(): String? {
        val relay = _relayAddTestState.value.warning?.relayUrl
        _relayAddTestState.value = RelayAddTestState()
        return relay
    }

    suspend fun restoreDefaultRelays(): Boolean {
        return saveRelays(defaultRelayUrls)
    }

    fun cancelRelayMigrationWarning() {
        pendingRelayMigrationDecision = null
        _relayMigrationState.value = RelayMigrationUiState()
        _message.value = "Relay settings were not changed."
    }

    suspend fun continueRelayMigration(): Boolean {
        val pending = pendingRelayMigrationDecision ?: return false
        val result = pending.result
        val sessionPubkey = pending.sessionPubkey
        val queuedPendingWrites = if (result != null && sessionPubkey != null && result.failedAddedRelays.isNotEmpty()) {
            runCatching {
                withContext(Dispatchers.IO) {
                    queueRelayMigrationPendingWrites(sessionPubkey, result, services.pendingWriteStore)
                }
            }
        } else {
            Result.success(Unit)
        }
        if (queuedPendingWrites.isFailure) {
            _message.value = "Relay migration pending writes could not be saved. ${queuedPendingWrites.exceptionOrNull()?.safePersistenceMessage()}"
            return false
        }
        val persisted = persistRelaySettings(pending.requestedConfigs, pending.successMessage)
        if (persisted) {
            pendingRelayMigrationDecision = null
            _relayMigrationState.value = RelayMigrationUiState()
        }
        return persisted
    }

    private suspend fun persistRelaySettings(configs: List<RelayConfig>, successMessage: String): Boolean {
        val persisted = runCatching { relaySettings.commitAndPersist(configs) }
        if (persisted.isFailure) {
            _message.value = "Relay settings could not be saved. ${persisted.exceptionOrNull()?.safePersistenceMessage()}"
            return false
        }
        _message.value = successMessage
        return true
    }

    private fun SaveResult.toCompactMessage(): String = when (this) {
        is SaveResult.LocalOnly -> reason.toUserFacingMessage()
        is SaveResult.Published -> relayMessages.firstOrNull()?.compactStatus() ?: "Saved"
        is SaveResult.Failed -> reason.toUserFacingMessage()
    }

    private fun SaveResult.toDiagnosticMessage(): String = when (this) {
        is SaveResult.LocalOnly -> reason
        is SaveResult.Published -> relayMessages.joinToString("\n")
        is SaveResult.Failed -> reason
    }

    private fun List<RelayStatus>.toSafeSummary(): String =
        joinToString("\n") { "${it.url}: read=${it.readable} write=${it.writable} ${it.message.take(180)}" }

    private fun String.compactStatus(): String = lineSequence().firstOrNull().orEmpty().take(120)

    private fun String.abbreviatedId(): String = take(12)

    private fun Throwable.safePersistenceMessage(): String =
        toUserFacingPersistenceMessage()

    private fun startupMessage(): String =
        when (services.mode) {
            AppRuntimeMode.DesktopRelay ->
                if (services.secureSecretStore.isAvailable) {
                    "Desktop relay runtime enabled. Session-only keys remain default; saved keys are optional through the desktop keyring."
                } else {
                    "Desktop relay runtime enabled. Keys are session-only; encrypted relay sync is available."
                }
            AppRuntimeMode.DesktopDevRelay ->
                if (services.secureSecretStore.isAvailable) {
                    "Desktop relay runtime enabled. Developer flag accepted; saved keys are optional through the desktop keyring."
                } else {
                    "Desktop relay runtime enabled. Developer flag accepted; keys are session-only."
                }
            AppRuntimeMode.Offline ->
                "Offline runtime. nsec is kept in memory only."
        }
}

private fun UserSession.isSignerBacked(): Boolean =
    authMethod == SessionAuthMethod.ExternalSigner || authMethod == SessionAuthMethod.RemoteSigner
