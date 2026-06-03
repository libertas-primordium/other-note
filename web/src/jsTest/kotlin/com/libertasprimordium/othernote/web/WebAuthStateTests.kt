package com.libertasprimordium.othernote.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WebAuthStateTests {
    @Test
    fun missingNip07SignerFailsSafely() {
        val state = beginNip07SignIn(WebAuthUiState(nip07Available = false))
        val failed = assertIs<WebSignInState.Failed>(state.signInState)
        assertEquals(WebAuthCopy.ExtensionMissing, failed.message)
    }

    @Test
    fun validPublicKeySignsInInMemoryState() {
        val key = "A1".repeat(32)
        val state = completeNip07SignIn(WebAuthUiState(nip07Available = true), key)
        val signedIn = assertIs<WebSignInState.SignedIn>(state.signInState)
        assertEquals(key.lowercase(), signedIn.identity.publicKeyHex)
        assertEquals(WebAuthMethod.Nip07, signedIn.identity.method)
        assertEquals("a1a1a1a1...a1a1a1a1", signedIn.identity.displayPublicKey)
    }

    @Test
    fun logoutClearsSignedInState() {
        val signedIn = WebAuthUiState(
            nip07Available = true,
            signInState = WebSignInState.SignedIn(WebAccountIdentity("01".repeat(32), WebAuthMethod.Nip46)),
            nip46Status = WebNip46Status.RequestingPublicKey,
            nip46Message = "Reading account public key from remote signer.",
        )
        val loggedOut = logoutWebAccount(signedIn)
        assertIs<WebSignInState.SignedOut>(loggedOut.signInState)
        assertEquals(WebNip46Status.Idle, loggedOut.nip46Status)
        assertEquals("", loggedOut.nip46Message)
    }

    @Test
    fun blankPublicKeyFailsSafely() {
        val state = completeNip07SignIn(WebAuthUiState(nip07Available = true), "   ")
        val failed = assertIs<WebSignInState.Failed>(state.signInState)
        assertEquals(WebAuthCopy.PublicKeyMissing, failed.message)
    }

    @Test
    fun malformedPublicKeyFailsSafely() {
        val state = completeNip07SignIn(WebAuthUiState(nip07Available = true), "npub-not-a-hex-key")
        val failed = assertIs<WebSignInState.Failed>(state.signInState)
        assertEquals(WebAuthCopy.PublicKeyMalformed, failed.message)
    }

    @Test
    fun extensionFailureMapsToSafeMessage() {
        val state = failNip07SignIn(WebAuthUiState(nip07Available = true))
        val failed = assertIs<WebSignInState.Failed>(state.signInState)
        assertEquals(WebAuthCopy.ExtensionRequestFailed, failed.message)
        assertEquals(WebAuthMethod.Nip07, failed.method)
    }

    @Test
    fun nip46StateTransitionsFromPendingToSignedIn() {
        val pending = beginNip46SignIn(WebAuthUiState(nip07Available = false))
        assertEquals(WebNip46Status.PreparingConnection, pending.nip46Status)
        val signingIn = assertIs<WebSignInState.SigningIn>(pending.signInState)
        assertEquals(WebAuthMethod.Nip46, signingIn.method)

        val key = "b2".repeat(32)
        val signedInState = completeNip46SignIn(pending, key)
        val signedIn = assertIs<WebSignInState.SignedIn>(signedInState.signInState)
        assertEquals(key, signedIn.identity.publicKeyHex)
        assertEquals(WebAuthMethod.Nip46, signedIn.identity.method)
        assertEquals(WebNip46Status.Idle, signedInState.nip46Status)
    }

    @Test
    fun nip46FailureIsScopedToRemoteSignerMethod() {
        val state = failNip46SignIn(WebAuthUiState(nip07Available = true), WebAuthCopy.Nip46SignerTimeout)
        val failed = assertIs<WebSignInState.Failed>(state.signInState)
        assertEquals(WebAuthCopy.Nip46SignerTimeout, failed.message)
        assertEquals(WebAuthMethod.Nip46, failed.method)
        assertEquals(WebNip46Status.Failed, state.nip46Status)
    }

    @Test
    fun nip46TokenInputIsPasswordStyleAndNotNsecLabeled() {
        assertEquals("password", Nip46TokenInputType)
        assertTrue(!Nip46TokenInputLabel.lowercase().contains("nsec"))
    }
}

class WebLayoutMenuStateTests {
    @Test
    fun signedInMenuIncludesSecondaryActionsAndLogout() {
        assertEquals(listOf("Reload notes", "Note relays", "About web preview", "Logout"), WebSignedInMenuItems)
    }

    @Test
    fun relaySettingsPanelIsHiddenByDefault() {
        val state = WebMenuUiState()

        assertEquals(false, state.open)
        assertEquals(WebMenuPanel.None, state.activePanel)
    }

    @Test
    fun menuOpensAndCanSelectNoteRelaysPanel() {
        val opened = toggleWebMenu(WebMenuUiState())
        val panel = openWebMenuPanel(opened, WebMenuPanel.NoteRelays)

        assertEquals(true, opened.open)
        assertEquals(false, panel.open)
        assertEquals(WebMenuPanel.NoteRelays, panel.activePanel)
    }

    @Test
    fun aboutPanelCanOpenAndClose() {
        val panel = openWebMenuPanel(WebMenuUiState(open = true), WebMenuPanel.About)
        val closed = closeWebMenuPanel(panel)

        assertEquals(false, panel.open)
        assertEquals(WebMenuPanel.About, panel.activePanel)
        assertEquals(WebMenuPanel.None, closed.activePanel)
    }

    @Test
    fun logoutResetsMenuPanelState() {
        val state = WebMenuUiState(open = true, activePanel = WebMenuPanel.NoteRelays)

        assertEquals(WebMenuUiState(), resetWebMenuState())
        assertEquals(WebMenuUiState(), closeWebMenuPanel(closeWebMenu(state)))
    }
}

class WebNip46TokenTests {
    @Test
    fun parsesBunkerTokenWithSignerRelays() {
        val pubkey = "01".repeat(32)
        val parsed = assertIs<WebNip46TokenParseResult.Valid>(
            parseWebNip46BunkerToken("bunker://$pubkey?relay=wss%3A%2F%2Frelay.example.com&secret=test-secret"),
        )
        assertEquals(pubkey, parsed.token.remoteSignerPubkey)
        assertEquals(listOf("wss://relay.example.com"), parsed.token.relays)
    }

    @Test
    fun invalidTokenFailsSafely() {
        val parsed = assertIs<WebNip46TokenParseResult.Invalid>(
            parseWebNip46BunkerToken("nostrconnect://not-supported"),
        )
        assertEquals(WebAuthCopy.Nip46InvalidToken, parsed.safeMessage)
    }

    @Test
    fun tokenMissingRelayFailsSafely() {
        val parsed = assertIs<WebNip46TokenParseResult.Invalid>(
            parseWebNip46BunkerToken("bunker://${"02".repeat(32)}"),
        )
        assertEquals(WebAuthCopy.Nip46MissingRelay, parsed.safeMessage)
    }

    @Test
    fun tokenWithInvalidSignerPubkeyFailsSafely() {
        val parsed = assertIs<WebNip46TokenParseResult.Invalid>(
            parseWebNip46BunkerToken("bunker://not-a-pubkey?relay=wss%3A%2F%2Frelay.example.com"),
        )
        assertEquals(WebAuthCopy.Nip46InvalidRemotePubkey, parsed.safeMessage)
    }
}

class WebNip46TransportKeyTests {
    @Test
    fun deterministicTransportKeyGenerationProducesValidPublicKey() {
        val result = assertIs<WebNip46KeyGenerationResult.Success>(
            generateWebNip46TransportKey(
                randomBytes = { WebNip46RandomBytesResult.Success(fixedPrivateKey(lastByte = 1)) },
            ),
        )
        assertEquals(64, result.keyPair.clientPubkey.length)
        assertTrue(result.keyPair.clientPubkey.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(1, readUint8ArrayByte(result.keyPair.clientPrivateKey, 31) and 0xff)
    }

    @Test
    fun generatedTransportKeysDifferWhenSecureRandomBytesDiffer() {
        var nextLastByte = 1
        val first = assertIs<WebNip46KeyGenerationResult.Success>(
            generateWebNip46TransportKey(
                randomBytes = { WebNip46RandomBytesResult.Success(fixedPrivateKey(lastByte = nextLastByte++)) },
            ),
        )
        val second = assertIs<WebNip46KeyGenerationResult.Success>(
            generateWebNip46TransportKey(
                randomBytes = { WebNip46RandomBytesResult.Success(fixedPrivateKey(lastByte = nextLastByte++)) },
            ),
        )
        assertNotEquals(first.keyPair.clientPubkey, second.keyPair.clientPubkey)
    }

    @Test
    fun allZeroTransportPrivateKeyFailsSafely() {
        val result = assertIs<WebNip46KeyGenerationResult.Failed>(
            generateWebNip46TransportKey(
                randomBytes = { WebNip46RandomBytesResult.Success(Uint8Array(32)) },
            ),
        )
        assertEquals(WebNip46KeyFailureReason.InvalidGeneratedPrivateKey, result.reason)
        assertEquals(WebAuthCopy.Nip46InvalidGeneratedPrivateKey, result.safeMessage)
    }

    @Test
    fun randomGenerationFailureFailsSafely() {
        val result = assertIs<WebNip46KeyGenerationResult.Failed>(
            generateWebNip46TransportKey(
                randomBytes = {
                    WebNip46RandomBytesResult.Failed(
                        reason = WebNip46KeyFailureReason.RandomGenerationFailed,
                        safeMessage = WebAuthCopy.Nip46RandomGenerationFailed,
                    )
                },
            ),
        )
        assertEquals(WebNip46KeyFailureReason.RandomGenerationFailed, result.reason)
        assertEquals(WebAuthCopy.Nip46RandomGenerationFailed, result.safeMessage)
    }

    @Test
    fun publicKeyDerivationFailureFailsSafely() {
        val result = assertIs<WebNip46KeyGenerationResult.Failed>(
            generateWebNip46TransportKey(
                randomBytes = { WebNip46RandomBytesResult.Success(fixedPrivateKey(lastByte = 3)) },
                derivePublicKey = { Result.failure(IllegalStateException("derive failed")) },
            ),
        )
        assertEquals(WebNip46KeyFailureReason.PublicKeyDerivationFailed, result.reason)
        assertEquals(WebAuthCopy.Nip46PublicKeyDerivationFailed, result.safeMessage)
    }

    private fun fixedPrivateKey(lastByte: Int): Uint8Array =
        Uint8Array(32).also { bytes ->
            writeUint8ArrayByte(bytes, 31, lastByte)
        }
}

class WebNip46ResponseClassificationTests {
    private val clientPubkey = "11".repeat(32)
    private val remotePubkey = "22".repeat(32)

    @Test
    fun wrongKindResponseIsIgnored() {
        val result = assertIs<WebNip46ResponseProcessingResult.Ignored>(
            processResponseEvent(
                session = testSession(),
                event = testEvent(kind = 1),
                expectedRequestId = "req-1",
                since = 1,
            ),
        )
        assertEquals(WebNip46ResponseIgnoreReason.WrongKind, result.reason)
    }

    @Test
    fun responseFromUnexpectedRemotePubkeyIsIgnored() {
        val result = assertIs<WebNip46ResponseProcessingResult.Ignored>(
            processResponseEvent(
                session = testSession(),
                event = testEvent(pubkey = "33".repeat(32)),
                expectedRequestId = "req-1",
                since = 1,
            ),
        )
        assertEquals(WebNip46ResponseIgnoreReason.UnexpectedPubkey, result.reason)
    }

    @Test
    fun responseAddressedToWrongClientPubkeyIsIgnored() {
        val result = assertIs<WebNip46ResponseProcessingResult.Ignored>(
            processResponseEvent(
                session = testSession(),
                event = testEvent(tags = listOf(listOf("p", "44".repeat(32)))),
                expectedRequestId = "req-1",
                since = 1,
            ),
        )
        assertEquals(WebNip46ResponseIgnoreReason.WrongRecipient, result.reason)
    }

    @Test
    fun responseWithoutRecipientTagIsAcceptedAsCandidateLikeNativeImplementation() {
        assertTrue(testEvent(tags = emptyList()).targetsClientPubkey(clientPubkey))
    }

    @Test
    fun candidateResponseDecryptionFailureIsClassifiedSafely() {
        val result = assertIs<WebNip46ResponseProcessingResult.Ignored>(
            processResponseEvent(
                session = testSession(),
                event = testEvent(content = "not encrypted nip46 content"),
                expectedRequestId = "req-1",
                since = 1,
            ),
        )
        assertEquals(WebNip46ResponseIgnoreReason.DecryptionFailed, result.reason)
    }

    @Test
    fun malformedDecryptedJsonMapsToSafeMalformedResponse() {
        val result = assertIs<WebNip46ResponseDecodeResult.Response>(
            decodeNip46ResponsePayload("not-json", expectedRequestId = "req-1"),
        )
        val error = assertIs<WebNip46RelayResponse.Error>(result.response)
        assertEquals(WebAuthCopy.Nip46MalformedResponse, error.safeMessage)
    }

    @Test
    fun jsonRpcIdMismatchIsIgnored() {
        val result = assertIs<WebNip46ResponseDecodeResult.Ignored>(
            decodeNip46ResponsePayload("""{"id":"other","result":"ok"}""", expectedRequestId = "req-1"),
        )
        assertEquals(WebNip46ResponseIgnoreReason.IdMismatch, result.reason)
    }

    @Test
    fun remoteSignerErrorObjectMapsToSafeReadableCopy() {
        val result = assertIs<WebNip46ResponseDecodeResult.Response>(
            decodeNip46ResponsePayload("""{"id":"req-1","error":{"message":"denied by signer"}}""", expectedRequestId = "req-1"),
        )
        val error = assertIs<WebNip46RelayResponse.Error>(result.response)
        assertEquals(WebAuthCopy.Nip46SignerRejected, error.safeMessage)
    }

    @Test
    fun remoteSignerSecretErrorDoesNotEchoSensitiveTokenMaterial() {
        val sensitive = "secret=do-not-display-this-token-value"
        val result = assertIs<WebNip46ResponseDecodeResult.Response>(
            decodeNip46ResponsePayload("""{"id":"req-1","error":{"message":"bad $sensitive"}}""", expectedRequestId = "req-1"),
        )
        val error = assertIs<WebNip46RelayResponse.Error>(result.response)

        assertEquals("Remote signer rejected the pairing secret.", error.safeMessage)
        assertTrue(!error.safeMessage.contains("do-not-display"))
        assertTrue(!error.safeMessage.contains("secret="))
    }

    @Test
    fun genericRemoteSignerErrorDoesNotEchoPlaintextOrRawPayload() {
        val rawError = "failed while handling plaintext note body: # private note"
        val result = assertIs<WebNip46ResponseDecodeResult.Response>(
            decodeNip46ResponsePayload("""{"id":"req-1","error":{"message":"$rawError"}}""", expectedRequestId = "req-1"),
        )
        val error = assertIs<WebNip46RelayResponse.Error>(result.response)

        assertEquals("Remote signer request failed.", error.safeMessage)
        assertTrue(!error.safeMessage.contains("private note"))
        assertTrue(!error.safeMessage.contains("plaintext note body"))
    }

    @Test
    fun validGetPublicKeyResponseDecodesAsSuccess() {
        val pubkey = "ab".repeat(32)
        val result = assertIs<WebNip46ResponseDecodeResult.Response>(
            decodeNip46ResponsePayload("""{"id":"req-1","result":"$pubkey"}""", expectedRequestId = "req-1"),
        )
        val success = assertIs<WebNip46RelayResponse.Success>(result.response)
        assertEquals(pubkey, success.result)
        val signedIn = completeNip46SignIn(WebAuthUiState(nip07Available = false), success.result)
        assertIs<WebSignInState.SignedIn>(signedIn.signInState)
    }

    @Test
    fun malformedReturnedPublicKeyFailsSafelyAfterDecode() {
        val result = assertIs<WebNip46ResponseDecodeResult.Response>(
            decodeNip46ResponsePayload("""{"id":"req-1","result":"not-a-pubkey"}""", expectedRequestId = "req-1"),
        )
        val success = assertIs<WebNip46RelayResponse.Success>(result.response)
        val signedIn = completeNip46SignIn(WebAuthUiState(nip07Available = false), success.result)
        val failed = assertIs<WebSignInState.Failed>(signedIn.signInState)
        assertEquals(WebAuthCopy.PublicKeyMalformed, failed.message)
    }

    private fun testSession(): WebNip46Session =
        WebNip46Session(
            clientPrivateKey = fixedPrivateKey(),
            clientPubkey = clientPubkey,
            remoteSignerPubkey = remotePubkey,
            relays = listOf("wss://relay.example.com"),
        )

    private fun testEvent(
        kind: Int = 24133,
        pubkey: String = remotePubkey,
        tags: List<List<String>> = listOf(listOf("p", clientPubkey)),
        content: String = "",
    ): WebNostrEvent =
        WebNostrEvent(
            id = "event-id",
            pubkey = pubkey,
            createdAt = 2,
            kind = kind,
            tags = tags,
            content = content,
            sig = "sig",
        )

    private fun fixedPrivateKey(): Uint8Array =
        Uint8Array(32).also { bytes ->
            writeUint8ArrayByte(bytes, 31, 1)
        }
}

class WebNip46RequestCreationTests {
    @Test
    fun validSessionCanCreateNip46ConnectRequestEvent() {
        val client = keyPair(lastByte = 1)
        val remote = keyPair(lastByte = 2)
        val session = WebNip46Session(
            clientPrivateKey = client.keyPair.clientPrivateKey,
            clientPubkey = client.keyPair.clientPubkey,
            remoteSignerPubkey = remote.keyPair.clientPubkey,
            relays = listOf("wss://relay.example.com"),
        )
        val request = WebNip46RequestPayload(
            id = "req-1",
            method = "connect",
            params = listOf(remote.keyPair.clientPubkey, "pairing-secret", "get_public_key,ping"),
        )

        val result = assertIs<WebNip46RequestBuildResult.Success>(buildRequestEvent(session, request))
        val event = result.event
        assertEquals(24133, event.kind)
        assertEquals(client.keyPair.clientPubkey, event.pubkey)
        assertEquals(listOf(listOf("p", remote.keyPair.clientPubkey)), event.tags)
        assertTrue(event.id.isNotBlank())
        assertTrue(event.sig.isNotBlank())
        assertTrue(event.content.isNotBlank())
    }

    @Test
    fun missingRemoteSignerPubkeyFailsRequestCreationSafely() {
        val client = keyPair(lastByte = 1)
        val result = assertIs<WebNip46RequestBuildResult.Failed>(
            buildRequestEvent(
                session = WebNip46Session(
                    clientPrivateKey = client.keyPair.clientPrivateKey,
                    clientPubkey = client.keyPair.clientPubkey,
                    remoteSignerPubkey = "",
                    relays = listOf("wss://relay.example.com"),
                ),
                request = WebNip46RequestPayload("req-1", "connect"),
            ),
        )
        assertEquals(WebNip46RequestFailureReason.MissingRemoteSignerPubkey, result.reason)
        assertEquals(WebAuthCopy.Nip46RequestMissingRemoteSigner, result.safeMessage)
    }

    @Test
    fun missingClientCommunicationKeyFailsRequestCreationSafely() {
        val remote = keyPair(lastByte = 2)
        val result = assertIs<WebNip46RequestBuildResult.Failed>(
            buildRequestEvent(
                session = WebNip46Session(
                    clientPrivateKey = Uint8Array(32),
                    clientPubkey = "not-a-client-pubkey",
                    remoteSignerPubkey = remote.keyPair.clientPubkey,
                    relays = listOf("wss://relay.example.com"),
                ),
                request = WebNip46RequestPayload("req-1", "connect"),
            ),
        )
        assertEquals(WebNip46RequestFailureReason.MissingClientCommunicationKey, result.reason)
        assertEquals(WebAuthCopy.Nip46RequestMissingClientKey, result.safeMessage)
    }

    @Test
    fun requestJsonSerializationFailureIsClassifiedSafely() {
        val result = assertIs<WebNip46RequestBuildResult.Failed>(
            buildRequestEvent(
                session = requestSession(),
                request = WebNip46RequestPayload("req-1", "connect"),
                builder = WebNip46RequestBuilder(
                    serializeRequest = { Result.failure(IllegalStateException("json failed")) },
                ),
            ),
        )
        assertEquals(WebNip46RequestFailureReason.JsonSerializationFailed, result.reason)
        assertEquals(WebAuthCopy.Nip46RequestJsonFailed, result.safeMessage)
    }

    @Test
    fun requestEncryptionFailureIsClassifiedSafely() {
        val result = assertIs<WebNip46RequestBuildResult.Failed>(
            buildRequestEvent(
                session = requestSession(),
                request = WebNip46RequestPayload("req-1", "connect"),
                builder = WebNip46RequestBuilder(
                    encryptRequest = { _, _ -> Result.failure(IllegalStateException("encrypt failed")) },
                ),
            ),
        )
        assertEquals(WebNip46RequestFailureReason.EncryptionFailed, result.reason)
        assertEquals(WebAuthCopy.Nip46RequestEncryptionFailed, result.safeMessage)
    }

    @Test
    fun requestSigningFailureIsClassifiedSafely() {
        val result = assertIs<WebNip46RequestBuildResult.Failed>(
            buildRequestEvent(
                session = requestSession(),
                request = WebNip46RequestPayload("req-1", "connect"),
                builder = WebNip46RequestBuilder(
                    signRequest = { _, _, _, _ -> Result.failure(IllegalStateException("sign failed")) },
                ),
            ),
        )
        assertEquals(WebNip46RequestFailureReason.SigningFailed, result.reason)
        assertEquals(WebAuthCopy.Nip46RequestSigningFailed, result.safeMessage)
    }

    @Test
    fun requestEventSerializationFailureIsClassifiedSafely() {
        val result = assertIs<WebNip46RequestBuildResult.Failed>(
            buildRequestEvent(
                session = requestSession(),
                request = WebNip46RequestPayload("req-1", "connect"),
                builder = WebNip46RequestBuilder(
                    serializeEvent = { Result.failure(IllegalStateException("event json failed")) },
                ),
            ),
        )
        assertEquals(WebNip46RequestFailureReason.EventSerializationFailed, result.reason)
        assertEquals(WebAuthCopy.Nip46RequestSerializationFailed, result.safeMessage)
    }

    private fun requestSession(): WebNip46Session {
        val client = keyPair(lastByte = 3)
        val remote = keyPair(lastByte = 4)
        return WebNip46Session(
            clientPrivateKey = client.keyPair.clientPrivateKey,
            clientPubkey = client.keyPair.clientPubkey,
            remoteSignerPubkey = remote.keyPair.clientPubkey,
            relays = listOf("wss://relay.example.com"),
        )
    }

    private fun keyPair(lastByte: Int): WebNip46KeyGenerationResult.Success =
        assertIs<WebNip46KeyGenerationResult.Success>(
            generateWebNip46TransportKey(
                randomBytes = { WebNip46RandomBytesResult.Success(fixedPrivateKey(lastByte)) },
            ),
        )

    private fun fixedPrivateKey(lastByte: Int): Uint8Array =
        Uint8Array(32).also { bytes ->
            writeUint8ArrayByte(bytes, 31, lastByte)
        }
}

class WebNip46RelayStageTests {
    @Test
    fun failedSecondaryRelayDoesNotFailWhenPrimaryPublishedRequest() {
        val tracker = WebNip46RelayRequestTracker(totalRelays = 2, method = "connect")
        val primary = WebNip46RelayAttemptState()
        val secondary = WebNip46RelayAttemptState()

        tracker.markPublished(primary)

        assertNull(tracker.markFailed(secondary))
        assertEquals(1, tracker.openedRelays)
        assertEquals(1, tracker.publishedRelays)
    }

    @Test
    fun allRelaysFailingBeforeOpenMapsToConnectionFailure() {
        val tracker = WebNip46RelayRequestTracker(totalRelays = 2, method = "connect")

        assertNull(tracker.markFailed(WebNip46RelayAttemptState()))
        assertEquals(
            WebAuthCopy.Nip46ConnectionFailed,
            tracker.markFailed(WebNip46RelayAttemptState()),
        )
    }

    @Test
    fun allRelaysFailingAfterOpenBeforePublishMapsToPublishFailure() {
        val tracker = WebNip46RelayRequestTracker(totalRelays = 1, method = "connect")
        val attempt = WebNip46RelayAttemptState()

        tracker.markOpened(attempt)

        assertEquals(WebAuthCopy.Nip46RelayPublishFailed, tracker.markFailed(attempt))
    }

    @Test
    fun relayCloseAfterPublishMapsToResponseWaitFailureNotConnectionFailure() {
        val tracker = WebNip46RelayRequestTracker(totalRelays = 1, method = "connect")
        val attempt = WebNip46RelayAttemptState()

        tracker.markPublished(attempt)

        assertEquals(WebAuthCopy.Nip46RelayClosedBeforeResponse, tracker.markFailed(attempt))
    }

    @Test
    fun failedPublishOnSecondaryRelayDoesNotFailWhenPrimaryIsWaiting() {
        val tracker = WebNip46RelayRequestTracker(totalRelays = 2, method = "connect")
        val primary = WebNip46RelayAttemptState()
        val secondary = WebNip46RelayAttemptState()

        tracker.markPublished(primary)

        assertNull(tracker.markPublishRejected(secondary))
    }

    @Test
    fun publishRejectedOnAllRelaysMapsToPublishFailure() {
        val tracker = WebNip46RelayRequestTracker(totalRelays = 1, method = "connect")
        val attempt = WebNip46RelayAttemptState()

        tracker.markPublished(attempt)

        assertEquals(WebAuthCopy.Nip46RelayPublishFailed, tracker.markPublishRejected(attempt))
    }

    @Test
    fun getPublicKeyTimeoutUsesPublicKeySpecificCopy() {
        val tracker = WebNip46RelayRequestTracker(totalRelays = 1, method = "get_public_key")

        assertEquals(WebAuthCopy.Nip46PublicKeyTimeout, tracker.timeoutMessage())
    }

    @Test
    fun getPublicKeyRelayCloseUsesPublicKeySpecificCopy() {
        val tracker = WebNip46RelayRequestTracker(totalRelays = 1, method = "get_public_key")
        val attempt = WebNip46RelayAttemptState()

        tracker.markPublished(attempt)

        assertEquals(WebAuthCopy.Nip46PublicKeyRelayClosed, tracker.markFailed(attempt))
    }
}

class WebReadOnlyNoteTests {
    private val accountPubkey = "aa".repeat(32)
    private val noteJson = Json { encodeDefaults = true }

    @Test
    fun noteRelayRequestTargetsSignedInPubkeyAndOtherNoteKind() {
        val message = webNoteRequestMessage(accountPubkey)

        assertTrue(message.contains(""""authors":["$accountPubkey"]"""))
        assertTrue(message.contains(""""kinds":[30078]"""))
        assertTrue(message.contains(""""#t":["other-note"]"""))
    }

    @Test
    fun signerTransportRelayFromBunkerTokenIsNotAddedToDefaultNoteRelays() {
        val signerRelay = "wss://signer-transport.example"
        val encodedRelay = signerRelay.replace(":", "%3A").replace("/", "%2F")
        val parsed = assertIs<WebNip46TokenParseResult.Valid>(
            parseWebNip46BunkerToken("bunker://${"22".repeat(32)}?relay=$encodedRelay"),
        )

        assertEquals(listOf(signerRelay), parsed.token.relays)
        assertTrue(signerRelay !in DefaultWebNoteRelays)
    }

    @Test
    fun latestEventWinsAndTombstoneHidesNote() {
        val old = noteEvent("event-old", createdAt = 1, noteId = "same")
        val newer = noteEvent("event-newer", createdAt = 2, noteId = "same")
        val deleted = noteEvent("event-delete", createdAt = 3, noteId = "same")
        val reduced = reduceDecryptedWebNoteEvents(
            events = listOf(old, newer, deleted),
            decryptedByEventId = mapOf(
                old.id to payload("same", body = "old", updatedAtMs = 1),
                newer.id to payload("same", body = "newer", updatedAtMs = 2),
                deleted.id to payload("same", body = "", updatedAtMs = 3, deleted = true),
            ),
        )

        assertTrue(reduced.notes.isEmpty())
        assertEquals(setOf("same"), reduced.selectedNotes.map { it.id }.toSet())
    }

    @Test
    fun olderEventDoesNotOverwriteNewerVisibleNote() {
        val old = noteEvent("event-old", createdAt = 1, noteId = "same")
        val newer = noteEvent("event-newer", createdAt = 2, noteId = "same")
        val reduced = reduceDecryptedWebNoteEvents(
            events = listOf(newer, old),
            decryptedByEventId = mapOf(
                old.id to payload("same", body = "old", updatedAtMs = 1),
                newer.id to payload("same", body = "newer", updatedAtMs = 2),
            ),
        )

        assertEquals("newer", reduced.notes.single().bodyMarkdown)
    }

    @Test
    fun wrongAccountAndMalformedPayloadAreIgnoredSafely() {
        val own = noteEvent("event-own", createdAt = 1, noteId = "own")
        val otherAccount = noteEvent("event-other", createdAt = 2, noteId = "other", pubkey = "bb".repeat(32))
        val malformed = noteEvent("event-bad", createdAt = 3, noteId = "bad")
        val scopedEvents = listOf(own, otherAccount, malformed).filter { it.pubkey == accountPubkey }
        val reduced = reduceDecryptedWebNoteEvents(
            events = scopedEvents,
            decryptedByEventId = mapOf(
                own.id to payload("own", body = "# Header\n**raw** markdown", updatedAtMs = 1),
                malformed.id to """{"schema":"wrong"}""",
            ),
        )

        assertEquals(1, reduced.notes.size)
        assertEquals("# Header\n**raw** markdown", reduced.notes.single().bodyMarkdown)
        assertEquals(1, reduced.payloadRejectedCount)
    }

    @Test
    fun singleDecryptFailureDoesNotCrashOrHideOtherReadableNotes() {
        val readable = noteEvent("event-readable", createdAt = 1, noteId = "readable")
        val decryptFailed = noteEvent("event-decrypt-failed", createdAt = 2, noteId = "failed")
        val reduced = reduceDecryptedWebNoteEvents(
            events = listOf(readable, decryptFailed),
            decryptedByEventId = mapOf(readable.id to payload("readable", body = "visible", updatedAtMs = 1)),
        )

        assertEquals(1, reduced.notes.size)
        assertEquals("visible", reduced.notes.single().bodyMarkdown)
        assertEquals(1, reduced.decryptRejectedCount)
        assertEquals(1, reduced.rejectedCount)
    }

    @Test
    fun duplicateRelayEventsDoNotDuplicateVisibleNotes() {
        val event = noteEvent("event-same", createdAt = 1, noteId = "same")
        val reduced = reduceDecryptedWebNoteEvents(
            events = listOf(event, event),
            decryptedByEventId = mapOf(event.id to payload("same", body = "one visible note", updatedAtMs = 1)),
        )

        assertEquals(1, reduced.notes.size)
        assertEquals("one visible note", reduced.notes.single().bodyMarkdown)
    }

    @Test
    fun dTagMismatchIsRejected() {
        val event = noteEvent("event", createdAt = 1, noteId = "tag-id")
        val reduced = reduceDecryptedWebNoteEvents(
            events = listOf(event),
            decryptedByEventId = mapOf(event.id to payload("payload-id", body = "body", updatedAtMs = 1)),
        )

        assertTrue(reduced.notes.isEmpty())
        assertEquals(1, reduced.dTagRejectedCount)
    }

    @Test
    fun nip07DecryptorWithoutCapabilityFailsSafely() {
        var callback: Result<String>? = null
        WebNip07NoteDecryptor(nip44 = null, userPubkey = accountPubkey).decrypt("ciphertext") { result ->
            callback = result
        }

        assertTrue(callback?.isFailure == true)
        assertEquals(WebNoteCopy.Nip07DecryptUnavailable, callback.exceptionOrNull()?.message)
    }

    @Test
    fun markdownRendererKeepsCodeBlockLiteralAndRawTextUnchanged() {
        val raw = "# Header\n\n```md\n**literal** `code`\n```\n\n> quote"
        val blocks = webMarkdownBlocks(raw)

        assertTrue(blocks.any { it is WebMarkdownBlock.Heading && it.text == "Header" })
        assertTrue(blocks.any { it is WebMarkdownBlock.BlockQuote && it.text == "quote" })
        assertEquals("**literal** `code`", blocks.filterIsInstance<WebMarkdownBlock.CodeBlock>().single().code)
        assertEquals("# Header\n\n```md\n**literal** `code`\n```\n\n> quote", raw)
    }

    @Test
    fun notePreviewUsesFirstMeaningfulMarkdownLine() {
        val preview = webNotePreview("\n\n## Project\n\nDetails with `code`")

        assertEquals("Project", preview.title)
        assertEquals("Details with code", preview.snippet)
    }

    private fun noteEvent(
        id: String,
        createdAt: Long,
        noteId: String,
        pubkey: String = accountPubkey,
    ): WebNostrEvent =
        WebNostrEvent(
            id = id,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = 30078,
            tags = listOf(listOf("d", webNoteDTag(noteId)), listOf("t", "other-note")),
            content = "ciphertext-$id",
            sig = "sig",
        )

    private fun payload(
        noteId: String,
        body: String,
        updatedAtMs: Long,
        deleted: Boolean = false,
    ): String =
        noteJson.encodeToString(
            WebNotePayload(
                noteId = noteId,
                createdAtMs = 1,
                updatedAtMs = updatedAtMs,
                bodyMarkdown = body,
                deleted = deleted,
            ),
        )
}

class WebNoteRelaySettingsTests {
    @Test
    fun defaultWebNoteRelayListIsUsedWhenCustomStateIsEmpty() {
        assertEquals(DefaultWebNoteRelays, selectedWebNoteRelays(WebNoteRelaySettingsState(relays = emptyList())))
        assertEquals(DefaultWebNoteRelays, selectedWebNoteRelays(defaultWebNoteRelaySettings()))
    }

    @Test
    fun addingValidRelayNormalizesNakedHostnameAndUsesItForNoteRelays() {
        val state = addWebNoteRelay(
            defaultWebNoteRelaySettings().copy(input = "Relay.Example.com/nostr/"),
        )

        assertEquals("wss://relay.example.com/nostr", selectedWebNoteRelays(state).last())
        assertEquals(WebNoteCopy.RelayAdded, state.message)
        assertEquals("", state.input)
    }

    @Test
    fun addingDuplicateRelayDoesNotCreateDuplicateRows() {
        val state = addWebNoteRelay(
            WebNoteRelaySettingsState(
                relays = listOf("wss://relay.example.com"),
                input = "wss://RELAY.EXAMPLE.COM/",
            ),
        )

        assertEquals(listOf("wss://relay.example.com"), selectedWebNoteRelays(state))
        assertEquals(WebNoteCopy.RelayAlreadyAdded, state.message)
    }

    @Test
    fun invalidRelayInputFailsSafely() {
        val state = addWebNoteRelay(defaultWebNoteRelaySettings().copy(input = "https://relay.example.com"))

        assertEquals(DefaultWebNoteRelays, selectedWebNoteRelays(state))
        assertEquals(WebNoteCopy.RelayInvalid, state.message)
    }

    @Test
    fun relayNormalizationRejectsUnsafeOrAmbiguousRelayUrls() {
        assertTrue(normalizeWebNoteRelayUrl("wss://relay.example.com").isSuccess)
        assertEquals("wss://relay.example.com", normalizeWebNoteRelayUrl("relay.example.com").getOrThrow())
        assertEquals("ws://localhost:7000", normalizeWebNoteRelayUrl("ws://localhost:7000/").getOrThrow())
        assertTrue(normalizeWebNoteRelayUrl("http://relay.example.com").isFailure)
        assertTrue(normalizeWebNoteRelayUrl("wss://relay.example.com?x=1").isFailure)
        assertTrue(normalizeWebNoteRelayUrl("wss://relay.example.com#fragment").isFailure)
        assertTrue(normalizeWebNoteRelayUrl("ws://relay.example.com").isFailure)
        assertTrue(normalizeWebNoteRelayUrl("relay example.com").isFailure)
    }

    @Test
    fun removingNoteRelayRemovesOnlyWebNoteRelayState() {
        val state = removeWebNoteRelay(
            WebNoteRelaySettingsState(relays = listOf("wss://one.example", "wss://two.example")),
            relay = "wss://one.example",
        )

        assertEquals(listOf("wss://two.example"), selectedWebNoteRelays(state))
        assertEquals(WebNoteCopy.RelayRemoved, state.message)
    }

    @Test
    fun removingLastNoteRelayFallsBackToDefaultRelays() {
        val state = removeWebNoteRelay(
            WebNoteRelaySettingsState(relays = listOf("wss://one.example")),
            relay = "wss://one.example",
        )

        assertEquals(DefaultWebNoteRelays, selectedWebNoteRelays(state))
        assertEquals(WebNoteCopy.RelayDefaultsRestored, state.message)
    }

    @Test
    fun restoringDefaultsRestoresDefaultNoteRelays() {
        val state = restoreDefaultWebNoteRelays(
            WebNoteRelaySettingsState(relays = listOf("wss://custom.example"), input = "draft"),
        )

        assertEquals(DefaultWebNoteRelays, selectedWebNoteRelays(state))
        assertEquals("", state.input)
        assertEquals(WebNoteCopy.RelayDefaultsRestored, state.message)
    }

    @Test
    fun nip46SignerRelaysAreNotDisplayedAsDefaultNoteRelays() {
        val signerRelay = "wss://signer-transport.example"
        val encodedRelay = signerRelay.replace(":", "%3A").replace("/", "%2F")
        val parsed = assertIs<WebNip46TokenParseResult.Valid>(
            parseWebNip46BunkerToken("bunker://${"22".repeat(32)}?relay=$encodedRelay"),
        )

        assertEquals(listOf(signerRelay), parsed.token.relays)
        assertTrue(signerRelay !in selectedWebNoteRelays(defaultWebNoteRelaySettings()))
    }

    @Test
    fun sameUrlInSignerAndNoteStateIsANoteRelayOnlyWhenExplicitlyAdded() {
        val sharedRelay = "wss://shared.example"
        val encodedRelay = sharedRelay.replace(":", "%3A").replace("/", "%2F")
        val parsed = assertIs<WebNip46TokenParseResult.Valid>(
            parseWebNip46BunkerToken("bunker://${"22".repeat(32)}?relay=$encodedRelay"),
        )
        val noteState = addWebNoteRelay(WebNoteRelaySettingsState(relays = emptyList(), input = sharedRelay))

        assertEquals(listOf(sharedRelay), parsed.token.relays)
        assertTrue(sharedRelay in selectedWebNoteRelays(noteState))
    }
}

class WebNoteCrudTests {
    private val accountPubkey = "cc".repeat(32)
    private val noteJson = Json { encodeDefaults = true }

    @Test
    fun createBuildsStableNotePayloadAndUnsignedEventShape() {
        val note = buildWebNoteForSave(
            existing = null,
            bodyMarkdown = "# Created\nraw",
            noteIdGenerator = { "note-1" },
            nowMs = { 1_700_000_000_000 },
        )
        val payload = decodeWebNotePayload(encodeWebNotePayload(note).getOrThrow()).getOrThrow()
        val unsigned = buildUnsignedWebNoteEvent(note, accountPubkey, "ciphertext")

        assertEquals("note-1", note.id)
        assertEquals("# Created\nraw", payload.bodyMarkdown)
        assertEquals(30078, unsigned.kind)
        assertEquals(accountPubkey, unsigned.pubkey)
        assertEquals(1_700_000_000, unsigned.createdAt)
        assertEquals(webNoteEventTags("note-1"), unsigned.tags)
        assertEquals("ciphertext", unsigned.content)
    }

    @Test
    fun editPreservesNoteIdentityAndAdvancesTimestamp() {
        val existing = WebReadOnlyNote(
            id = "same-note",
            createdAtMs = 1_000,
            updatedAtMs = 1_000,
            bodyMarkdown = "old",
        )
        val edited = buildWebNoteForSave(
            existing = existing,
            bodyMarkdown = "new",
            noteIdGenerator = { "should-not-use" },
            nowMs = { 1_001 },
        )

        assertEquals("same-note", edited.id)
        assertEquals(1_000, edited.createdAtMs)
        assertEquals(2_000, edited.updatedAtMs)
        assertEquals("new", edited.bodyMarkdown)
        assertTrue(!edited.deleted)
    }

    @Test
    fun tombstoneMergeHidesOlderVisibleNote() {
        val visible = signedEvent("event-visible", createdAt = 1, noteId = "same", content = "cipher-visible")
        val tombstone = signedEvent("event-delete", createdAt = 2, noteId = "same", content = "cipher-delete")
        val reduced = mergePublishedWebNoteEvent(
            events = listOf(visible),
            decryptedByEventId = mapOf(visible.id to payload("same", body = "visible", updatedAtMs = 1_000)),
            event = tombstone,
            plaintextPayload = payload("same", body = "", updatedAtMs = 2_000, deleted = true),
        )

        assertTrue(reduced.notes.isEmpty())
        assertEquals(setOf("same"), reduced.selectedNotes.map { it.id }.toSet())
    }

    @Test
    fun signedEventValidationRejectsWrongShape() {
        val note = WebReadOnlyNote("note-1", createdAtMs = 1_000, updatedAtMs = 1_000, bodyMarkdown = "body")
        val valid = signedEvent("event", createdAt = 1, noteId = "note-1", content = "cipher")

        assertTrue(validateWebSignedNoteEvent(note, valid, accountPubkey))
        assertTrue(!validateWebSignedNoteEvent(note, valid.copy(pubkey = "dd".repeat(32)), accountPubkey))
        assertTrue(!validateWebSignedNoteEvent(note, valid.copy(tags = listOf(listOf("d", "wrong"))), accountPubkey))
        assertTrue(!validateWebSignedNoteEvent(note, valid.copy(sig = ""), accountPubkey))
    }

    @Test
    fun publishStatusDistinguishesPartialAndAllFailed() {
        val partial = WebNotePublishResult(
            listOf(
                WebNoteRelayStatus("wss://one.example", connected = true, acceptedWrite = true),
                WebNoteRelayStatus("wss://two.example", connected = true, failed = true),
            ),
        )
        val failed = WebNotePublishResult(
            listOf(WebNoteRelayStatus("wss://one.example", connected = true, failed = true)),
        )

        assertTrue(partial.anyAccepted)
        assertTrue(partial.safeStatus.contains("some relays"))
        assertTrue(!failed.anyAccepted)
        assertEquals(WebNoteCopy.PublishFailed, failed.safeStatus)
    }

    @Test
    fun nip46SignEventJsonUsesUnsignedEventPayloadWithoutUserPubkeyByDefault() {
        val note = WebReadOnlyNote("note-1", createdAtMs = 1_000, updatedAtMs = 1_000, bodyMarkdown = "body")
        val unsigned = buildUnsignedWebNoteEvent(note, accountPubkey, "ciphertext")
        val json = unsigned.toSignEventJson()

        assertTrue(json.contains(""""kind":30078"""))
        assertTrue(json.contains(""""content":"ciphertext""""))
        assertTrue(!json.contains(""""pubkey""""))
    }

    @Test
    fun createPublishesSignedEncryptedKind30078EventAndUpdatesInMemoryReducer() {
        val publisher = FakeNotePublisher(acceptedPublish())
        val signer = FakeCrudSigner()
        val service = WebNoteCrudService(
            publisher = publisher,
            noteIdGenerator = { "created-note" },
            nowMs = { 1_700_000_000_000 },
        )
        var result: WebNoteSaveResult? = null

        service.save(
            existing = null,
            bodyMarkdown = "# Created\nraw markdown",
            accountPubkey = accountPubkey,
            signer = signer,
            onProgress = {},
            onResult = { result = it },
        )

        val published = assertIs<WebNoteSaveResult.Published>(result)
        val payload = decodeWebNotePayload(published.plaintextPayload).getOrThrow()
        val reduced = mergePublishedWebNoteEvent(emptyList(), emptyMap(), published.event, published.plaintextPayload)

        assertEquals("created-note", published.note.id)
        assertEquals("# Created\nraw markdown", payload.bodyMarkdown)
        assertEquals(30078, published.event.kind)
        assertEquals(accountPubkey, published.event.pubkey)
        assertEquals(webNoteEventTags("created-note"), published.event.tags)
        assertEquals("ciphertext-1", published.event.content)
        assertEquals(listOf(published.event), publisher.publishedEvents)
        assertEquals("# Created\nraw markdown", reduced.notes.single().bodyMarkdown)
    }

    @Test
    fun editPublishesSameNoteIdentityAndLatestBodyWinsInMemory() {
        val oldEvent = signedEvent("event-old", createdAt = 1, noteId = "same-note", content = "old-cipher")
        val oldPayload = payload("same-note", body = "old body", updatedAtMs = 1_000)
        val existing = WebReadOnlyNote(
            id = "same-note",
            createdAtMs = 1_000,
            updatedAtMs = 1_000,
            bodyMarkdown = "old body",
            sourceEventId = oldEvent.id,
        )
        val service = WebNoteCrudService(
            publisher = FakeNotePublisher(acceptedPublish()),
            noteIdGenerator = { "unused" },
            nowMs = { 1_001 },
        )
        var result: WebNoteSaveResult? = null

        service.save(
            existing = existing,
            bodyMarkdown = "new body",
            accountPubkey = accountPubkey,
            signer = FakeCrudSigner(),
            onProgress = {},
            onResult = { result = it },
        )

        val published = assertIs<WebNoteSaveResult.Published>(result)
        val reduced = mergePublishedWebNoteEvent(
            events = listOf(oldEvent),
            decryptedByEventId = mapOf(oldEvent.id to oldPayload),
            event = published.event,
            plaintextPayload = published.plaintextPayload,
        )

        assertEquals("same-note", published.note.id)
        assertEquals(webNoteDTag("same-note"), published.event.dTag())
        assertEquals(2_000, published.note.updatedAtMs)
        assertEquals("new body", reduced.notes.single().bodyMarkdown)
    }

    @Test
    fun deletePublishesTombstoneAndKeepsOlderEventsHiddenAfterReduction() {
        val oldEvent = signedEvent("event-old", createdAt = 1, noteId = "same-note", content = "old-cipher")
        val oldPayload = payload("same-note", body = "visible before delete", updatedAtMs = 1_000)
        val existing = WebReadOnlyNote(
            id = "same-note",
            createdAtMs = 1_000,
            updatedAtMs = 1_000,
            bodyMarkdown = "visible before delete",
            sourceEventId = oldEvent.id,
        )
        val service = WebNoteCrudService(
            publisher = FakeNotePublisher(acceptedPublish()),
            nowMs = { 1_001 },
        )
        var result: WebNoteSaveResult? = null

        service.delete(
            note = existing,
            accountPubkey = accountPubkey,
            signer = FakeCrudSigner(),
            onProgress = {},
            onResult = { result = it },
        )

        val published = assertIs<WebNoteSaveResult.Published>(result)
        val tombstone = decodeWebNotePayload(published.plaintextPayload).getOrThrow()
        val reduced = mergePublishedWebNoteEvent(
            events = listOf(oldEvent),
            decryptedByEventId = mapOf(oldEvent.id to oldPayload),
            event = published.event,
            plaintextPayload = published.plaintextPayload,
        )

        assertEquals("same-note", tombstone.noteId)
        assertTrue(tombstone.deleted)
        assertEquals("", tombstone.bodyMarkdown)
        assertTrue(reduced.notes.isEmpty())
        assertEquals(setOf("same-note"), reduced.selectedNotes.map { it.id }.toSet())
    }

    @Test
    fun allRelayPublishFailureDoesNotClaimSuccessOrUpdateWithPublishedResult() {
        val service = WebNoteCrudService(
            publisher = FakeNotePublisher(
                WebNotePublishResult(
                    listOf(WebNoteRelayStatus("wss://note-relay.example", connected = true, failed = true)),
                ),
            ),
            noteIdGenerator = { "note-1" },
            nowMs = { 1_000 },
        )
        var result: WebNoteSaveResult? = null

        service.save(
            existing = null,
            bodyMarkdown = "body",
            accountPubkey = accountPubkey,
            signer = FakeCrudSigner(),
            onProgress = {},
            onResult = { result = it },
        )

        val failed = assertIs<WebNoteSaveResult.Failed>(result)
        assertEquals(WebNoteCopy.PublishFailed, failed.safeMessage)
    }

    @Test
    fun partialRelayPublishFailureReturnsPublishedResultWithSafePartialStatus() {
        val service = WebNoteCrudService(
            publisher = FakeNotePublisher(
                WebNotePublishResult(
                    listOf(
                        WebNoteRelayStatus("wss://accepted-note-relay.example", connected = true, acceptedWrite = true),
                        WebNoteRelayStatus("wss://failed-note-relay.example", connected = true, failed = true),
                    ),
                ),
            ),
            noteIdGenerator = { "note-1" },
            nowMs = { 1_000 },
        )
        var result: WebNoteSaveResult? = null

        service.save(
            existing = null,
            bodyMarkdown = "body",
            accountPubkey = accountPubkey,
            signer = FakeCrudSigner(),
            onProgress = {},
            onResult = { result = it },
        )

        val published = assertIs<WebNoteSaveResult.Published>(result)
        assertTrue(published.status.contains(WebNoteCopy.PublishPartial))
        assertTrue(published.relayStatuses.any { it.acceptedWrite })
        assertTrue(published.relayStatuses.any { it.failed })
    }

    @Test
    fun malformedSignedEventFailsValidationBeforeRelayPublish() {
        val publisher = FakeNotePublisher(acceptedPublish())
        val signer = FakeCrudSigner(
            signFactory = { unsigned ->
                WebNoteSignResult.Signed(
                    WebNostrEvent(
                        id = "bad-event",
                        pubkey = accountPubkey,
                        createdAt = unsigned.createdAt,
                        kind = unsigned.kind,
                        tags = listOf(listOf("d", "wrong")),
                        content = unsigned.content,
                        sig = "sig",
                    ),
                )
            },
        )
        val service = WebNoteCrudService(
            publisher = publisher,
            noteIdGenerator = { "note-1" },
            nowMs = { 1_000 },
        )
        var result: WebNoteSaveResult? = null

        service.save(
            existing = null,
            bodyMarkdown = "body",
            accountPubkey = accountPubkey,
            signer = signer,
            onProgress = {},
            onResult = { result = it },
        )

        val failed = assertIs<WebNoteSaveResult.Failed>(result)
        assertEquals(WebNoteCopy.EventValidationFailed, failed.safeMessage)
        assertTrue(publisher.publishedEvents.isEmpty())
    }

    @Test
    fun signerCapabilityFailureUsesSafeCopyAndDoesNotExposeDraftText() {
        val privateDraft = "private draft should stay in memory"
        val service = WebNoteCrudService(noteIdGenerator = { "note-1" }, nowMs = { 1_000 })
        var result: WebNoteSaveResult? = null

        service.save(
            existing = null,
            bodyMarkdown = privateDraft,
            accountPubkey = accountPubkey,
            signer = null,
            onProgress = {},
            onResult = { result = it },
        )

        val failed = assertIs<WebNoteSaveResult.Failed>(result)
        assertEquals(WebNoteCopy.CrudCapabilityUnavailable, failed.safeMessage)
        assertTrue(!failed.safeMessage.contains("private draft"))
    }

    @Test
    fun crudServiceCloseClearsActivePublisherStateForLogoutLifecycle() {
        val publisher = FakeNotePublisher(acceptedPublish())
        val service = WebNoteCrudService(publisher = publisher)

        service.close()

        assertEquals(1, publisher.closeCount)
    }

    private fun signedEvent(
        id: String,
        createdAt: Long,
        noteId: String,
        content: String,
    ): WebNostrEvent =
        WebNostrEvent(
            id = id,
            pubkey = accountPubkey,
            createdAt = createdAt,
            kind = 30078,
            tags = webNoteEventTags(noteId),
            content = content,
            sig = "sig-$id",
        )

    private fun payload(
        noteId: String,
        body: String,
        updatedAtMs: Long,
        deleted: Boolean = false,
    ): String =
        noteJson.encodeToString(
            WebNotePayload(
                noteId = noteId,
                createdAtMs = 1_000,
                updatedAtMs = updatedAtMs,
                bodyMarkdown = body,
                deleted = deleted,
            ),
        )

    private fun acceptedPublish(): WebNotePublishResult =
        WebNotePublishResult(
            listOf(WebNoteRelayStatus("wss://note-relay.example", connected = true, acceptedWrite = true)),
        )

    private class FakeNotePublisher(
        private val result: WebNotePublishResult,
    ) : WebNotePublisher {
        val publishedEvents = mutableListOf<WebNostrEvent>()
        var closeCount = 0

        override fun publish(event: WebNostrEvent, onResult: (WebNotePublishResult) -> Unit) {
            publishedEvents += event
            onResult(result)
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeCrudSigner(
        private val encryptFactory: (String) -> WebSignerOperationResult = { WebSignerOperationResult.Success("ciphertext-1") },
        private val signFactory: (WebUnsignedNoteEvent) -> WebNoteSignResult = { unsigned ->
            WebNoteSignResult.Signed(
                WebNostrEvent(
                    id = "signed-${unsigned.createdAt}-${unsigned.content}",
                    pubkey = unsigned.pubkey,
                    createdAt = unsigned.createdAt,
                    kind = unsigned.kind,
                    tags = unsigned.tags,
                    content = unsigned.content,
                    sig = "sig",
                ),
            )
        },
    ) : WebNoteCrudSigner {
        val encryptedPlaintexts = mutableListOf<String>()
        val signedRequests = mutableListOf<WebUnsignedNoteEvent>()

        override fun encrypt(plaintext: String, onResult: (WebSignerOperationResult) -> Unit) {
            encryptedPlaintexts += plaintext
            onResult(encryptFactory(plaintext))
        }

        override fun sign(unsignedEvent: WebUnsignedNoteEvent, onResult: (WebNoteSignResult) -> Unit) {
            signedRequests += unsignedEvent
            onResult(signFactory(unsignedEvent))
        }
    }
}
