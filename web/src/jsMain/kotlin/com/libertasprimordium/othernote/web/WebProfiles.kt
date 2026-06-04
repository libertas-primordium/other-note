package com.libertasprimordium.othernote.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal const val WebProfileKind = 0
private const val WebProfileRelaySubscriptionId = "other-note-web-profile"
private const val WebProfileRelayTimeoutMs = 8_000
private const val WebProfileNameMaxLength = 80
private const val WebProfileAboutMaxLength = 280
private const val WebProfileUrlMaxLength = 240

internal data class WebProfileMetadata(
    val pubkey: String,
    val name: String? = null,
    val displayName: String? = null,
    val about: String? = null,
    val nip05: String? = null,
    val pictureUrl: String? = null,
    val bannerUrl: String? = null,
    val createdAt: Long? = null,
) {
    val bestName: String? get() = displayName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
}

internal data class WebProfileUiState(
    val loading: Boolean = false,
    val pubkey: String? = null,
    val metadata: WebProfileMetadata? = null,
    val safeMessage: String? = null,
)

internal data class WebProfileHeaderSummary(
    val primary: String,
    val secondary: String,
    val tertiary: String? = null,
    val about: String? = null,
)

internal object WebProfileCopy {
    const val Loading = "Loading profile..."
    const val Unavailable = "Profile unavailable."
}

internal data class WebProfileLoadRequest(
    val generation: Int,
    val accountPubkey: String,
    val method: WebAuthMethod,
)

internal data class WebProfileLoadStart(
    val guard: WebProfileLoadGuard,
    val request: WebProfileLoadRequest,
)

internal data class WebProfileLoadGuard(
    val generation: Int = 0,
) {
    fun start(identity: WebAccountIdentity): WebProfileLoadStart {
        val next = copy(generation = generation + 1)
        return WebProfileLoadStart(
            guard = next,
            request = WebProfileLoadRequest(
                generation = next.generation,
                accountPubkey = identity.publicKeyHex,
                method = identity.method,
            ),
        )
    }

    fun invalidate(): WebProfileLoadGuard =
        copy(generation = generation + 1)

    fun accepts(request: WebProfileLoadRequest, authState: WebAuthUiState): Boolean {
        val signedIn = authState.signInState as? WebSignInState.SignedIn ?: return false
        return request.generation == generation &&
            request.accountPubkey == signedIn.identity.publicKeyHex &&
            request.method == signedIn.identity.method
    }
}

internal fun webProfileHeaderSummary(
    identity: WebAccountIdentity,
    state: WebProfileUiState,
): WebProfileHeaderSummary {
    val profile = state.metadata?.takeIf { it.pubkey == identity.publicKeyHex }
    val fallback = identity.displayPublicKey
    return WebProfileHeaderSummary(
        primary = profile?.bestName?.takeIf { it.isNotBlank() } ?: fallback,
        secondary = "${identity.method.displayName} · $fallback",
        tertiary = when {
            profile?.nip05?.isNotBlank() == true -> profile.nip05
            state.loading && profile == null && state.pubkey == identity.publicKeyHex -> WebProfileCopy.Loading
            state.safeMessage != null && state.pubkey == identity.publicKeyHex -> state.safeMessage
            else -> null
        },
        about = profile?.about?.takeIf { it.isNotBlank() },
    )
}

internal fun parseWebProfileMetadata(pubkey: String, content: String, createdAt: Long? = null): WebProfileMetadata? {
    val obj = runCatching { WebProfileJson.parseToJsonElement(content).jsonObjectOrNull() }.getOrNull() ?: return null
    return WebProfileMetadata(
        pubkey = pubkey,
        name = obj.safeProfileString("name", WebProfileNameMaxLength),
        displayName = obj.safeProfileString("display_name", WebProfileNameMaxLength),
        about = obj.safeProfileString("about", WebProfileAboutMaxLength),
        nip05 = obj.safeProfileString("nip05", WebProfileNameMaxLength),
        pictureUrl = obj.safeProfileString("picture", WebProfileUrlMaxLength),
        bannerUrl = obj.safeProfileString("banner", WebProfileUrlMaxLength),
        createdAt = createdAt,
    )
}

internal fun selectLatestWebProfileMetadata(
    events: List<WebNostrEvent>,
    pubkey: String,
    validateEvent: (WebNostrEvent) -> Boolean = ::validateWebProfileEventAuthenticity,
): WebProfileMetadata? =
    events
        .asSequence()
        .filter { it.kind == WebProfileKind && it.pubkey == pubkey }
        .filter(validateEvent)
        .sortedWith(compareByDescending<WebNostrEvent> { it.createdAt }.thenByDescending { it.id })
        .mapNotNull { parseWebProfileMetadata(pubkey = it.pubkey, content = it.content, createdAt = it.createdAt) }
        .firstOrNull()

internal fun webProfileRequestMessage(accountPubkey: String): String =
    buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(WebProfileRelaySubscriptionId))
        add(
            buildJsonObject {
                put("authors", buildJsonArray { add(JsonPrimitive(accountPubkey)) })
                put("kinds", buildJsonArray { add(JsonPrimitive(WebProfileKind)) })
                put("limit", JsonPrimitive(20))
            },
        )
    }.toString()

internal class WebProfileFetcher(
    private val relays: List<String> = DefaultWebNoteRelays,
    private val timeoutMs: Int = WebProfileRelayTimeoutMs,
) {
    private val sockets = mutableListOf<WebSocket>()
    private var completed = false
    private var timeoutHandle: Int? = null
    private var activeGeneration = 0

    fun fetch(accountPubkey: String, onResult: (WebProfileMetadata?) -> Unit) {
        closeSockets()
        completed = false
        val generation = ++activeGeneration
        val eventsById = linkedMapOf<String, WebNostrEvent>()
        val finishedRelays = relays.associateWith { false }.toMutableMap()
        timeoutHandle = setTimeout(
            {
                complete(generation, accountPubkey, eventsById.values.toList(), onResult)
            },
            timeoutMs,
        )
        relays.forEach { relay ->
            val socket = runCatching { WebSocket(relay) }.getOrElse {
                finishedRelays[relay] = true
                maybeComplete(generation, accountPubkey, eventsById, finishedRelays, onResult)
                return@forEach
            }
            sockets += socket
            socket.onopen = {
                if (isCurrent(generation)) {
                    socket.send(webProfileRequestMessage(accountPubkey))
                }
            }
            socket.onmessage = { message ->
                if (isCurrent(generation)) {
                    handleRelayMessage(generation, accountPubkey, relay, message.data, eventsById, finishedRelays, onResult)
                }
            }
            socket.onerror = {
                markRelayFinished(generation, accountPubkey, relay, eventsById, finishedRelays, onResult)
            }
            socket.onclose = {
                markRelayFinished(generation, accountPubkey, relay, eventsById, finishedRelays, onResult)
            }
        }
    }

    fun close() {
        activeGeneration += 1
        completed = true
        closeSockets()
    }

    private fun handleRelayMessage(
        generation: Int,
        accountPubkey: String,
        relay: String,
        raw: String,
        eventsById: MutableMap<String, WebNostrEvent>,
        finishedRelays: MutableMap<String, Boolean>,
        onResult: (WebProfileMetadata?) -> Unit,
    ) {
        when (val message = parseWebProfileRelayMessage(raw)) {
            is WebProfileRelayMessage.Event -> {
                if (
                    message.subscriptionId == WebProfileRelaySubscriptionId &&
                    message.event.kind == WebProfileKind &&
                    message.event.pubkey == accountPubkey
                ) {
                    eventsById[message.event.id] = message.event
                }
            }
            is WebProfileRelayMessage.Eose -> {
                if (message.subscriptionId == WebProfileRelaySubscriptionId) {
                    markRelayFinished(generation, accountPubkey, relay, eventsById, finishedRelays, onResult)
                }
            }
            WebProfileRelayMessage.Closed -> markRelayFinished(generation, accountPubkey, relay, eventsById, finishedRelays, onResult)
            WebProfileRelayMessage.Ignored -> Unit
        }
    }

    private fun markRelayFinished(
        generation: Int,
        accountPubkey: String,
        relay: String,
        eventsById: MutableMap<String, WebNostrEvent>,
        finishedRelays: MutableMap<String, Boolean>,
        onResult: (WebProfileMetadata?) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        if (finishedRelays[relay] == true) return
        finishedRelays[relay] = true
        maybeComplete(generation, accountPubkey, eventsById, finishedRelays, onResult)
    }

    private fun maybeComplete(
        generation: Int,
        accountPubkey: String,
        eventsById: MutableMap<String, WebNostrEvent>,
        finishedRelays: MutableMap<String, Boolean>,
        onResult: (WebProfileMetadata?) -> Unit,
    ) {
        if (finishedRelays.values.all { it }) {
            complete(generation, accountPubkey, eventsById.values.toList(), onResult)
        }
    }

    private fun complete(
        generation: Int,
        accountPubkey: String,
        events: List<WebNostrEvent>,
        onResult: (WebProfileMetadata?) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        completed = true
        activeGeneration += 1
        closeSockets()
        onResult(selectLatestWebProfileMetadata(events, accountPubkey))
    }

    private fun closeSockets() {
        timeoutHandle?.let(::clearTimeout)
        timeoutHandle = null
        sockets.forEach { socket -> runCatching { socket.close() } }
        sockets.clear()
    }

    private fun isCurrent(generation: Int): Boolean =
        !completed && generation == activeGeneration
}

private sealed interface WebProfileRelayMessage {
    data class Event(val subscriptionId: String, val event: WebNostrEvent) : WebProfileRelayMessage
    data class Eose(val subscriptionId: String) : WebProfileRelayMessage
    data object Closed : WebProfileRelayMessage
    data object Ignored : WebProfileRelayMessage
}

private val WebProfileJson = Json { ignoreUnknownKeys = true }

private fun parseWebProfileRelayMessage(raw: String): WebProfileRelayMessage =
    runCatching {
        val array = WebProfileJson.parseToJsonElement(raw).jsonArrayOrNull() ?: return@runCatching WebProfileRelayMessage.Ignored
        when (array.getOrNull(0)?.stringOrNull()) {
            "EVENT" -> {
                val subscriptionId = array.getOrNull(1)?.stringOrNull() ?: return@runCatching WebProfileRelayMessage.Ignored
                val event = array.getOrNull(2)?.jsonObjectOrNull()?.let(::parseWebProfileNostrEventObject)
                    ?: return@runCatching WebProfileRelayMessage.Ignored
                WebProfileRelayMessage.Event(subscriptionId, event)
            }
            "EOSE" -> {
                val subscriptionId = array.getOrNull(1)?.stringOrNull() ?: return@runCatching WebProfileRelayMessage.Ignored
                WebProfileRelayMessage.Eose(subscriptionId)
            }
            "CLOSED" -> WebProfileRelayMessage.Closed
            else -> WebProfileRelayMessage.Ignored
        }
    }.getOrElse { WebProfileRelayMessage.Ignored }

private fun parseWebProfileNostrEventObject(obj: JsonObject): WebNostrEvent? {
    val tags = obj["tags"]?.jsonArrayOrNull()?.mapNotNull { tag ->
        tag.jsonArrayOrNull()?.mapNotNull { it.stringOrNull() }
    } ?: return null
    return WebNostrEvent(
        id = obj["id"]?.stringOrNull() ?: return null,
        pubkey = obj["pubkey"]?.stringOrNull() ?: return null,
        createdAt = obj["created_at"]?.jsonPrimitive?.longOrNull ?: return null,
        kind = obj["kind"]?.jsonPrimitive?.intOrNull ?: return null,
        tags = tags,
        content = obj["content"]?.stringOrNull() ?: return null,
        sig = obj["sig"]?.stringOrNull() ?: return null,
    )
}

private fun validateWebProfileEventAuthenticity(event: WebNostrEvent): Boolean =
    validateWebProfileEventShape(event) && runCatching {
        val dynamicEvent = event.toDynamicNostrEvent()
        NostrTools.validateEvent(dynamicEvent) && NostrTools.verifyEvent(dynamicEvent)
    }.getOrDefault(false)

internal fun validateWebProfileEventShape(event: WebNostrEvent): Boolean =
    event.kind == WebProfileKind &&
        event.pubkey.length == 64 &&
        event.id.length == 64 &&
        event.sig.length == 128 &&
        event.pubkey.all { it.isWebHexDigit() } &&
        event.id.all { it.isWebHexDigit() } &&
        event.sig.all { it.isWebHexDigit() }

private fun WebNostrEvent.toDynamicNostrEvent(): dynamic {
    val event = js("({})")
    event.id = id
    event.pubkey = pubkey
    event.created_at = createdAt.toDouble()
    event.kind = kind
    event.tags = tags.map { it.toTypedArray() }.toTypedArray()
    event.content = content
    event.sig = sig
    return event
}

private fun JsonObject.safeProfileString(key: String, maxLength: Int): String? {
    val value = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.trim() ?: return null
    if (value.isBlank()) return null
    return value.take(maxLength)
}

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.stringOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()
private fun Char.isWebHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
