package com.libertasprimordium.othernote.security

import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrFilter
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.util.nowMs
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import com.libertasprimordium.othernote.util.stableRandomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

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
)

class RelayNip46RequestTransport(
    private val client: NostrClient,
    private val crypto: NostrCrypto,
    private val requestTimeoutMs: Long = 12_000,
    private val pollIntervalMs: Long = 750,
) : Nip46RequestTransport {
    override suspend fun sendRequest(session: Nip46TransportSession, request: Nip46RequestPayload): Result<Nip46Response> = runCatching {
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
        val event = crypto.sign(unsigned, session.clientPrivateKey).getOrThrow()
        val publish = client.publish(session.relays, event)
        if (!publish.anySucceeded) {
            error("stage=relay_publish_failed statuses=${publish.statuses.safeStatusSummary(write = true)}")
        }
        var attempts = 0
        var responseEventCount = 0
        var decryptFailureCount = 0
        var idMismatchCount = 0
        var lastFetchStatuses = emptyList<com.libertasprimordium.othernote.domain.RelayStatus>()
        val responseEvent = withTimeoutOrNull(requestTimeoutMs) {
            while (true) {
                val fetch = fetchResponses(session)
                attempts++
                lastFetchStatuses = fetch.statuses
                val candidateEvents = fetch.events
                    .filter { it.kind == Nip46EventKind && it.pubkey == session.remoteSignerPubkey }
                responseEventCount += candidateEvents.size
                val matching = candidateEvents.firstNotNullOfOrNull { event ->
                    decryptResponse(session, event, request.id)
                        .onFailure { failure ->
                            if (failure.message.orEmpty().contains("id mismatch", ignoreCase = true)) {
                                idMismatchCount++
                            } else {
                                decryptFailureCount++
                            }
                        }
                        .getOrNull()
                        ?.let { event to it }
                    }
                if (matching != null) return@withTimeoutOrNull matching.second
                delay(pollIntervalMs)
            }
            error("unreachable")
        }
        responseEvent ?: when {
            lastFetchStatuses.isNotEmpty() && lastFetchStatuses.none { it.readable } ->
                error("stage=response_fetch_failed attempts=$attempts statuses=${lastFetchStatuses.safeStatusSummary(write = false)}")
            idMismatchCount > 0 ->
                error("stage=response_id_mismatch events=$responseEventCount count=$idMismatchCount")
            decryptFailureCount > 0 ->
                error("stage=response_decrypt_failed events=$responseEventCount count=$decryptFailureCount")
            responseEventCount == 0 ->
                error("stage=response_fetch_timed_out reason=no_matching_response attempts=$attempts statuses=${lastFetchStatuses.safeStatusSummary(write = false)}")
            else ->
                error("stage=no_matching_response attempts=$attempts events=$responseEventCount")
        }
    }

    private suspend fun fetchResponses(session: Nip46TransportSession): RelayFetchResult =
        client.fetchEvents(
            session.relays,
            NostrFilter(
                authors = listOf(session.remoteSignerPubkey),
                kinds = listOf(Nip46EventKind),
                tTags = emptyList(),
                pTags = listOf(session.clientPubkey),
                limit = 50,
            ),
        )

    private fun decryptResponse(session: Nip46TransportSession, event: NostrEvent, expectedRequestId: String): Result<Nip46Response> = runCatching {
        val plaintext = crypto.decryptFromSelf(
            ciphertext = event.content,
            privateKey = session.clientPrivateKey,
            publicKey = NostrPublicKey(session.remoteSignerPubkey, ""),
        ).getOrThrow()
        Nip46PayloadJson.decodeResponse(plaintext, expectedRequestId).getOrThrow()
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
            is Nip46Response.Error -> fail("Remote signer rejected connect: ${connectResponse.safeMessage}")
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
            is Nip46Response.Error -> SignEventRequestResult.Failed("Remote signer returned error: ${response.safeMessage.redactNip46Secretish()}")
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
            is Nip46Response.Error -> SignerNip44OperationResult.Failed("Remote signer returned error: ${response.safeMessage.redactNip46Secretish()}")
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
            is Nip46Response.Error -> SignerNip44OperationResult.Failed("Remote signer returned error: ${response.safeMessage.redactNip46Secretish()}")
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
            is Nip46Response.Error -> SignerPublicKeyRequestResult.Failed("Remote signer returned error: ${response.safeMessage.redactNip46Secretish()}")
        }
    }

    private suspend fun requestAsync(method: Nip46Method, params: List<String> = emptyList()): Nip46Response {
        val session = transportSession ?: return Nip46Response.Error(requestIdProvider(), "Remote signer is not connected")
        val request = Nip46RequestPayload(id = requestIdProvider(), method = method.wireName, params = params)
        return withContext(Dispatchers.Default) {
            transport.sendRequest(session, request).getOrElse {
                val message = it.message.orEmpty().redactNip46Secretish(activeTokenSecret)
                if (message.contains("response_fetch_timed_out", ignoreCase = true) ||
                    message.contains("timed out", ignoreCase = true) ||
                    message.contains("timeout", ignoreCase = true)
                ) {
                    lastState = Nip46ConnectionState.TimedOut
                    lastSafeMessage = when {
                        message.contains("response_fetch_timed_out", ignoreCase = true) ->
                            "Remote signer response fetch timed out (${message.take(140)})"
                        else -> "Remote signer request timed out"
                    }
                    return@withContext Nip46Response.Error(request.id, lastSafeMessage)
                }
                lastState = Nip46ConnectionState.Failed
                lastSafeMessage = when {
                    message.contains("relay_publish_failed", ignoreCase = true) ->
                        "Remote signer relay publish failed (${message.take(140)})"
                    message.contains("response_fetch_failed", ignoreCase = true) ->
                        "Remote signer response fetch failed (${message.take(140)})"
                    message.contains("response_decrypt_failed", ignoreCase = true) ->
                        "Remote signer response decrypt failed (${message.take(140)})"
                    message.contains("response_id_mismatch", ignoreCase = true) ->
                        "Remote signer response id mismatch (${message.take(140)})"
                    message.contains("no_matching_response", ignoreCase = true) ->
                        "Remote signer returned no matching response (${message.take(140)})"
                    message.isNotBlank() -> "Remote signer request failed (${message.take(140)})"
                    else -> "Remote signer request failed"
                }
                Nip46Response.Error(request.id, lastSafeMessage)
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
        transportSession = session.copy(relays = parsed)
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

private fun List<com.libertasprimordium.othernote.domain.RelayStatus>.safeStatusSummary(write: Boolean): String =
    joinToString("|") { status ->
        val flag = if (write) "writable=${status.writable}" else "readable=${status.readable}"
        "${status.url} $flag ${status.message.redactNip46Secretish().take(140)}"
    }.take(600)

private fun String.redactNip46Secretish(activeSecret: String? = null): String {
    val withoutParam = replace(Regex("secret=([^&\\s]+)"), "secret=redacted")
    val withoutActive = activeSecret
        ?.takeIf { it.isNotBlank() }
        ?.let { withoutParam.replace(it, "redacted") }
        ?: withoutParam
    return withoutActive.take(180)
}
