package com.libertasprimordium.othernote.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
