package com.libertasprimordium.othernote.ui

import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.SessionAuthMethod
import com.libertasprimordium.othernote.domain.SyncState
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.domain.hasSessionPrivateKey
import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.security.SignerPublicKeyRequestResult
import com.libertasprimordium.othernote.sync.DeleteNoteUseCase
import com.libertasprimordium.othernote.sync.MigrateRelaysUseCase
import com.libertasprimordium.othernote.sync.SaveNoteUseCase
import com.libertasprimordium.othernote.sync.SaveResult
import com.libertasprimordium.othernote.sync.SyncNotesUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppMode {
    SignedOut,
    LocalOnly,
    Authenticated,
}

class AppState(private val services: AppServices = defaultAppServices()) {
    private val crypto = services.crypto
    private val client = services.client
    private val nostr = NostrRepository(crypto, client)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runtimeMode: AppRuntimeMode = services.mode
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
    private val syncNotes = SyncNotesUseCase(
        notes,
        nostr,
        crypto,
        services.localEventCache,
        services.pendingWriteStore,
        appScope,
    )
    private val migrateRelays = MigrateRelaysUseCase()

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

    val showRelayDiagnostics: Boolean = services.showRelayDiagnostics
    val externalSignerAvailable: Boolean = services.externalSignerProvider.isAvailable
    val externalSignerDisplayName: String? = services.externalSignerProvider.displayName
    val externalSignerStatus: String = if (services.externalSignerProvider.isAvailable) {
        "External signer detected: ${services.externalSignerProvider.displayName ?: "NIP-55 signer"}"
    } else {
        services.externalSignerProvider.unavailableReason ?: "External signer unavailable"
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
                _session.value = UserSession(
                    nsec = "nsec-redacted",
                    privateKeyHex = decoded.privateKey.hex,
                    npub = publicKey.npub,
                    publicKeyHex = publicKey.hex,
                    authMethod = SessionAuthMethod.SessionOnlyNsec,
                )
                _mode.value = AppMode.Authenticated
                _message.value = if (runtimeMode == AppRuntimeMode.DesktopDevRelay) {
                    "Developer relay mode. nsec is session-only and not persisted."
                } else {
                    "nsec validated. Relay sync is disabled in offline runtime."
                }
                true
            }
            is KeyDecodeResult.Invalid -> {
                _message.value = decoded.reason
                false
            }
        }
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

    private fun handleSignerPublicKeyResult(result: SignerPublicKeyRequestResult) {
        when (result) {
            is SignerPublicKeyRequestResult.Success -> {
                _session.value = UserSession(
                    nsec = "external-signer",
                    privateKeyHex = "",
                    npub = result.npub,
                    publicKeyHex = result.pubkeyHex,
                    authMethod = SessionAuthMethod.ExternalSigner,
                    signerPackage = result.signerPackage,
                )
                _mode.value = AppMode.Authenticated
                _message.value = "Signer login ready; note sync through signer is not implemented yet."
            }
            SignerPublicKeyRequestResult.Cancelled -> {
                _message.value = "Signer request cancelled"
            }
            is SignerPublicKeyRequestResult.Unavailable -> {
                _message.value = result.safeReason
            }
            is SignerPublicKeyRequestResult.Failed -> {
                _message.value = result.safeReason
            }
            is SignerPublicKeyRequestResult.InvalidResponse -> {
                _message.value = result.safeReason
            }
        }
    }

    fun continueLocalOnly() {
        _session.value = null
        _mode.value = AppMode.LocalOnly
        _message.value = "Local-only mode. Notes stay on this device and relay sync is disabled."
    }

    suspend fun logout() {
        _session.value = null
        _mode.value = AppMode.SignedOut
        notes.clear()
        _message.value = "Session cleared. Local in-memory notes were removed."
    }

    suspend fun save(existing: Note?, markdown: String): Boolean {
        val session = _session.value
        if (session != null && !session.hasSessionPrivateKey()) {
            _message.value = "Signer login ready; saving through signer is not implemented yet."
            return false
        }
        val result = saveNote.save(existing, markdown, _session.value, relaySettings.normalizedUrls())
        _message.value = result.toCompactMessage()
        _diagnosticMessage.value = result.toDiagnosticMessage()
        return result !is SaveResult.Failed
    }

    suspend fun delete(note: Note): Boolean {
        val session = _session.value
        if (session != null && !session.hasSessionPrivateKey()) {
            _message.value = "Signer login ready; deleting through signer is not implemented yet."
            return false
        }
        val result = deleteNote.delete(note, _session.value, relaySettings.normalizedUrls())
        _message.value = result.toCompactMessage()
        _diagnosticMessage.value = result.toDiagnosticMessage()
        return result !is SaveResult.Failed
    }

    suspend fun sync() {
        if (_session.value == null || runtimeMode != AppRuntimeMode.DesktopDevRelay || _session.value?.hasSessionPrivateKey() != true) {
            _syncState.value = SyncState(errors = listOf("Relay sync requires a validated nsec session"))
            _message.value = _syncState.value.summary
            return
        }
        _syncState.value = _syncState.value.copy(syncing = true)
        try {
            _syncState.value = syncNotes.sync(_session.value, relaySettings.normalizedUrls()) { partial ->
                _syncState.value = partial.copy(syncing = true)
                _message.value = partial.toCompactMessage()
                _diagnosticMessage.value = partial.toDiagnosticMessage()
            }
        } finally {
            _syncState.value = _syncState.value.copy(syncing = false)
            _message.value = _syncState.value.toCompactMessage()
            _diagnosticMessage.value = _syncState.value.toDiagnosticMessage()
        }
    }

    fun startSync(): Job = appScope.launch { sync() }

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
                errors.first()
            }
        }
        val total = relayStatuses.size
        val reachable = relayStatuses.count { it.readable }
        return when {
            total == 0 && warnings.isEmpty() -> "Not synced"
            total == 0 -> warnings.first().compactStatus()
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

    suspend fun saveRelays(rawRelays: List<String>) {
        val newConfig = relaySettings.previewChange(rawRelays)
        val configs = newConfig.getOrNull()
        if (configs == null) {
            _message.value = newConfig.exceptionOrNull()?.message ?: "Invalid relay settings"
            return
        }
        val old = relaySettings.normalizedUrls()
        val plan = migrateRelays.migrate(old, configs.map { it.url })
        relaySettings.commit(configs)
        _message.value = buildString {
            append("Relay settings saved.")
            if (plan.addedRelays.isNotEmpty()) append(" Added: ${plan.addedRelays.joinToString()}.")
            if (plan.removedRelays.isNotEmpty()) append(" Removed after migration planning: ${plan.removedRelays.joinToString()}.")
        }
    }

    private fun SaveResult.toCompactMessage(): String = when (this) {
        is SaveResult.LocalOnly -> reason
        is SaveResult.Published -> relayMessages.firstOrNull()?.compactStatus() ?: "Saved"
        is SaveResult.Failed -> reason
    }

    private fun SaveResult.toDiagnosticMessage(): String = when (this) {
        is SaveResult.LocalOnly -> reason
        is SaveResult.Published -> relayMessages.joinToString("\n")
        is SaveResult.Failed -> reason
    }

    private fun List<RelayStatus>.toSafeSummary(): String =
        joinToString("\n") { "${it.url}: read=${it.readable} write=${it.writable} ${it.message.take(180)}" }

    private fun String.compactStatus(): String = lineSequence().firstOrNull().orEmpty().take(120)

    private fun startupMessage(): String =
        if (services.mode == AppRuntimeMode.DesktopDevRelay) {
            "Developer relay runtime enabled. Use throwaway nsecs only; keys are session-only."
        } else {
            "Offline runtime. Desktop key persistence is disabled; nsec is kept in memory only."
        }
}
