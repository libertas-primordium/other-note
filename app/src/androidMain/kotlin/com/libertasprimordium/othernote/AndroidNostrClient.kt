package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.nostr.FanoutNostrClient
import com.libertasprimordium.othernote.nostr.IncrementalNostrClient
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
) : IncrementalNostrClient, FanoutNostrClient {
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

private fun timedMessage(stage: String, outcome: String, start: TimeSource.Monotonic.ValueTimeMark, detail: String): String =
    "stage=$stage outcome=$outcome duration_ms=${start.elapsedNow().inWholeMilliseconds} $detail"

private fun timeoutMessage(base: String, notices: List<String>): String =
    if (notices.isEmpty()) base else "$base; relay messages: ${notices.joinToString(" | ") { it.take(160) }}"

private fun NostrFilter.safeLabel(): String =
    "authors=${authors.size},kinds=${kinds.joinToString("/")},t=${tTags.joinToString("/").ifBlank { "none" }},p=${pTags.size},limit=$limit"

private fun Throwable.safeMessage(): String = "${this::class.simpleName}: ${message?.take(160).orEmpty()}"
