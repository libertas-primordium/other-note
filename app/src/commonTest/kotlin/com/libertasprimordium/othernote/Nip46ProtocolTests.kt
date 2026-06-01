package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.security.Nip46ConnectionToken
import com.libertasprimordium.othernote.security.Nip46ConnectionTokenParser
import com.libertasprimordium.othernote.security.Nip46Method
import com.libertasprimordium.othernote.security.Nip46PayloadJson
import com.libertasprimordium.othernote.security.Nip46Permissions
import com.libertasprimordium.othernote.security.Nip46RequestPayload
import com.libertasprimordium.othernote.security.Nip46Response
import com.libertasprimordium.othernote.security.Nip46ResponsePayload
import com.libertasprimordium.othernote.nostr.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip46ProtocolTests {
    @Test
    fun parsesBunkerTokenWithOneRelay() {
        val parsed = Nip46ConnectionTokenParser.parse("bunker://$RemotePubkey?relay=wss%3A%2F%2Frelay.example.com&secret=super-secret").getOrThrow()

        val token = assertIs<Nip46ConnectionToken.Bunker>(parsed)
        assertEquals(RemotePubkey, token.remoteSignerPubkey)
        assertEquals(listOf("wss://relay.example.com"), token.relays)
        assertEquals("super-secret", token.secret)
        assertFalse(token.toString().contains("super-secret"))
    }

    @Test
    fun parsesBunkerTokenWithMultipleRelays() {
        val parsed = Nip46ConnectionTokenParser.parse("bunker://$RemotePubkey?relay=wss://one.example.com&relay=wss://two.example.com").getOrThrow()

        val token = assertIs<Nip46ConnectionToken.Bunker>(parsed)
        assertEquals(listOf("wss://one.example.com", "wss://two.example.com"), token.relays)
    }

    @Test
    fun rejectsMalformedOrUnsafeTokens() {
        assertTrue(Nip46ConnectionTokenParser.parse("bunker://bad?relay=wss://relay.example.com").isFailure)
        assertTrue(Nip46ConnectionTokenParser.parse("bunker://$RemotePubkey").isFailure)
        assertTrue(Nip46ConnectionTokenParser.parse("nostrsigner://$RemotePubkey?relay=wss://relay.example.com").isFailure)
        assertTrue(Nip46ConnectionTokenParser.parse("bunker://$RemotePubkey?relay=ws://relay.example.com").isFailure)
        assertTrue(Nip46ConnectionTokenParser.parse("nostrconnect://$RemotePubkey?relay=wss://relay.example.com").isFailure)
    }

    @Test
    fun generatesNostrConnectTokenWithPermissions() {
        val uri = Nip46ConnectionTokenParser.generateNostrConnectUri(
            clientPubkey = RemotePubkey,
            relays = listOf("wss://relay.example.com"),
            secret = "pair-secret",
            permissions = Nip46Permissions(listOf(Nip46Method.GetPublicKey, Nip46Method.SignEvent)),
            name = "Other Note Test",
        ).getOrThrow()

        assertTrue(uri.startsWith("nostrconnect://$RemotePubkey?"))
        assertTrue(uri.contains("relay=wss%3A%2F%2Frelay.example.com"))
        assertTrue(uri.contains("secret=pair-secret"))
        assertTrue(uri.contains("perms=get_public_key%2Csign_event"))
        val parsed = assertIs<Nip46ConnectionToken.NostrConnect>(Nip46ConnectionTokenParser.parse(uri).getOrThrow())
        assertEquals(listOf(Nip46Method.GetPublicKey, Nip46Method.SignEvent), parsed.permissions.methods)
        assertFalse(parsed.toString().contains("pair-secret"))
    }

    @Test
    fun requestAndResponsePayloadsRoundTrip() {
        val request = Nip46RequestPayload("req-1", "sign_event", listOf("""{"kind":30078}"""))
        val rawRequest = Nip46PayloadJson.encodeRequest(request)

        assertEquals(request, Nip46PayloadJson.decodeRequest(rawRequest).getOrThrow())

        val success = Nip46PayloadJson.decodeResponse(
            Nip46PayloadJson.encodeResponse(Nip46ResponsePayload("req-1", result = "ok")),
            expectedRequestId = "req-1",
        ).getOrThrow()
        assertEquals(Nip46Response.Success("req-1", "ok"), success)

        val error = Nip46PayloadJson.decodeResponse(
            Nip46PayloadJson.encodeResponse(Nip46ResponsePayload("req-1", error = "rejected")),
            expectedRequestId = "req-1",
        ).getOrThrow()
        assertEquals(Nip46Response.Error("req-1", "rejected"), error)

        val auth = Nip46PayloadJson.decodeResponse(
            Nip46PayloadJson.encodeResponse(Nip46ResponsePayload("req-1", result = "auth_url", error = "https://signer.example.com/approve")),
            expectedRequestId = "req-1",
        ).getOrThrow()
        assertEquals(Nip46Response.AuthChallenge("req-1", "https://signer.example.com/approve"), auth)
    }

    @Test
    fun encodesSpecCompatibleSignEventPayload() {
        val event = NostrEvent(
            id = "id-not-sent",
            pubkey = RemotePubkey,
            createdAt = 123,
            kind = 30078,
            tags = listOf(listOf("d", "note"), listOf("t", "other-note")),
            content = "encrypted-content",
            sig = "sig-not-sent",
        )

        val payload = Json.parseToJsonElement(Nip46PayloadJson.encodeUnsignedSignEvent(event)).jsonObject

        assertEquals("123", payload["created_at"]?.jsonPrimitive?.content)
        assertEquals("30078", payload["kind"]?.jsonPrimitive?.content)
        assertEquals("encrypted-content", payload["content"]?.jsonPrimitive?.content)
        assertNull(payload["pubkey"])
        assertNull(payload["id"])
        assertNull(payload["sig"])
    }

    @Test
    fun otherNoteConnectPermissionsRequestKindScopedSigning() {
        val permissions = Nip46Permissions.otherNoteConnectPermissions()

        assertTrue(permissions.contains("get_public_key"))
        assertTrue(permissions.contains("sign_event:30078"))
        assertTrue(permissions.contains("nip44_encrypt"))
        assertTrue(permissions.contains("nip44_decrypt"))
        assertTrue(permissions.contains("ping"))
        assertTrue(permissions.contains("switch_relays"))
    }

    @Test
    fun rejectsMismatchedResponseIds() {
        val decoded = Nip46PayloadJson.decodeResponse(
            Nip46PayloadJson.encodeResponse(Nip46ResponsePayload("other", result = "ok")),
            expectedRequestId = "req-1",
        )

        assertTrue(decoded.isFailure)
    }
}

private val RemotePubkey = "ab".repeat(32)
