package com.libertasprimordium.othernote.web

import kotlin.js.Promise
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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

internal const val WebNoteKind = 30078
internal const val WebOtherNoteTag = "other-note"
internal const val WebNotePayloadSchema = "com.libertasprimordium.othernote.note.v1"
private const val WebNoteRelaySubscriptionId = "other-note-web-notes"
private const val WebNoteRelayTimeoutMs = 12_000
private const val WebNotePublishTimeoutMs = 10_000

internal val DefaultWebNoteRelays = listOf(
    "wss://relay.damus.io",
    "wss://relay.primal.net",
    "wss://relay.nostr.net",
    "wss://nos.lol",
    "wss://relay.ditto.pub",
)

external interface Nip07Nip44 {
    fun encrypt(pubkey: String, plaintext: String): Promise<String?>
    fun decrypt(pubkey: String, ciphertext: String): Promise<String?>
}

internal sealed interface WebNoteLoadState {
    data object Idle : WebNoteLoadState
    data class Loading(val message: String) : WebNoteLoadState
    data class Loaded(val notes: List<WebReadOnlyNote>, val status: String? = null) : WebNoteLoadState
    data class Empty(val status: String? = null) : WebNoteLoadState
    data class SignerUnsupported(val message: String) : WebNoteLoadState
    data class Failed(val message: String) : WebNoteLoadState
}

internal object WebNoteCopy {
    const val Nip07DecryptUnavailable =
        "This extension signed you in, but it does not expose the decrypt capability needed to read notes yet."
    const val NoteDecryptUnavailable = "The active signer cannot decrypt notes in this web session."
    const val RelayFetchFailed = "Could not fetch notes from the note relays."
    const val NoReadableRelays = "No note relay returned readable results."
    const val AllDecryptFailed = "Fetched encrypted notes, but none could be decrypted."
    const val NoNotes = "No notes found for this account."
    const val CrudCapabilityUnavailable =
        "The active signer does not expose the encrypt and sign capabilities needed to save notes in this web preview."
    const val EncryptFailed = "Could not encrypt the note with the active signer."
    const val SignFailed = "Could not sign the encrypted note event with the active signer."
    const val EventValidationFailed = "The signer returned an invalid note event."
    const val PublishFailed = "No note relay accepted the signed encrypted note event."
    const val PublishPartial = "Saved to at least one note relay; some relays did not accept the write."
    const val PublishSucceeded = "Saved to note relays."
}

internal data class WebReadOnlyNote(
    val id: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val bodyMarkdown: String,
    val deleted: Boolean = false,
    val sourceEventId: String? = null,
) {
    val dTag: String get() = webNoteDTag(id)
}

@Serializable
internal data class WebNotePayload(
    val schema: String = WebNotePayloadSchema,
    @SerialName("note_id")
    val noteId: String,
    @SerialName("created_at_ms")
    val createdAtMs: Long,
    @SerialName("updated_at_ms")
    val updatedAtMs: Long,
    @SerialName("body_markdown")
    val bodyMarkdown: String,
    val deleted: Boolean,
)

internal data class WebReducedNoteState(
    val notes: List<WebReadOnlyNote>,
    val selectedEvents: List<WebNostrEvent>,
    val rejectedCount: Int,
    val decryptRejectedCount: Int,
    val payloadRejectedCount: Int,
    val dTagRejectedCount: Int,
    val selectedNotes: List<WebReadOnlyNote>,
    val decryptedByEventId: Map<String, String>,
)

internal data class WebNoteRelayStatus(
    val url: String,
    val connected: Boolean = false,
    val returnedEvents: Int = 0,
    val acceptedWrite: Boolean = false,
    val failed: Boolean = false,
    val timedOut: Boolean = false,
)

internal data class WebNoteRelayFetchResult(
    val events: List<WebNostrEvent>,
    val statuses: List<WebNoteRelayStatus>,
)

internal sealed interface WebNoteLoadResult {
    data class Loaded(
        val state: WebReducedNoteState,
        val relayStatus: String?,
        val events: List<WebNostrEvent>,
        val decryptedByEventId: Map<String, String>,
    ) : WebNoteLoadResult
    data class Failed(val safeMessage: String) : WebNoteLoadResult
    data class SignerUnsupported(val safeMessage: String) : WebNoteLoadResult
}

internal interface WebNoteDecryptor {
    fun decrypt(ciphertext: String, onResult: (Result<String>) -> Unit)
}

internal class WebNip46NoteDecryptor(
    private val remoteSigner: WebNip46RemoteSigner,
    private val userPubkey: String,
) : WebNoteDecryptor {
    override fun decrypt(ciphertext: String, onResult: (Result<String>) -> Unit) {
        remoteSigner.decryptNotePayload(userPubkey, ciphertext) { result ->
            when (result) {
                is WebNip46DecryptResult.Decrypted -> onResult(Result.success(result.plaintext))
                is WebNip46DecryptResult.Failed -> onResult(Result.failure(IllegalStateException(result.safeMessage)))
            }
        }
    }
}

internal class WebNip07NoteDecryptor(
    private val nip44: Nip07Nip44?,
    private val userPubkey: String,
) : WebNoteDecryptor {
    override fun decrypt(ciphertext: String, onResult: (Result<String>) -> Unit) {
        val decrypt = nip44
        if (decrypt == null) {
            onResult(Result.failure(IllegalStateException(WebNoteCopy.Nip07DecryptUnavailable)))
            return
        }
        try {
            decrypt.decrypt(userPubkey, ciphertext).then(
                { plaintext ->
                    if (plaintext.isNullOrBlank()) {
                        onResult(Result.failure(IllegalStateException(WebNoteCopy.NoteDecryptUnavailable)))
                    } else {
                        onResult(Result.success(plaintext))
                    }
                },
                {
                    onResult(Result.failure(IllegalStateException(WebNoteCopy.NoteDecryptUnavailable)))
                },
            )
        } catch (_: Throwable) {
            onResult(Result.failure(IllegalStateException(WebNoteCopy.NoteDecryptUnavailable)))
        }
    }
}

internal data class WebUnsignedNoteEvent(
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)

internal sealed interface WebSignerOperationResult {
    data class Success(val value: String) : WebSignerOperationResult
    data class Failed(val safeMessage: String) : WebSignerOperationResult
}

internal sealed interface WebNoteSignResult {
    data class Signed(val event: WebNostrEvent) : WebNoteSignResult
    data class Failed(val safeMessage: String) : WebNoteSignResult
}

internal interface WebNoteCrudSigner {
    fun encrypt(plaintext: String, onResult: (WebSignerOperationResult) -> Unit)
    fun sign(unsignedEvent: WebUnsignedNoteEvent, onResult: (WebNoteSignResult) -> Unit)
}

internal class WebNip46NoteCrudSigner(
    private val remoteSigner: WebNip46RemoteSigner,
    private val userPubkey: String,
) : WebNoteCrudSigner {
    override fun encrypt(plaintext: String, onResult: (WebSignerOperationResult) -> Unit) {
        remoteSigner.encryptNotePayload(userPubkey, plaintext) { result ->
            when (result) {
                is WebNip46EncryptResult.Encrypted -> onResult(WebSignerOperationResult.Success(result.ciphertext))
                is WebNip46EncryptResult.Failed -> onResult(WebSignerOperationResult.Failed(result.safeMessage))
            }
        }
    }

    override fun sign(unsignedEvent: WebUnsignedNoteEvent, onResult: (WebNoteSignResult) -> Unit) {
        remoteSigner.signNoteEvent(unsignedEvent) { result ->
            when (result) {
                is WebNip46NoteSignResult.Signed -> onResult(WebNoteSignResult.Signed(result.event))
                is WebNip46NoteSignResult.Failed -> onResult(WebNoteSignResult.Failed(result.safeMessage))
            }
        }
    }
}

internal class WebNip07NoteCrudSigner(
    private val signer: Nip07Signer?,
    private val userPubkey: String,
) : WebNoteCrudSigner {
    override fun encrypt(plaintext: String, onResult: (WebSignerOperationResult) -> Unit) {
        val nip44 = signer?.nip44
        if (nip44 == null || !signer.hasNip07EncryptCapability()) {
            onResult(WebSignerOperationResult.Failed(WebNoteCopy.CrudCapabilityUnavailable))
            return
        }
        try {
            nip44.encrypt(userPubkey, plaintext).then(
                { ciphertext ->
                    if (ciphertext.isNullOrBlank()) {
                        onResult(WebSignerOperationResult.Failed(WebNoteCopy.EncryptFailed))
                    } else {
                        onResult(WebSignerOperationResult.Success(ciphertext))
                    }
                },
                { onResult(WebSignerOperationResult.Failed(WebNoteCopy.EncryptFailed)) },
            )
        } catch (_: Throwable) {
            onResult(WebSignerOperationResult.Failed(WebNoteCopy.EncryptFailed))
        }
    }

    override fun sign(unsignedEvent: WebUnsignedNoteEvent, onResult: (WebNoteSignResult) -> Unit) {
        val extension = signer
        if (extension == null || !extension.hasNip07SignCapability()) {
            onResult(WebNoteSignResult.Failed(WebNoteCopy.CrudCapabilityUnavailable))
            return
        }
        try {
            extension.signEvent(unsignedEvent.toDynamicUnsignedEvent()).then(
                { signed: dynamic ->
                    val event = parseDynamicSignedEvent(signed)
                    if (event == null) {
                        onResult(WebNoteSignResult.Failed(WebNoteCopy.SignFailed))
                    } else {
                        onResult(WebNoteSignResult.Signed(event))
                    }
                },
                { _: dynamic -> onResult(WebNoteSignResult.Failed(WebNoteCopy.SignFailed)) },
            )
        } catch (_: Throwable) {
            onResult(WebNoteSignResult.Failed(WebNoteCopy.SignFailed))
        }
    }
}

internal sealed interface WebNoteSaveResult {
    data class Published(
        val note: WebReadOnlyNote,
        val event: WebNostrEvent,
        val plaintextPayload: String,
        val status: String,
        val relayStatuses: List<WebNoteRelayStatus>,
    ) : WebNoteSaveResult

    data class Failed(val safeMessage: String) : WebNoteSaveResult
}

internal class WebNoteCrudService(
    private val publisher: WebNoteRelayPublisher = WebNoteRelayPublisher(),
    private val noteIdGenerator: () -> String = ::webStableRandomId,
    private val nowMs: () -> Long = ::webNowMs,
) {
    fun close() {
        publisher.close()
    }

    fun save(
        existing: WebReadOnlyNote?,
        bodyMarkdown: String,
        accountPubkey: String,
        signer: WebNoteCrudSigner?,
        onProgress: (String) -> Unit,
        onResult: (WebNoteSaveResult) -> Unit,
    ) {
        if (signer == null) {
            onResult(WebNoteSaveResult.Failed(WebNoteCopy.CrudCapabilityUnavailable))
            return
        }
        val note = buildWebNoteForSave(existing, bodyMarkdown, noteIdGenerator, nowMs)
        publishNote(note, accountPubkey, signer, onProgress, onResult)
    }

    fun delete(
        note: WebReadOnlyNote,
        accountPubkey: String,
        signer: WebNoteCrudSigner?,
        onProgress: (String) -> Unit,
        onResult: (WebNoteSaveResult) -> Unit,
    ) {
        if (signer == null) {
            onResult(WebNoteSaveResult.Failed(WebNoteCopy.CrudCapabilityUnavailable))
            return
        }
        val tombstone = note.copy(
            bodyMarkdown = "",
            deleted = true,
            updatedAtMs = nextWebNoteVersionUpdatedAtMs(note.updatedAtMs, nowMs()),
        )
        publishNote(tombstone, accountPubkey, signer, onProgress, onResult)
    }

    private fun publishNote(
        note: WebReadOnlyNote,
        accountPubkey: String,
        signer: WebNoteCrudSigner,
        onProgress: (String) -> Unit,
        onResult: (WebNoteSaveResult) -> Unit,
    ) {
        val plaintextPayload = encodeWebNotePayload(note).getOrElse {
            onResult(WebNoteSaveResult.Failed("Could not encode the note payload."))
            return
        }
        onProgress("Encrypting note with the active signer.")
        signer.encrypt(plaintextPayload) { encrypted ->
            when (encrypted) {
                is WebSignerOperationResult.Failed -> onResult(WebNoteSaveResult.Failed(encrypted.safeMessage))
                is WebSignerOperationResult.Success -> {
                    val unsignedEvent = buildUnsignedWebNoteEvent(note, accountPubkey, encrypted.value)
                    onProgress("Signing encrypted note event.")
                    signer.sign(unsignedEvent) { signed ->
                        when (signed) {
                            is WebNoteSignResult.Failed -> onResult(WebNoteSaveResult.Failed(signed.safeMessage))
                            is WebNoteSignResult.Signed -> {
                                if (!validateWebSignedNoteEvent(note, signed.event, accountPubkey)) {
                                    onResult(WebNoteSaveResult.Failed(WebNoteCopy.EventValidationFailed))
                                    return@sign
                                }
                                onProgress("Publishing signed encrypted note event to note relays.")
                                publisher.publish(signed.event) { publish ->
                                    if (!publish.anyAccepted) {
                                        onResult(WebNoteSaveResult.Failed(WebNoteCopy.PublishFailed))
                                    } else {
                                        onResult(
                                            WebNoteSaveResult.Published(
                                                note = note.copy(sourceEventId = signed.event.id),
                                                event = signed.event,
                                                plaintextPayload = plaintextPayload,
                                                status = publish.safeStatus,
                                                relayStatuses = publish.statuses,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal class WebNoteLoader(
    private val relayFetcher: WebNoteRelayFetcher = WebNoteRelayFetcher(),
) {
    fun close() {
        relayFetcher.close()
    }

    fun load(
        accountPubkey: String,
        decryptor: WebNoteDecryptor?,
        onProgress: (String) -> Unit,
        onResult: (WebNoteLoadResult) -> Unit,
    ) {
        if (decryptor == null) {
            onResult(WebNoteLoadResult.SignerUnsupported(WebNoteCopy.Nip07DecryptUnavailable))
            return
        }
        onProgress("Fetching encrypted notes from note relays.")
        relayFetcher.fetch(accountPubkey) { fetch ->
            val status = fetch.statuses.safeRelayStatus()
            if (fetch.events.isEmpty() && fetch.statuses.none { it.connected }) {
                onResult(WebNoteLoadResult.Failed(WebNoteCopy.NoReadableRelays))
                return@fetch
            }
            if (fetch.events.isEmpty()) {
                onResult(WebNoteLoadResult.Loaded(emptyReducedNotes(), status, emptyList(), emptyMap()))
                return@fetch
            }
            onProgress("Decrypting notes with the active signer.")
            reduceWithAsyncDecrypt(fetch.events, decryptor) { reduced ->
                when {
                    reduced.notes.isNotEmpty() -> onResult(WebNoteLoadResult.Loaded(reduced, status, fetch.events, reduced.decryptedByEventId))
                    reduced.decryptRejectedCount > 0 && reduced.payloadRejectedCount == 0 && reduced.dTagRejectedCount == 0 ->
                        onResult(WebNoteLoadResult.Failed(WebNoteCopy.AllDecryptFailed))
                    else -> onResult(WebNoteLoadResult.Loaded(reduced, status, fetch.events, reduced.decryptedByEventId))
                }
            }
        }
    }
}

internal data class WebNotePublishResult(
    val statuses: List<WebNoteRelayStatus>,
) {
    val anyAccepted: Boolean get() = statuses.any { it.acceptedWrite }
    val safeStatus: String
        get() {
            val accepted = statuses.count { it.acceptedWrite }
            return when {
                accepted <= 0 -> WebNoteCopy.PublishFailed
                accepted == statuses.size -> "${WebNoteCopy.PublishSucceeded} ($accepted/${statuses.size})"
                else -> "${WebNoteCopy.PublishPartial} ($accepted/${statuses.size})"
            }
        }
}

internal class WebNoteRelayPublisher(
    private val relays: List<String> = DefaultWebNoteRelays,
    private val timeoutMs: Int = WebNotePublishTimeoutMs,
) {
    private val sockets = mutableListOf<WebSocket>()
    private var completed = false
    private var timeoutHandle: Int? = null
    private var activeGeneration = 0

    fun publish(event: WebNostrEvent, onResult: (WebNotePublishResult) -> Unit) {
        closeSockets()
        completed = false
        val generation = ++activeGeneration
        val statuses = relays.associateWith { WebMutableNoteRelayStatus(url = it) }.toMutableMap()
        timeoutHandle = setTimeout(
            {
                statuses.values.filterNot { it.finished }.forEach { status ->
                    status.timedOut = true
                    status.finished = true
                }
                completePublish(generation, statuses.values.map { it.snapshot() }, onResult)
            },
            timeoutMs,
        )
        relays.forEach { relay ->
            val socket = runCatching { WebSocket(relay) }.getOrElse {
                statuses[relay]?.failed = true
                statuses[relay]?.finished = true
                maybeCompletePublish(generation, statuses, onResult)
                return@forEach
            }
            sockets += socket
            socket.onopen = {
                if (isCurrent(generation)) {
                    statuses[relay]?.connected = true
                    socket.send(webNoteEventMessage(event))
                }
            }
            socket.onmessage = { message ->
                if (isCurrent(generation)) {
                    handlePublishMessage(generation, relay, event.id, message.data, statuses, onResult)
                }
            }
            socket.onerror = { markPublishFailed(generation, relay, statuses, onResult) }
            socket.onclose = { markPublishFailed(generation, relay, statuses, onResult) }
        }
    }

    fun close() {
        activeGeneration += 1
        completed = true
        closeSockets()
    }

    private fun handlePublishMessage(
        generation: Int,
        relay: String,
        eventId: String,
        raw: String,
        statuses: MutableMap<String, WebMutableNoteRelayStatus>,
        onResult: (WebNotePublishResult) -> Unit,
    ) {
        val ok = parseWebRelayOk(raw) ?: return
        if (ok.eventId != eventId) return
        val status = statuses[relay] ?: return
        status.acceptedWrite = ok.accepted
        status.failed = !ok.accepted
        status.finished = true
        maybeCompletePublish(generation, statuses, onResult)
    }

    private fun markPublishFailed(
        generation: Int,
        relay: String,
        statuses: MutableMap<String, WebMutableNoteRelayStatus>,
        onResult: (WebNotePublishResult) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        val status = statuses[relay] ?: return
        if (status.finished) return
        status.failed = true
        status.finished = true
        maybeCompletePublish(generation, statuses, onResult)
    }

    private fun maybeCompletePublish(
        generation: Int,
        statuses: MutableMap<String, WebMutableNoteRelayStatus>,
        onResult: (WebNotePublishResult) -> Unit,
    ) {
        if (statuses.values.all { it.finished }) {
            completePublish(generation, statuses.values.map { it.snapshot() }, onResult)
        }
    }

    private fun completePublish(
        generation: Int,
        statuses: List<WebNoteRelayStatus>,
        onResult: (WebNotePublishResult) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        completed = true
        activeGeneration += 1
        closeSockets()
        onResult(WebNotePublishResult(statuses))
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

internal class WebNoteRelayFetcher(
    private val relays: List<String> = DefaultWebNoteRelays,
    private val timeoutMs: Int = WebNoteRelayTimeoutMs,
) {
    private val sockets = mutableListOf<WebSocket>()
    private var completed = false
    private var timeoutHandle: Int? = null
    private var activeGeneration = 0

    fun fetch(accountPubkey: String, onResult: (WebNoteRelayFetchResult) -> Unit) {
        closeSockets()
        completed = false
        val generation = ++activeGeneration
        val eventsById = linkedMapOf<String, WebNostrEvent>()
        val statuses = relays.associateWith { WebMutableNoteRelayStatus(url = it) }.toMutableMap()
        timeoutHandle = setTimeout(
            {
                statuses.values.filterNot { it.finished }.forEach { status ->
                    status.timedOut = true
                    status.finished = true
                }
                complete(generation, eventsById.values.toList(), statuses.values.map { it.snapshot() }, onResult)
            },
            timeoutMs,
        )
        relays.forEach { relay ->
            val socket = runCatching { WebSocket(relay) }.getOrElse {
                statuses[relay]?.failed = true
                statuses[relay]?.finished = true
                maybeComplete(generation, eventsById, statuses, onResult)
                return@forEach
            }
            sockets += socket
            socket.onopen = {
                if (isCurrent(generation)) {
                    statuses[relay]?.connected = true
                    socket.send(webNoteRequestMessage(accountPubkey))
                }
            }
            socket.onmessage = { message ->
                if (isCurrent(generation)) {
                    handleRelayMessage(generation, accountPubkey, relay, message.data, eventsById, statuses, onResult)
                }
            }
            socket.onerror = {
                markFailed(generation, relay, eventsById, statuses, onResult)
            }
            socket.onclose = {
                markFailed(generation, relay, eventsById, statuses, onResult)
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
        statuses: MutableMap<String, WebMutableNoteRelayStatus>,
        onResult: (WebNoteRelayFetchResult) -> Unit,
    ) {
        when (val message = parseWebNoteRelayMessage(raw)) {
            is WebNoteRelayMessage.Event -> {
                if (
                    message.subscriptionId == WebNoteRelaySubscriptionId &&
                    message.event.pubkey == accountPubkey &&
                    message.event.isReadableOtherNoteEvent()
                ) {
                    eventsById[message.event.id] = message.event
                    statuses[relay]?.returnedEvents = (statuses[relay]?.returnedEvents ?: 0) + 1
                }
            }
            is WebNoteRelayMessage.Eose -> {
                if (message.subscriptionId == WebNoteRelaySubscriptionId) {
                    statuses[relay]?.finished = true
                    maybeComplete(generation, eventsById, statuses, onResult)
                }
            }
            is WebNoteRelayMessage.Closed -> markFailed(generation, relay, eventsById, statuses, onResult)
            WebNoteRelayMessage.Ignored -> Unit
        }
    }

    private fun markFailed(
        generation: Int,
        relay: String,
        eventsById: MutableMap<String, WebNostrEvent>,
        statuses: MutableMap<String, WebMutableNoteRelayStatus>,
        onResult: (WebNoteRelayFetchResult) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        val status = statuses[relay] ?: return
        if (status.finished) return
        status.failed = true
        status.finished = true
        maybeComplete(generation, eventsById, statuses, onResult)
    }

    private fun maybeComplete(
        generation: Int,
        eventsById: MutableMap<String, WebNostrEvent>,
        statuses: MutableMap<String, WebMutableNoteRelayStatus>,
        onResult: (WebNoteRelayFetchResult) -> Unit,
    ) {
        if (statuses.values.all { it.finished }) {
            complete(generation, eventsById.values.toList(), statuses.values.map { it.snapshot() }, onResult)
        }
    }

    private fun complete(
        generation: Int,
        events: List<WebNostrEvent>,
        statuses: List<WebNoteRelayStatus>,
        onResult: (WebNoteRelayFetchResult) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        completed = true
        activeGeneration += 1
        closeSockets()
        onResult(WebNoteRelayFetchResult(events, statuses))
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

private data class WebMutableNoteRelayStatus(
    val url: String,
    var connected: Boolean = false,
    var returnedEvents: Int = 0,
    var acceptedWrite: Boolean = false,
    var failed: Boolean = false,
    var timedOut: Boolean = false,
    var finished: Boolean = false,
) {
    fun snapshot(): WebNoteRelayStatus =
        WebNoteRelayStatus(url, connected, returnedEvents, acceptedWrite, failed, timedOut)
}

private sealed interface WebNoteRelayMessage {
    data class Event(val subscriptionId: String, val event: WebNostrEvent) : WebNoteRelayMessage
    data class Eose(val subscriptionId: String) : WebNoteRelayMessage
    data object Closed : WebNoteRelayMessage
    data object Ignored : WebNoteRelayMessage
}

internal fun reduceDecryptedWebNoteEvents(
    events: List<WebNostrEvent>,
    decryptedByEventId: Map<String, String>,
): WebReducedNoteState {
    val accepted = mutableListOf<Pair<WebNostrEvent, WebReadOnlyNote>>()
    var decryptRejected = 0
    var payloadRejected = 0
    var dTagRejected = 0
    events.filter { it.isReadableOtherNoteEvent() && it.dTag() != null }.forEach { event ->
        val plaintext = decryptedByEventId[event.id]
        if (plaintext == null) {
            decryptRejected++
            return@forEach
        }
        val payload = decodeWebNotePayload(plaintext).getOrNull()
        if (payload == null) {
            payloadRejected++
            return@forEach
        }
        val note = payload.toWebNote(event.id)
        if (event.dTag() != note.dTag) {
            dTagRejected++
            return@forEach
        }
        accepted += event to note
    }
    val selected = accepted
        .groupBy { it.second.dTag }
        .values
        .map { versions ->
            versions
                .sortedWith(compareByDescending<Pair<WebNostrEvent, WebReadOnlyNote>> { it.first.createdAt }.thenBy { it.first.id })
                .first()
        }
    return WebReducedNoteState(
        notes = selected.map { it.second }.filterNot { it.deleted }.sortedByDescending { it.updatedAtMs },
        selectedEvents = selected.map { it.first },
        rejectedCount = decryptRejected + payloadRejected + dTagRejected,
        decryptRejectedCount = decryptRejected,
        payloadRejectedCount = payloadRejected,
        dTagRejectedCount = dTagRejected,
        selectedNotes = selected.map { it.second },
        decryptedByEventId = decryptedByEventId,
    )
}

private fun reduceWithAsyncDecrypt(
    events: List<WebNostrEvent>,
    decryptor: WebNoteDecryptor,
    onResult: (WebReducedNoteState) -> Unit,
) {
    val decrypted = linkedMapOf<String, String>()
    fun decryptAt(index: Int) {
        if (index >= events.size) {
            onResult(reduceDecryptedWebNoteEvents(events, decrypted))
            return
        }
        val event = events[index]
        decryptor.decrypt(event.content) { result ->
            result.getOrNull()?.let { decrypted[event.id] = it }
            decryptAt(index + 1)
        }
    }
    decryptAt(0)
}

private fun emptyReducedNotes(): WebReducedNoteState =
    WebReducedNoteState(
        notes = emptyList(),
        selectedEvents = emptyList(),
        rejectedCount = 0,
        decryptRejectedCount = 0,
        payloadRejectedCount = 0,
        dTagRejectedCount = 0,
        selectedNotes = emptyList(),
        decryptedByEventId = emptyMap(),
    )

private val WebNoteJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal fun decodeWebNotePayload(raw: String): Result<WebNotePayload> = runCatching {
    val payload = WebNoteJson.decodeFromString<WebNotePayload>(raw)
    require(payload.schema == WebNotePayloadSchema) { "Unsupported note payload schema" }
    payload
}

internal fun encodeWebNotePayload(note: WebReadOnlyNote): Result<String> = runCatching {
    WebNoteJson.encodeToString(
        WebNotePayload(
            noteId = note.id,
            createdAtMs = note.createdAtMs,
            updatedAtMs = note.updatedAtMs,
            bodyMarkdown = note.bodyMarkdown,
            deleted = note.deleted,
        ),
    )
}

private fun WebNotePayload.toWebNote(sourceEventId: String): WebReadOnlyNote =
    WebReadOnlyNote(
        id = noteId,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        bodyMarkdown = bodyMarkdown,
        deleted = deleted,
        sourceEventId = sourceEventId,
    )

internal fun webNoteDTag(noteId: String): String = "other-note:note:$noteId"

internal fun WebNostrEvent.dTag(): String? =
    tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)

internal fun WebNostrEvent.isReadableOtherNoteEvent(): Boolean =
    kind == WebNoteKind && tags.any { it.size >= 2 && it[0] == "t" && it[1] == WebOtherNoteTag }

internal fun webNoteRequestMessage(accountPubkey: String): String =
    buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(WebNoteRelaySubscriptionId))
        add(
            buildJsonObject {
                put("authors", buildJsonArray { add(JsonPrimitive(accountPubkey)) })
                put("kinds", buildJsonArray { add(JsonPrimitive(WebNoteKind)) })
                put("#t", buildJsonArray { add(JsonPrimitive(WebOtherNoteTag)) })
                put("limit", JsonPrimitive(500))
            },
        )
    }.toString()

internal fun webNoteEventTags(noteId: String): List<List<String>> =
    listOf(
        listOf("d", webNoteDTag(noteId)),
        listOf("t", WebOtherNoteTag),
        listOf("alt", "Encrypted Other Note note"),
    )

internal fun buildUnsignedWebNoteEvent(note: WebReadOnlyNote, accountPubkey: String, ciphertext: String): WebUnsignedNoteEvent =
    WebUnsignedNoteEvent(
        pubkey = accountPubkey,
        createdAt = note.updatedAtMs / 1_000,
        kind = WebNoteKind,
        tags = webNoteEventTags(note.id),
        content = ciphertext,
    )

internal fun validateWebSignedNoteEvent(note: WebReadOnlyNote, event: WebNostrEvent, accountPubkey: String): Boolean =
    event.pubkey == accountPubkey &&
        event.kind == WebNoteKind &&
        event.dTag() == note.dTag &&
        event.tags == webNoteEventTags(note.id) &&
        event.content.isNotBlank() &&
        event.id.isNotBlank() &&
        event.sig.isNotBlank() &&
        event.isReadableOtherNoteEvent()

internal fun buildWebNoteForSave(
    existing: WebReadOnlyNote?,
    bodyMarkdown: String,
    noteIdGenerator: () -> String = ::webStableRandomId,
    nowMs: () -> Long = ::webNowMs,
): WebReadOnlyNote {
    val now = nowMs()
    return existing?.copy(
        bodyMarkdown = bodyMarkdown,
        updatedAtMs = nextWebNoteVersionUpdatedAtMs(existing.updatedAtMs, now),
        deleted = false,
    ) ?: WebReadOnlyNote(
        id = noteIdGenerator(),
        createdAtMs = now,
        updatedAtMs = now,
        bodyMarkdown = bodyMarkdown,
        deleted = false,
    )
}

internal fun nextWebNoteVersionUpdatedAtMs(previousUpdatedAtMs: Long, nowMs: Long): Long {
    val nextEventSecondMs = if (previousUpdatedAtMs >= Long.MAX_VALUE - 1_000) {
        Long.MAX_VALUE
    } else {
        ((previousUpdatedAtMs / 1_000) + 1) * 1_000
    }
    return maxOf(nowMs, nextEventSecondMs)
}

internal fun mergePublishedWebNoteEvent(
    events: List<WebNostrEvent>,
    decryptedByEventId: Map<String, String>,
    event: WebNostrEvent,
    plaintextPayload: String,
): WebReducedNoteState {
    val eventMap = linkedMapOf<String, WebNostrEvent>()
    events.forEach { eventMap[it.id] = it }
    eventMap[event.id] = event
    val decrypted = decryptedByEventId.toMutableMap()
    decrypted[event.id] = plaintextPayload
    return reduceDecryptedWebNoteEvents(eventMap.values.toList(), decrypted)
}

internal fun webNoteEventMessage(event: WebNostrEvent): String =
    buildJsonArray {
        add(JsonPrimitive("EVENT"))
        add(webNostrEventObject(event))
    }.toString()

private fun parseWebNoteRelayMessage(raw: String): WebNoteRelayMessage =
    runCatching {
        val array = WebNoteJson.parseToJsonElement(raw).jsonArrayOrNull() ?: return@runCatching WebNoteRelayMessage.Ignored
        when (array.getOrNull(0)?.stringOrNull()) {
            "EVENT" -> {
                val subscriptionId = array.getOrNull(1)?.stringOrNull() ?: return@runCatching WebNoteRelayMessage.Ignored
                val event = array.getOrNull(2)?.jsonObjectOrNull()?.let(::parseWebNostrEventObject)
                    ?: return@runCatching WebNoteRelayMessage.Ignored
                WebNoteRelayMessage.Event(subscriptionId, event)
            }
            "EOSE" -> {
                val subscriptionId = array.getOrNull(1)?.stringOrNull() ?: return@runCatching WebNoteRelayMessage.Ignored
                WebNoteRelayMessage.Eose(subscriptionId)
            }
            "CLOSED" -> WebNoteRelayMessage.Closed
            else -> WebNoteRelayMessage.Ignored
        }
    }.getOrElse { WebNoteRelayMessage.Ignored }

private data class WebRelayOk(val eventId: String, val accepted: Boolean)

private fun parseWebRelayOk(raw: String): WebRelayOk? =
    runCatching {
        val array = WebNoteJson.parseToJsonElement(raw).jsonArrayOrNull() ?: return null
        if (array.getOrNull(0)?.stringOrNull() != "OK") return null
        WebRelayOk(
            eventId = array.getOrNull(1)?.stringOrNull() ?: return null,
            accepted = array.getOrNull(2)?.jsonPrimitive?.content == "true",
        )
    }.getOrNull()

private fun parseWebNostrEventObject(obj: JsonObject): WebNostrEvent? {
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

private fun webNostrEventObject(event: WebNostrEvent): JsonObject = buildJsonObject {
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

private fun WebUnsignedNoteEvent.toDynamicUnsignedEvent(): dynamic {
    val template = js("({})")
    template.pubkey = pubkey
    template.created_at = createdAt.toDouble()
    template.kind = kind
    template.tags = tags.map { it.toTypedArray() }.toTypedArray()
    template.content = content
    return template
}

internal fun WebUnsignedNoteEvent.toSignEventJson(includePubkey: Boolean = false): String =
    buildJsonObject {
        if (includePubkey) put("pubkey", JsonPrimitive(pubkey))
        put("created_at", JsonPrimitive(createdAt))
        put("kind", JsonPrimitive(kind))
        put(
            "tags",
            buildJsonArray {
                tags.forEach { tag ->
                    add(
                        buildJsonArray {
                            tag.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }
            },
        )
        put("content", JsonPrimitive(content))
    }.toString()

internal fun parseWebSignedEventJson(raw: String): WebNostrEvent? =
    runCatching {
        val obj = WebNoteJson.parseToJsonElement(raw).jsonObjectOrNull() ?: return null
        parseWebNostrEventObject(obj)
    }.getOrNull()

private fun parseDynamicSignedEvent(value: dynamic): WebNostrEvent? =
    runCatching {
        val tags = (value.tags as Array<*>).map { rawTag ->
            (rawTag as Array<*>).mapNotNull { it as? String }
        }
        WebNostrEvent(
            id = jsString(value.id),
            pubkey = jsString(value.pubkey),
            createdAt = jsLong(value.created_at),
            kind = jsInt(value.kind),
            tags = tags,
            content = jsString(value.content),
            sig = jsString(value.sig),
        )
    }.getOrNull()

private fun jsString(value: dynamic): String = value.unsafeCast<String>()
private fun jsLong(value: dynamic): Long = value.unsafeCast<Double>().toLong()
private fun jsInt(value: dynamic): Int = value.unsafeCast<Double>().toInt()

private fun List<WebNoteRelayStatus>.safeRelayStatus(): String? {
    if (isEmpty()) return null
    val readable = count { it.connected && !it.failed && it.returnedEvents >= 0 }
    val failed = count { it.failed || it.timedOut }
    return when {
        failed == 0 -> null
        readable > 0 -> "Loaded from available note relays; $failed relay(s) did not finish."
        else -> WebNoteCopy.RelayFetchFailed
    }
}

internal fun Nip07Signer?.hasWebNoteCrudCapability(): Boolean =
    this.hasNip07SignCapability() && this.hasNip07EncryptCapability()

private fun Nip07Signer?.hasNip07SignCapability(): Boolean =
    this != null && this.asDynamic().signEvent != undefined

private fun Nip07Signer?.hasNip07EncryptCapability(): Boolean =
    this?.nip44 != null && this.nip44.asDynamic().encrypt != undefined

private fun webStableRandomId(): String = secureWebRandomHex(16).getOrElse {
    "web-note-${webNowMs()}"
}

private fun secureWebRandomHex(byteCount: Int): Result<String> = runCatching {
    val crypto = globalThis.crypto ?: error("Web Crypto unavailable")
    crypto.getRandomValues(Uint8Array(byteCount)).toHex(byteCount)
}

private fun webNowMs(): Long = (js("Date.now()") as Double).toLong()

private fun Uint8Array.toHex(byteCount: Int): String = buildString {
    repeat(byteCount) { index ->
        append((readUint8ArrayByte(this@toHex, index) and 0xff).toString(16).padStart(2, '0'))
    }
}

internal sealed class WebMarkdownBlock {
    data class Heading(val level: Int, val text: String) : WebMarkdownBlock()
    data class Paragraph(val text: String) : WebMarkdownBlock()
    data class BlockQuote(val text: String) : WebMarkdownBlock()
    data class CodeBlock(val code: String) : WebMarkdownBlock()
}

internal sealed class WebMarkdownSpan {
    data class Text(val text: String) : WebMarkdownSpan()
    data class Bold(val text: String) : WebMarkdownSpan()
    data class Italic(val text: String) : WebMarkdownSpan()
    data class Strike(val text: String) : WebMarkdownSpan()
    data class Code(val text: String) : WebMarkdownSpan()
}

internal data class WebNotePreview(
    val title: String,
    val snippet: String,
)

internal fun webMarkdownBlocks(markdown: String): List<WebMarkdownBlock> {
    val blocks = mutableListOf<WebMarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val quote = mutableListOf<String>()
    val code = StringBuilder()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += WebMarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    fun flushQuote() {
        if (quote.isNotEmpty()) {
            blocks += WebMarkdownBlock.BlockQuote(quote.joinToString("\n").trim())
            quote.clear()
        }
    }

    fun flushTextBlocks() {
        flushParagraph()
        flushQuote()
    }

    markdown.lines().forEach { line ->
        when {
            line.trim().startsWith("```") && !inCode -> {
                flushTextBlocks()
                inCode = true
            }
            line.trim().startsWith("```") && inCode -> {
                blocks += WebMarkdownBlock.CodeBlock(code.toString().trimEnd())
                code.clear()
                inCode = false
            }
            inCode -> code.appendLine(line)
            line.isBlank() -> flushTextBlocks()
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                if (line.getOrNull(level) == ' ') {
                    flushTextBlocks()
                    blocks += WebMarkdownBlock.Heading(level, line.drop(level + 1).trim())
                } else {
                    flushQuote()
                    paragraph += line
                }
            }
            line.startsWith(">") -> {
                flushParagraph()
                quote += line.drop(1).removePrefix(" ").trimEnd()
            }
            else -> {
                flushQuote()
                paragraph += line
            }
        }
    }
    if (inCode) blocks += WebMarkdownBlock.CodeBlock(code.toString().trimEnd())
    flushTextBlocks()
    return blocks
}

internal fun webMarkdownSpans(markdown: String): List<WebMarkdownSpan> {
    val spans = mutableListOf<WebMarkdownSpan>()
    var index = 0

    fun appendText(text: String) {
        if (text.isNotEmpty()) spans += WebMarkdownSpan.Text(text)
    }

    while (index < markdown.length) {
        val marker = when {
            markdown.startsWith("`", index) -> "`"
            markdown.startsWith("**", index) -> "**"
            markdown.startsWith("~~", index) -> "~~"
            markdown.startsWith("~", index) -> "~"
            markdown.startsWith("*", index) -> "*"
            else -> null
        }
        if (marker == null) {
            val next = listOf(
                markdown.indexOf("`", index),
                markdown.indexOf("**", index),
                markdown.indexOf("~~", index),
                markdown.indexOf("~", index),
                markdown.indexOf("*", index),
            ).filter { it >= 0 }.minOrNull() ?: markdown.length
            appendText(markdown.substring(index, next))
            index = next
            continue
        }
        val close = markdown.indexOf(marker, index + marker.length)
        if (close < 0) {
            appendText(marker)
            index += marker.length
            continue
        }
        val content = markdown.substring(index + marker.length, close)
        if (content.isEmpty()) {
            appendText(marker + marker)
        } else {
            spans += when (marker) {
                "`" -> WebMarkdownSpan.Code(content)
                "**" -> WebMarkdownSpan.Bold(content)
                "*" -> WebMarkdownSpan.Italic(content)
                "~", "~~" -> WebMarkdownSpan.Strike(content)
                else -> WebMarkdownSpan.Text(marker + content + marker)
            }
        }
        index = close + marker.length
    }
    return spans
}

internal fun webNotePreview(markdown: String): WebNotePreview {
    val lines = markdown.lines()
    val firstContentIndex = lines.indexOfFirst { it.isNotBlank() }
    if (firstContentIndex < 0) return WebNotePreview(title = "Untitled note", snippet = "")
    val firstLine = lines[firstContentIndex].trim()
    if (firstLine.startsWith("```")) {
        val codeLines = lines.drop(firstContentIndex + 1)
            .takeWhile { !it.trim().startsWith("```") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return WebNotePreview(
            title = "Code block",
            snippet = codeLines.joinToString(" ").compactWebPreviewText(140),
        )
    }
    return WebNotePreview(
        title = firstLine.toWebPreviewText().ifBlank { "Untitled note" }.compactWebPreviewText(80),
        snippet = lines.drop(firstContentIndex + 1)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .map { it.toWebPreviewText() }
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(" ")
            .compactWebPreviewText(140),
    )
}

private fun String.toWebPreviewText(): String =
    trim()
        .removePrefix("> ")
        .removePrefix(">")
        .replace(Regex("""^#{1,6}\s+"""), "")
        .replace(Regex("""^[-*]\s+"""), "")
        .replace(Regex("""^\d+\.\s+"""), "")
        .replace(Regex("```.*$"), "")
        .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
        .replace(Regex("""\*([^*]+)\*"""), "$1")
        .replace(Regex("""~~([^~]+)~~"""), "$1")
        .replace(Regex("""~([^~]+)~"""), "$1")
        .replace(Regex("""`([^`]+)`"""), "$1")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun String.compactWebPreviewText(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars).trimEnd() + "..."

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.stringOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()
