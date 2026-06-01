package com.libertasprimordium.othernote.ui

import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.OtherNoteTag
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.SessionAuthMethod
import com.libertasprimordium.othernote.domain.SyncState
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.domain.hasSessionPrivateKey
import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.RelayPublishResult
import com.libertasprimordium.othernote.security.SignerPublicKeyRequestResult
import com.libertasprimordium.othernote.security.SignEventRequestResult
import com.libertasprimordium.othernote.security.SignerNip44Operation
import com.libertasprimordium.othernote.security.SignerNip44OperationResult
import com.libertasprimordium.othernote.security.SignerNip44RequestBuilder
import com.libertasprimordium.othernote.security.SignerNip44TestPayload
import com.libertasprimordium.othernote.security.SignerNoteEventBuildResult
import com.libertasprimordium.othernote.security.SignerNoteEventBuilder
import com.libertasprimordium.othernote.security.SignerNoteEventBuildStage
import com.libertasprimordium.othernote.security.SignerSignEventRequestBuilder
import com.libertasprimordium.othernote.security.SignerTestEventFactory
import com.libertasprimordium.othernote.sync.DeleteNoteUseCase
import com.libertasprimordium.othernote.sync.MigrateRelaysUseCase
import com.libertasprimordium.othernote.sync.SaveNoteUseCase
import com.libertasprimordium.othernote.sync.SaveResult
import com.libertasprimordium.othernote.sync.SyncNotesUseCase
import com.libertasprimordium.othernote.sync.reduceNoteEvents
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

private enum class SignerWriteAction {
    Save,
    Delete,
}

class AppState(private val services: AppServices = defaultAppServices()) {
    private val crypto = services.crypto
    private val signerVerifier = ProductionNostrCryptoFactory.createOrNull()
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
    private val signerNoteEventBuilder = SignerNoteEventBuilder(
        nip44 = services.externalSignerNip44Operator,
        eventSigner = services.externalSignerEventSigner,
    )

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
    val showNip55Diagnostics: Boolean = services.showNip55Diagnostics
    val externalSignerAvailable: Boolean = services.externalSignerProvider.isAvailable
    val externalSignerDisplayName: String? = services.externalSignerProvider.displayName
    val externalSignerCanSignEvent: Boolean = services.externalSignerProvider.canSignEvent
    val externalSignerCanNip44RoundTrip: Boolean = services.externalSignerProvider.canNip44RoundTrip
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
                _message.value = "Signer login ready; relay sync can run with signer prompts."
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
                _message.value = result.safeReason
            }
            is SignEventRequestResult.Failed -> {
                _message.value = result.safeReason
            }
            is SignEventRequestResult.InvalidResponse -> {
                _message.value = result.safeReason
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
                _message.value = encrypted.safeReason
            }
            is SignerNip44OperationResult.Failed -> {
                _message.value = encrypted.safeReason
            }
            is SignerNip44OperationResult.InvalidResponse -> {
                _message.value = encrypted.safeReason
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
                _message.value = result.safeReason
            }
            is SignerNip44OperationResult.Failed -> {
                _message.value = result.safeReason
            }
            is SignerNip44OperationResult.InvalidResponse -> {
                _message.value = result.safeReason
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
                _message.value = result.safeReason
            }
            is SignerNoteEventBuildResult.Failed -> {
                _message.value = result.safeReason
            }
            is SignerNoteEventBuildResult.InvalidResponse -> {
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
        if (session?.authMethod == SessionAuthMethod.ExternalSigner) {
            return saveWithExternalSigner(existing, markdown, session)
        }
        if (session != null && !session.hasSessionPrivateKey()) {
            _message.value = "Saving requires a session key or Android signer."
            return false
        }
        val result = saveNote.save(existing, markdown, _session.value, relaySettings.normalizedUrls())
        _message.value = result.toCompactMessage()
        _diagnosticMessage.value = result.toDiagnosticMessage()
        return result !is SaveResult.Failed
    }

    private suspend fun saveWithExternalSigner(existing: Note?, markdown: String, session: UserSession): Boolean {
        if (!externalSignerCanSignEvent || !externalSignerCanNip44RoundTrip) {
            _message.value = "External signer local save is unavailable."
            return false
        }
        _message.value = "Waiting for signer..."
        val result = if (existing == null) {
            signerNoteEventBuilder.buildNewLocalNoteEvent(session, session.signerPackage, markdown, ::handleSignerNoteSaveStage)
        } else {
            signerNoteEventBuilder.buildReplacementLocalNoteEvent(session, session.signerPackage, existing, markdown, ::handleSignerNoteSaveStage)
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
                _message.value = result.safeReason
                false
            }
            is SignerNoteEventBuildResult.Failed -> {
                _message.value = result.safeReason
                false
            }
            is SignerNoteEventBuildResult.InvalidResponse -> {
                _message.value = result.safeReason
                false
            }
        }
    }

    private fun handleSignerNoteSaveStage(stage: SignerNoteEventBuildStage) {
        _message.value = when (stage) {
            SignerNoteEventBuildStage.PayloadEncoded -> "Waiting for signer..."
            SignerNoteEventBuildStage.Encrypted -> "Signer encrypted note"
            SignerNoteEventBuildStage.EventBuilt -> "Waiting for signer..."
            SignerNoteEventBuildStage.Signed -> "Signer signed note"
            SignerNoteEventBuildStage.Validated -> "Signer signed note"
            SignerNoteEventBuildStage.Decrypted -> "Signer decrypted note"
            SignerNoteEventBuildStage.PayloadDecoded -> "Saving locally..."
        }
    }

    suspend fun delete(note: Note): Boolean {
        val session = _session.value
        if (session?.authMethod == SessionAuthMethod.ExternalSigner) {
            return deleteWithExternalSigner(note, session)
        }
        if (session != null && !session.hasSessionPrivateKey()) {
            _message.value = "Deleting requires a session key or Android signer."
            return false
        }
        val result = deleteNote.delete(note, _session.value, relaySettings.normalizedUrls())
        _message.value = result.toCompactMessage()
        _diagnosticMessage.value = result.toDiagnosticMessage()
        return result !is SaveResult.Failed
    }

    private suspend fun deleteWithExternalSigner(note: Note, session: UserSession): Boolean {
        if (!externalSignerCanSignEvent || !externalSignerCanNip44RoundTrip) {
            _message.value = "External signer local delete is unavailable."
            return false
        }
        _message.value = "Waiting for signer..."
        val result = signerNoteEventBuilder.buildLocalTombstoneEvent(
            session = session,
            signerPackage = session.signerPackage,
            existing = note,
            onStage = ::handleSignerTombstoneStage,
        )
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
                _message.value = result.safeReason
                false
            }
            is SignerNoteEventBuildResult.Failed -> {
                _message.value = result.safeReason
                false
            }
            is SignerNoteEventBuildResult.InvalidResponse -> {
                _message.value = result.safeReason
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
        if (session?.authMethod == SessionAuthMethod.ExternalSigner) {
            syncWithExternalSigner(session)
            return
        }
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

    private suspend fun publishSignerEvent(
        session: UserSession,
        event: NostrEvent,
        action: SignerWriteAction,
    ): RelayPublishResult {
        val relays = relaySettings.normalizedUrls().distinct()
        services.pendingWriteStore.enqueuePendingWrite(session.publicKeyHex, event, relays)
        val publish = nostr.publishBestEffort(relays, event, appScope) { statuses ->
            appScope.launch { updateSignerPendingStatuses(session.publicKeyHex, event.id, statuses) }
            updateSignerPublishStatus(statuses, action)
        }
        val firstAccepted = publish.firstAccepted.await()
        updateSignerPendingStatuses(session.publicKeyHex, event.id, firstAccepted.statuses)
        appScope.launch {
            runCatching {
                val complete = publish.complete.await()
                updateSignerPendingStatuses(session.publicKeyHex, event.id, complete.statuses)
                updateSignerPublishStatus(complete.statuses, action)
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
        val relays = relaySettings.normalizedUrls()
        _syncState.value = _syncState.value.copy(syncing = true)
        _message.value = "Syncing..."
        val aggregateEvents = mutableListOf<NostrEvent>()
        val aggregateStatuses = linkedMapOf<String, RelayStatus>()
        val cached = services.localEventCache.loadEvents(session.publicKeyHex)
        if (cached.isNotEmpty()) {
            aggregateEvents += cached
            applySignerFetchedEvents(session, aggregateEvents, emptyList(), final = false)
        }
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
        val reduced = reduceNoteEvents(candidateEvents) { event ->
            when (val decrypted = services.externalSignerNip44Operator.decryptFromSelf(event.content, null, session.publicKeyHex, session.signerPackage)) {
                is SignerNip44OperationResult.Decrypted -> Result.success(decrypted.plaintext)
                else -> Result.failure(IllegalStateException("Signer decrypt failed"))
            }
        }
        val selectedNoteIds = reduced.selectedNoteIds
        val preservedNotes = notes.notes.value.filter { it.id !in selectedNoteIds }
        notes.replaceFromSync(reduced.notes + preservedNotes)
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

    private fun String.abbreviatedId(): String = take(12)

    private fun startupMessage(): String =
        if (services.mode == AppRuntimeMode.DesktopDevRelay) {
            "Developer relay runtime enabled. Use throwaway nsecs only; keys are session-only."
        } else {
            "Offline runtime. Desktop key persistence is disabled; nsec is kept in memory only."
        }
}
