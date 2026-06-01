package com.libertasprimordium.othernote.security

import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.NotePayload
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

sealed class SignerNoteEventBuildResult {
    data class Success(
        val signedEvent: NostrEvent,
        val noteId: String,
        val dTag: String,
        val safeSummary: String,
    ) : SignerNoteEventBuildResult()

    data object Cancelled : SignerNoteEventBuildResult()
    data class Unavailable(val safeReason: String) : SignerNoteEventBuildResult()
    data class Failed(val safeReason: String) : SignerNoteEventBuildResult()
    data class InvalidResponse(val safeReason: String) : SignerNoteEventBuildResult()
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
    ): SignerNoteEventBuildResult {
        val verifier = crypto ?: return SignerNoteEventBuildResult.Unavailable(ProductionNostrCryptoFactory.unavailableReason)
        val noteId = idProvider()
        val nowMs = nowMsProvider()
        val dTag = noteDTag(noteId)
        val payload = NotePayload(
            noteId = noteId,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            bodyMarkdown = bodyMarkdown,
            deleted = false,
        )
        val payloadJson = JsonNotePayloadCodec.encode(payload)
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
        if (ciphertext.contains(bodyMarkdown) || ciphertext.contains(payloadJson)) {
            return SignerNoteEventBuildResult.InvalidResponse("Signer returned invalid encryption result")
        }
        val unsigned = UnsignedNostrEvent(
            pubkey = session.publicKeyHex,
            createdAt = nowMs / 1000,
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
        validateSignedEvent(requested, signed, verifier)?.let { return it }
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
        val decoded = JsonNotePayloadCodec.decode(plaintext).getOrElse {
            return SignerNoteEventBuildResult.InvalidResponse("Signer note payload failed decode")
        }
        if (decoded != payload || decoded.deleted) {
            return SignerNoteEventBuildResult.InvalidResponse("Signer note payload mismatch")
        }
        return SignerNoteEventBuildResult.Success(
            signedEvent = signed,
            noteId = noteId,
            dTag = dTag,
            safeSummary = "Signer note event built and verified (${signed.id.take(12)}, ${dTag.takeLast(12)})",
        )
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
