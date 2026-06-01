package com.libertasprimordium.othernote.ui

import com.libertasprimordium.othernote.data.InMemoryNoteRepository
import com.libertasprimordium.othernote.data.RelaySettingsStore
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.SyncState
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.sync.DeleteNoteUseCase
import com.libertasprimordium.othernote.sync.MigrateRelaysUseCase
import com.libertasprimordium.othernote.sync.SaveNoteUseCase
import com.libertasprimordium.othernote.sync.SaveResult
import com.libertasprimordium.othernote.sync.SyncNotesUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppMode {
    SignedOut,
    LocalOnly,
    Authenticated,
}

class AppState {
    private val crypto = NonProductionNostrCrypto()
    private val client = OfflineNostrClient()
    private val nostr = NostrRepository(crypto, client)
    val notes = InMemoryNoteRepository()
    val relaySettings = RelaySettingsStore()
    private val saveNote = SaveNoteUseCase(notes, nostr)
    private val deleteNote = DeleteNoteUseCase(notes, nostr)
    private val syncNotes = SyncNotesUseCase(notes, nostr, crypto)
    private val migrateRelays = MigrateRelaysUseCase()

    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session

    private val _mode = MutableStateFlow(AppMode.SignedOut)
    val mode: StateFlow<AppMode> = _mode

    private val _syncState = MutableStateFlow(SyncState(warnings = listOf("Production Nostr crypto is not wired yet")))
    val syncState: StateFlow<SyncState> = _syncState

    private val _message = MutableStateFlow("Desktop key persistence is disabled; nsec is kept in memory only.")
    val message: StateFlow<String> = _message

    fun login(rawNsec: String): Boolean {
        return when (val decoded = crypto.decodeNsec(rawNsec)) {
            is KeyDecodeResult.Valid -> {
                _session.value = UserSession(
                    nsec = "nsec-redacted",
                    privateKeyHex = decoded.privateKey.hex,
                    npub = "npub unavailable until secp256k1 integration",
                    publicKeyHex = "unavailable",
                )
                _mode.value = AppMode.Authenticated
                _message.value = "nsec validated. Public key derivation and relay publishing need a production crypto adapter."
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

    suspend fun save(existing: Note?, markdown: String) {
        val result = saveNote.save(existing, markdown, _session.value, relaySettings.normalizedUrls())
        _message.value = result.toMessage()
    }

    suspend fun delete(note: Note) {
        val result = deleteNote.delete(note, _session.value, relaySettings.normalizedUrls())
        _message.value = result.toMessage()
    }

    suspend fun sync() {
        if (_session.value == null) {
            _syncState.value = SyncState(errors = listOf("Relay sync requires a validated nsec session"))
            _message.value = _syncState.value.summary
            return
        }
        _syncState.value = _syncState.value.copy(syncing = true)
        _syncState.value = syncNotes.sync(_session.value, relaySettings.normalizedUrls())
        _message.value = _syncState.value.summary
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
    }
}
