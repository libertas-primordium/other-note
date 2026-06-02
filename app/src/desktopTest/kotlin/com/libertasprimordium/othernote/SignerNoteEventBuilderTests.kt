package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.SessionAuthMethod
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrWireJson
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.nostr.noteEventTags
import com.libertasprimordium.othernote.security.NostrSignerEventSigner
import com.libertasprimordium.othernote.security.NostrSignerNip44Operator
import com.libertasprimordium.othernote.security.SignEventRequestResult
import com.libertasprimordium.othernote.security.SignerNip44OperationResult
import com.libertasprimordium.othernote.security.SignerNoteEventBuildResult
import com.libertasprimordium.othernote.security.SignerNoteEventBuilder
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SignerNoteEventBuilderTests {
    private val crypto = ProductionNostrCryptoFactory.createOrNull()
        ?: error(ProductionNostrCryptoFactory.unavailableReason)

    @Test
    fun buildsAndVerifiesSignerBackedNoteEvent() {
        val fixture = fixture()
        val nip44 = FakeNip44Operator()
        val signer = SigningEventSigner(fixture.privateKey)
        val builder = builder(nip44, signer)

        val result = builder.buildTestNoteEvent(fixture.session, "com.example.signer")

        val success = assertIs<SignerNoteEventBuildResult.Success>(result)
        assertEquals("test-note-id", success.noteId)
        assertEquals(noteDTag("test-note-id"), success.dTag)
        assertEquals("test-note-id", success.note.id)
        assertEquals(success.signedEvent.id, success.note.sourceEventId)
        assertEquals(NoteKind, success.signedEvent.kind)
        assertEquals(fixture.session.publicKeyHex, success.signedEvent.pubkey)
        assertEquals(noteEventTags(noteDTag("test-note-id")), success.signedEvent.tags)
        assertEquals(nip44.ciphertext, success.signedEvent.content)
        assertTrue(crypto.validate(success.signedEvent).getOrThrow())
        assertFalse(success.safeSummary.contains(SignerNoteEventBuilder.TestBodyMarkdown))
        assertFalse(success.safeSummary.contains(nip44.ciphertext))
    }

    @Test
    fun buildsRealEditorBodyAsNonTombstoneLocalNoteEvent() {
        val fixture = fixture()
        val nip44 = FakeNip44Operator()
        val signer = SigningEventSigner(fixture.privateKey)
        val builder = builder(nip44, signer)
        val body = "editor note with **markdown**"

        val result = assertIs<SignerNoteEventBuildResult.Success>(
            builder.buildNewLocalNoteEvent(fixture.session, "com.example.signer", body),
        )
        val payload = JsonNotePayloadCodec.decode(nip44.lastPlaintext ?: error("missing plaintext")).getOrThrow()

        assertEquals("test-note-id", result.note.id)
        assertEquals(body, result.note.bodyMarkdown)
        assertEquals(result.note.id, payload.noteId)
        assertEquals(body, payload.bodyMarkdown)
        assertFalse(payload.deleted)
        assertFalse(result.safeSummary.contains(body))
        assertFalse(result.safeSummary.contains(nip44.ciphertext))
    }

    @Test
    fun replacementPreservesDTagAndUsesNewerEventTimestamp() {
        val fixture = fixture()
        val nip44 = FakeNip44Operator()
        val signer = SigningEventSigner(fixture.privateKey)
        val builder = builder(nip44, signer)
        val existing = Note(
            id = "existing-note-id",
            createdAtMs = 1_700_000_000_000,
            updatedAtMs = 1_700_000_000_500,
            bodyMarkdown = "old",
        )

        val result = assertIs<SignerNoteEventBuildResult.Success>(
            builder.buildReplacementLocalNoteEvent(fixture.session, "com.example.signer", existing, "edited"),
        )
        val payload = JsonNotePayloadCodec.decode(nip44.lastPlaintext ?: error("missing plaintext")).getOrThrow()

        assertEquals(existing.id, result.note.id)
        assertEquals(noteDTag(existing.id), result.dTag)
        assertEquals(noteEventTags(noteDTag(existing.id)), result.signedEvent.tags)
        assertTrue(result.signedEvent.createdAt > existing.updatedAtMs / 1_000)
        assertEquals("edited", payload.bodyMarkdown)
        assertEquals(result.note.updatedAtMs, payload.updatedAtMs)
    }

    @Test
    fun tombstoneUsesNewerEventTimestamp() {
        val fixture = fixture()
        val nip44 = FakeNip44Operator()
        val signer = SigningEventSigner(fixture.privateKey)
        val builder = builder(nip44, signer)
        val existing = Note(
            id = "existing-note-id",
            createdAtMs = 1_700_000_000_000,
            updatedAtMs = 1_700_000_000_500,
            bodyMarkdown = "old",
        )

        val result = assertIs<SignerNoteEventBuildResult.Success>(
            builder.buildLocalTombstoneEvent(fixture.session, "com.example.signer", existing),
        )

        assertTrue(result.signedEvent.createdAt > existing.updatedAtMs / 1_000)
        assertTrue(result.note.deleted)
    }

    @Test
    fun rejectsWrongPubkeyReturnedEvent() {
        val fixture = fixture()
        val otherPrivateKey = crypto.generatePrivateKey().getOrThrow()
        val nip44 = FakeNip44Operator()
        val signer = SigningEventSigner(otherPrivateKey)
        val builder = builder(nip44, signer)

        val result = builder.buildTestNoteEvent(fixture.session, "com.example.signer")

        assertIs<SignerNoteEventBuildResult.InvalidResponse>(result)
    }

    @Test
    fun rejectsWrongKindOrMissingTags() {
        val fixture = fixture()
        val nip44 = FakeNip44Operator()
        val wrongKind = builder(
            nip44,
            MutatingSigningEventSigner(fixture.privateKey) { it.copy(kind = 1) },
        ).buildTestNoteEvent(fixture.session, "com.example.signer")
        val missingDTag = builder(
            nip44,
            MutatingSigningEventSigner(fixture.privateKey) { it.copy(tags = it.tags.filterNot { tag -> tag.firstOrNull() == "d" }) },
        ).buildTestNoteEvent(fixture.session, "com.example.signer")
        val missingTTag = builder(
            nip44,
            MutatingSigningEventSigner(fixture.privateKey) { it.copy(tags = it.tags.filterNot { tag -> tag.firstOrNull() == "t" }) },
        ).buildTestNoteEvent(fixture.session, "com.example.signer")

        assertIs<SignerNoteEventBuildResult.InvalidResponse>(wrongKind)
        assertIs<SignerNoteEventBuildResult.InvalidResponse>(missingDTag)
        assertIs<SignerNoteEventBuildResult.InvalidResponse>(missingTTag)
    }

    @Test
    fun rejectsDecryptedPayloadMismatch() {
        val fixture = fixture()
        val nip44 = FakeNip44Operator(decryptOverride = """{"schema":"wrong"}""")
        val signer = SigningEventSigner(fixture.privateKey)
        val builder = builder(nip44, signer)

        val result = builder.buildTestNoteEvent(fixture.session, "com.example.signer")

        assertIs<SignerNoteEventBuildResult.InvalidResponse>(result)
    }

    @Test
    fun doesNotProduceTombstonePayloadForNormalTestEvent() {
        val fixture = fixture()
        val nip44 = FakeNip44Operator()
        val signer = SigningEventSigner(fixture.privateKey)
        val builder = builder(nip44, signer)

        val result = assertIs<SignerNoteEventBuildResult.Success>(
            builder.buildTestNoteEvent(fixture.session, "com.example.signer"),
        )
        val payload = JsonNotePayloadCodec.decode(nip44.lastPlaintext ?: error("missing plaintext")).getOrThrow()

        assertEquals(result.noteId, payload.noteId)
        assertEquals(SignerNoteEventBuilder.TestBodyMarkdown, payload.bodyMarkdown)
        assertFalse(payload.deleted)
    }

    @Test
    fun buildsTombstoneWithSameDTagAndDeletedPayload() {
        val fixture = fixture()
        val nip44 = FakeNip44Operator()
        val signer = SigningEventSigner(fixture.privateKey)
        val builder = builder(nip44, signer)
        val note = Note(
            id = "existing-note-id",
            createdAtMs = 1_600_000_000_000,
            updatedAtMs = 1_600_000_000_000,
            bodyMarkdown = "body to delete",
        )

        val result = assertIs<SignerNoteEventBuildResult.Success>(
            builder.buildLocalTombstoneEvent(fixture.session, "com.example.signer", note),
        )
        val payload = JsonNotePayloadCodec.decode(nip44.lastPlaintext ?: error("missing plaintext")).getOrThrow()

        assertEquals(note.id, result.note.id)
        assertEquals(noteDTag(note.id), result.dTag)
        assertEquals(noteEventTags(noteDTag(note.id)), result.signedEvent.tags)
        assertEquals(note.id, payload.noteId)
        assertEquals("", payload.bodyMarkdown)
        assertTrue(payload.deleted)
        assertFalse(result.safeSummary.contains(note.bodyMarkdown))
    }

    private fun builder(
        nip44: NostrSignerNip44Operator,
        signer: NostrSignerEventSigner,
    ): SignerNoteEventBuilder = SignerNoteEventBuilder(
        nip44 = nip44,
        eventSigner = signer,
        crypto = crypto,
        idProvider = { "test-note-id" },
        nowMsProvider = { 1_700_000_000_000 },
    )

    private fun fixture(): Fixture {
        val privateKey = crypto.generatePrivateKey().getOrThrow()
        val publicKey = crypto.derivePublicKey(privateKey).getOrThrow()
        return Fixture(
            privateKey = privateKey,
            session = UserSession(
                nsec = "external-signer",
                privateKeyHex = "",
                npub = publicKey.npub,
                publicKeyHex = publicKey.hex,
                authMethod = SessionAuthMethod.ExternalSigner,
                signerPackage = "com.example.signer",
            ),
        )
    }

    private data class Fixture(
        val privateKey: com.libertasprimordium.othernote.nostr.NostrPrivateKey,
        val session: UserSession,
    )

    private class FakeNip44Operator(
        val ciphertext: String = "encrypted-test-payload",
        private val decryptOverride: String? = null,
    ) : NostrSignerNip44Operator {
        var lastPlaintext: String? = null

        override fun encryptToSelf(
            plaintext: String,
            currentUserPubkey: String,
            signerPackage: String?,
        ): SignerNip44OperationResult {
            lastPlaintext = plaintext
            return SignerNip44OperationResult.Encrypted(ciphertext, signerPackage)
        }

        override fun decryptFromSelf(
            ciphertext: String,
            expectedPlaintext: String?,
            currentUserPubkey: String,
            signerPackage: String?,
        ): SignerNip44OperationResult =
            SignerNip44OperationResult.Decrypted(decryptOverride ?: expectedPlaintext.orEmpty(), signerPackage)
    }

    private inner class SigningEventSigner(
        private val privateKey: com.libertasprimordium.othernote.nostr.NostrPrivateKey,
    ) : NostrSignerEventSigner {
        override fun signEvent(
            unsignedEvent: NostrEvent,
            currentUserPubkey: String,
            signerPackage: String?,
            onResult: (SignEventRequestResult) -> Unit,
        ) {
            val signed = crypto.sign(
                UnsignedNostrEvent(
                    pubkey = unsignedEvent.pubkey,
                    createdAt = unsignedEvent.createdAt,
                    kind = unsignedEvent.kind,
                    tags = unsignedEvent.tags,
                    content = unsignedEvent.content,
                ),
                privateKey,
            ).getOrThrow()
            onResult(SignEventRequestResult.Success(signed, signerPackage))
        }
    }

    private inner class MutatingSigningEventSigner(
        private val privateKey: com.libertasprimordium.othernote.nostr.NostrPrivateKey,
        private val mutate: (NostrEvent) -> NostrEvent,
    ) : NostrSignerEventSigner {
        override fun signEvent(
            unsignedEvent: NostrEvent,
            currentUserPubkey: String,
            signerPackage: String?,
            onResult: (SignEventRequestResult) -> Unit,
        ) {
            val changed = mutate(unsignedEvent)
            val signed = crypto.sign(
                UnsignedNostrEvent(changed.pubkey, changed.createdAt, changed.kind, changed.tags, changed.content),
                privateKey,
            ).getOrThrow()
            onResult(SignEventRequestResult.Success(signed, signerPackage))
        }
    }
}
