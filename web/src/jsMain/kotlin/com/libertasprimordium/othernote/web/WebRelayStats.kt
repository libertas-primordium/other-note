package com.libertasprimordium.othernote.web

private const val WebRelayStatsTimeoutMs = 8_000

internal sealed interface WebRelayEventStat {
    data object Unknown : WebRelayEventStat
    data object Checking : WebRelayEventStat
    data class Loaded(val eventCount: Int) : WebRelayEventStat
    data object Unavailable : WebRelayEventStat
}

internal data class WebRelayStatsUiState(
    val pubkey: String? = null,
    val stats: Map<String, WebRelayEventStat> = emptyMap(),
)

internal data class WebRelayStatsResult(
    val stats: Map<String, WebRelayEventStat>,
)

internal data class WebRelayStatsRequest(
    val generation: Int,
    val accountPubkey: String,
    val method: WebAuthMethod,
    val relays: List<String>,
)

internal data class WebRelayStatsStart(
    val guard: WebRelayStatsGuard,
    val request: WebRelayStatsRequest,
)

internal data class WebRelayStatsGuard(
    val generation: Int = 0,
) {
    fun start(identity: WebAccountIdentity, relays: List<String>): WebRelayStatsStart {
        val normalizedRelays = relays.mapNotNull { normalizeWebNoteRelayUrl(it).getOrNull() }.distinct()
        val next = copy(generation = generation + 1)
        return WebRelayStatsStart(
            guard = next,
            request = WebRelayStatsRequest(
                generation = next.generation,
                accountPubkey = identity.publicKeyHex,
                method = identity.method,
                relays = normalizedRelays,
            ),
        )
    }

    fun invalidate(): WebRelayStatsGuard =
        copy(generation = generation + 1)

    fun accepts(request: WebRelayStatsRequest, authState: WebAuthUiState, activeRelays: List<String>): Boolean {
        val signedIn = authState.signInState as? WebSignInState.SignedIn ?: return false
        val normalizedRelays = activeRelays.mapNotNull { normalizeWebNoteRelayUrl(it).getOrNull() }.distinct()
        return request.generation == generation &&
            request.accountPubkey == signedIn.identity.publicKeyHex &&
            request.method == signedIn.identity.method &&
            request.relays == normalizedRelays
    }
}

internal fun webRelayEventStatLabel(stat: WebRelayEventStat?): String {
    val resolved = stat ?: WebRelayEventStat.Unknown
    return when (resolved) {
        WebRelayEventStat.Unknown -> "unknown"
        WebRelayEventStat.Checking -> "checking..."
        is WebRelayEventStat.Loaded -> {
            val count = resolved.eventCount
            "$count ${if (count == 1) "event" else "events"} found"
        }
        WebRelayEventStat.Unavailable -> "unavailable"
    }
}

internal fun webRelayEventStatForEvents(
    events: List<WebNostrEvent>,
    accountPubkey: String,
    failed: Boolean = false,
    timedOut: Boolean = false,
    validateEvent: (WebNostrEvent) -> Boolean = ::validateWebMigrationNoteEventAuthenticity,
): WebRelayEventStat =
    if (failed || timedOut) {
        WebRelayEventStat.Unavailable
    } else {
        WebRelayEventStat.Loaded(webRelayStatsCountValidEvents(events, accountPubkey, validateEvent))
    }

internal fun webRelayStatsCountValidEvents(
    events: List<WebNostrEvent>,
    accountPubkey: String,
    validateEvent: (WebNostrEvent) -> Boolean = ::validateWebMigrationNoteEventAuthenticity,
): Int =
    events
        .distinctBy { it.id }
        .count { event ->
            event.pubkey == accountPubkey &&
                event.isReadableOtherNoteEvent() &&
                event.dTag() != null &&
                validateEvent(event)
        }

internal class WebRelayStatsFetcher(
    private val relays: List<String>,
    private val timeoutMs: Int = WebRelayStatsTimeoutMs,
) {
    private val sockets = mutableListOf<WebSocket>()
    private var completed = false
    private var timeoutHandle: Int? = null
    private var activeGeneration = 0

    fun fetch(accountPubkey: String, onResult: (WebRelayStatsResult) -> Unit) {
        close()
        completed = false
        val generation = ++activeGeneration
        val relayEvents = relays.associateWith { linkedMapOf<String, WebNostrEvent>() }.toMutableMap()
        val relayStates = relays.associateWith { WebMutableRelayStatsState() }.toMutableMap()
        timeoutHandle = setTimeout(
            {
                relayStates.values.filterNot { it.finished }.forEach { state ->
                    state.timedOut = true
                    state.finished = true
                }
                complete(generation, accountPubkey, relayEvents, relayStates, onResult)
            },
            timeoutMs,
        )
        if (relays.isEmpty()) {
            complete(generation, accountPubkey, relayEvents, relayStates, onResult)
            return
        }
        relays.forEach { relay ->
            val socket = runCatching { WebSocket(relay) }.getOrElse {
                relayStates[relay]?.failed = true
                relayStates[relay]?.finished = true
                maybeComplete(generation, accountPubkey, relayEvents, relayStates, onResult)
                return@forEach
            }
            sockets += socket
            socket.onopen = {
                if (isCurrent(generation)) {
                    socket.send(webNoteRequestMessage(accountPubkey))
                }
            }
            socket.onmessage = { message ->
                if (isCurrent(generation)) {
                    handleRelayMessage(generation, accountPubkey, relay, message.data, relayEvents, relayStates, onResult)
                }
            }
            socket.onerror = { markFailed(generation, accountPubkey, relay, relayEvents, relayStates, onResult) }
            socket.onclose = { markFailed(generation, accountPubkey, relay, relayEvents, relayStates, onResult) }
        }
    }

    fun close() {
        activeGeneration += 1
        completed = true
        timeoutHandle?.let(::clearTimeout)
        timeoutHandle = null
        sockets.forEach { socket -> runCatching { socket.close() } }
        sockets.clear()
    }

    private fun handleRelayMessage(
        generation: Int,
        accountPubkey: String,
        relay: String,
        raw: String,
        relayEvents: MutableMap<String, LinkedHashMap<String, WebNostrEvent>>,
        relayStates: MutableMap<String, WebMutableRelayStatsState>,
        onResult: (WebRelayStatsResult) -> Unit,
    ) {
        when (val message = parseWebNoteRelayMessage(raw)) {
            is WebNoteRelayMessage.Event -> {
                if (
                    message.subscriptionId == WebNoteRelaySubscriptionId &&
                    message.event.pubkey == accountPubkey &&
                    message.event.isReadableOtherNoteEvent()
                ) {
                    relayEvents[relay]?.put(message.event.id, message.event)
                }
            }
            is WebNoteRelayMessage.Eose -> {
                if (message.subscriptionId == WebNoteRelaySubscriptionId) {
                    relayStates[relay]?.finished = true
                    maybeComplete(generation, accountPubkey, relayEvents, relayStates, onResult)
                }
            }
            WebNoteRelayMessage.Closed -> markFailed(generation, accountPubkey, relay, relayEvents, relayStates, onResult)
            WebNoteRelayMessage.Ignored -> Unit
        }
    }

    private fun markFailed(
        generation: Int,
        accountPubkey: String,
        relay: String,
        relayEvents: MutableMap<String, LinkedHashMap<String, WebNostrEvent>>,
        relayStates: MutableMap<String, WebMutableRelayStatsState>,
        onResult: (WebRelayStatsResult) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        val state = relayStates[relay] ?: return
        if (state.finished) return
        state.failed = true
        state.finished = true
        maybeComplete(generation, accountPubkey, relayEvents, relayStates, onResult)
    }

    private fun maybeComplete(
        generation: Int,
        accountPubkey: String,
        relayEvents: MutableMap<String, LinkedHashMap<String, WebNostrEvent>>,
        relayStates: MutableMap<String, WebMutableRelayStatsState>,
        onResult: (WebRelayStatsResult) -> Unit,
    ) {
        if (relayStates.values.all { it.finished }) {
            complete(generation, accountPubkey, relayEvents, relayStates, onResult)
        }
    }

    private fun complete(
        generation: Int,
        accountPubkey: String,
        relayEvents: Map<String, LinkedHashMap<String, WebNostrEvent>>,
        relayStates: Map<String, WebMutableRelayStatsState>,
        onResult: (WebRelayStatsResult) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        completed = true
        activeGeneration += 1
        timeoutHandle?.let(::clearTimeout)
        timeoutHandle = null
        sockets.forEach { socket -> runCatching { socket.close() } }
        sockets.clear()
        val stats = relays.associateWith { relay ->
            val state = relayStates[relay] ?: WebMutableRelayStatsState(failed = true)
            webRelayEventStatForEvents(
                events = relayEvents[relay]?.values?.toList().orEmpty(),
                accountPubkey = accountPubkey,
                failed = state.failed,
                timedOut = state.timedOut,
            )
        }
        onResult(WebRelayStatsResult(stats))
    }

    private fun isCurrent(generation: Int): Boolean =
        !completed && generation == activeGeneration
}

private data class WebMutableRelayStatsState(
    var failed: Boolean = false,
    var timedOut: Boolean = false,
    var finished: Boolean = false,
)
