package com.libertasprimordium.othernote.security

import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrFilter
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.Nip46LiveNostrClient
import com.libertasprimordium.othernote.nostr.Nip46LiveRelayOutcome
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.util.nowMs
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import com.libertasprimordium.othernote.util.stableRandomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

sealed class Nip46ConnectResult {
    data class Connected(val userPubkey: String, val userNpub: String, val remoteSignerPubkey: String, val relays: List<String>) : Nip46ConnectResult()
    data class AwaitingApproval(val safeUrl: String) : Nip46ConnectResult()
    data class Failed(val safeReason: String) : Nip46ConnectResult()
    data object TimedOut : Nip46ConnectResult()
    data object Unavailable : Nip46ConnectResult()
}

enum class Nip46ConnectionState {
    Disconnected,
    Connecting,
    AwaitingSignerApproval,
    Connected,
    Failed,
    TimedOut,
}

interface Nip46RequestTransport {
    suspend fun sendRequest(session: Nip46TransportSession, request: Nip46RequestPayload): Result<Nip46Response>
}

data class Nip46TransportSession(
    val clientPrivateKey: NostrPrivateKey,
    val clientPubkey: String,
    val remoteSignerPubkey: String,
    val relays: List<String>,
    val relaySource: String = "token",
    val relaySources: Map<String, String> = relays.associateWith { relaySource },
)

class RelayNip46RequestTransport(
    private val client: NostrClient,
    private val crypto: NostrCrypto,
    private val requestTimeoutMs: Long = 12_000,
    private val pollIntervalMs: Long = 750,
) : Nip46RequestTransport {
    private val responseClockSkewSeconds = 60L
    private val relayHealthMutex = Mutex()
    private val relayHealth = mutableMapOf<Nip46RelayHealthKey, Nip46RelayHealth>()

    override suspend fun sendRequest(session: Nip46TransportSession, request: Nip46RequestPayload): Result<Nip46Response> = runCatching {
        val liveClient = client as? Nip46LiveNostrClient
            ?: error("stage=live_transport_unavailable method=${request.safeMethod} request_id=${request.safeIdPrefix} relay_source=${session.relaySource}")
        val event = buildRequestEvent(session, request)
        val candidateRelays = orderedRelays(session)
        val started = TimeSource.Monotonic.markNow()
        val mutex = Mutex()
        var candidateEventCount = 0
        var decryptFailureCount = 0
        var idMismatchCount = 0
        var matchingIdFound = false
        var matchedResponse: Nip46Response? = null
        val filter = responseFilter(session, event.createdAt - responseClockSkewSeconds)
        val live = liveClient.requestNip46Response(
            relays = candidateRelays,
            requestEvent = event,
            filter = filter,
            timeoutMs = requestTimeoutMs,
        ) { _, candidate ->
            if (!candidate.isRelevantResponseCandidate(session, event.createdAt - responseClockSkewSeconds)) return@requestNip46Response false
            mutex.withLock {
                candidateEventCount++
                val payload = decryptResponsePayload(session, candidate).getOrElse {
                    decryptFailureCount++
                    return@withLock false
                }
                if (payload.id != request.id) {
                    idMismatchCount++
                    return@withLock false
                }
                matchingIdFound = true
                matchedResponse = payload.toNip46Response()
                true
            }
        }
        updateRelayHealth(session, live.relayOutcomes)
        val response = mutex.withLock { matchedResponse }
        if (response != null) return@runCatching response

        val publishAcceptedCount = live.publishStatuses.count { it.writable }
        val publishRejectedCount = live.publishStatuses.count { !it.writable }
        val acceptedRelays = live.publishStatuses.filter { it.writable }.map { it.url }
        val rejectedRelays = live.publishStatuses.filter { !it.writable }.map { it.url }
        val outcomeSummary = live.relayOutcomes.safeRelayOutcomeSummary(session)
        if (live.publishStatuses.isNotEmpty() && publishAcceptedCount == 0) {
            error(
                "stage=relay_publish_failed method=${request.safeMethod} request_id=${request.safeIdPrefix} " +
                    "relay_source=${session.relaySource} " +
                    "relays_publish_accepted=${acceptedRelays.safeRelayList()} relays_publish_rejected=${rejectedRelays.safeRelayList()} " +
                    "relays_attempted=${candidateRelays.size} attempted_relays=${candidateRelays.safeRelayList()} " +
                    "publish_accepted_count=$publishAcceptedCount publish_rejected_count=$publishRejectedCount " +
                    "candidate_events=$candidateEventCount decrypt_failures=$decryptFailureCount mismatched_ids=$idMismatchCount " +
                    "matching_id_found=$matchingIdFound live_response_after_publish=${live.liveEventAfterPublishCount > 0} " +
                    "elapsed_ms=${started.elapsedNow().inWholeMilliseconds} per_relay_outcomes=$outcomeSummary statuses=${live.publishStatuses.safeStatusSummary(write = true)}",
            )
        }
        error(
            "stage=response_fetch_timed_out reason=no_matching_response method=${request.safeMethod} request_id=${request.safeIdPrefix} " +
                "relay_source=${session.relaySource} " +
                "relays_publish_accepted=${acceptedRelays.safeRelayList()} relays_publish_rejected=${rejectedRelays.safeRelayList()} " +
                "relays_attempted=${candidateRelays.size} attempted_relays=${candidateRelays.safeRelayList()} " +
                "publish_accepted_count=$publishAcceptedCount publish_rejected_count=$publishRejectedCount " +
                "candidate_events=$candidateEventCount decrypt_failures=$decryptFailureCount mismatched_ids=$idMismatchCount " +
                "matching_id_found=$matchingIdFound live_response_after_publish=${live.liveEventAfterPublishCount > 0} " +
                "elapsed_ms=${started.elapsedNow().inWholeMilliseconds} per_relay_outcomes=$outcomeSummary statuses=${live.publishStatuses.safeStatusSummary(write = true)}",
        )
    }

    private fun buildRequestEvent(session: Nip46TransportSession, request: Nip46RequestPayload): NostrEvent {
        val remotePublicKey = NostrPublicKey(session.remoteSignerPubkey, "")
        val encryptedRequest = crypto.encryptToSelf(
            plaintext = Nip46PayloadJson.encodeRequest(request),
            privateKey = session.clientPrivateKey,
            publicKey = remotePublicKey,
        ).getOrThrow()
        val unsigned = UnsignedNostrEvent(
            pubkey = session.clientPubkey,
            createdAt = nowMs() / 1000,
            kind = Nip46EventKind,
            tags = listOf(listOf("p", session.remoteSignerPubkey)),
            content = encryptedRequest,
        )
        return crypto.sign(unsigned, session.clientPrivateKey).getOrThrow()
    }

    private fun responseFilter(session: Nip46TransportSession, since: Long): NostrFilter =
        NostrFilter(
            authors = listOf(session.remoteSignerPubkey),
            kinds = listOf(Nip46EventKind),
            tTags = emptyList(),
            pTags = listOf(session.clientPubkey),
            since = since,
            limit = 50,
        )

    private fun decryptResponsePayload(session: Nip46TransportSession, event: NostrEvent): Result<Nip46ResponsePayload> = runCatching {
        val plaintext = crypto.decryptFromSelf(
            ciphertext = event.content,
            privateKey = session.clientPrivateKey,
            publicKey = NostrPublicKey(session.remoteSignerPubkey, ""),
        ).getOrThrow()
        Nip46PayloadJson.decodeResponsePayload(plaintext).getOrThrow()
    }

    private suspend fun orderedRelays(session: Nip46TransportSession): List<String> {
        val relays = session.relays.distinct()
        if (relays.size < 2) return relays
        return relayHealthMutex.withLock {
            val successful = relays.mapNotNull { relay ->
                val health = relayHealth[session.healthKey(relay)]?.takeIf { it.responseMatched } ?: return@mapNotNull null
                relay to health
            }.sortedWith(
                compareBy<Pair<String, Nip46RelayHealth>> { it.second.latencyMs }
                    .thenByDescending { it.second.successCount }
                    .thenByDescending { it.second.updatedAtMs },
            ).map { it.first }
            if (successful.isEmpty()) relays else successful + relays.filterNot { it in successful }
        }
    }

    private suspend fun updateRelayHealth(session: Nip46TransportSession, outcomes: List<Nip46LiveRelayOutcome>) {
        if (outcomes.isEmpty()) return
        val updatedAt = nowMs()
        relayHealthMutex.withLock {
            outcomes.forEach { outcome ->
                val key = session.healthKey(outcome.relay)
                val existing = relayHealth[key]
                relayHealth[key] = Nip46RelayHealth(
                    source = session.sourceForRelay(outcome.relay),
                    publishAccepted = outcome.publishStatus?.writable == true,
                    publishRejected = outcome.publishStatus?.writable == false,
                    responseMatched = outcome.responseMatched || existing?.responseMatched == true,
                    latencyMs = if (outcome.responseMatched || existing == null) outcome.latencyMs else existing.latencyMs,
                    failureReason = outcome.failureReason,
                    successCount = (existing?.successCount ?: 0) + if (outcome.responseMatched) 1 else 0,
                    updatedAtMs = updatedAt,
                )
            }
        }
    }
}

class Nip46RemoteSigner(
    private val transport: Nip46RequestTransport,
    private val crypto: NostrCrypto? = ProductionNostrCryptoFactory.createOrNull(),
    private val requestIdProvider: () -> String = ::stableRandomId,
) : NostrSignerProvider, NostrSignerPublicKeyRequester, NostrSignerEventSigner, NostrSignerNip44Operator {
    private val signerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transportSession: Nip46TransportSession? = null
    private var userPubkey: String? = null
    private var userNpub: String? = null
    private var activeTokenSecret: String? = null
    private var lastState: Nip46ConnectionState = Nip46ConnectionState.Disconnected
    private var lastSafeMessage: String = "Remote signer disconnected"

    override val mode: SignerMode = SignerMode.ExternalSigner
    override val isAvailable: Boolean get() = crypto != null
    override val unavailableReason: String? get() = if (crypto == null) ProductionNostrCryptoFactory.unavailableReason else null
    override val displayName: String? get() = "NIP-46 remote signer"
    override val canGetPublicKey: Boolean get() = isAvailable
    override val canSignEvent: Boolean get() = transportSession != null && userPubkey != null
    override val canNip44EncryptDecrypt: Boolean get() = transportSession != null && userPubkey != null
    override val safeDiagnostics: List<String> get() = listOf("state=$lastState", "message=${lastSafeMessage.take(120)}")

    suspend fun connectAsync(rawToken: String): Nip46ConnectResult = withContext(Dispatchers.Default) {
        val crypto = crypto ?: return@withContext Nip46ConnectResult.Unavailable
        val token = Nip46ConnectionTokenParser.parse(rawToken).getOrElse {
            lastState = Nip46ConnectionState.Failed
            lastSafeMessage = it.message ?: "Remote signer token is invalid"
            return@withContext Nip46ConnectResult.Failed(lastSafeMessage)
        }
        if (token !is Nip46ConnectionToken.Bunker) {
            lastState = Nip46ConnectionState.Failed
            lastSafeMessage = "Paste a bunker:// remote signer token for this login path"
            return@withContext Nip46ConnectResult.Failed(lastSafeMessage)
        }
        activeTokenSecret = token.secret
        lastState = Nip46ConnectionState.Connecting
        lastSafeMessage = "Connecting to remote signer"
        val clientPrivateKey = crypto.generatePrivateKey().getOrElse {
            lastState = Nip46ConnectionState.Failed
            lastSafeMessage = "Could not create remote signer transport key"
            return@withContext Nip46ConnectResult.Failed(lastSafeMessage)
        }
        val clientPublicKey = crypto.derivePublicKey(clientPrivateKey).getOrElse {
            lastState = Nip46ConnectionState.Failed
            lastSafeMessage = "Could not create remote signer transport identity"
            return@withContext Nip46ConnectResult.Failed(lastSafeMessage)
        }
        val session = Nip46TransportSession(
            clientPrivateKey = clientPrivateKey,
            clientPubkey = clientPublicKey.hex,
            remoteSignerPubkey = token.remoteSignerPubkey,
            relays = token.relays,
        )
        transportSession = session
        val connectResponse = requestAsync(Nip46Method.Connect, connectParams(token))
        if (lastState == Nip46ConnectionState.TimedOut) return@withContext Nip46ConnectResult.TimedOut
        return@withContext when (connectResponse) {
            is Nip46Response.AuthChallenge -> {
                lastState = Nip46ConnectionState.AwaitingSignerApproval
                lastSafeMessage = "Remote signer approval required"
                Nip46ConnectResult.AwaitingApproval(connectResponse.safeUrl)
            }
            is Nip46Response.Error -> fail(remoteSignerReturnedErrorMessage(Nip46Method.Connect, connectResponse.safeMessage))
            is Nip46Response.TransportFailure -> fail(connectResponse.safeMessage)
            is Nip46Response.Success -> {
                val publicKey = getPublicKeyInternal()
                when (publicKey) {
                    is SignerPublicKeyRequestResult.Success -> {
                        userPubkey = publicKey.pubkeyHex
                        userNpub = publicKey.npub
                        activeTokenSecret = null
                        runCatching { requestAsync(Nip46Method.Ping) }
                        runCatching { applySwitchRelays(requestAsync(Nip46Method.SwitchRelays)) }
                        val activeRelays = transportSession?.relays ?: token.relays
                        lastState = Nip46ConnectionState.Connected
                        lastSafeMessage = "Remote signer connected"
                        Nip46ConnectResult.Connected(publicKey.pubkeyHex, publicKey.npub, token.remoteSignerPubkey, activeRelays)
                    }
                    is SignerPublicKeyRequestResult.InvalidResponse -> fail(publicKey.safeReason)
                    is SignerPublicKeyRequestResult.Failed -> fail(publicKey.safeReason)
                    is SignerPublicKeyRequestResult.Unavailable -> fail(publicKey.safeReason)
                    SignerPublicKeyRequestResult.Cancelled -> fail("Remote signer public-key request was cancelled")
                }
            }
        }
    }

    override fun requestPublicKey(onResult: (SignerPublicKeyRequestResult) -> Unit) {
        signerScope.launch {
            onResult(getPublicKeyInternal())
        }
    }

    override fun signEvent(
        unsignedEvent: NostrEvent,
        currentUserPubkey: String,
        signerPackage: String?,
        onResult: (SignEventRequestResult) -> Unit,
    ) {
        if (currentUserPubkey != userPubkey) {
            onResult(SignEventRequestResult.InvalidResponse("Remote signer user pubkey does not match session"))
            return
        }
        signerScope.launch {
            onResult(signEventAsync(unsignedEvent, currentUserPubkey, signerPackage))
        }
    }

    suspend fun signEventAsync(
        unsignedEvent: NostrEvent,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignEventRequestResult = withContext(Dispatchers.Default) {
        if (currentUserPubkey != userPubkey) {
            return@withContext SignEventRequestResult.InvalidResponse("Remote signer user pubkey does not match session")
        }
        when (val response = requestAsync(Nip46Method.SignEvent, listOf(Nip46PayloadJson.encodeUnsignedSignEvent(unsignedEvent)))) {
            is Nip46Response.Success -> SignerSignEventResponseParser.parseAndValidate(
                requestedEvent = unsignedEvent,
                eventJson = response.result,
                signature = null,
                returnedId = null,
                signerPackage = signerPackage,
                crypto = crypto,
            )
            is Nip46Response.AuthChallenge -> SignEventRequestResult.Failed("Remote signer approval required")
            is Nip46Response.Error -> SignEventRequestResult.Failed(remoteSignerReturnedErrorMessage(Nip46Method.SignEvent, response.safeMessage))
            is Nip46Response.TransportFailure -> SignEventRequestResult.Failed(response.safeMessage)
        }
    }

    override fun encryptToSelf(
        plaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult =
        SignerNip44OperationResult.Failed("Remote signer NIP-44 encryption must run in a background request.")

    suspend fun encryptToSelfAsync(
        plaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult = withContext(Dispatchers.Default) {
        if (currentUserPubkey != userPubkey) return@withContext SignerNip44OperationResult.InvalidResponse("Remote signer user pubkey does not match session")
        when (val response = requestAsync(Nip46Method.Nip44Encrypt, listOf(currentUserPubkey, plaintext))) {
            is Nip46Response.Success -> SignerNip44ResponseParser.parseEncryptResult(response.result, plaintext, signerPackage)
            is Nip46Response.AuthChallenge -> SignerNip44OperationResult.Failed("Remote signer approval required")
            is Nip46Response.Error -> SignerNip44OperationResult.Failed(remoteSignerReturnedErrorMessage(Nip46Method.Nip44Encrypt, response.safeMessage))
            is Nip46Response.TransportFailure -> SignerNip44OperationResult.Failed(response.safeMessage)
        }
    }

    override fun decryptFromSelf(
        ciphertext: String,
        expectedPlaintext: String?,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult =
        SignerNip44OperationResult.Failed("Remote signer NIP-44 decryption must run in a background request.")

    suspend fun decryptFromSelfAsync(
        ciphertext: String,
        expectedPlaintext: String?,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult = withContext(Dispatchers.Default) {
        if (currentUserPubkey != userPubkey) return@withContext SignerNip44OperationResult.InvalidResponse("Remote signer user pubkey does not match session")
        when (val response = requestAsync(Nip46Method.Nip44Decrypt, listOf(currentUserPubkey, ciphertext))) {
            is Nip46Response.Success -> SignerNip44ResponseParser.parseDecryptResult(response.result, expectedPlaintext, signerPackage)
            is Nip46Response.AuthChallenge -> SignerNip44OperationResult.Failed("Remote signer approval required")
            is Nip46Response.Error -> SignerNip44OperationResult.Failed(remoteSignerReturnedErrorMessage(Nip46Method.Nip44Decrypt, response.safeMessage))
            is Nip46Response.TransportFailure -> SignerNip44OperationResult.Failed(response.safeMessage)
        }
    }

    private suspend fun getPublicKeyInternal(): SignerPublicKeyRequestResult {
        val cached = userPubkey
        val cachedNpub = userNpub
        if (cached != null && cachedNpub != null) {
            return SignerPublicKeyRequestResult.Success(cached, cachedNpub, "nip46")
        }
        return when (val response = requestAsync(Nip46Method.GetPublicKey)) {
            is Nip46Response.Success -> SignerPublicKeyResponseParser.parse(result = response.result, signerPackage = "nip46")
            is Nip46Response.AuthChallenge -> SignerPublicKeyRequestResult.Failed("Remote signer approval required")
            is Nip46Response.Error -> SignerPublicKeyRequestResult.Failed(remoteSignerReturnedErrorMessage(Nip46Method.GetPublicKey, response.safeMessage))
            is Nip46Response.TransportFailure -> SignerPublicKeyRequestResult.Failed(response.safeMessage)
        }
    }

    private suspend fun requestAsync(method: Nip46Method, params: List<String> = emptyList()): Nip46Response {
        val session = transportSession ?: return Nip46Response.Error(requestIdProvider(), "Remote signer is not connected")
        val request = Nip46RequestPayload(id = requestIdProvider(), method = method.wireName, params = params)
        return withContext(Dispatchers.Default) {
            transport.sendRequest(session, request).getOrElse {
                val message = it.message.orEmpty().redactNip46Secretish(activeTokenSecret)
                val failure = message.toNip46Failure()
                if (failure.reason == Nip46FailureReason.NoSignerResponse ||
                    failure.reason == Nip46FailureReason.SignerRequestTimedOut
                ) {
                    lastState = Nip46ConnectionState.TimedOut
                    lastSafeMessage = failure.safeMessage
                    return@withContext Nip46Response.TransportFailure(request.id, failure.reason, lastSafeMessage)
                }
                lastState = Nip46ConnectionState.Failed
                lastSafeMessage = failure.safeMessage
                Nip46Response.TransportFailure(request.id, failure.reason, lastSafeMessage)
            }
        }
    }

    private fun connectParams(token: Nip46ConnectionToken.Bunker): List<String> {
        val permissions = Nip46Permissions.otherNoteConnectPermissions()
        return buildList {
            add(token.remoteSignerPubkey)
            if (token.secret != null) {
                add(token.secret)
            } else if (permissions.isNotBlank()) {
                add("")
            }
            if (permissions.isNotBlank()) add(permissions)
        }
    }

    private fun applySwitchRelays(response: Nip46Response) {
        val result = (response as? Nip46Response.Success)?.result?.takeIf { it.isNotBlank() && it != "null" } ?: return
        val parsed = runCatching {
            val array = Json.parseToJsonElement(result) as? JsonArray ?: error("not a relay list")
            array.map { it.jsonPrimitive.content }
                .map { relay -> normalizeRelayUrl(relay).getOrThrow() }
                .distinct()
                .also { relays ->
                    require(relays.isNotEmpty()) { "empty relay list" }
                    require(relays.all { it.startsWith("wss://") }) { "non-wss relay" }
                }
        }.getOrElse {
            lastSafeMessage = "Remote signer returned invalid relay switch response"
            return
        }
        val session = transportSession ?: return
        val merged = (session.relays + parsed).distinct()
        val newSources = parsed
            .filterNot { it in session.relaySources }
            .associateWith { "switch_relays_advisory" }
        transportSession = session.copy(
            relays = merged,
            relaySource = "${session.relaySource}+switch_relays_advisory",
            relaySources = session.relaySources + newSources,
        )
        lastSafeMessage = "Remote signer relay switch response recorded as advisory"
    }

    private fun fail(reason: String): Nip46ConnectResult.Failed {
        val safeReason = reason.redactNip46Secretish(activeTokenSecret)
        transportSession = null
        userPubkey = null
        userNpub = null
        activeTokenSecret = null
        lastState = Nip46ConnectionState.Failed
        lastSafeMessage = safeReason
        return Nip46ConnectResult.Failed(lastSafeMessage)
    }
}

fun NostrClient.nip46RemoteSigner(): Nip46RemoteSigner? {
    val crypto = ProductionNostrCryptoFactory.createOrNull() ?: return null
    return Nip46RemoteSigner(RelayNip46RequestTransport(this, crypto), crypto)
}

private val Nip46RequestPayload.safeMethod: String
    get() = method.take(48)

private val Nip46RequestPayload.safeIdPrefix: String
    get() = id.take(12)

private fun NostrEvent.targetsClientPubkey(clientPubkey: String): Boolean {
    val pTags = tags.mapNotNull { tag ->
        tag.takeIf { it.size >= 2 && it[0] == "p" }?.get(1)
    }
    return pTags.isEmpty() || pTags.any { it == clientPubkey }
}

private fun NostrEvent.isRelevantResponseCandidate(session: Nip46TransportSession, since: Long): Boolean =
    kind == Nip46EventKind &&
        pubkey == session.remoteSignerPubkey &&
        targetsClientPubkey(session.clientPubkey) &&
        createdAt >= since

private fun Nip46ResponsePayload.toNip46Response(): Nip46Response {
    val error = error?.takeIf { it.isNotBlank() }
    val result = result?.takeIf { it.isNotBlank() }
    return when {
        result == "auth_url" && error != null -> Nip46Response.AuthChallenge(id, error.take(180))
        error != null -> Nip46Response.Error(id, error.take(180))
        result != null -> Nip46Response.Success(id, result)
        else -> Nip46Response.Error(id, "Remote signer returned an empty response")
    }
}

private fun List<com.libertasprimordium.othernote.domain.RelayStatus>.safeStatusSummary(write: Boolean): String =
    joinToString("|") { status ->
        val flag = if (write) "writable=${status.writable}" else "readable=${status.readable}"
        "${status.url} $flag ${status.message.redactNip46Secretish().take(140)}"
    }.take(600)

private fun List<String>.safeRelayList(): String =
    joinToString("|") { it.take(120) }.take(600)

private data class Nip46RelayHealthKey(
    val clientPubkey: String,
    val remoteSignerPubkey: String,
    val relay: String,
)

private data class Nip46RelayHealth(
    val source: String,
    val publishAccepted: Boolean,
    val publishRejected: Boolean,
    val responseMatched: Boolean,
    val latencyMs: Long,
    val failureReason: String?,
    val successCount: Int,
    val updatedAtMs: Long,
)

private fun Nip46TransportSession.healthKey(relay: String): Nip46RelayHealthKey =
    Nip46RelayHealthKey(
        clientPubkey = clientPubkey,
        remoteSignerPubkey = remoteSignerPubkey,
        relay = normalizeRelayUrl(relay).getOrNull() ?: relay,
    )

private fun Nip46TransportSession.sourceForRelay(relay: String): String =
    relaySources[relay]
        ?: relaySources[normalizeRelayUrl(relay).getOrNull()]
        ?: relaySource

private fun List<Nip46LiveRelayOutcome>.safeRelayOutcomeSummary(session: Nip46TransportSession): String =
    joinToString("|") { outcome ->
        val status = outcome.publishStatus
        val writable = status?.writable?.toString() ?: "unknown"
        val reason = outcome.failureReason ?: "matched"
        "${outcome.relay.take(120)} source=${session.sourceForRelay(outcome.relay).take(48)} " +
            "subscribed=${outcome.subscribed} writable=$writable matched=${outcome.responseMatched} " +
            "latency_ms=${outcome.latencyMs} reason=${reason.take(48)} candidates=${outcome.candidateEventCount}"
    }.take(800)

private data class Nip46MappedFailure(
    val reason: Nip46FailureReason,
    val safeMessage: String,
)

private fun String.toNip46Failure(): Nip46MappedFailure {
    val detail = take(520)
    val method = extractNip46Method()
    return when {
        contains("relay_publish_failed", ignoreCase = true) && contains("outcome=rejected", ignoreCase = true) ->
            Nip46MappedFailure(
                Nip46FailureReason.SignerRelayPublishRejected,
                "Remote signer relay rejected the ${method.requestLabel()} request. Check the bunker relay settings. ($detail)",
            )
        contains("relay_publish_failed", ignoreCase = true) && contains("outcome=timeout", ignoreCase = true) ->
            Nip46MappedFailure(
                Nip46FailureReason.SignerRelayPublishTimedOut,
                "Remote signer relay publish timed out for the ${method.requestLabel()} request. ($detail)",
            )
        contains("relay_publish_failed", ignoreCase = true) && contains("connect_failed", ignoreCase = true) ->
            Nip46MappedFailure(
                Nip46FailureReason.SignerRelayConnectionFailed,
                "Remote signer relay connection failed for the ${method.requestLabel()} request. ($detail)",
            )
        contains("relay_publish_failed", ignoreCase = true) ->
            Nip46MappedFailure(
                Nip46FailureReason.SignerTransportFailed,
                "Remote signer relay publish failed for the ${method.requestLabel()} request. ($detail)",
            )
        contains("response_fetch_timed_out", ignoreCase = true) ->
            Nip46MappedFailure(
                Nip46FailureReason.NoSignerResponse,
                method.timeoutMessage(detail),
            )
        contains("timed out", ignoreCase = true) || contains("timeout", ignoreCase = true) ->
            Nip46MappedFailure(
                Nip46FailureReason.SignerRequestTimedOut,
                method.timeoutMessage(detail),
            )
        contains("response_fetch_failed", ignoreCase = true) ->
            Nip46MappedFailure(
                Nip46FailureReason.NoSignerResponse,
                "Remote signer response fetch failed for the ${method.requestLabel()} request. ($detail)",
            )
        contains("response_decrypt_failed", ignoreCase = true) ->
            Nip46MappedFailure(
                Nip46FailureReason.SignerResponseDecryptFailed,
                "Remote signer response could not be decrypted for the ${method.requestLabel()} request. ($detail)",
            )
        contains("response_id_mismatch", ignoreCase = true) ->
            Nip46MappedFailure(
                Nip46FailureReason.SignerResponseIdMismatch,
                "Remote signer response id did not match the ${method.requestLabel()} request. ($detail)",
            )
        contains("no_matching_response", ignoreCase = true) ->
            Nip46MappedFailure(
                Nip46FailureReason.NoSignerResponse,
                "Remote signer returned no matching response for the ${method.requestLabel()} request. ($detail)",
            )
        isNotBlank() ->
            Nip46MappedFailure(Nip46FailureReason.SignerTransportFailed, "Remote signer ${method.requestLabel()} request failed. ($detail)")
        else ->
            Nip46MappedFailure(Nip46FailureReason.SignerTransportFailed, "Remote signer request failed")
    }
}

private fun remoteSignerReturnedErrorMessage(method: Nip46Method, message: String): String =
    "Remote signer returned ${method.errorLabel()} error: ${message.redactNip46Secretish()}"

private fun String.extractNip46Method(): String? =
    Regex("""(?:^|\s)method=([A-Za-z0-9_:-]+)""").find(this)?.groupValues?.getOrNull(1)

private fun String?.requestLabel(): String = when (this) {
    Nip46Method.Connect.wireName -> "connect"
    Nip46Method.GetPublicKey.wireName -> "public-key"
    Nip46Method.Ping.wireName -> "ping"
    Nip46Method.Nip44Encrypt.wireName -> "encryption"
    Nip46Method.Nip44Decrypt.wireName -> "decryption"
    Nip46Method.SignEvent.wireName -> "signing"
    Nip46Method.SwitchRelays.wireName -> "relay-switch"
    null -> "remote signer"
    else -> this.take(48)
}

private fun Nip46Method.errorLabel(): String = when (this) {
    Nip46Method.Connect -> "connect"
    Nip46Method.GetPublicKey -> "public-key"
    Nip46Method.Ping -> "ping"
    Nip46Method.Nip44Encrypt -> "encryption"
    Nip46Method.Nip44Decrypt -> "decryption"
    Nip46Method.SignEvent -> "signing"
    Nip46Method.SwitchRelays -> "relay-switch"
}

private fun String?.timeoutMessage(detail: String): String = when (this) {
    Nip46Method.Nip44Encrypt.wireName -> "Remote signer did not respond to encryption request. ($detail)"
    Nip46Method.SignEvent.wireName -> "Remote signer did not respond to signing request. ($detail)"
    Nip46Method.Nip44Decrypt.wireName -> "Remote signer did not respond to decryption request. ($detail)"
    Nip46Method.Connect.wireName -> "Remote signer did not respond to connect request. ($detail)"
    Nip46Method.GetPublicKey.wireName -> "Remote signer did not respond to public-key request. ($detail)"
    Nip46Method.Ping.wireName -> "Remote signer did not respond to ping request. ($detail)"
    Nip46Method.SwitchRelays.wireName -> "Remote signer did not respond to relay-switch request. ($detail)"
    else -> "Remote signer did not respond before timeout. ($detail)"
}

private fun String.redactNip46Secretish(activeSecret: String? = null): String {
    val withoutParam = replace(Regex("secret=([^&\\s]+)"), "secret=redacted")
    val withoutActive = activeSecret
        ?.takeIf { it.isNotBlank() }
        ?.let { withoutParam.replace(it, "redacted") }
        ?: withoutParam
    return withoutActive.take(600)
}
