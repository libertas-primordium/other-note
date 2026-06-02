package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.nostr.FanoutNostrClient
import com.libertasprimordium.othernote.nostr.IncrementalNostrClient
import com.libertasprimordium.othernote.nostr.Nip46LiveNostrClient
import com.libertasprimordium.othernote.nostr.Nip46LiveRelayOutcome
import com.libertasprimordium.othernote.nostr.Nip46LiveRelayResult
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrFilter
import com.libertasprimordium.othernote.nostr.NostrRelayMessage
import com.libertasprimordium.othernote.nostr.NostrWireJson
import com.libertasprimordium.othernote.nostr.ProfileMetadata
import com.libertasprimordium.othernote.nostr.PublishBestEffortHandle
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.RelayPublishResult
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class AndroidNostrClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    private val relayTimeoutMs: Long = 5_000,
    private val maxEvents: Int = 200,
) : IncrementalNostrClient, FanoutNostrClient, Nip46LiveNostrClient {
    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult = coroutineScope {
        val results = relays.distinct().map { relay ->
            async(Dispatchers.IO) { fetchFromRelay(relay, authorPubkey) }
        }.map { it.await() }
        RelayFetchResult(results.flatMap { it.events }, results.map { it.status })
    }

    override suspend fun fetchEvents(relays: List<String>, filter: NostrFilter): RelayFetchResult = coroutineScope {
        val results = relays.distinct().map { relay ->
            async(Dispatchers.IO) {
                val url = normalizeRelayUrl(relay).getOrElse {
                    return@async RelayFetchOneResult(status = RelayStatus(relay, readable = false, message = "stage=fetch outcome=invalid_url duration_ms=0 ${it.message ?: "Invalid relay URL"}"))
                }
                fetchWithFilter(url, filter.copy(limit = minOf(filter.limit, maxEvents)), "generic")
            }
        }.map { it.await() }
        RelayFetchResult(results.flatMap { it.events }, results.map { it.status })
    }

    override suspend fun fetchNotesIncrementally(
        relays: List<String>,
        authorPubkey: String,
        onRelayResult: suspend (RelayFetchResult) -> Unit,
    ): RelayFetchResult = coroutineScope {
        val uniqueRelays = relays.distinct()
        val channel = Channel<RelayFetchOneResult>(Channel.UNLIMITED)
        uniqueRelays.forEach { relay ->
            launch(Dispatchers.IO) {
                channel.send(runCatching { fetchFromRelay(relay, authorPubkey) }.getOrElse { failedFetchResult(relay, it) })
            }
        }
        val results = mutableListOf<RelayFetchOneResult>()
        while (results.size < uniqueRelays.size) {
            val result = channel.receive()
            results += result
            onRelayResult(RelayFetchResult(result.events, listOf(result.status)))
        }
        channel.close()
        RelayFetchResult(results.flatMap { it.events }, results.map { it.status })
    }

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult = coroutineScope {
        RelayPublishResult(
            relays.distinct().map { relay ->
                async(Dispatchers.IO) { publishToRelay(relay, event) }
            }.map { it.await() },
        )
    }

    override fun publishBestEffort(
        relays: List<String>,
        event: NostrEvent,
        scope: CoroutineScope,
        onStatus: (List<RelayStatus>) -> Unit,
    ): PublishBestEffortHandle {
        val uniqueRelays = relays.distinct()
        val firstAccepted = CompletableDeferred<RelayPublishResult>()
        val complete = CompletableDeferred<RelayPublishResult>()
        val channel = Channel<RelayStatus>(Channel.UNLIMITED)
        if (uniqueRelays.isEmpty()) {
            val empty = RelayPublishResult(emptyList())
            firstAccepted.complete(empty)
            complete.complete(empty)
            return PublishBestEffortHandle(firstAccepted, complete)
        }
        uniqueRelays.forEach { relay ->
            scope.launch(Dispatchers.IO) {
                channel.send(runCatching { publishToRelay(relay, event) }.getOrElse { failedPublishStatus(relay, it) })
            }
        }
        scope.launch {
            val statuses = mutableListOf<RelayStatus>()
            while (statuses.size < uniqueRelays.size) {
                val status = channel.receive()
                statuses += status
                runCatching { onStatus(statuses.toList()) }
                if (status.writable && !firstAccepted.isCompleted) {
                    firstAccepted.complete(RelayPublishResult(statuses.toList()))
                }
            }
            if (!firstAccepted.isCompleted) firstAccepted.complete(RelayPublishResult(statuses.toList()))
            complete.complete(RelayPublishResult(statuses.toList()))
            channel.close()
        }
        return PublishBestEffortHandle(firstAccepted, complete)
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null

    override suspend fun requestNip46Response(
        relays: List<String>,
        requestEvent: NostrEvent,
        filter: NostrFilter,
        timeoutMs: Long,
        onCandidate: suspend (relay: String, event: NostrEvent) -> Boolean,
    ): Nip46LiveRelayResult = coroutineScope {
        val uniqueRelays = relays.distinct()
        if (uniqueRelays.isEmpty()) {
            return@coroutineScope Nip46LiveRelayResult(
                responseFound = false,
                publishStatuses = emptyList(),
                candidateEventCount = 0,
                liveEventAfterPublishCount = 0,
                relayOutcomes = emptyList(),
            )
        }
        val start = TimeSource.Monotonic.markNow()
        val channel = Channel<Nip46RelayConversationResult>(Channel.UNLIMITED)
        val stateMutex = Mutex()
        val latestByRelay = uniqueRelays
            .map { normalizeRelayUrl(it).getOrNull() ?: it }
            .associateWith { relay ->
                Nip46RelayConversationResult(
                    relay = relay,
                    subscribed = false,
                    publishStatus = null,
                    latencyMs = 0,
                    failureReason = "not_started",
                )
            }
            .toMutableMap()
        suspend fun record(result: Nip46RelayConversationResult) {
            stateMutex.withLock {
                latestByRelay[result.relay] = result
            }
        }
        val jobs = uniqueRelays.map { relay ->
            launch(Dispatchers.IO) {
                val result = runCatching { requestNip46OnRelay(relay, requestEvent, filter, timeoutMs, onCandidate, ::record) }
                    .getOrElse { failedNip46Conversation(relay, it) }
                record(result)
                channel.send(result)
            }
        }
        val results = mutableListOf<Nip46RelayConversationResult>()
        var matched = false
        while (results.size < uniqueRelays.size && !matched) {
            val remaining = timeoutMs - start.elapsedNow().inWholeMilliseconds
            if (remaining <= 0) break
            val result = withTimeoutOrNull(remaining) { channel.receive() } ?: break
            results += result
            matched = result.matched
        }
        jobs.filter { it.isActive }.forEach { it.cancelAndJoin() }
        channel.close()
        val finalResults = stateMutex.withLock {
            latestByRelay.mapValues { (_, result) ->
                result.completeTimedOut(start)
            }
        }.values.toList()
        Nip46LiveRelayResult(
            responseFound = matched,
            publishStatuses = finalResults.mapNotNull { it.publishStatus },
            candidateEventCount = finalResults.sumOf { it.candidateEventCount },
            liveEventAfterPublishCount = finalResults.sumOf { it.liveEventAfterPublishCount },
            relayOutcomes = finalResults.map { it.toLiveOutcome() },
        )
    }

    private suspend fun publishToRelay(relay: String, event: NostrEvent): RelayStatus = withContext(Dispatchers.IO) {
        val start = TimeSource.Monotonic.markNow()
        val url = normalizeRelayUrl(relay).getOrElse {
            return@withContext RelayStatus(relay, writable = false, message = timedMessage("publish", "invalid_url", start, it.message ?: "Invalid relay URL"))
        }
        val socket = AndroidRelaySocket.connect(okHttpClient, url, relayTimeoutMs).getOrElse {
            return@withContext RelayStatus(url, writable = false, message = timedMessage("publish", "connect_failed", start, it.safeMessage()))
        }
        try {
            socket.send(NostrWireJson.publishEventMessage(event))
            val notices = mutableListOf<String>()
            val ok = socket.receiveUntil(relayTimeoutMs) { raw ->
                when (val message = runCatching { NostrWireJson.parseRelayMessage(raw) }.getOrNull()) {
                    is NostrRelayMessage.Ok -> if (message.eventId == event.id) message else null
                    is NostrRelayMessage.Notice -> {
                        notices += message.message
                        null
                    }
                    is NostrRelayMessage.Closed -> {
                        notices += message.message
                        null
                    }
                    else -> null
                }
            }
            when {
                ok == null -> RelayStatus(url, writable = false, message = timedMessage("publish", "timeout", start, timeoutMessage("Timed out waiting for OK", notices)))
                ok.accepted -> RelayStatus(url, writable = true, message = timedMessage("publish", "accepted", start, ok.message.ifBlank { "accepted" }))
                else -> RelayStatus(url, writable = false, message = timedMessage("publish", "rejected", start, ok.message.ifBlank { "rejected" }))
            }
        } finally {
            socket.close()
        }
    }

    private suspend fun fetchFromRelay(relay: String, authorPubkey: String): RelayFetchOneResult = withContext(Dispatchers.IO) {
        val url = normalizeRelayUrl(relay).getOrElse {
            return@withContext RelayFetchOneResult(status = RelayStatus(relay, readable = false, message = "stage=fetch outcome=invalid_url duration_ms=0 ${it.message ?: "Invalid relay URL"}"))
        }
        val primary = fetchWithFilter(url, NostrFilter(authors = listOf(authorPubkey), limit = maxEvents), "primary")
        if (primary.status.readable && primary.events.isNotEmpty()) return@withContext primary
        if (!primary.status.readable) return@withContext primary
        val fallback = fetchWithFilter(url, NostrFilter(authors = listOf(authorPubkey), tTags = emptyList(), limit = maxEvents * 5), "fallback")
        if (fallback.status.readable && fallback.events.isNotEmpty()) return@withContext fallback.copy(events = fallback.events.filter { it.isOtherNoteEvent() })
        primary
    }

    private suspend fun fetchWithFilter(url: String, filter: NostrFilter, mode: String): RelayFetchOneResult {
        val start = TimeSource.Monotonic.markNow()
        val socket = AndroidRelaySocket.connect(okHttpClient, url, relayTimeoutMs).getOrElse {
            return RelayFetchOneResult(status = RelayStatus(url, readable = false, message = timedMessage("fetch", "connect_failed", start, it.safeMessage())))
        }
        val subscriptionId = "other-note-${UUID.randomUUID()}"
        return try {
            socket.send(NostrWireJson.requestMessage(subscriptionId, filter))
            val events = mutableListOf<NostrEvent>()
            val notices = mutableListOf<String>()
            var terminalMessage: String? = null
            withTimeoutOrNull(relayTimeoutMs) {
                while (events.size < maxEvents && terminalMessage == null) {
                    val raw = socket.receive(relayTimeoutMs) ?: break
                    when (val message = runCatching { NostrWireJson.parseRelayMessage(raw) }.getOrNull()) {
                        is NostrRelayMessage.Event -> if (message.subscriptionId == subscriptionId) events += message.event
                        is NostrRelayMessage.Eose -> if (message.subscriptionId == subscriptionId) terminalMessage = "EOSE"
                        is NostrRelayMessage.Closed -> if (message.subscriptionId == subscriptionId) terminalMessage = message.message.ifBlank { "CLOSED" }
                        is NostrRelayMessage.Notice -> notices += message.message
                        else -> Unit
                    }
                }
            } ?: run {
                terminalMessage = "Timed out waiting for EOSE"
            }
            socket.send(NostrWireJson.closeMessage(subscriptionId))
            RelayFetchOneResult(
                events = events,
                status = RelayStatus(
                    url = url,
                    readable = terminalMessage == "EOSE" || events.isNotEmpty(),
                    message = timedMessage("fetch", if (terminalMessage == "EOSE" || events.isNotEmpty()) "complete" else "failed", start, "mode=$mode query=${filter.safeLabel()} ${fetchMessage(terminalMessage, events.size, notices)}"),
                ),
            )
        } finally {
            socket.close()
        }
    }

    private suspend fun requestNip46OnRelay(
        relay: String,
        requestEvent: NostrEvent,
        filter: NostrFilter,
        timeoutMs: Long,
        onCandidate: suspend (relay: String, event: NostrEvent) -> Boolean,
        onProgress: suspend (Nip46RelayConversationResult) -> Unit,
    ): Nip46RelayConversationResult {
        val start = TimeSource.Monotonic.markNow()
        val url = normalizeRelayUrl(relay).getOrElse {
            return Nip46RelayConversationResult(
                relay = relay,
                subscribed = false,
                publishStatus = RelayStatus(relay, writable = false, message = timedMessage("nip46", "invalid_url", start, it.message ?: "Invalid relay URL")),
                latencyMs = start.elapsedNow().inWholeMilliseconds,
                failureReason = "invalid_url",
            )
        }
        val socket = AndroidRelaySocket.connect(okHttpClient, url, relayTimeoutMs).getOrElse {
            return Nip46RelayConversationResult(
                relay = url,
                subscribed = false,
                publishStatus = RelayStatus(url, writable = false, message = timedMessage("nip46", "connect_failed", start, it.safeMessage())),
                latencyMs = start.elapsedNow().inWholeMilliseconds,
                failureReason = "connect_failed",
            )
        }
        val subscriptionId = "other-note-nip46-${UUID.randomUUID()}"
        return try {
            socket.send(NostrWireJson.requestMessage(subscriptionId, filter))
            var publishStatus: RelayStatus? = null
            var candidateCount = 0
            var liveAfterPublishCount = 0
            val notices = mutableListOf<String>()
            onProgress(
                Nip46RelayConversationResult(
                    relay = url,
                    subscribed = true,
                    publishStatus = null,
                    latencyMs = start.elapsedNow().inWholeMilliseconds,
                    failureReason = null,
                ),
            )
            socket.send(NostrWireJson.publishEventMessage(requestEvent))
            val matched = withTimeoutOrNull(timeoutMs) {
                while (true) {
                    val raw = socket.receive(timeoutMs) ?: return@withTimeoutOrNull false
                    when (val message = runCatching { NostrWireJson.parseRelayMessage(raw) }.getOrNull()) {
                        is NostrRelayMessage.Event -> if (message.subscriptionId == subscriptionId) {
                            liveAfterPublishCount++
                            candidateCount++
                            onProgress(
                                Nip46RelayConversationResult(
                                    relay = url,
                                    subscribed = true,
                                    publishStatus = publishStatus,
                                    candidateEventCount = candidateCount,
                                    liveEventAfterPublishCount = liveAfterPublishCount,
                                    latencyMs = start.elapsedNow().inWholeMilliseconds,
                                    failureReason = null,
                                ),
                            )
                            if (onCandidate(url, message.event)) return@withTimeoutOrNull true
                        }
                        is NostrRelayMessage.Ok -> if (message.eventId == requestEvent.id) {
                            publishStatus = if (message.accepted) {
                                RelayStatus(url, writable = true, message = timedMessage("publish", "accepted", start, message.message.ifBlank { "accepted" }))
                            } else {
                                RelayStatus(url, writable = false, message = timedMessage("publish", "rejected", start, message.message.ifBlank { "rejected" }))
                            }
                            onProgress(
                                Nip46RelayConversationResult(
                                    relay = url,
                                    subscribed = true,
                                    publishStatus = publishStatus,
                                    candidateEventCount = candidateCount,
                                    liveEventAfterPublishCount = liveAfterPublishCount,
                                    latencyMs = start.elapsedNow().inWholeMilliseconds,
                                    failureReason = if (message.accepted) null else "publish_rejected",
                                ),
                            )
                            if (!message.accepted) return@withTimeoutOrNull false
                        }
                        is NostrRelayMessage.Closed -> if (message.subscriptionId == subscriptionId) {
                            notices += message.message
                            return@withTimeoutOrNull false
                        }
                        is NostrRelayMessage.Notice -> notices += message.message
                        else -> Unit
                    }
                }
                false
            } ?: false
            val finalPublishStatus = publishStatus ?: if (matched) {
                RelayStatus(url, writable = true, message = timedMessage("publish", "response_matched", start, "response arrived before OK"))
            } else {
                RelayStatus(url, writable = false, message = timedMessage("publish", "timeout", start, timeoutMessage("Timed out waiting for OK or response", notices)))
            }
            val failureReason = when {
                matched -> null
                publishStatus?.writable == true -> "response_timeout"
                publishStatus?.message?.contains("outcome=rejected") == true -> "publish_rejected"
                publishStatus?.message?.contains("outcome=timeout") == true -> "publish_timeout"
                publishStatus != null -> "publish_failed"
                else -> "publish_timeout"
            }
            val result = Nip46RelayConversationResult(
                relay = url,
                subscribed = true,
                publishStatus = finalPublishStatus,
                candidateEventCount = candidateCount,
                liveEventAfterPublishCount = liveAfterPublishCount,
                matched = matched,
                latencyMs = start.elapsedNow().inWholeMilliseconds,
                failureReason = failureReason,
            )
            onProgress(result)
            result
        } finally {
            runCatching { socket.send(NostrWireJson.closeMessage(subscriptionId)) }
            socket.close()
        }
    }

    private fun fetchMessage(terminalMessage: String?, eventCount: Int, notices: List<String>): String {
        val base = when {
            eventCount >= maxEvents -> "Reached max event count: $eventCount"
            terminalMessage == null -> "Connection ended before EOSE"
            terminalMessage == "EOSE" -> "EOSE with $eventCount event(s)"
            else -> terminalMessage
        }
        return timeoutMessage(base, notices)
    }
}

private data class RelayFetchOneResult(
    val events: List<NostrEvent> = emptyList(),
    val status: RelayStatus,
)

private data class Nip46RelayConversationResult(
    val relay: String,
    val subscribed: Boolean,
    val publishStatus: RelayStatus?,
    val candidateEventCount: Int = 0,
    val liveEventAfterPublishCount: Int = 0,
    val matched: Boolean = false,
    val latencyMs: Long = 0,
    val failureReason: String? = null,
)

private fun Nip46RelayConversationResult.completeTimedOut(start: TimeSource.Monotonic.ValueTimeMark): Nip46RelayConversationResult {
    if (matched || failureReason in setOf("invalid_url", "connect_failed", "publish_rejected", "failed")) return this
    val timeoutMs = start.elapsedNow().inWholeMilliseconds
    val finalStatus = publishStatus ?: RelayStatus(
        url = relay,
        writable = false,
        message = "stage=nip46 outcome=timeout duration_ms=$timeoutMs " +
            (if (subscribed) "timed out after subscription on same relay" else "global request timeout before subscription"),
    )
    val finalReason = when {
        publishStatus?.writable == true -> "response_timeout"
        subscribed -> "publish_timeout"
        failureReason == "not_started" -> "request_timeout"
        else -> failureReason ?: "request_timeout"
    }
    return copy(
        publishStatus = finalStatus,
        latencyMs = timeoutMs,
        failureReason = finalReason,
    )
}

private class AndroidRelaySocket private constructor(
    private val webSocket: WebSocket,
    private val messages: Channel<String>,
    private val closed: CompletableDeferred<Unit>,
) {
    fun send(message: String) {
        webSocket.send(message)
    }

    suspend fun receive(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) { messages.receive() }

    suspend fun <T : Any> receiveUntil(timeoutMs: Long, match: (String) -> T?): T? =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val result = match(messages.receive())
                if (result != null) return@withTimeoutOrNull result
            }
            error("unreachable")
        }

    fun close() {
        webSocket.close(1000, "done")
        messages.close()
        closed.complete(Unit)
    }

    companion object {
        suspend fun connect(client: OkHttpClient, url: String, timeoutMs: Long): Result<AndroidRelaySocket> = runCatching {
            val opened = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val messages = Channel<String>(Channel.UNLIMITED)
            lateinit var webSocket: WebSocket
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    messages.trySend(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                    messages.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened.isCompleted) opened.completeExceptionally(t)
                    closed.complete(Unit)
                    messages.close(t)
                }
            }
            webSocket = client.newWebSocket(Request.Builder().url(url).build(), listener)
            withTimeoutOrNull(timeoutMs) { opened.await() } ?: error("connect timeout")
            AndroidRelaySocket(webSocket, messages, closed)
        }
    }
}

private fun failedFetchResult(relay: String, throwable: Throwable): RelayFetchOneResult =
    RelayFetchOneResult(status = RelayStatus(relay, readable = false, message = "stage=fetch outcome=failed duration_ms=0 ${throwable.safeMessage()}"))

private fun failedPublishStatus(relay: String, throwable: Throwable): RelayStatus =
    RelayStatus(relay, writable = false, message = "stage=publish outcome=failed duration_ms=0 ${throwable.safeMessage()}")

private fun failedNip46Conversation(relay: String, throwable: Throwable): Nip46RelayConversationResult =
    Nip46RelayConversationResult(
        relay = normalizeRelayUrl(relay).getOrNull() ?: relay,
        subscribed = false,
        publishStatus = RelayStatus(
            url = normalizeRelayUrl(relay).getOrNull() ?: relay,
            writable = false,
            message = "stage=nip46 outcome=failed duration_ms=0 ${throwable.safeMessage()}",
        ),
        failureReason = "failed",
    )

private fun Nip46RelayConversationResult.toLiveOutcome(): Nip46LiveRelayOutcome =
    Nip46LiveRelayOutcome(
        relay = relay,
        subscribed = subscribed,
        publishStatus = publishStatus,
        responseMatched = matched,
        candidateEventCount = candidateEventCount,
        liveEventAfterPublishCount = liveEventAfterPublishCount,
        latencyMs = latencyMs,
        failureReason = failureReason,
    )

private fun timedMessage(stage: String, outcome: String, start: TimeSource.Monotonic.ValueTimeMark, detail: String): String =
    "stage=$stage outcome=$outcome duration_ms=${start.elapsedNow().inWholeMilliseconds} $detail"

private fun timeoutMessage(base: String, notices: List<String>): String =
    if (notices.isEmpty()) base else "$base; relay messages: ${notices.joinToString(" | ") { it.take(160) }}"

private fun NostrFilter.safeLabel(): String =
    "authors=${authors.size},kinds=${kinds.joinToString("/")},t=${tTags.joinToString("/").ifBlank { "none" }},p=${pTags.size},since=${since ?: "none"},limit=$limit"

private fun Throwable.safeMessage(): String = "${this::class.simpleName}: ${message?.take(160).orEmpty()}"
