package com.libertasprimordium.othernote.nostr

import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.domain.toPayload
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlin.time.TimeSource

data class SignedNoteEventBuild(
    val event: NostrEvent,
    val diagnostics: List<String>,
)

class NostrRepository(
    private val crypto: NostrCrypto,
    private val client: NostrClient,
) {
    fun buildSignedNoteEvent(note: Note, session: UserSession): Result<NostrEvent> {
        return buildSignedNoteEventWithDiagnostics(note, session).map { it.event }
    }

    fun buildSignedNoteEventWithDiagnostics(note: Note, session: UserSession): Result<SignedNoteEventBuild> {
        val privateKey = NostrPrivateKey(session.privateKeyHex)
        val publicKey = NostrPublicKey(session.publicKeyHex, session.npub)
        return runCatching {
            val diagnostics = mutableListOf<String>()
            val encodeStart = TimeSource.Monotonic.markNow()
            val payloadJson = JsonNotePayloadCodec.encode(note.toPayload())
            diagnostics += "encode_ms=${encodeStart.elapsedNow().inWholeMilliseconds}"
            val encryptStart = TimeSource.Monotonic.markNow()
            val encrypted = crypto.encryptToSelf(payloadJson, privateKey, publicKey).getOrThrow()
            diagnostics += "encrypt_ms=${encryptStart.elapsedNow().inWholeMilliseconds}"
            val unsigned = UnsignedNostrEvent(
                pubkey = session.publicKeyHex,
                createdAt = note.updatedAtMs / 1000,
                kind = NoteKind,
                tags = noteEventTags(noteDTag(note.id)),
                content = encrypted,
            )
            val signStart = TimeSource.Monotonic.markNow()
            val event = crypto.sign(unsigned, privateKey).getOrThrow()
            diagnostics += "sign_ms=${signStart.elapsedNow().inWholeMilliseconds}"
            SignedNoteEventBuild(event, diagnostics)
        }
    }

    fun validateSignedNoteEvent(note: Note, event: NostrEvent, session: UserSession): Result<Unit> = runCatching {
        validateSignedNoteEventWithDiagnostics(note, event, session).getOrThrow()
    }

    fun validateSignedNoteEventWithDiagnostics(note: Note, event: NostrEvent, session: UserSession): Result<List<String>> = runCatching {
        val diagnostics = mutableListOf<String>()
        require(event.pubkey == session.publicKeyHex) { "Signed event author did not match session" }
        val validateStart = TimeSource.Monotonic.markNow()
        require(crypto.validate(event).getOrThrow()) { "Signed event failed local validation" }
        diagnostics += "validate_ms=${validateStart.elapsedNow().inWholeMilliseconds}"
        val privateKey = NostrPrivateKey(session.privateKeyHex)
        val publicKey = NostrPublicKey(session.publicKeyHex, session.npub)
        val decryptStart = TimeSource.Monotonic.markNow()
        val decoded = crypto.decryptFromSelf(event.content, privateKey, publicKey)
            .mapCatching { JsonNotePayloadCodec.decode(it).getOrThrow() }
            .getOrThrow()
        diagnostics += "decrypt_decode_ms=${decryptStart.elapsedNow().inWholeMilliseconds}"
        require(decoded == note.toPayload()) { "Signed event payload failed local decrypt/decode control" }
        diagnostics
    }

    suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult = client.publish(relays, event)
    fun publishBestEffort(
        relays: List<String>,
        event: NostrEvent,
        scope: CoroutineScope,
        onStatus: (List<RelayStatus>) -> Unit,
    ): PublishBestEffortHandle {
        if (client is FanoutNostrClient) {
            return client.publishBestEffort(relays, event, scope, onStatus)
        }
        val complete = scope.async {
            client.publish(relays, event).also { onStatus(it.statuses) }
        }
        return PublishBestEffortHandle(firstAccepted = complete, complete = complete)
    }

    suspend fun fetch(relays: List<String>, pubkey: String): RelayFetchResult = client.fetchNotes(relays, pubkey)
    suspend fun fetchIncrementally(
        relays: List<String>,
        pubkey: String,
        onRelayResult: suspend (RelayFetchResult) -> Unit,
    ): RelayFetchResult =
        if (client is IncrementalNostrClient) {
            client.fetchNotesIncrementally(relays, pubkey, onRelayResult)
        } else {
            client.fetchNotes(relays, pubkey).also { onRelayResult(it) }
        }
}
