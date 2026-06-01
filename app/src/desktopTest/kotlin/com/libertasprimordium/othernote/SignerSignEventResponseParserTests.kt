package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.nostr.NostrWireJson
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.security.SignEventRequestResult
import com.libertasprimordium.othernote.security.SignerSignEventResponseParser
import com.libertasprimordium.othernote.security.SignerTestEventFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SignerSignEventResponseParserTests {
    private val crypto = ProductionNostrCryptoFactory.createOrNull()
        ?: error(ProductionNostrCryptoFactory.unavailableReason)

    @Test
    fun validatesSignedEventJson() {
        val fixture = signedFixture()

        val result = SignerSignEventResponseParser.parseAndValidate(
            requestedEvent = fixture.requested,
            eventJson = NostrWireJson.eventJson(fixture.signed),
            signature = null,
            returnedId = null,
            signerPackage = "com.example.signer",
            crypto = crypto,
        )

        val success = assertIs<SignEventRequestResult.Success>(result)
        assertEquals(fixture.signed, success.signedEvent)
        assertEquals("com.example.signer", success.signerPackage)
    }

    @Test
    fun canBuildSignedEventFromSignatureResult() {
        val fixture = signedFixture()

        val result = SignerSignEventResponseParser.parseAndValidate(
            requestedEvent = fixture.requested,
            eventJson = null,
            signature = fixture.signed.sig,
            returnedId = fixture.signed.id,
            signerPackage = null,
            crypto = crypto,
        )

        val success = assertIs<SignEventRequestResult.Success>(result)
        assertEquals(fixture.signed, success.signedEvent)
    }

    @Test
    fun rejectsInvalidSignature() {
        val fixture = signedFixture()
        val invalid = fixture.signed.copy(sig = "00".repeat(64))

        val result = SignerSignEventResponseParser.parseAndValidate(
            requestedEvent = fixture.requested,
            eventJson = NostrWireJson.eventJson(invalid),
            signature = null,
            returnedId = null,
            signerPackage = null,
            crypto = crypto,
        )

        assertIs<SignEventRequestResult.InvalidResponse>(result)
    }

    @Test
    fun rejectsWrongPubkeySignedEvent() {
        val fixture = signedFixture()
        val otherPrivateKey = crypto.generatePrivateKey().getOrThrow()
        val otherPublicKey = crypto.derivePublicKey(otherPrivateKey).getOrThrow()
        val changed = fixture.requested.copy(pubkey = otherPublicKey.hex)
        val signed = crypto.sign(
            UnsignedNostrEvent(changed.pubkey, changed.createdAt, changed.kind, changed.tags, changed.content),
            otherPrivateKey,
        ).getOrThrow()

        val result = SignerSignEventResponseParser.parseAndValidate(
            requestedEvent = fixture.requested,
            eventJson = NostrWireJson.eventJson(signed),
            signature = null,
            returnedId = null,
            signerPackage = null,
            crypto = crypto,
        )

        assertIs<SignEventRequestResult.InvalidResponse>(result)
    }

    @Test
    fun rejectsUnexpectedContent() {
        val fixture = signedFixture()
        val changed = fixture.requested.copy(content = "unexpected")
        val signed = crypto.sign(
            UnsignedNostrEvent(changed.pubkey, changed.createdAt, changed.kind, changed.tags, changed.content),
            fixture.privateKey,
        ).getOrThrow()

        val result = SignerSignEventResponseParser.parseAndValidate(
            requestedEvent = fixture.requested,
            eventJson = NostrWireJson.eventJson(signed),
            signature = null,
            returnedId = null,
            signerPackage = null,
            crypto = crypto,
        )

        assertIs<SignEventRequestResult.InvalidResponse>(result)
    }

    @Test
    fun rejectsMalformedOrMissingSignedEvent() {
        val fixture = signedFixture()

        val malformed = SignerSignEventResponseParser.parseAndValidate(
            requestedEvent = fixture.requested,
            eventJson = "{not-json",
            signature = null,
            returnedId = null,
            signerPackage = null,
            crypto = crypto,
        )
        val missingSig = SignerSignEventResponseParser.parseAndValidate(
            requestedEvent = fixture.requested,
            eventJson = null,
            signature = "",
            returnedId = fixture.requested.id,
            signerPackage = null,
            crypto = crypto,
        )

        assertIs<SignEventRequestResult.InvalidResponse>(malformed)
        assertIs<SignEventRequestResult.InvalidResponse>(missingSig)
    }

    @Test
    fun rejectsResultWithNoEventToVerify() {
        val fixture = signedFixture()

        val result = SignerSignEventResponseParser.parseAndValidate(
            requestedEvent = fixture.requested,
            eventJson = null,
            signature = null,
            returnedId = fixture.requested.id,
            signerPackage = null,
            crypto = crypto,
        )

        val invalid = assertIs<SignEventRequestResult.InvalidResponse>(result)
        assertEquals("Signer returned no event to verify", invalid.safeReason)
    }

    private fun signedFixture(): SignedFixture {
        val privateKey = crypto.generatePrivateKey().getOrThrow()
        val publicKey = crypto.derivePublicKey(privateKey).getOrThrow()
        val requested = SignerTestEventFactory.build(publicKey.hex, nowMs = 1_700_000_000_000).getOrThrow()
        val signed = crypto.sign(
            UnsignedNostrEvent(requested.pubkey, requested.createdAt, requested.kind, requested.tags, requested.content),
            privateKey,
        ).getOrThrow()
        return SignedFixture(privateKey, requested, signed)
    }

    private data class SignedFixture(
        val privateKey: com.libertasprimordium.othernote.nostr.NostrPrivateKey,
        val requested: com.libertasprimordium.othernote.nostr.NostrEvent,
        val signed: com.libertasprimordium.othernote.nostr.NostrEvent,
    )
}
