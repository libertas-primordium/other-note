package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrFilter
import com.libertasprimordium.othernote.nostr.NostrRelayMessage
import com.libertasprimordium.othernote.nostr.NostrWireJson
import com.libertasprimordium.othernote.nostr.ProfileMetadata
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.RelayPublishResult
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

class DesktopNostrClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val relayTimeoutMs: Long = 15_000,
    private val maxEvents: Int = 200,
) : NostrClient {
    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult = coroutineScope {
        val results = relays.distinct().map { relay ->
            async(Dispatchers.IO) { fetchFromRelay(relay, authorPubkey) }
        }.map { it.await() }
        RelayFetchResult(
            events = results.flatMap { it.events },
            statuses = results.map { it.status },
        )
    }

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult = coroutineScope {
        val statuses = relays.distinct().map { relay ->
            async(Dispatchers.IO) { publishToRelay(relay, event) }
        }.map { it.await() }
        RelayPublishResult(statuses)
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null

    private suspend fun publishToRelay(relay: String, event: NostrEvent): RelayStatus =
        withContext(Dispatchers.IO) {
            val url = normalizeRelayUrl(relay).getOrElse {
                return@withContext RelayStatus(relay, writable = false, message = it.message ?: "Invalid relay URL")
            }
            val socket = RelaySocket.connect(httpClient, url).getOrElse {
                return@withContext RelayStatus(url, writable = false, message = "Connect failed: ${it.safeMessage()}")
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
                    ok == null -> RelayStatus(url, writable = false, message = timeoutMessage("Timed out waiting for OK", notices))
                    ok.accepted -> RelayStatus(url, writable = true, message = ok.message.ifBlank { "accepted" })
                    else -> RelayStatus(url, writable = false, message = ok.message.ifBlank { "rejected" })
                }
            } finally {
                socket.close()
            }
        }

    private suspend fun fetchFromRelay(relay: String, authorPubkey: String): RelayFetchOneResult =
        withContext(Dispatchers.IO) {
            val url = normalizeRelayUrl(relay).getOrElse {
                return@withContext RelayFetchOneResult(
                    status = RelayStatus(relay, readable = false, message = it.message ?: "Invalid relay URL"),
                )
            }
            val primary = fetchWithFilter(url, NostrFilter(authors = listOf(authorPubkey), limit = maxEvents))
            if (primary.status.readable && primary.events.isNotEmpty()) return@withContext primary
            val fallback = fetchWithFilter(url, NostrFilter(authors = listOf(authorPubkey), tTags = emptyList(), limit = maxEvents))
            if (fallback.status.readable && fallback.events.isNotEmpty()) {
                return@withContext fallback.copy(events = fallback.events.filter { it.isOtherNoteEvent() })
            }
            if (!primary.status.readable && fallback.status.readable) {
                return@withContext fallback.copy(events = fallback.events.filter { it.isOtherNoteEvent() })
            }
            primary
        }

    private suspend fun fetchWithFilter(url: String, filter: NostrFilter): RelayFetchOneResult {
        val socket = RelaySocket.connect(httpClient, url).getOrElse {
            return RelayFetchOneResult(status = RelayStatus(url, readable = false, message = "Connect failed: ${it.safeMessage()}"))
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
                    message = fetchMessage(terminalMessage, events.size, notices),
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

    private fun timeoutMessage(base: String, notices: List<String>): String =
        if (notices.isEmpty()) base else "$base; relay messages: ${notices.joinToString(" | ") { it.take(160) }}"

    private fun Throwable.safeMessage(): String = message?.take(160) ?: javaClass.simpleName
}

private data class RelayFetchOneResult(
    val events: List<NostrEvent> = emptyList(),
    val status: RelayStatus,
)

private class RelaySocket private constructor(
    private val webSocket: WebSocket,
    private val messages: Channel<String>,
) {
    suspend fun send(message: String) {
        webSocket.sendText(message, true).get(5, TimeUnit.SECONDS)
    }

    suspend fun receive(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) {
        runCatching { messages.receive() }.getOrNull()
    }

    suspend fun <T> receiveUntil(timeoutMs: Long, transform: (String) -> T?): T? {
        var result: T? = null
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val raw = receive(timeoutMs) ?: return@withTimeoutOrNull
                val value = transform(raw)
                if (value != null) {
                    result = value
                    return@withTimeoutOrNull
                }
            }
        }
        return result
    }

    suspend fun close() {
        runCatching { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS) }
        messages.close()
    }

    companion object {
        suspend fun connect(httpClient: HttpClient, url: String): Result<RelaySocket> = runCatching {
            val listener = Listener()
            val webSocket = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(url), listener)
                .get(10, TimeUnit.SECONDS)
            RelaySocket(webSocket, listener.messages)
        }
    }

    private class Listener : WebSocket.Listener {
        val messages = Channel<String>(Channel.UNLIMITED)
        private val partial = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            partial.append(data)
            if (last) {
                messages.trySend(partial.toString())
                partial.clear()
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            messages.close(error)
        }
    }
}
