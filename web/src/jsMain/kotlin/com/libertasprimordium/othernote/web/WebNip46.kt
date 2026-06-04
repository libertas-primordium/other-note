package com.libertasprimordium.othernote.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val Nip46EventKind = 24133
private const val ResponseClockSkewSeconds = 60L
private const val RequestTimeoutMs = 15_000
private const val RelaySubscriptionId = "other-note-nip46"
private const val WebNip46RequestedPermissions = "get_public_key,sign_event:30078,sign_event:10002,nip44_encrypt,nip44_decrypt,ping"
private const val TransportPrivateKeyByteCount = 32
private const val TransportKeyGenerationAttempts = 8

external class WebSocket(url: String) {
    var onopen: ((dynamic) -> Unit)?
    var onmessage: ((WebSocketMessageEvent) -> Unit)?
    var onerror: ((dynamic) -> Unit)?
    var onclose: ((dynamic) -> Unit)?
    fun send(data: String)
    fun close()
}

external interface WebSocketMessageEvent {
    val data: String
}

external class Uint8Array(length: Int)

external interface BrowserCrypto {
    fun getRandomValues(values: Uint8Array): Uint8Array
}

external interface BrowserGlobal {
    val crypto: BrowserCrypto?
}

@JsModule("nostr-tools")
@JsNonModule
external object NostrTools {
    fun getPublicKey(privateKey: Uint8Array): String
    fun finalizeEvent(eventTemplate: dynamic, privateKey: Uint8Array): dynamic
    fun validateEvent(event: dynamic): Boolean
    fun verifyEvent(event: dynamic): Boolean
    val nip44: dynamic
}

external val globalThis: BrowserGlobal
external fun setTimeout(handler: () -> Unit, timeout: Int): Int
external fun clearTimeout(handle: Int)

data class WebNip46BunkerToken(
    val remoteSignerPubkey: String,
    val relays: List<String>,
    val secret: String?,
)

sealed interface WebNip46TokenParseResult {
    data class Valid(val token: WebNip46BunkerToken) : WebNip46TokenParseResult
    data class Invalid(val safeMessage: String) : WebNip46TokenParseResult
}

data class WebNip46Session(
    val clientPrivateKey: Uint8Array,
    val clientPubkey: String,
    val remoteSignerPubkey: String,
    val relays: List<String>,
)

sealed interface WebNip46ConnectResult {
    data class Connected(
        val userPubkey: String,
        val session: WebNip46Session? = null,
    ) : WebNip46ConnectResult
    data class Failed(val safeMessage: String) : WebNip46ConnectResult
}

sealed interface WebNip46DecryptResult {
    data class Decrypted(val plaintext: String) : WebNip46DecryptResult
    data class Failed(val safeMessage: String) : WebNip46DecryptResult
}

internal sealed interface WebNip46EncryptResult {
    data class Encrypted(val ciphertext: String) : WebNip46EncryptResult
    data class Failed(val safeMessage: String) : WebNip46EncryptResult
}

internal sealed interface WebNip46NoteSignResult {
    data class Signed(val event: WebNostrEvent) : WebNip46NoteSignResult
    data class Failed(val safeMessage: String) : WebNip46NoteSignResult
}

sealed interface WebNip46RelayResponse {
    data class Success(val result: String) : WebNip46RelayResponse
    data class Error(val safeMessage: String) : WebNip46RelayResponse
    data class AuthChallenge(val safeUrl: String) : WebNip46RelayResponse
}

enum class WebNip46ResponseIgnoreReason {
    WrongKind,
    UnexpectedPubkey,
    WrongRecipient,
    TooOld,
    DecryptionFailed,
    IdMismatch,
}

internal sealed interface WebNip46ResponseProcessingResult {
    data class Response(val response: WebNip46RelayResponse) : WebNip46ResponseProcessingResult
    data class Ignored(val reason: WebNip46ResponseIgnoreReason) : WebNip46ResponseProcessingResult
}

enum class WebNip46RequestFailureReason {
    MissingRemoteSignerPubkey,
    MissingClientCommunicationKey,
    JsonSerializationFailed,
    EncryptionFailed,
    SigningFailed,
    EventSerializationFailed,
}

internal sealed interface WebNip46RequestBuildResult {
    data class Success(val event: WebNostrEvent) : WebNip46RequestBuildResult
    data class Failed(
        val reason: WebNip46RequestFailureReason,
        val safeMessage: String,
    ) : WebNip46RequestBuildResult
}

internal data class WebNip46RequestBuilder(
    val serializeRequest: (WebNip46RequestPayload) -> Result<String> = { request ->
        runCatching { requestJson(request) }
    },
    val encryptRequest: (WebNip46Session, String) -> Result<String> = ::encryptRequestPayload,
    val signRequest: (WebNip46Session, Long, List<List<String>>, String) -> Result<WebNostrEvent> = ::signRequestEvent,
    val serializeEvent: (WebNostrEvent) -> Result<String> = { event ->
        runCatching { eventObject(event).toString() }
    },
)

data class WebNip46RequestPayload(
    val id: String,
    val method: String,
    val params: List<String> = emptyList(),
)

enum class WebNip46KeyFailureReason {
    MissingWebCrypto,
    RandomGenerationFailed,
    InvalidGeneratedPrivateKey,
    PublicKeyDerivationFailed,
}

internal sealed interface WebNip46KeyGenerationResult {
    data class Success(val keyPair: WebNip46KeyPair) : WebNip46KeyGenerationResult
    data class Failed(
        val reason: WebNip46KeyFailureReason,
        val safeMessage: String,
    ) : WebNip46KeyGenerationResult
}

internal sealed interface WebNip46RandomBytesResult {
    data class Success(val bytes: Uint8Array) : WebNip46RandomBytesResult
    data class Failed(
        val reason: WebNip46KeyFailureReason,
        val safeMessage: String,
    ) : WebNip46RandomBytesResult
}

class WebNip46RemoteSigner {
    private var activeTransport: WebNip46RelayTransport? = null
    private var activeSession: WebNip46Session? = null

    fun connectWithBunkerToken(
        rawToken: String,
        onProgress: (WebNip46Status, String) -> Unit,
        onResult: (WebNip46ConnectResult) -> Unit,
    ) {
        disconnect()
        val token = when (val parsed = parseWebNip46BunkerToken(rawToken)) {
            is WebNip46TokenParseResult.Valid -> parsed.token
            is WebNip46TokenParseResult.Invalid -> {
                onResult(WebNip46ConnectResult.Failed(parsed.safeMessage))
                return
            }
        }

        onProgress(WebNip46Status.PreparingConnection, "Creating in-memory remote signer transport key.")
        val session = when (val created = createTransportSession(token)) {
            is WebNip46SessionCreationResult.Success -> created.session
            is WebNip46SessionCreationResult.Failed -> {
                onResult(WebNip46ConnectResult.Failed(created.safeMessage))
                return
            }
        }
        val requestId = when (val generated = secureRandomHex(16)) {
            is WebNip46RandomHexResult.Success -> generated.hex
            is WebNip46RandomHexResult.Failed -> {
                disconnect()
                onResult(WebNip46ConnectResult.Failed(generated.safeMessage))
                return
            }
        }
        val connectRequest = WebNip46RequestPayload(
            id = requestId,
            method = "connect",
            params = connectParams(token),
        )

        activeSession = session
        val transport = WebNip46RelayTransport()
        activeTransport = transport

        onProgress(WebNip46Status.WaitingForSigner, "Waiting for remote signer approval.")
        transport.sendRequest(
            session = session,
            request = connectRequest,
        ) { connectResponse ->
            when (connectResponse) {
                is WebNip46RelayResponse.AuthChallenge -> {
                    disconnect()
                    onResult(WebNip46ConnectResult.Failed("Remote signer approval required. Open the signer approval link and try again."))
                }
                is WebNip46RelayResponse.Error -> {
                    disconnect()
                    onResult(WebNip46ConnectResult.Failed(connectResponse.safeMessage))
                }
                is WebNip46RelayResponse.Success -> {
                    onProgress(WebNip46Status.RequestingPublicKey, "Reading account public key from remote signer.")
                    requestPublicKey(session, onResult)
                }
            }
        }
    }

    fun disconnect() {
        activeTransport?.close()
        activeTransport = null
        activeSession = null
    }

    fun resumeWithSession(
        session: WebNip46Session,
        onProgress: (WebNip46Status, String) -> Unit,
        onResult: (WebNip46ConnectResult) -> Unit,
    ) {
        disconnect()
        activeSession = session
        activeTransport = WebNip46RelayTransport()
        onProgress(WebNip46Status.RequestingPublicKey, "Checking remembered remote signer session.")
        requestPublicKey(session, onResult)
    }

    fun activeSessionSnapshot(): WebNip46Session? = activeSession

    private fun requestPublicKey(
        session: WebNip46Session,
        onResult: (WebNip46ConnectResult) -> Unit,
    ) {
        val transport = activeTransport ?: WebNip46RelayTransport().also { activeTransport = it }
        val requestId = when (val generated = secureRandomHex(16)) {
            is WebNip46RandomHexResult.Success -> generated.hex
            is WebNip46RandomHexResult.Failed -> {
                disconnect()
                onResult(WebNip46ConnectResult.Failed(generated.safeMessage))
                return
            }
        }
        val request = WebNip46RequestPayload(
            id = requestId,
            method = "get_public_key",
        )
        transport.sendRequest(
            session = session,
            request = request,
        ) { response ->
            when (response) {
                is WebNip46RelayResponse.AuthChallenge -> {
                    disconnect()
                    onResult(WebNip46ConnectResult.Failed("Remote signer approval required for public-key request."))
                }
                is WebNip46RelayResponse.Error -> {
                    disconnect()
                    onResult(WebNip46ConnectResult.Failed(response.safeMessage))
                }
                is WebNip46RelayResponse.Success -> {
                    val validation = validateNip07PublicKey(response.result)
                    when (validation) {
                        is Nip07PublicKeyResult.Valid -> onResult(WebNip46ConnectResult.Connected(validation.publicKeyHex, session))
                        is Nip07PublicKeyResult.Invalid -> {
                            disconnect()
                            onResult(WebNip46ConnectResult.Failed(validation.message))
                        }
                    }
                }
            }
        }
    }

    fun decryptNotePayload(
        userPubkey: String,
        ciphertext: String,
        onResult: (WebNip46DecryptResult) -> Unit,
    ) {
        val session = activeSession
        if (session == null || !isValidHexPublicKey(userPubkey)) {
            onResult(WebNip46DecryptResult.Failed(WebNoteCopy.NoteDecryptUnavailable))
            return
        }
        val requestId = when (val generated = secureRandomHex(16)) {
            is WebNip46RandomHexResult.Success -> generated.hex
            is WebNip46RandomHexResult.Failed -> {
                onResult(WebNip46DecryptResult.Failed(generated.safeMessage))
                return
            }
        }
        val request = WebNip46RequestPayload(
            id = requestId,
            method = "nip44_decrypt",
            params = listOf(userPubkey, ciphertext),
        )
        val transport = activeTransport ?: WebNip46RelayTransport().also { activeTransport = it }
        transport.sendRequest(session, request) { response ->
            when (response) {
                is WebNip46RelayResponse.Success -> onResult(WebNip46DecryptResult.Decrypted(response.result))
                is WebNip46RelayResponse.Error -> onResult(WebNip46DecryptResult.Failed(response.safeMessage))
                is WebNip46RelayResponse.AuthChallenge ->
                    onResult(WebNip46DecryptResult.Failed("Remote signer approval required for decryption."))
            }
        }
    }

    internal fun encryptNotePayload(
        userPubkey: String,
        plaintext: String,
        onResult: (WebNip46EncryptResult) -> Unit,
    ) {
        val session = activeSession
        if (session == null || !isValidHexPublicKey(userPubkey)) {
            onResult(WebNip46EncryptResult.Failed(WebNoteCopy.CrudCapabilityUnavailable))
            return
        }
        val requestId = when (val generated = secureRandomHex(16)) {
            is WebNip46RandomHexResult.Success -> generated.hex
            is WebNip46RandomHexResult.Failed -> {
                onResult(WebNip46EncryptResult.Failed(generated.safeMessage))
                return
            }
        }
        val request = WebNip46RequestPayload(
            id = requestId,
            method = "nip44_encrypt",
            params = listOf(userPubkey, plaintext),
        )
        val transport = activeTransport ?: WebNip46RelayTransport().also { activeTransport = it }
        transport.sendRequest(session, request) { response ->
            when (response) {
                is WebNip46RelayResponse.Success -> onResult(WebNip46EncryptResult.Encrypted(response.result))
                is WebNip46RelayResponse.Error -> onResult(WebNip46EncryptResult.Failed(response.safeMessage))
                is WebNip46RelayResponse.AuthChallenge ->
                    onResult(WebNip46EncryptResult.Failed("Remote signer approval required for encryption."))
            }
        }
    }

    internal fun signNoteEvent(
        unsignedEvent: WebUnsignedNoteEvent,
        onResult: (WebNip46NoteSignResult) -> Unit,
    ) {
        val session = activeSession
        if (session == null || !isValidHexPublicKey(unsignedEvent.pubkey)) {
            onResult(WebNip46NoteSignResult.Failed(WebNoteCopy.CrudCapabilityUnavailable))
            return
        }
        val requestId = when (val generated = secureRandomHex(16)) {
            is WebNip46RandomHexResult.Success -> generated.hex
            is WebNip46RandomHexResult.Failed -> {
                onResult(WebNip46NoteSignResult.Failed(generated.safeMessage))
                return
            }
        }
        val request = WebNip46RequestPayload(
            id = requestId,
            method = "sign_event",
            params = listOf(unsignedEvent.toSignEventJson()),
        )
        val transport = activeTransport ?: WebNip46RelayTransport().also { activeTransport = it }
        transport.sendRequest(session, request) { response ->
            when (response) {
                is WebNip46RelayResponse.Success -> {
                    val event = parseWebSignedEventJson(response.result)
                    if (event == null) {
                        onResult(WebNip46NoteSignResult.Failed(WebNoteCopy.SignFailed))
                    } else {
                        onResult(WebNip46NoteSignResult.Signed(event))
                    }
                }
                is WebNip46RelayResponse.Error -> onResult(WebNip46NoteSignResult.Failed(response.safeMessage))
                is WebNip46RelayResponse.AuthChallenge ->
                    onResult(WebNip46NoteSignResult.Failed("Remote signer approval required for signing."))
            }
        }
    }

    private fun createTransportSession(token: WebNip46BunkerToken): WebNip46SessionCreationResult =
        when (val generated = generateWebNip46TransportKey()) {
            is WebNip46KeyGenerationResult.Success -> WebNip46SessionCreationResult.Success(
                WebNip46Session(
                    clientPrivateKey = generated.keyPair.clientPrivateKey,
                    clientPubkey = generated.keyPair.clientPubkey,
                    remoteSignerPubkey = token.remoteSignerPubkey,
                    relays = token.relays,
                ),
            )
            is WebNip46KeyGenerationResult.Failed -> WebNip46SessionCreationResult.Failed(generated.safeMessage)
        }

    private fun connectParams(token: WebNip46BunkerToken): List<String> =
        buildList {
            add(token.remoteSignerPubkey)
            if (token.secret != null) {
                add(token.secret)
            } else {
                add("")
            }
            add(WebNip46RequestedPermissions)
        }
}

private sealed interface WebNip46SessionCreationResult {
    data class Success(val session: WebNip46Session) : WebNip46SessionCreationResult
    data class Failed(val safeMessage: String) : WebNip46SessionCreationResult
}

private sealed interface WebNip46RandomHexResult {
    data class Success(val hex: String) : WebNip46RandomHexResult
    data class Failed(val safeMessage: String) : WebNip46RandomHexResult
}

class WebNip46RelayTransport(
    private val timeoutMs: Int = RequestTimeoutMs,
) {
    private val sockets = mutableListOf<WebSocket>()
    private var completed = false
    private var timeoutHandle: Int? = null
    private var activeGeneration = 0

    fun sendRequest(
        session: WebNip46Session,
        request: WebNip46RequestPayload,
        onResult: (WebNip46RelayResponse) -> Unit,
    ) {
        closeSockets()
        completed = false
        val generation = ++activeGeneration
        val requestEvent = when (val built = buildRequestEvent(session, request)) {
            is WebNip46RequestBuildResult.Success -> built.event
            is WebNip46RequestBuildResult.Failed -> {
                complete(generation, WebNip46RelayResponse.Error(built.safeMessage), onResult)
                return
            }
        }
        val since = requestEvent.createdAt - ResponseClockSkewSeconds
        val tracker = WebNip46RelayRequestTracker(session.relays.size, request.method)
        timeoutHandle = setTimeout(
            {
                complete(generation, WebNip46RelayResponse.Error(tracker.timeoutMessage()), onResult)
            },
            timeoutMs,
        )

        session.relays.forEach { relay ->
            val attempt = WebNip46RelayAttemptState()
            val socket = WebSocket(relay)
            sockets += socket
            socket.onopen = {
                if (isCurrent(generation)) {
                    tracker.markOpened(attempt)
                    runCatching {
                        socket.send(requestMessage(RelaySubscriptionId, session, since))
                        socket.send(publishEventMessage(requestEvent))
                        tracker.markPublished(attempt)
                    }.onFailure {
                        markRelayFailed(generation, attempt, tracker, onResult)
                    }
                }
            }
            socket.onmessage = { message ->
                val raw = message.data
                handleRelayMessage(generation, raw, session, request, since, attempt, tracker, onResult)
            }
            socket.onerror = {
                markRelayFailed(generation, attempt, tracker, onResult)
            }
            socket.onclose = {
                markRelayFailed(generation, attempt, tracker, onResult)
            }
        }
    }

    fun close() {
        activeGeneration += 1
        completed = true
        closeSockets()
    }

    private fun closeSockets() {
        timeoutHandle?.let(::clearTimeout)
        timeoutHandle = null
        sockets.forEach { socket -> runCatching { socket.close() } }
        sockets.clear()
    }

    private fun handleRelayMessage(
        generation: Int,
        raw: String,
        session: WebNip46Session,
        request: WebNip46RequestPayload,
        since: Long,
        attempt: WebNip46RelayAttemptState,
        tracker: WebNip46RelayRequestTracker,
        onResult: (WebNip46RelayResponse) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        val parsed = parseRelayMessage(raw) ?: return
        when (parsed) {
            is WebRelayMessage.Event -> {
                when (val processed = processResponseEvent(session, parsed.event, request.id, since)) {
                    is WebNip46ResponseProcessingResult.Response -> complete(generation, processed.response, onResult)
                    is WebNip46ResponseProcessingResult.Ignored -> Unit
                }
            }
            is WebRelayMessage.Ok -> {
                if (!parsed.accepted) {
                    val message = tracker.markPublishRejected(attempt) ?: return
                    complete(generation, WebNip46RelayResponse.Error(message), onResult)
                }
            }
            is WebRelayMessage.Closed ->
                markRelayFailed(generation, attempt, tracker, onResult)
            WebRelayMessage.Ignored -> Unit
        }
    }

    private fun markRelayFailed(
        generation: Int,
        attempt: WebNip46RelayAttemptState,
        tracker: WebNip46RelayRequestTracker,
        onResult: (WebNip46RelayResponse) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        val message = tracker.markFailed(attempt) ?: return
        complete(generation, WebNip46RelayResponse.Error(message), onResult)
    }

    private fun complete(
        generation: Int,
        response: WebNip46RelayResponse,
        onResult: (WebNip46RelayResponse) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        completed = true
        activeGeneration += 1
        closeSockets()
        onResult(response)
    }

    private fun isCurrent(generation: Int): Boolean =
        !completed && generation == activeGeneration
}

internal class WebNip46RelayAttemptState {
    var opened: Boolean = false
    var published: Boolean = false
    var failed: Boolean = false
}

internal class WebNip46RelayRequestTracker(
    private val totalRelays: Int,
    private val method: String,
) {
    var openedRelays: Int = 0
        private set
    var publishedRelays: Int = 0
        private set
    private var failedRelays: Int = 0

    fun markOpened(attempt: WebNip46RelayAttemptState) {
        if (!attempt.opened) {
            attempt.opened = true
            openedRelays += 1
        }
    }

    fun markPublished(attempt: WebNip46RelayAttemptState) {
        markOpened(attempt)
        if (!attempt.published) {
            attempt.published = true
            publishedRelays += 1
        }
    }

    fun markFailed(attempt: WebNip46RelayAttemptState): String? {
        if (attempt.failed) return null
        attempt.failed = true
        failedRelays += 1
        if (failedRelays < totalRelays.coerceAtLeast(1)) return null
        return aggregateFailureMessage()
    }

    fun markPublishRejected(attempt: WebNip46RelayAttemptState): String? {
        if (attempt.failed) return null
        attempt.failed = true
        failedRelays += 1
        if (failedRelays < totalRelays.coerceAtLeast(1)) return null
        return WebAuthCopy.Nip46RelayPublishFailed
    }

    fun timeoutMessage(): String =
        if (method == "get_public_key") {
            WebAuthCopy.Nip46PublicKeyTimeout
        } else {
            WebAuthCopy.Nip46SignerTimeout
        }

    private fun aggregateFailureMessage(): String =
        when {
            openedRelays == 0 -> WebAuthCopy.Nip46ConnectionFailed
            publishedRelays == 0 -> WebAuthCopy.Nip46RelayPublishFailed
            method == "get_public_key" -> WebAuthCopy.Nip46PublicKeyRelayClosed
            else -> WebAuthCopy.Nip46RelayClosedBeforeResponse
        }
}

fun parseWebNip46BunkerToken(raw: String): WebNip46TokenParseResult {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("bunker://")) {
        return WebNip46TokenParseResult.Invalid(WebAuthCopy.Nip46InvalidToken)
    }
    val rest = trimmed.removePrefix("bunker://")
    val publicKey = percentDecode(rest.substringBefore("?")).lowercase()
    if (!isValidHexPublicKey(publicKey)) {
        return WebNip46TokenParseResult.Invalid(WebAuthCopy.Nip46InvalidRemotePubkey)
    }
    val params = parseQueryParams(rest.substringAfter("?", missingDelimiterValue = ""))
    val relays = params["relay"].orEmpty()
        .map { percentDecode(it) }
        .mapNotNull(::normalizeSignerRelay)
        .distinct()
    if (relays.isEmpty()) {
        return WebNip46TokenParseResult.Invalid(WebAuthCopy.Nip46MissingRelay)
    }
    val secret = params["secret"]
        ?.firstOrNull()
        ?.let(::percentDecode)
        ?.takeIf { it.isNotBlank() }
    return WebNip46TokenParseResult.Valid(
        WebNip46BunkerToken(
            remoteSignerPubkey = publicKey,
            relays = relays,
            secret = secret,
        ),
    )
}

internal data class WebNip46KeyPair(
    val clientPrivateKey: Uint8Array,
    val clientPubkey: String,
)

internal data class WebNostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
)

private sealed interface WebRelayMessage {
    data class Event(val event: WebNostrEvent) : WebRelayMessage
    data class Ok(val eventId: String, val accepted: Boolean) : WebRelayMessage
    data class Closed(val safeMessage: String) : WebRelayMessage
    data object Ignored : WebRelayMessage
}

internal fun generateWebNip46TransportKey(
    randomBytes: (Int) -> WebNip46RandomBytesResult = ::secureRandomBytes,
    derivePublicKey: (Uint8Array) -> Result<String> = ::deriveNostrPublicKey,
    attempts: Int = TransportKeyGenerationAttempts,
): WebNip46KeyGenerationResult {
    var sawInvalidPrivateKey = false
    repeat(attempts.coerceAtLeast(1)) {
        val clientPrivateKey = when (val generated = randomBytes(TransportPrivateKeyByteCount)) {
            is WebNip46RandomBytesResult.Success -> generated.bytes
            is WebNip46RandomBytesResult.Failed -> return WebNip46KeyGenerationResult.Failed(
                reason = generated.reason,
                safeMessage = generated.safeMessage,
            )
        }
        if (!isUsablePrivateKeyCandidate(clientPrivateKey, TransportPrivateKeyByteCount)) {
            sawInvalidPrivateKey = true
            return@repeat
        }
        val clientPubkey = derivePublicKey(clientPrivateKey).getOrElse {
            return@repeat
        }
        if (isValidHexPublicKey(clientPubkey)) {
            return WebNip46KeyGenerationResult.Success(
                WebNip46KeyPair(clientPrivateKey, clientPubkey.lowercase()),
            )
        }
    }
    return if (sawInvalidPrivateKey) {
        WebNip46KeyGenerationResult.Failed(
            reason = WebNip46KeyFailureReason.InvalidGeneratedPrivateKey,
            safeMessage = WebAuthCopy.Nip46InvalidGeneratedPrivateKey,
        )
    } else {
        WebNip46KeyGenerationResult.Failed(
            reason = WebNip46KeyFailureReason.PublicKeyDerivationFailed,
            safeMessage = WebAuthCopy.Nip46PublicKeyDerivationFailed,
        )
    }
}

private fun secureRandomHex(byteCount: Int): WebNip46RandomHexResult =
    when (val generated = secureRandomBytes(byteCount)) {
        is WebNip46RandomBytesResult.Success -> WebNip46RandomHexResult.Success(generated.bytes.toHex(byteCount))
        is WebNip46RandomBytesResult.Failed -> WebNip46RandomHexResult.Failed(generated.safeMessage)
    }

private fun secureRandomBytes(byteCount: Int): WebNip46RandomBytesResult {
    val browserCrypto = runCatching { globalThis.crypto }.getOrNull()
        ?: return WebNip46RandomBytesResult.Failed(
            reason = WebNip46KeyFailureReason.MissingWebCrypto,
            safeMessage = WebAuthCopy.Nip46BrowserCryptoMissing,
        )
    return runCatching {
        WebNip46RandomBytesResult.Success(browserCrypto.getRandomValues(Uint8Array(byteCount)))
    }.getOrElse {
        WebNip46RandomBytesResult.Failed(
            reason = WebNip46KeyFailureReason.RandomGenerationFailed,
            safeMessage = WebAuthCopy.Nip46RandomGenerationFailed,
        )
    }
}

private fun deriveNostrPublicKey(clientPrivateKey: Uint8Array): Result<String> =
    runCatching { NostrTools.getPublicKey(clientPrivateKey) }

private fun isUsablePrivateKeyCandidate(bytes: Uint8Array, byteCount: Int): Boolean =
    (0 until byteCount).any { index -> (readUint8ArrayByte(bytes, index) and 0xff) != 0 }

private fun Uint8Array.toHex(byteCount: Int): String = buildString {
    repeat(byteCount) { index ->
        append((readUint8ArrayByte(this@toHex, index) and 0xff).toString(16).padStart(2, '0'))
    }
}

internal fun readUint8ArrayByte(bytes: Uint8Array, index: Int): Int =
    (bytes.asDynamic()[index] as Number).toInt()

internal fun writeUint8ArrayByte(bytes: Uint8Array, index: Int, value: Int) {
    bytes.asDynamic()[index] = value
}

internal fun buildRequestEvent(
    session: WebNip46Session,
    request: WebNip46RequestPayload,
    builder: WebNip46RequestBuilder = WebNip46RequestBuilder(),
): WebNip46RequestBuildResult {
    if (!isValidHexPublicKey(session.remoteSignerPubkey)) {
        return WebNip46RequestBuildResult.Failed(
            reason = WebNip46RequestFailureReason.MissingRemoteSignerPubkey,
            safeMessage = WebAuthCopy.Nip46RequestMissingRemoteSigner,
        )
    }
    if (!isValidHexPublicKey(session.clientPubkey) ||
        !isUsablePrivateKeyCandidate(session.clientPrivateKey, TransportPrivateKeyByteCount)
    ) {
        return WebNip46RequestBuildResult.Failed(
            reason = WebNip46RequestFailureReason.MissingClientCommunicationKey,
            safeMessage = WebAuthCopy.Nip46RequestMissingClientKey,
        )
    }
    val payloadJson = builder.serializeRequest(request).getOrElse {
        return WebNip46RequestBuildResult.Failed(
            reason = WebNip46RequestFailureReason.JsonSerializationFailed,
            safeMessage = WebAuthCopy.Nip46RequestJsonFailed,
        )
    }
    val encryptedRequest = builder.encryptRequest(session, payloadJson).getOrElse {
        return WebNip46RequestBuildResult.Failed(
            reason = WebNip46RequestFailureReason.EncryptionFailed,
            safeMessage = WebAuthCopy.Nip46RequestEncryptionFailed,
        )
    }
    val createdAt = epochSeconds()
    val tags = listOf(listOf("p", session.remoteSignerPubkey))
    val event = builder.signRequest(session, createdAt, tags, encryptedRequest).getOrElse {
        return WebNip46RequestBuildResult.Failed(
            reason = WebNip46RequestFailureReason.SigningFailed,
            safeMessage = WebAuthCopy.Nip46RequestSigningFailed,
        )
    }
    builder.serializeEvent(event).getOrElse {
        return WebNip46RequestBuildResult.Failed(
            reason = WebNip46RequestFailureReason.EventSerializationFailed,
            safeMessage = WebAuthCopy.Nip46RequestSerializationFailed,
        )
    }
    return WebNip46RequestBuildResult.Success(event)
}

private fun encryptRequestPayload(session: WebNip46Session, payloadJson: String): Result<String> = runCatching {
    val conversationKey = NostrTools.nip44.v2.utils.getConversationKey(session.clientPrivateKey, session.remoteSignerPubkey)
    NostrTools.nip44.v2.encrypt(payloadJson, conversationKey) as String
}

private fun signRequestEvent(
    session: WebNip46Session,
    createdAt: Long,
    tags: List<List<String>>,
    encryptedRequest: String,
): Result<WebNostrEvent> = runCatching {
    val template = js("({})")
    template.kind = Nip46EventKind
    template.created_at = createdAt.toDouble()
    template.tags = tags.map { it.toTypedArray() }.toTypedArray()
    template.content = encryptedRequest
    val event = NostrTools.finalizeEvent(template, session.clientPrivateKey)
    WebNostrEvent(
        id = jsString(event.id),
        pubkey = jsString(event.pubkey),
        createdAt = jsLong(event.created_at),
        kind = jsInt(event.kind),
        tags = tags,
        content = jsString(event.content),
        sig = jsString(event.sig),
    )
}

private fun jsString(value: dynamic): String = value.unsafeCast<String>()
private fun jsLong(value: dynamic): Long = value.unsafeCast<Double>().toLong()
private fun jsInt(value: dynamic): Int = value.unsafeCast<Double>().toInt()

private fun requestJson(request: WebNip46RequestPayload): String =
    buildJsonObject {
        put("id", JsonPrimitive(request.id))
        put("method", JsonPrimitive(request.method))
        put(
            "params",
            buildJsonArray {
                request.params.forEach { add(JsonPrimitive(it)) }
            },
        )
    }.toString()

private fun publishEventMessage(event: WebNostrEvent): String =
    buildJsonArray {
        add(JsonPrimitive("EVENT"))
        add(eventObject(event))
    }.toString()

private fun requestMessage(subscriptionId: String, session: WebNip46Session, since: Long): String =
    buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subscriptionId))
        add(
            buildJsonObject {
                put(
                    "authors",
                    buildJsonArray {
                        add(JsonPrimitive(session.remoteSignerPubkey))
                    },
                )
                put(
                    "kinds",
                    buildJsonArray {
                        add(JsonPrimitive(Nip46EventKind))
                    },
                )
                put(
                    "#p",
                    buildJsonArray {
                        add(JsonPrimitive(session.clientPubkey))
                    },
                )
                put("since", JsonPrimitive(since))
                put("limit", JsonPrimitive(50))
            },
        )
    }.toString()

private fun parseRelayMessage(raw: String): WebRelayMessage? =
    runCatching {
        val array = Json.parseToJsonElement(raw).jsonArrayOrNull() ?: return null
        when (array.getOrNull(0)?.stringOrNull()) {
            "EVENT" -> {
                val event = array.getOrNull(2)?.jsonObjectOrNull()?.let(::parseEventObject) ?: return null
                WebRelayMessage.Event(event)
            }
            "OK" -> WebRelayMessage.Ok(
                eventId = array.getOrNull(1)?.stringOrNull().orEmpty(),
                accepted = array.getOrNull(2)?.jsonPrimitive?.content == "true",
            )
            "CLOSED" -> WebRelayMessage.Closed(array.getOrNull(2)?.stringOrNull().orEmpty().take(120))
            else -> WebRelayMessage.Ignored
        }
    }.getOrNull()

private fun eventObject(event: WebNostrEvent): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(event.id))
    put("pubkey", JsonPrimitive(event.pubkey))
    put("created_at", JsonPrimitive(event.createdAt))
    put("kind", JsonPrimitive(event.kind))
    put(
        "tags",
        buildJsonArray {
            event.tags.forEach { tag ->
                add(
                    buildJsonArray {
                        tag.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        },
    )
    put("content", JsonPrimitive(event.content))
    put("sig", JsonPrimitive(event.sig))
}

private fun parseEventObject(obj: JsonObject): WebNostrEvent? {
    val tags = obj["tags"]?.jsonArrayOrNull()?.mapNotNull { tag ->
        tag.jsonArrayOrNull()?.mapNotNull { it.stringOrNull() }
    } ?: return null
    return WebNostrEvent(
        id = obj["id"]?.stringOrNull() ?: return null,
        pubkey = obj["pubkey"]?.stringOrNull() ?: return null,
        createdAt = obj["created_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null,
        kind = obj["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null,
        tags = tags,
        content = obj["content"]?.stringOrNull() ?: return null,
        sig = obj["sig"]?.stringOrNull() ?: return null,
    )
}

internal fun processResponseEvent(
    session: WebNip46Session,
    event: WebNostrEvent,
    expectedRequestId: String,
    since: Long,
): WebNip46ResponseProcessingResult {
    val ignoredReason = event.responseCandidateIgnoreReason(session, since)
    if (ignoredReason != null) {
        return WebNip46ResponseProcessingResult.Ignored(ignoredReason)
    }
    val decrypted = decryptResponsePayload(session, event).getOrElse {
        return WebNip46ResponseProcessingResult.Ignored(WebNip46ResponseIgnoreReason.DecryptionFailed)
    }
    return when (val decoded = decodeNip46ResponsePayload(decrypted, expectedRequestId)) {
        is WebNip46ResponseDecodeResult.Response -> WebNip46ResponseProcessingResult.Response(decoded.response)
        is WebNip46ResponseDecodeResult.Ignored -> WebNip46ResponseProcessingResult.Ignored(decoded.reason)
    }
}

private fun WebNostrEvent.responseCandidateIgnoreReason(
    session: WebNip46Session,
    since: Long,
): WebNip46ResponseIgnoreReason? =
    when {
        kind != Nip46EventKind -> WebNip46ResponseIgnoreReason.WrongKind
        pubkey != session.remoteSignerPubkey -> WebNip46ResponseIgnoreReason.UnexpectedPubkey
        !targetsClientPubkey(session.clientPubkey) -> WebNip46ResponseIgnoreReason.WrongRecipient
        createdAt < since -> WebNip46ResponseIgnoreReason.TooOld
        else -> null
    }

internal fun WebNostrEvent.targetsClientPubkey(clientPubkey: String): Boolean {
    val pTags = tags.mapNotNull { tag ->
        tag.takeIf { it.size >= 2 && it[0] == "p" }?.get(1)
    }
    return pTags.isEmpty() || pTags.any { it == clientPubkey }
}

private fun decryptResponsePayload(
    session: WebNip46Session,
    event: WebNostrEvent,
): Result<String> = runCatching {
    val conversationKey = NostrTools.nip44.v2.utils.getConversationKey(session.clientPrivateKey, session.remoteSignerPubkey)
    NostrTools.nip44.v2.decrypt(event.content, conversationKey) as String
}

internal sealed interface WebNip46ResponseDecodeResult {
    data class Response(val response: WebNip46RelayResponse) : WebNip46ResponseDecodeResult
    data class Ignored(val reason: WebNip46ResponseIgnoreReason) : WebNip46ResponseDecodeResult
}

internal fun decodeNip46ResponsePayload(
    plaintext: String,
    expectedRequestId: String,
): WebNip46ResponseDecodeResult =
    runCatching {
        val obj = Json.parseToJsonElement(plaintext).jsonObject
        val id = obj["id"]?.stringOrNull() ?: return@runCatching WebNip46ResponseDecodeResult.Response(
            WebNip46RelayResponse.Error(WebAuthCopy.Nip46MalformedResponse),
        )
        if (id != expectedRequestId) {
            return@runCatching WebNip46ResponseDecodeResult.Ignored(WebNip46ResponseIgnoreReason.IdMismatch)
        }
        val result = obj["result"]?.stringOrNull()?.takeIf { it.isNotBlank() }
        val error = obj["error"]?.safeErrorMessageOrNull()
        WebNip46ResponseDecodeResult.Response(responseFromPayload(result, error))
    }.getOrElse {
        WebNip46ResponseDecodeResult.Response(WebNip46RelayResponse.Error(WebAuthCopy.Nip46MalformedResponse))
    }

private fun responseFromPayload(result: String?, error: String?): WebNip46RelayResponse =
    when {
        result == "auth_url" && error != null -> WebNip46RelayResponse.AuthChallenge(error.take(180))
        error != null -> WebNip46RelayResponse.Error(mapRemoteSignerError(error))
        result != null -> WebNip46RelayResponse.Success(result)
        else -> WebNip46RelayResponse.Error(WebAuthCopy.Nip46EmptyResponse)
    }

private fun JsonElement.safeErrorMessageOrNull(): String? {
    val primitive = this as? JsonPrimitive
    if (primitive != null) {
        return primitive.content.takeIf { it.isNotBlank() }?.take(180)
    }
    val obj = this as? JsonObject ?: return null
    return sequenceOf("message", "reason", "error")
        .mapNotNull { key -> obj[key]?.stringOrNull()?.takeIf { it.isNotBlank() } }
        .firstOrNull()
        ?.take(180)
}

private fun mapRemoteSignerError(error: String): String {
    val lower = error.lowercase()
    return when {
        "reject" in lower || "denied" in lower || "cancel" in lower -> WebAuthCopy.Nip46SignerRejected
        "secret" in lower -> "Remote signer rejected the pairing secret."
        else -> "Remote signer request failed."
    }
}

private fun parseQueryParams(raw: String): Map<String, List<String>> =
    raw.split("&")
        .filter { it.isNotBlank() }
        .map { part ->
            val key = percentDecode(part.substringBefore("="))
            val value = part.substringAfter("=", missingDelimiterValue = "")
            key to value
        }
        .groupBy({ it.first }, { it.second })

internal fun normalizeSignerRelay(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank() || trimmed.any { it.isWhitespace() }) return null
    if (!trimmed.startsWith("wss://")) return null
    if ("#" in trimmed) return null
    return trimmed.removeSuffix("/")
}

private fun percentDecode(raw: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val hex = raw.substring(i + 1, i + 3)
            val byte = hex.toIntOrNull(16)
            if (byte != null) {
                out.append(byte.toChar())
                i += 3
                continue
            }
        }
        out.append(if (c == '+') ' ' else c)
        i++
    }
    return out.toString()
}

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.stringOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()

internal fun isValidHexPublicKey(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

internal fun isValidHexPrivateKey(value: String): Boolean =
    value.length == 64 &&
        value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } &&
        value.chunked(2).any { it.toInt(16) != 0 }

internal fun uint8ArrayFromHex(hex: String): Uint8Array? {
    val normalized = hex.lowercase()
    if (!isValidHexPrivateKey(normalized)) return null
    val bytes = Uint8Array(normalized.length / 2)
    normalized.chunked(2).forEachIndexed { index, byte ->
        writeUint8ArrayByte(bytes, index, byte.toInt(16))
    }
    return bytes
}

internal fun Uint8Array.toFixedHex(byteCount: Int): String = buildString {
    repeat(byteCount) { index ->
        append((readUint8ArrayByte(this@toFixedHex, index) and 0xff).toString(16).padStart(2, '0'))
    }
}

private fun epochSeconds(): Long =
    (js("Date.now()") as Double).toLong() / 1000
