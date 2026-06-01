package com.libertasprimordium.othernote.ui

import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.SyncState
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.NostrRepository
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
    private val saveNote = SaveNoteUseCase(notes, nostr, appScope, ::updatePublishStatuses)
    private val deleteNote = DeleteNoteUseCase(notes, nostr, appScope, ::updatePublishStatuses)
    private val syncNotes = SyncNotesUseCase(notes, nostr, crypto, appScope)
    private val migrateRelays = MigrateRelaysUseCase()

    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session

    private val _mode = MutableStateFlow(AppMode.SignedOut)
    val mode: StateFlow<AppMode> = _mode

    private val _syncState = MutableStateFlow(SyncState(warnings = services.startupWarnings))
    val syncState: StateFlow<SyncState> = _syncState

    private val _message = MutableStateFlow(startupMessage())
    val message: StateFlow<String> = _message

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
        val result = saveNote.save(existing, markdown, _session.value, relaySettings.normalizedUrls())
        _message.value = result.toMessage()
        return result !is SaveResult.Failed
    }

    suspend fun delete(note: Note): Boolean {
        val result = deleteNote.delete(note, _session.value, relaySettings.normalizedUrls())
        _message.value = result.toMessage()
        return result !is SaveResult.Failed
    }

    suspend fun sync() {
        if (_session.value == null || runtimeMode != AppRuntimeMode.DesktopDevRelay) {
            _syncState.value = SyncState(errors = listOf("Relay sync requires a validated nsec session"))
            _message.value = _syncState.value.summary
            return
        }
        _syncState.value = _syncState.value.copy(syncing = true)
        try {
            _syncState.value = syncNotes.sync(_session.value, relaySettings.normalizedUrls()) { partial ->
                _syncState.value = partial.copy(syncing = true)
                _message.value = partial.toMessage()
            }
        } finally {
            _syncState.value = _syncState.value.copy(syncing = false)
            _message.value = _syncState.value.toMessage()
        }
    }

    fun startSync(): Job = appScope.launch { sync() }

    private fun SyncState.toMessage(): String =
        buildString {
            append(summary)
            val relaySummary = relayStatuses.toSafeSummary()
            if (relaySummary.isNotBlank()) append("\n").append(relaySummary)
        }

    private fun updatePublishStatuses(statuses: List<RelayStatus>) {
        _syncState.value = _syncState.value.copy(relayStatuses = statuses)
        _message.value = buildString {
            append("Relay write fanout in progress: ")
            append(statuses.count { it.writable })
            append("/")
            append(statuses.size)
            append(" accepted")
            val relaySummary = statuses.toSafeSummary()
            if (relaySummary.isNotBlank()) append("\n").append(relaySummary)
        }
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

    private fun SaveResult.toMessage(): String = when (this) {
        is SaveResult.LocalOnly -> reason
        is SaveResult.Published -> relayMessages.joinToString("\n")
        is SaveResult.Failed -> reason
    }

    private fun List<RelayStatus>.toSafeSummary(): String =
        joinToString("\n") { "${it.url}: read=${it.readable} write=${it.writable} ${it.message.take(180)}" }

    private fun startupMessage(): String =
        if (services.mode == AppRuntimeMode.DesktopDevRelay) {
            "Developer relay runtime enabled. Use throwaway nsecs only; keys are session-only."
        } else {
            "Offline runtime. Desktop key persistence is disabled; nsec is kept in memory only."
        }
}
