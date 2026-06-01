package com.libertasprimordium.othernote.security

import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.toPayload
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.nostr.noteEventTags
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import com.libertasprimordium.othernote.util.nowMs
import com.libertasprimordium.othernote.util.stableRandomId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class SignerNoteEventBuildResult {
    data class Success(
        val signedEvent: NostrEvent,
        val note: Note,
        val noteId: String,
        val dTag: String,
        val safeSummary: String,
    ) : SignerNoteEventBuildResult()

    data object Cancelled : SignerNoteEventBuildResult()
    data class Unavailable(val safeReason: String) : SignerNoteEventBuildResult()
    data class Failed(val safeReason: String) : SignerNoteEventBuildResult()
    data class InvalidResponse(val safeReason: String) : SignerNoteEventBuildResult()
}

enum class SignerNoteEventBuildStage {
    PayloadEncoded,
    Encrypted,
    EventBuilt,
    Signed,
    Validated,
    Decrypted,
    PayloadDecoded,
}

class SignerNoteEventBuilder(
    private val nip44: NostrSignerNip44Operator,
    private val eventSigner: NostrSignerEventSigner,
    private val crypto: NostrCrypto? = ProductionNostrCryptoFactory.createOrNull(),
    private val idProvider: () -> String = ::stableRandomId,
    private val nowMsProvider: () -> Long = ::nowMs,
) {
    fun buildTestNoteEvent(
        session: UserSession,
        signerPackage: String?,
        bodyMarkdown: String = TestBodyMarkdown,
        onStage: (SignerNoteEventBuildStage) -> Unit = {},
    ): SignerNoteEventBuildResult {
        val nowMs = nowMsProvider()
        return buildNoteEvent(
            session = session,
            signerPackage = signerPackage,
            note = Note(
                id = idProvider(),
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
                bodyMarkdown = bodyMarkdown,
                deleted = false,
            ),
            successMessage = "Signer note event built and verified",
            onStage = onStage,
        )
    }

    fun buildNewLocalNoteEvent(
        session: UserSession,
        signerPackage: String?,
        bodyMarkdown: String,
        onStage: (SignerNoteEventBuildStage) -> Unit = {},
    ): SignerNoteEventBuildResult {
        val nowMs = nowMsProvider()
        return buildNoteEvent(
            session = session,
            signerPackage = signerPackage,
            note = Note(
                id = idProvider(),
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
                bodyMarkdown = bodyMarkdown,
                deleted = false,
            ),
            successMessage = "Saved locally",
            onStage = onStage,
        )
    }

    suspend fun buildNewLocalNoteEventAsync(
        session: UserSession,
        signerPackage: String?,
        bodyMarkdown: String,
        onStage: (SignerNoteEventBuildStage) -> Unit = {},
    ): SignerNoteEventBuildResult {
        val nowMs = nowMsProvider()
        return buildNoteEventAsync(
            session = session,
            signerPackage = signerPackage,
            note = Note(
                id = idProvider(),
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
                bodyMarkdown = bodyMarkdown,
                deleted = false,
            ),
            successMessage = "Saved locally",
            onStage = onStage,
        )
    }

    fun buildReplacementLocalNoteEvent(
        session: UserSession,
        signerPackage: String?,
        existing: Note,
        bodyMarkdown: String,
        onStage: (SignerNoteEventBuildStage) -> Unit = {},
    ): SignerNoteEventBuildResult {
        val nowMs = nowMsProvider()
        return buildNoteEvent(
            session = session,
            signerPackage = signerPackage,
            note = existing.copy(
                bodyMarkdown = bodyMarkdown,
                updatedAtMs = nowMs,
                deleted = false,
            ),
            successMessage = "Saved locally",
            onStage = onStage,
        )
    }

    suspend fun buildReplacementLocalNoteEventAsync(
        session: UserSession,
        signerPackage: String?,
        existing: Note,
        bodyMarkdown: String,
        onStage: (SignerNoteEventBuildStage) -> Unit = {},
    ): SignerNoteEventBuildResult {
        val nowMs = nowMsProvider()
        return buildNoteEventAsync(
            session = session,
            signerPackage = signerPackage,
            note = existing.copy(
                bodyMarkdown = bodyMarkdown,
                updatedAtMs = nowMs,
                deleted = false,
            ),
            successMessage = "Saved locally",
            onStage = onStage,
        )
    }

    fun buildLocalTombstoneEvent(
        session: UserSession,
        signerPackage: String?,
        existing: Note,
        onStage: (SignerNoteEventBuildStage) -> Unit = {},
    ): SignerNoteEventBuildResult {
        val nowMs = nowMsProvider()
        return buildNoteEvent(
            session = session,
            signerPackage = signerPackage,
            note = existing.copy(
                bodyMarkdown = "",
                updatedAtMs = nowMs,
                deleted = true,
            ),
            successMessage = "Deleted locally",
            expectedDeleted = true,
            onStage = onStage,
        )
    }

    suspend fun buildLocalTombstoneEventAsync(
        session: UserSession,
        signerPackage: String?,
        existing: Note,
        onStage: (SignerNoteEventBuildStage) -> Unit = {},
    ): SignerNoteEventBuildResult {
        val nowMs = nowMsProvider()
        return buildNoteEventAsync(
            session = session,
            signerPackage = signerPackage,
            note = existing.copy(
                bodyMarkdown = "",
                updatedAtMs = nowMs,
                deleted = true,
            ),
            successMessage = "Deleted locally",
            expectedDeleted = true,
            onStage = onStage,
        )
    }

    private fun buildNoteEvent(
        session: UserSession,
        signerPackage: String?,
        note: Note,
        successMessage: String,
        expectedDeleted: Boolean = false,
        onStage: (SignerNoteEventBuildStage) -> Unit,
    ): SignerNoteEventBuildResult {
        val verifier = crypto ?: return SignerNoteEventBuildResult.Unavailable(ProductionNostrCryptoFactory.unavailableReason)
        val dTag = noteDTag(note.id)
        val payload = note.toPayload()
        val payloadJson = JsonNotePayloadCodec.encode(payload)
        onStage(SignerNoteEventBuildStage.PayloadEncoded)
        val encrypted = nip44.encryptToSelf(
            plaintext = payloadJson,
            currentUserPubkey = session.publicKeyHex,
            signerPackage = signerPackage,
        )
        val ciphertext = when (encrypted) {
            is SignerNip44OperationResult.Encrypted -> encrypted.payload
            SignerNip44OperationResult.Cancelled -> return SignerNoteEventBuildResult.Cancelled
            is SignerNip44OperationResult.Unavailable -> return SignerNoteEventBuildResult.Unavailable(encrypted.safeReason)
            is SignerNip44OperationResult.Failed -> return SignerNoteEventBuildResult.Failed(encrypted.safeReason)
            is SignerNip44OperationResult.InvalidResponse -> return SignerNoteEventBuildResult.InvalidResponse(encrypted.safeReason)
            is SignerNip44OperationResult.Decrypted -> return SignerNoteEventBuildResult.InvalidResponse("Signer returned invalid encryption result")
        }
        if ((note.bodyMarkdown.isNotBlank() && ciphertext.contains(note.bodyMarkdown)) || ciphertext.contains(payloadJson)) {
            return SignerNoteEventBuildResult.InvalidResponse("Signer returned invalid encryption result")
        }
        onStage(SignerNoteEventBuildStage.Encrypted)
        val unsigned = UnsignedNostrEvent(
            pubkey = session.publicKeyHex,
            createdAt = note.updatedAtMs / 1000,
            kind = NoteKind,
            tags = noteEventTags(dTag),
            content = ciphertext,
        )
        val requested = NostrEvent(
            id = verifier.computeEventId(unsigned).getOrElse {
                return SignerNoteEventBuildResult.Failed("Could not build signer note event.")
            },
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = "",
        )
        onStage(SignerNoteEventBuildStage.EventBuilt)
        var signResult: SignEventRequestResult? = null
        eventSigner.signEvent(
            unsignedEvent = requested,
            currentUserPubkey = session.publicKeyHex,
            signerPackage = signerPackage,
        ) { signResult = it }
        val signed = when (val result = signResult) {
            is SignEventRequestResult.Success -> result.signedEvent
            SignEventRequestResult.Cancelled -> return SignerNoteEventBuildResult.Cancelled
            is SignEventRequestResult.Unavailable -> return SignerNoteEventBuildResult.Unavailable(result.safeReason)
            is SignEventRequestResult.Failed -> return SignerNoteEventBuildResult.Failed(result.safeReason)
            is SignEventRequestResult.InvalidResponse -> return SignerNoteEventBuildResult.InvalidResponse(result.safeReason)
            null -> return SignerNoteEventBuildResult.Failed("Signer did not return a note event.")
        }
        onStage(SignerNoteEventBuildStage.Signed)
        validateSignedEvent(requested, signed, verifier)?.let { return it }
        onStage(SignerNoteEventBuildStage.Validated)
        val decrypted = nip44.decryptFromSelf(
            ciphertext = signed.content,
            expectedPlaintext = payloadJson,
            currentUserPubkey = session.publicKeyHex,
            signerPackage = signerPackage,
        )
        val plaintext = when (decrypted) {
            is SignerNip44OperationResult.Decrypted -> decrypted.plaintext
            SignerNip44OperationResult.Cancelled -> return SignerNoteEventBuildResult.Cancelled
            is SignerNip44OperationResult.Unavailable -> return SignerNoteEventBuildResult.Unavailable(decrypted.safeReason)
            is SignerNip44OperationResult.Failed -> return SignerNoteEventBuildResult.Failed(decrypted.safeReason)
            is SignerNip44OperationResult.InvalidResponse -> return SignerNoteEventBuildResult.InvalidResponse(decrypted.safeReason)
            is SignerNip44OperationResult.Encrypted -> return SignerNoteEventBuildResult.InvalidResponse("Signer decryption failed")
        }
        onStage(SignerNoteEventBuildStage.Decrypted)
        val decoded = JsonNotePayloadCodec.decode(plaintext).getOrElse {
            return SignerNoteEventBuildResult.InvalidResponse("Signer note payload failed decode")
        }
        if (decoded != payload || decoded.deleted != expectedDeleted) {
            return SignerNoteEventBuildResult.InvalidResponse("Signer note payload mismatch")
        }
        onStage(SignerNoteEventBuildStage.PayloadDecoded)
        return SignerNoteEventBuildResult.Success(
            signedEvent = signed,
            note = note.copy(sourceEventId = signed.id),
            noteId = note.id,
            dTag = dTag,
            safeSummary = "$successMessage (${signed.id.take(12)}, ${dTag.takeLast(12)})",
        )
    }

    private suspend fun buildNoteEventAsync(
        session: UserSession,
        signerPackage: String?,
        note: Note,
        successMessage: String,
        expectedDeleted: Boolean = false,
        onStage: (SignerNoteEventBuildStage) -> Unit,
    ): SignerNoteEventBuildResult = withContext(Dispatchers.Default) {
        val verifier = crypto ?: return@withContext SignerNoteEventBuildResult.Unavailable(ProductionNostrCryptoFactory.unavailableReason)
        val dTag = noteDTag(note.id)
        val payload = note.toPayload()
        val payloadJson = JsonNotePayloadCodec.encode(payload)
        onStage(SignerNoteEventBuildStage.PayloadEncoded)
        val encrypted = encryptToSelfAsync(payloadJson, session.publicKeyHex, signerPackage)
        val ciphertext = when (encrypted) {
            is SignerNip44OperationResult.Encrypted -> encrypted.payload
            SignerNip44OperationResult.Cancelled -> return@withContext SignerNoteEventBuildResult.Cancelled
            is SignerNip44OperationResult.Unavailable -> return@withContext SignerNoteEventBuildResult.Unavailable(encrypted.safeReason)
            is SignerNip44OperationResult.Failed -> return@withContext SignerNoteEventBuildResult.Failed(encrypted.safeReason)
            is SignerNip44OperationResult.InvalidResponse -> return@withContext SignerNoteEventBuildResult.InvalidResponse(encrypted.safeReason)
            is SignerNip44OperationResult.Decrypted -> return@withContext SignerNoteEventBuildResult.InvalidResponse("Signer returned invalid encryption result")
        }
        if ((note.bodyMarkdown.isNotBlank() && ciphertext.contains(note.bodyMarkdown)) || ciphertext.contains(payloadJson)) {
            return@withContext SignerNoteEventBuildResult.InvalidResponse("Signer returned invalid encryption result")
        }
        onStage(SignerNoteEventBuildStage.Encrypted)
        val unsigned = UnsignedNostrEvent(
            pubkey = session.publicKeyHex,
            createdAt = note.updatedAtMs / 1000,
            kind = NoteKind,
            tags = noteEventTags(dTag),
            content = ciphertext,
        )
        val requested = NostrEvent(
            id = verifier.computeEventId(unsigned).getOrElse {
                return@withContext SignerNoteEventBuildResult.Failed("Could not build signer note event.")
            },
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = "",
        )
        onStage(SignerNoteEventBuildStage.EventBuilt)
        val signResult = signEventAsync(requested, session.publicKeyHex, signerPackage)
        val signed = when (signResult) {
            is SignEventRequestResult.Success -> signResult.signedEvent
            SignEventRequestResult.Cancelled -> return@withContext SignerNoteEventBuildResult.Cancelled
            is SignEventRequestResult.Unavailable -> return@withContext SignerNoteEventBuildResult.Unavailable(signResult.safeReason)
            is SignEventRequestResult.Failed -> return@withContext SignerNoteEventBuildResult.Failed(signResult.safeReason)
            is SignEventRequestResult.InvalidResponse -> return@withContext SignerNoteEventBuildResult.InvalidResponse(signResult.safeReason)
        }
        onStage(SignerNoteEventBuildStage.Signed)
        validateSignedEvent(requested, signed, verifier)?.let { return@withContext it }
        onStage(SignerNoteEventBuildStage.Validated)
        val decrypted = decryptFromSelfAsync(signed.content, payloadJson, session.publicKeyHex, signerPackage)
        val plaintext = when (decrypted) {
            is SignerNip44OperationResult.Decrypted -> decrypted.plaintext
            SignerNip44OperationResult.Cancelled -> return@withContext SignerNoteEventBuildResult.Cancelled
            is SignerNip44OperationResult.Unavailable -> return@withContext SignerNoteEventBuildResult.Unavailable(decrypted.safeReason)
            is SignerNip44OperationResult.Failed -> return@withContext SignerNoteEventBuildResult.Failed(decrypted.safeReason)
            is SignerNip44OperationResult.InvalidResponse -> return@withContext SignerNoteEventBuildResult.InvalidResponse(decrypted.safeReason)
            is SignerNip44OperationResult.Encrypted -> return@withContext SignerNoteEventBuildResult.InvalidResponse("Signer decryption failed")
        }
        onStage(SignerNoteEventBuildStage.Decrypted)
        val decoded = JsonNotePayloadCodec.decode(plaintext).getOrElse {
            return@withContext SignerNoteEventBuildResult.InvalidResponse("Signer note payload failed decode")
        }
        if (decoded != payload || decoded.deleted != expectedDeleted) {
            return@withContext SignerNoteEventBuildResult.InvalidResponse("Signer note payload mismatch")
        }
        onStage(SignerNoteEventBuildStage.PayloadDecoded)
        SignerNoteEventBuildResult.Success(
            signedEvent = signed,
            note = note.copy(sourceEventId = signed.id),
            noteId = note.id,
            dTag = dTag,
            safeSummary = "$successMessage (${signed.id.take(12)}, ${dTag.takeLast(12)})",
        )
    }

    private suspend fun encryptToSelfAsync(
        plaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult =
        if (nip44 is Nip46RemoteSigner) {
            nip44.encryptToSelfAsync(plaintext, currentUserPubkey, signerPackage)
        } else {
            withContext(Dispatchers.IO) { nip44.encryptToSelf(plaintext, currentUserPubkey, signerPackage) }
        }

    private suspend fun decryptFromSelfAsync(
        ciphertext: String,
        expectedPlaintext: String?,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult =
        if (nip44 is Nip46RemoteSigner) {
            nip44.decryptFromSelfAsync(ciphertext, expectedPlaintext, currentUserPubkey, signerPackage)
        } else {
            withContext(Dispatchers.IO) { nip44.decryptFromSelf(ciphertext, expectedPlaintext, currentUserPubkey, signerPackage) }
        }

    private suspend fun signEventAsync(
        requested: NostrEvent,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignEventRequestResult =
        if (eventSigner is Nip46RemoteSigner) {
            eventSigner.signEventAsync(requested, currentUserPubkey, signerPackage)
        } else {
            withContext(Dispatchers.IO) {
                var signResult: SignEventRequestResult? = null
                eventSigner.signEvent(requested, currentUserPubkey, signerPackage) { signResult = it }
                signResult ?: SignEventRequestResult.Failed("Signer did not return a note event.")
            }
        }

    private fun validateSignedEvent(
        requested: NostrEvent,
        signed: NostrEvent,
        verifier: NostrCrypto,
    ): SignerNoteEventBuildResult.InvalidResponse? {
        if (signed.pubkey != requested.pubkey) {
            return SignerNoteEventBuildResult.InvalidResponse("Signer returned event for a different pubkey")
        }
        if (signed.kind != NoteKind) {
            return SignerNoteEventBuildResult.InvalidResponse("Signer returned unexpected event kind")
        }
        if (signed.createdAt != requested.createdAt || signed.tags != requested.tags || signed.content != requested.content) {
            return SignerNoteEventBuildResult.InvalidResponse("Signer returned unexpected event content")
        }
        if (signed.dTag() != requested.dTag()) {
            return SignerNoteEventBuildResult.InvalidResponse("Signer returned event with wrong d tag")
        }
        if (!signed.isOtherNoteEvent()) {
            return SignerNoteEventBuildResult.InvalidResponse("Signer returned event without Other Note tag")
        }
        if (signed.id.isBlank() || signed.sig.isBlank()) {
            return SignerNoteEventBuildResult.InvalidResponse("Signer returned event without id or signature")
        }
        if (!verifier.validate(signed).getOrDefault(false)) {
            return SignerNoteEventBuildResult.InvalidResponse("Signer returned invalid event signature")
        }
        return null
    }

    companion object {
        const val TestBodyMarkdown = "Other Note signer note event test"
    }
}
