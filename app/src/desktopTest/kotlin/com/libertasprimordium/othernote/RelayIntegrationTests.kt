package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.NotePayload
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.NostrRelayMessage
import com.libertasprimordium.othernote.nostr.NostrWireJson
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.nostr.noteEventTags
import com.libertasprimordium.othernote.sync.reduceNoteEvents
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class RelayIntegrationTests {
    private val initialTestBody =
        "Other Note disposable relay integration test payload\nQuote: \"hello\"\nUnicode: test\nTab:\tvalue\n```text\nrelay\n```"
    private val updatedTestBody =
        "Other Note disposable relay integration test payload updated\nQuote: \"hello\"\nUnicode: test\nTab:\tvalue\n```text\nrelay\n```"

    @Test
    fun encryptedNoteRelayRoundTripIsExplicitlyOptIn() = runBlocking {
        if (System.getenv("OTHER_NOTE_RELAY_TESTS") != "1") return@runBlocking
        val relays = System.getenv("OTHER_NOTE_TEST_RELAYS")
            ?.split(',')
            ?.mapNotNull { normalizeRelayUrl(it).getOrNull() }
            ?.distinct()
            .orEmpty()
        if (relays.isEmpty()) return@runBlocking

        val crypto = ProductionNostrCryptoFactory.createOrNull()
            ?: fail(ProductionNostrCryptoFactory.unavailableReason)

        val client = DesktopNostrClient()
        val privateKey = crypto.generatePrivateKey().getOrThrow()
        val publicKey = crypto.derivePublicKey(privateKey).getOrThrow()
        val noteId = "relay-test-${UUID.randomUUID()}"
        val createdAtMs = System.currentTimeMillis()
        val basePayload = NotePayload(
            noteId = noteId,
            createdAtMs = createdAtMs,
            updatedAtMs = createdAtMs,
            bodyMarkdown = initialTestBody,
            deleted = false,
        )

        val initialEvent = signPayload(crypto, privateKey, publicKey, basePayload, createdAt = createdAtMs / 1000)
        assertTrue(crypto.validate(initialEvent).getOrThrow(), "Local initial event failed validation before publish")
        val localInitialClassification = RelayFetchResult(listOf(initialEvent), emptyList())
            .classifyTestEvents(crypto, privateKey, publicKey, noteId)
        assertTrue(
            localInitialClassification.single().valid,
            "Local initial event failed decrypt/decode before publish: ${localInitialClassification.classificationSummary()}",
        )
        val localWireEvent = NostrWireJson.parseRelayMessage("""["EVENT","local",${NostrWireJson.eventObject(initialEvent)}]""")
        assertTrue(
            localWireEvent is NostrRelayMessage.Event && localWireEvent.event == initialEvent,
            "Local initial event failed Nostr wire event round trip before publish",
        )

        val initialPublish = client.publish(relays, initialEvent)
        assertTrue(
            initialPublish.statuses.any { it.writable },
            "No relay accepted initial write. Statuses: ${initialPublish.statuses.safeSummary()}",
        )

        val initialFetch = client.fetchNotes(relays, publicKey.hex)
        val initialClassifications = initialFetch.classifyTestEvents(crypto, privateKey, publicKey, noteId)
        val validInitial = initialClassifications.validEvents()
        assertTrue(
            validInitial.isNotEmpty(),
            "No valid initial test event fetched. Publish: ${initialPublish.statuses.safeSummary()}; " +
                "fetch: ${initialFetch.statuses.safeSummary()}; rejected: ${initialClassifications.classificationSummary()}",
        )
        assertTrue(
            validInitial.any { event -> decryptPayloadOrNull(crypto, event, privateKey, publicKey) == basePayload },
            "Initial payload did not round trip. Publish: ${initialPublish.statuses.safeSummary()}; fetch: ${initialFetch.statuses.safeSummary()}",
        )

        val updatedPayload = basePayload.copy(
            updatedAtMs = createdAtMs + 2_000,
            bodyMarkdown = updatedTestBody,
        )
        val updatedEvent = signPayload(crypto, privateKey, publicKey, updatedPayload, createdAt = initialEvent.createdAt + 2)
        val updatedPublish = client.publish(relays, updatedEvent)
        assertTrue(
            updatedPublish.statuses.any { it.writable },
            "No relay accepted update write. Statuses: ${updatedPublish.statuses.safeSummary()}",
        )

        val updatedFetch = client.fetchNotes(relays, publicKey.hex)
        val updatedClassifications = updatedFetch.classifyTestEvents(crypto, privateKey, publicKey, noteId)
        val validUpdated = updatedClassifications.validEvents()
        val reducedUpdated = reduceNoteEvents(validUpdated) { event ->
            crypto.decryptFromSelf(event.content, privateKey, publicKey)
        }
        assertTrue(
            reducedUpdated.notes.singleOrNull { it.id == noteId }?.bodyMarkdown == updatedPayload.bodyMarkdown,
            "Reducer did not select updated note. Publish: ${updatedPublish.statuses.safeSummary()}; fetch: ${updatedFetch.statuses.safeSummary()}",
        )

        val tombstonePayload = updatedPayload.copy(
            updatedAtMs = createdAtMs + 4_000,
            bodyMarkdown = "",
            deleted = true,
        )
        val tombstoneEvent = signPayload(crypto, privateKey, publicKey, tombstonePayload, createdAt = updatedEvent.createdAt + 2)
        val tombstonePublish = client.publish(relays, tombstoneEvent)
        assertTrue(
            tombstonePublish.statuses.any { it.writable },
            "No relay accepted tombstone write. Statuses: ${tombstonePublish.statuses.safeSummary()}",
        )

        val tombstoneFetch = client.fetchNotes(relays, publicKey.hex)
        val tombstoneClassifications = tombstoneFetch.classifyTestEvents(crypto, privateKey, publicKey, noteId)
        val validTombstone = tombstoneClassifications.validEvents()
        val reducedTombstone = reduceNoteEvents(validTombstone) { event ->
            crypto.decryptFromSelf(event.content, privateKey, publicKey)
        }
        assertTrue(
            reducedTombstone.notes.none { it.id == noteId },
            "Reducer did not hide tombstoned note. Publish: ${tombstonePublish.statuses.safeSummary()}; fetch: ${tombstoneFetch.statuses.safeSummary()}",
        )
    }

    private fun signPayload(
        crypto: NostrCrypto,
        privateKey: NostrPrivateKey,
        publicKey: NostrPublicKey,
        payload: NotePayload,
        createdAt: Long,
    ): NostrEvent {
        val ciphertext = crypto.encryptToSelf(JsonNotePayloadCodec.encode(payload), privateKey, publicKey).getOrThrow()
        return crypto.sign(
            UnsignedNostrEvent(
                pubkey = publicKey.hex,
                createdAt = createdAt,
                kind = NoteKind,
                tags = noteEventTags(noteDTag(payload.noteId)),
                content = ciphertext,
            ),
            privateKey,
        ).getOrThrow()
    }

    private fun RelayFetchResult.classifyTestEvents(
        crypto: NostrCrypto,
        privateKey: NostrPrivateKey,
        publicKey: NostrPublicKey,
        noteId: String,
    ): List<EventClassification> = events.map { event ->
        val validateResult = crypto.validate(event)
        val decryptResult = if (validateResult.getOrDefault(false)) {
            crypto.decryptFromSelf(event.content, privateKey, publicKey)
        } else {
            Result.failure(IllegalStateException("not attempted"))
        }
        val payloadResult = if (decryptResult.isSuccess) {
            decryptResult.mapCatching { JsonNotePayloadCodec.decode(it).getOrThrow() }
        } else {
            Result.failure(IllegalStateException("not attempted"))
        }
        val payload = payloadResult.getOrNull()
        EventClassification(
            event = event,
            idPrefix = event.id.take(12),
            pubkeyMatches = event.pubkey == publicKey.hex,
            kind = event.kind,
            dTag = event.dTag()?.takeIf { it == noteDTag(noteId) } ?: event.dTag()?.take(48),
            dTagMatches = event.dTag() == noteDTag(noteId),
            hasOtherNoteTag = event.isOtherNoteEvent(),
            validationSucceeded = validateResult.getOrDefault(false),
            validationFailure = validateResult.exceptionOrNull()?.safeFailure(),
            decryptSucceeded = decryptResult.isSuccess,
            decryptFailure = decryptResult.exceptionOrNull()?.safeFailure(),
            payloadDecodeSucceeded = payloadResult.isSuccess,
            payloadDecodeFailure = payloadResult.exceptionOrNull()?.safeFailure(),
            noteIdMatches = payload?.noteId == noteId,
        )
    }

    private fun decryptPayloadOrNull(
        crypto: NostrCrypto,
        event: NostrEvent,
        privateKey: NostrPrivateKey,
        publicKey: NostrPublicKey,
    ): NotePayload? =
        crypto.decryptFromSelf(event.content, privateKey, publicKey)
            .mapCatching { JsonNotePayloadCodec.decode(it).getOrThrow() }
            .getOrNull()

    private fun List<RelayStatus>.safeSummary(): String =
        joinToString("; ") { status ->
            "${status.url} read=${status.readable} write=${status.writable} message=${status.message.take(180)}"
        }

    private fun List<EventClassification>.validEvents(): List<NostrEvent> =
        filter { it.valid }.map { it.event }

    private fun List<EventClassification>.classificationSummary(): String =
        if (isEmpty()) {
            "no events"
        } else {
            joinToString("; ") { it.safeSummary() }
        }

    private fun Throwable.safeFailure(): String =
        "${this::class.simpleName}:${message?.redactDiagnosticMessage()?.take(160) ?: ""}"

    private fun String.redactDiagnosticMessage(): String =
        replace(Regex("[0-9a-fA-F]{24,}"), "<hex-redacted>")
}

private data class EventClassification(
    val event: NostrEvent,
    val idPrefix: String,
    val pubkeyMatches: Boolean,
    val kind: Int,
    val dTag: String?,
    val dTagMatches: Boolean,
    val hasOtherNoteTag: Boolean,
    val validationSucceeded: Boolean,
    val validationFailure: String?,
    val decryptSucceeded: Boolean,
    val decryptFailure: String?,
    val payloadDecodeSucceeded: Boolean,
    val payloadDecodeFailure: String?,
    val noteIdMatches: Boolean,
) {
    val valid: Boolean
        get() = pubkeyMatches &&
            kind == NoteKind &&
            dTagMatches &&
            hasOtherNoteTag &&
            validationSucceeded &&
            decryptSucceeded &&
            payloadDecodeSucceeded &&
            noteIdMatches

    fun safeSummary(): String =
        "event=$idPrefix pubkeyMatches=$pubkeyMatches kind=$kind dTag=${dTag ?: "missing"} " +
            "dTagMatches=$dTagMatches hasOtherNoteTag=$hasOtherNoteTag " +
            "validation=$validationSucceeded${validationFailure.detail()} " +
            "decrypt=$decryptSucceeded${decryptFailure.detail()} " +
            "payloadDecode=$payloadDecodeSucceeded${payloadDecodeFailure.detail()} noteIdMatches=$noteIdMatches"

    private fun String?.detail(): String = if (this == null) "" else "($this)"
}
