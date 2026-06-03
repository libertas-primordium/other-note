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

internal const val WebRelayListKind = 10002
private const val WebRelayListRelaySubscriptionId = "other-note-web-relay-list"
private const val WebRelayListRelayTimeoutMs = 8_000

internal data class WebRelayListEntry(
    val url: String,
    val marker: String?,
    val extraFields: List<String> = emptyList(),
) {
    val isWrite: Boolean get() =
        marker == null || marker.equals("write", ignoreCase = true) || marker.equals("outbox", ignoreCase = true)

    val isReadOnly: Boolean get() =
        marker.equals("read", ignoreCase = true) || marker.equals("inbox", ignoreCase = true)
}

internal data class WebPublishedRelayList(
    val event: WebNostrEvent,
    val relayEntries: List<WebRelayListEntry>,
    val preservedTags: List<List<String>>,
    val malformedRelayTagCount: Int,
) {
    val writeRelayUrls: List<String> get() =
        relayEntries.filter { it.isWrite && !it.isReadOnly }.map { it.url }.distinct()
}

internal data class WebRelayListUiState(
    val loading: Boolean = false,
    val pubkey: String? = null,
    val message: String = "",
    val pendingPublishedRelays: List<String> = emptyList(),
)

internal object WebRelayListCopy {
    const val Loading = "Checking published relay list..."
    const val Imported = "Imported published write relays for this session."
    const val NoPublishedWriteRelays = "No published write relays found; keeping current note relays."
    const val FetchFailed = "Could not refresh the published relay list; keeping current note relays."
    const val DeferredForLocalDraft = "Published relays found. Finish the add-relay draft or reload the published list."
    const val KeptLocalEdits = "Keeping current session note relays."
}

internal data class WebRelayListLoadRequest(
    val generation: Int,
    val accountPubkey: String,
    val method: WebAuthMethod,
)

internal data class WebRelayListLoadStart(
    val guard: WebRelayListLoadGuard,
    val request: WebRelayListLoadRequest,
)

internal data class WebRelayListLoadGuard(
    val generation: Int = 0,
) {
    fun start(identity: WebAccountIdentity): WebRelayListLoadStart {
        val next = copy(generation = generation + 1)
        return WebRelayListLoadStart(
            guard = next,
            request = WebRelayListLoadRequest(
                generation = next.generation,
                accountPubkey = identity.publicKeyHex,
                method = identity.method,
            ),
        )
    }

    fun invalidate(): WebRelayListLoadGuard =
        copy(generation = generation + 1)

    fun accepts(request: WebRelayListLoadRequest, authState: WebAuthUiState): Boolean {
        val signedIn = authState.signInState as? WebSignInState.SignedIn ?: return false
        return request.generation == generation &&
            request.accountPubkey == signedIn.identity.publicKeyHex &&
            request.method == signedIn.identity.method
    }
}

internal sealed interface WebRelayListImportDecision {
    data class Applied(val settings: WebNoteRelaySettingsState, val relayState: WebRelayListUiState, val changed: Boolean) :
        WebRelayListImportDecision

    data class Deferred(val settings: WebNoteRelaySettingsState, val relayState: WebRelayListUiState) :
        WebRelayListImportDecision

    data class KeptCurrent(val settings: WebNoteRelaySettingsState, val relayState: WebRelayListUiState) :
        WebRelayListImportDecision
}

internal fun parseWebRelayListEvent(event: WebNostrEvent): WebPublishedRelayList {
    val entries = mutableListOf<WebRelayListEntry>()
    val preservedTags = mutableListOf<List<String>>()
    var malformed = 0
    event.tags.forEach { tag ->
        if (tag.firstOrNull() != "r") {
            preservedTags += tag
            return@forEach
        }
        val rawUrl = tag.getOrNull(1)
        if (rawUrl.isNullOrBlank()) {
            malformed += 1
            return@forEach
        }
        val normalized = normalizeWebNoteRelayUrl(rawUrl).getOrNull()
        if (normalized == null) {
            malformed += 1
            return@forEach
        }
        entries += WebRelayListEntry(
            url = normalized,
            marker = tag.getOrNull(2)?.takeIf { it.isNotBlank() },
            extraFields = if (tag.size > 3) tag.drop(3) else emptyList(),
        )
    }
    return WebPublishedRelayList(event, entries, preservedTags, malformed)
}

internal fun selectLatestWebRelayList(
    events: List<WebNostrEvent>,
    pubkey: String,
    validateEvent: (WebNostrEvent) -> Boolean = ::validateWebRelayListEventAuthenticity,
): WebPublishedRelayList? =
    events
        .asSequence()
        .filter { it.kind == WebRelayListKind && it.pubkey == pubkey }
        .filter(validateEvent)
        .sortedWith(compareByDescending<WebNostrEvent> { it.createdAt }.thenBy { it.id })
        .map(::parseWebRelayListEvent)
        .firstOrNull()

internal fun importPublishedWebNoteRelays(
    settings: WebNoteRelaySettingsState,
    relayState: WebRelayListUiState,
    publishedRelays: List<String>,
): WebRelayListImportDecision {
    val normalized = publishedRelays.mapNotNull { normalizeWebNoteRelayUrl(it).getOrNull() }.distinct()
    if (normalized.isEmpty()) {
        return WebRelayListImportDecision.KeptCurrent(
            settings = settings,
            relayState = relayState.copy(
                loading = false,
                message = WebRelayListCopy.NoPublishedWriteRelays,
                pendingPublishedRelays = emptyList(),
            ),
        )
    }
    if (settings.input.isNotBlank() && normalized != selectedWebNoteRelays(settings)) {
        return WebRelayListImportDecision.Deferred(
            settings = settings,
            relayState = relayState.copy(
                loading = false,
                message = WebRelayListCopy.DeferredForLocalDraft,
                pendingPublishedRelays = normalized,
            ),
        )
    }
    val changed = normalized != selectedWebNoteRelays(settings)
    return WebRelayListImportDecision.Applied(
        settings = settings.copy(
            relays = normalized,
            input = "",
            message = WebRelayListCopy.Imported,
        ),
        relayState = relayState.copy(
            loading = false,
            message = WebRelayListCopy.Imported,
            pendingPublishedRelays = emptyList(),
        ),
        changed = changed,
    )
}

internal fun applyPendingPublishedWebNoteRelays(
    settings: WebNoteRelaySettingsState,
    relayState: WebRelayListUiState,
): WebRelayListImportDecision {
    val pending = relayState.pendingPublishedRelays
    if (pending.isEmpty()) {
        return WebRelayListImportDecision.KeptCurrent(
            settings = settings,
            relayState = relayState.copy(loading = false, message = WebRelayListCopy.KeptLocalEdits),
        )
    }
    val changed = pending != selectedWebNoteRelays(settings)
    return WebRelayListImportDecision.Applied(
        settings = settings.copy(
            relays = pending,
            input = "",
            message = WebRelayListCopy.Imported,
        ),
        relayState = relayState.copy(
            loading = false,
            message = WebRelayListCopy.Imported,
            pendingPublishedRelays = emptyList(),
        ),
        changed = changed,
    )
}

internal fun keepLocalWebRelayEdits(relayState: WebRelayListUiState): WebRelayListUiState =
    relayState.copy(
        loading = false,
        message = WebRelayListCopy.KeptLocalEdits,
        pendingPublishedRelays = emptyList(),
    )

internal fun webRelayListRequestMessage(accountPubkey: String): String =
    buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(WebRelayListRelaySubscriptionId))
        add(
            buildJsonObject {
                put("authors", buildJsonArray { add(JsonPrimitive(accountPubkey)) })
                put("kinds", buildJsonArray { add(JsonPrimitive(WebRelayListKind)) })
                put("limit", JsonPrimitive(20))
            },
        )
    }.toString()

internal class WebRelayListFetcher(
    private val relays: List<String> = DefaultWebNoteRelays,
    private val timeoutMs: Int = WebRelayListRelayTimeoutMs,
) {
    private val sockets = mutableListOf<WebSocket>()
    private var completed = false
    private var timeoutHandle: Int? = null
    private var activeGeneration = 0

    fun fetch(accountPubkey: String, onResult: (WebPublishedRelayList?) -> Unit) {
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
                    socket.send(webRelayListRequestMessage(accountPubkey))
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
        onResult: (WebPublishedRelayList?) -> Unit,
    ) {
        when (val message = parseWebRelayListRelayMessage(raw)) {
            is WebRelayListRelayMessage.Event -> {
                if (
                    message.subscriptionId == WebRelayListRelaySubscriptionId &&
                    message.event.kind == WebRelayListKind &&
                    message.event.pubkey == accountPubkey
                ) {
                    eventsById[message.event.id] = message.event
                }
            }
            is WebRelayListRelayMessage.Eose -> {
                if (message.subscriptionId == WebRelayListRelaySubscriptionId) {
                    markRelayFinished(generation, accountPubkey, relay, eventsById, finishedRelays, onResult)
                }
            }
            WebRelayListRelayMessage.Closed -> markRelayFinished(generation, accountPubkey, relay, eventsById, finishedRelays, onResult)
            WebRelayListRelayMessage.Ignored -> Unit
        }
    }

    private fun markRelayFinished(
        generation: Int,
        accountPubkey: String,
        relay: String,
        eventsById: MutableMap<String, WebNostrEvent>,
        finishedRelays: MutableMap<String, Boolean>,
        onResult: (WebPublishedRelayList?) -> Unit,
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
        onResult: (WebPublishedRelayList?) -> Unit,
    ) {
        if (finishedRelays.values.all { it }) {
            complete(generation, accountPubkey, eventsById.values.toList(), onResult)
        }
    }

    private fun complete(
        generation: Int,
        accountPubkey: String,
        events: List<WebNostrEvent>,
        onResult: (WebPublishedRelayList?) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        completed = true
        activeGeneration += 1
        closeSockets()
        onResult(selectLatestWebRelayList(events, accountPubkey))
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

private sealed interface WebRelayListRelayMessage {
    data class Event(val subscriptionId: String, val event: WebNostrEvent) : WebRelayListRelayMessage
    data class Eose(val subscriptionId: String) : WebRelayListRelayMessage
    data object Closed : WebRelayListRelayMessage
    data object Ignored : WebRelayListRelayMessage
}

private val WebRelayListJson = Json { ignoreUnknownKeys = true }

private fun parseWebRelayListRelayMessage(raw: String): WebRelayListRelayMessage =
    runCatching {
        val array = WebRelayListJson.parseToJsonElement(raw).jsonArrayOrNull() ?: return@runCatching WebRelayListRelayMessage.Ignored
        when (array.getOrNull(0)?.stringOrNull()) {
            "EVENT" -> {
                val subscriptionId = array.getOrNull(1)?.stringOrNull() ?: return@runCatching WebRelayListRelayMessage.Ignored
                val event = array.getOrNull(2)?.jsonObjectOrNull()?.let(::parseWebRelayListNostrEventObject)
                    ?: return@runCatching WebRelayListRelayMessage.Ignored
                WebRelayListRelayMessage.Event(subscriptionId, event)
            }
            "EOSE" -> {
                val subscriptionId = array.getOrNull(1)?.stringOrNull() ?: return@runCatching WebRelayListRelayMessage.Ignored
                WebRelayListRelayMessage.Eose(subscriptionId)
            }
            "CLOSED" -> WebRelayListRelayMessage.Closed
            else -> WebRelayListRelayMessage.Ignored
        }
    }.getOrElse { WebRelayListRelayMessage.Ignored }

private fun parseWebRelayListNostrEventObject(obj: JsonObject): WebNostrEvent? {
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

private fun validateWebRelayListEventAuthenticity(event: WebNostrEvent): Boolean =
    validateWebRelayListEventShape(event) && runCatching {
        val dynamicEvent = event.toDynamicRelayListNostrEvent()
        NostrTools.validateEvent(dynamicEvent) && NostrTools.verifyEvent(dynamicEvent)
    }.getOrDefault(false)

internal fun validateWebRelayListEventShape(event: WebNostrEvent): Boolean =
    event.kind == WebRelayListKind &&
        event.pubkey.length == 64 &&
        event.id.length == 64 &&
        event.sig.length == 128 &&
        event.pubkey.all { it.isWebRelayListHexDigit() } &&
        event.id.all { it.isWebRelayListHexDigit() } &&
        event.sig.all { it.isWebRelayListHexDigit() }

private fun WebNostrEvent.toDynamicRelayListNostrEvent(): dynamic {
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

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.stringOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()
private fun Char.isWebRelayListHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
