package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.InMemoryLocalEventCache
import com.libertasprimordium.othernote.data.InMemoryPendingWriteStore
import com.libertasprimordium.othernote.domain.RelayConfig
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.SessionAuthMethod
import com.libertasprimordium.othernote.nostr.FanoutNostrClient
import com.libertasprimordium.othernote.nostr.Nip46LiveNostrClient
import com.libertasprimordium.othernote.nostr.Nip46LiveRelayOutcome
import com.libertasprimordium.othernote.nostr.Nip46LiveRelayResult
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrFilter
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.NostrWireJson
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.ProfileMetadata
import com.libertasprimordium.othernote.nostr.PublishBestEffortHandle
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.RelayPublishResult
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.security.Nip46EventKind
import com.libertasprimordium.othernote.security.Nip46ConnectResult
import com.libertasprimordium.othernote.security.Nip46Method
import com.libertasprimordium.othernote.security.Nip46PayloadJson
import com.libertasprimordium.othernote.security.Nip46RemoteSigner
import com.libertasprimordium.othernote.security.Nip46RequestPayload
import com.libertasprimordium.othernote.security.Nip46RequestTransport
import com.libertasprimordium.othernote.security.Nip46Response
import com.libertasprimordium.othernote.security.Nip46ResponsePayload
import com.libertasprimordium.othernote.security.Nip46TransportSession
import com.libertasprimordium.othernote.security.RelayNip46RequestTransport
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.AppState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip46RemoteSignerTests {
    private val crypto = ProductionNostrCryptoFactory.createOrNull()
        ?: error(ProductionNostrCryptoFactory.unavailableReason)

    @Test
    fun connectValidatesSecretAndSetsUserPubkey() = runBlocking {
        val fixture = fixture()
        val transport = FakeNip46Transport(crypto, fixture, expectedSecret = "secret")
        val signer = Nip46RemoteSigner(transport, crypto)

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=secret")

        val connected = assertIs<Nip46ConnectResult.Connected>(result)
        assertEquals(fixture.userPubkey, connected.userPubkey)
        assertEquals(fixture.remotePubkey, transport.connectParams.single()[0])
        assertEquals("secret", transport.connectParams.single()[1])
        assertNotEquals(transport.connectClientPubkeys.single(), transport.connectParams.single()[0])
        assertTrue(transport.connectParams.single()[2].contains("sign_event:30078"))
        assertTrue(transport.connectParams.single()[2].contains("nip44_encrypt"))
        assertTrue(signer.canSignEvent)
        assertTrue(signer.canNip44RoundTrip)
    }

    @Test
    fun connectWithoutSecretStillSendsRemoteSignerPubkeyFirst() = runBlocking {
        val fixture = fixture()
        val transport = FakeNip46Transport(crypto, fixture)
        val signer = Nip46RemoteSigner(transport, crypto)

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com")

        assertIs<Nip46ConnectResult.Connected>(result)
        val params = transport.connectParams.single()
        assertEquals(fixture.remotePubkey, params[0])
        assertEquals("", params[1])
        assertTrue(params[2].contains("get_public_key"))
        assertTrue(params[2].contains("switch_relays"))
        assertNotEquals(transport.connectClientPubkeys.single(), params[0])
    }

    @Test
    fun connectFailureDoesNotExposeSecret() = runBlocking {
        val fixture = fixture()
        val signer = Nip46RemoteSigner(FakeNip46Transport(crypto, fixture, expectedSecret = "expected"), crypto)

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=wrong-secret")

        val failed = assertIs<Nip46ConnectResult.Failed>(result)
        assertFalse(failed.safeReason.contains("wrong-secret"))
        assertFalse(failed.safeReason.contains("expected"))
    }

    @Test
    fun requestTimeoutMapsToCleanConnectResult() = runBlocking {
        val fixture = fixture()
        val signer = Nip46RemoteSigner(FakeNip46Transport(crypto, fixture, timeout = true), crypto)

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=secret")

        assertEquals(Nip46ConnectResult.TimedOut, result)
        assertTrue(signer.safeDiagnostics.joinToString("\n").contains("timed out"))
        assertFalse(signer.safeDiagnostics.joinToString("\n").contains("must-not-appear"))
    }

    @Test
    fun requestFailureDiagnosticsAreSafeAndStageAware() = runBlocking {
        val fixture = fixture()
        val signer = Nip46RemoteSigner(
            FakeNip46Transport(
                crypto,
                fixture,
                throwMessage = "stage=relay_publish_failed statuses=wss://relay.example.com writable=false outcome=rejected secret=must-not-appear",
            ),
            crypto,
        )

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=must-not-appear")

        val failed = assertIs<Nip46ConnectResult.Failed>(result)
        assertTrue(failed.safeReason.contains("Remote signer relay rejected"))
        assertTrue(failed.safeReason.contains("relay_publish_failed"))
        assertFalse(failed.safeReason.contains("must-not-appear"))
    }

    @Test
    fun responseDecryptAndIdMismatchDiagnosticsAreDistinct() = runBlocking {
        val fixture = fixture()
        val decryptSigner = Nip46RemoteSigner(
            FakeNip46Transport(crypto, fixture, throwMessage = "stage=response_decrypt_failed events=1 count=1 secret=must-not-appear"),
            crypto,
        )
        val mismatchSigner = Nip46RemoteSigner(
            FakeNip46Transport(crypto, fixture, throwMessage = "stage=response_id_mismatch events=1 count=1 secret=must-not-appear"),
            crypto,
        )

        val decryptFailure = assertIs<Nip46ConnectResult.Failed>(
            decryptSigner.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=must-not-appear"),
        )
        val mismatchFailure = assertIs<Nip46ConnectResult.Failed>(
            mismatchSigner.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com&secret=must-not-appear"),
        )

        assertTrue(decryptFailure.safeReason.contains("response_decrypt_failed"))
        assertTrue(mismatchFailure.safeReason.contains("response_id_mismatch"))
        assertFalse(decryptFailure.safeReason.contains("must-not-appear"))
        assertFalse(mismatchFailure.safeReason.contains("must-not-appear"))
    }

    @Test
    fun switchRelaysSendsNoParamsAndKeepsTokenRelayAdvisory() = runBlocking {
        val fixture = fixture()
        val transport = FakeNip46Transport(crypto, fixture, switchRelayResult = """["wss://switched.example.com"]""")
        val signer = Nip46RemoteSigner(transport, crypto)

        val result = signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com")

        val connected = assertIs<Nip46ConnectResult.Connected>(result)
        assertEquals(listOf(emptyList()), transport.switchRelayParams)
        assertEquals(listOf("wss://relay.example.com", "wss://switched.example.com"), connected.relays)
        assertIs<com.libertasprimordium.othernote.security.SignEventRequestResult.Success>(
            signer.signEventAsync(unsignedEvent(fixture.userPubkey, "encrypted-content"), fixture.userPubkey, "nip46"),
        )
        assertTrue(transport.requestRelaySnapshots.last().contains("wss://relay.example.com"))
        assertTrue(transport.requestRelaySnapshots.last().contains("wss://switched.example.com"))
        assertTrue(transport.requestRelaySources.last().contains("switch_relays_advisory"))
    }

    @Test
    fun signerRelayPublishRejectionFailsWithoutResponseFetch() = runBlocking {
        val fixture = fixture()
        val session = transportSession(crypto, fixture, listOf("wss://restricted.example.com"))
        val client = TransportNostrClient(
            publishStatuses = listOf(
                RelayStatus(
                    "wss://restricted.example.com",
                    writable = false,
                    message = "stage=publish outcome=rejected restricted temporary pubkey",
                ),
            ),
        )
        val transport = RelayNip46RequestTransport(client, crypto, requestTimeoutMs = 2_000, pollIntervalMs = 10)

        val result = transport.sendRequest(session, Nip46RequestPayload("req-1", Nip46Method.Ping.wireName))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("stage=relay_publish_failed"))
        assertTrue(message.contains("relay_source=token"))
        assertTrue(message.contains("outcome=rejected"))
        assertEquals(0, client.fetchCalls)
        assertEquals(1, client.liveCalls)
    }

    @Test
    fun signerRelayOneRejectedOneAcceptedStillReadsResponse() = runBlocking {
        val fixture = fixture()
        val session = transportSession(crypto, fixture, listOf("wss://restricted.example.com", "wss://open.example.com"))
        val response = responseEvent(crypto, fixture, session, requestId = "req-2", result = "pong")
        val client = TransportNostrClient(
            publishStatuses = listOf(
                RelayStatus("wss://restricted.example.com", writable = false, message = "stage=publish outcome=rejected"),
                RelayStatus("wss://open.example.com", writable = true, message = "stage=publish outcome=accepted"),
            ),
            fetchEvents = listOf(response),
            fetchStatuses = listOf(RelayStatus("wss://open.example.com", readable = true, message = "stage=fetch outcome=complete")),
        )
        val transport = RelayNip46RequestTransport(client, crypto, requestTimeoutMs = 2_000, pollIntervalMs = 10)

        val result = transport.sendRequest(session, Nip46RequestPayload("req-2", Nip46Method.Ping.wireName)).getOrThrow()

        val success = assertIs<Nip46Response.Success>(result)
        assertEquals("pong", success.result)
        assertEquals(0, client.fetchCalls)
        assertEquals(0, client.fanoutCalls)
        assertEquals(1, client.liveCalls)
    }

    @Test
    fun staleWrongIdResponseBeforeMatchingResponseIsIgnored() = runBlocking {
        val fixture = fixture()
        val session = transportSession(crypto, fixture, listOf("wss://relay.example.com"))
        val stale = responseEvent(crypto, fixture, session, requestId = "old-req", result = "old", createdAt = 1)
        val matching = responseEvent(crypto, fixture, session, requestId = "req-3", result = "pong")
        val client = TransportNostrClient(
            publishStatuses = listOf(RelayStatus("wss://relay.example.com", writable = true, message = "stage=publish outcome=accepted")),
            fetchEvents = listOf(stale, matching),
            fetchStatuses = listOf(RelayStatus("wss://relay.example.com", readable = true, message = "stage=fetch outcome=complete")),
        )
        val transport = RelayNip46RequestTransport(client, crypto, requestTimeoutMs = 2_000, pollIntervalMs = 10)

        val result = transport.sendRequest(session, Nip46RequestPayload("req-3", Nip46Method.Ping.wireName)).getOrThrow()

        val success = assertIs<Nip46Response.Success>(result)
        assertEquals("pong", success.result)
        assertEquals(0, client.fetchCalls)
        assertEquals(1, client.liveCalls)
        val liveFilter = client.liveFilters.single()
        assertEquals(listOf(fixture.remotePubkey), liveFilter.authors)
        assertEquals(listOf(Nip46EventKind), liveFilter.kinds)
        assertEquals(listOf(session.clientPubkey), liveFilter.pTags)
        assertTrue(liveFilter.since != null)
    }

    @Test
    fun multipleWrongIdResponsesBeforeMatchingResponseAreIgnored() = runBlocking {
        val fixture = fixture()
        val session = transportSession(crypto, fixture, listOf("wss://relay.example.com"))
        val wrongNewest = responseEvent(crypto, fixture, session, requestId = "wrong-new", result = "old", createdAt = 4_000_000_003)
        val wrongOlder = responseEvent(crypto, fixture, session, requestId = "wrong-old", result = "old", createdAt = 4_000_000_002)
        val matching = responseEvent(crypto, fixture, session, requestId = "req-4", result = "pong", createdAt = 4_000_000_001)
        val client = TransportNostrClient(
            publishStatuses = listOf(RelayStatus("wss://relay.example.com", writable = true, message = "stage=publish outcome=accepted")),
            fetchEvents = listOf(wrongNewest, wrongOlder, matching),
            fetchStatuses = listOf(RelayStatus("wss://relay.example.com", readable = true, message = "stage=fetch outcome=complete")),
        )
        val transport = RelayNip46RequestTransport(client, crypto, requestTimeoutMs = 2_000, pollIntervalMs = 10)

        val result = transport.sendRequest(session, Nip46RequestPayload("req-4", Nip46Method.Ping.wireName)).getOrThrow()

        val success = assertIs<Nip46Response.Success>(result)
        assertEquals("pong", success.result)
    }

    @Test
    fun wrongIdOnlyResponsesTimeOutAsNoMatchingResponse() = runBlocking {
        val fixture = fixture()
        val session = transportSession(crypto, fixture, listOf("wss://relay.example.com"))
        val wrong = responseEvent(crypto, fixture, session, requestId = "wrong-only", result = "old")
        val client = TransportNostrClient(
            publishStatuses = listOf(RelayStatus("wss://relay.example.com", writable = true, message = "stage=publish outcome=accepted")),
            fetchEvents = listOf(wrong),
            fetchStatuses = listOf(RelayStatus("wss://relay.example.com", readable = true, message = "stage=fetch outcome=complete")),
        )
        val transport = RelayNip46RequestTransport(client, crypto, requestTimeoutMs = 60, pollIntervalMs = 10)

        val result = transport.sendRequest(session, Nip46RequestPayload("req-5", Nip46Method.Ping.wireName))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("stage=response_fetch_timed_out"))
        assertTrue(message.contains("reason=no_matching_response"))
        assertTrue(message.contains("method=ping"))
        assertTrue(message.contains("request_id=req-5"))
        assertTrue(message.contains("relay_source=token"))
        assertTrue(message.contains("relays_attempted=1"))
        assertTrue(message.contains("publish_accepted_count=1"))
        assertTrue(message.contains("publish_rejected_count=0"))
        assertTrue(message.contains("candidate_events="))
        assertTrue(message.contains("decrypt_failures="))
        assertTrue(message.contains("mismatched_ids="))
        assertTrue(message.contains("matching_id_found=false"))
        assertTrue(message.contains("elapsed_ms="))
        assertFalse(message.contains("stage=response_id_mismatch"))
    }

    @Test
    fun nip44EncryptTimeoutIsDistinguishableFromSignEventTimeout() = runBlocking {
        val fixture = fixture()
        val encryptSigner = connectedSigner(
            fixture,
            FakeNip46Transport(
                crypto,
                fixture,
                throwByMethod = mapOf(
                    Nip46Method.Nip44Encrypt.wireName to
                        "stage=response_fetch_timed_out reason=no_matching_response method=nip44_encrypt request_id=req-encrypt relay_source=token attempts=7 candidate_events=28 decrypt_failures=0 mismatched_ids=28 matching_id_found=false elapsed_ms=12000 secret=must-not-appear",
                ),
            ),
        )
        val signSigner = connectedSigner(
            fixture,
            FakeNip46Transport(
                crypto,
                fixture,
                throwByMethod = mapOf(
                    Nip46Method.SignEvent.wireName to
                        "stage=response_fetch_timed_out reason=no_matching_response method=sign_event request_id=req-sign relay_source=token attempts=7 candidate_events=28 decrypt_failures=0 mismatched_ids=28 matching_id_found=false elapsed_ms=12000 secret=must-not-appear",
                ),
            ),
        )

        val encryptResult = encryptSigner.encryptToSelfAsync("plain note payload", fixture.userPubkey, "nip46")
        val signResult = signSigner.signEventAsync(unsignedEvent(fixture.userPubkey, "encrypted-content"), fixture.userPubkey, "nip46")

        val encryptFailure = assertIs<com.libertasprimordium.othernote.security.SignerNip44OperationResult.Failed>(encryptResult)
        val signFailure = assertIs<com.libertasprimordium.othernote.security.SignEventRequestResult.Failed>(signResult)
        assertTrue(encryptFailure.safeReason.contains("encryption request"))
        assertTrue(encryptFailure.safeReason.contains("method=nip44_encrypt"))
        assertFalse(encryptFailure.safeReason.contains("signing request"))
        assertFalse(encryptFailure.safeReason.contains("must-not-appear"))
        assertTrue(signFailure.safeReason.contains("signing request"))
        assertTrue(signFailure.safeReason.contains("method=sign_event"))
        assertFalse(signFailure.safeReason.contains("encryption request"))
        assertFalse(signFailure.safeReason.contains("must-not-appear"))
    }

    @Test
    fun unsupportedSignerMethodResponseMapsToMethodSpecificCleanError() = runBlocking {
        val fixture = fixture()
        val signer = connectedSigner(
            fixture,
            FakeNip46Transport(
                crypto,
                fixture,
                errorByMethod = mapOf(Nip46Method.Nip44Encrypt.wireName to "unsupported method secret=must-not-appear"),
            ),
        )

        val result = signer.encryptToSelfAsync("plain note payload", fixture.userPubkey, "nip46")

        val failure = assertIs<com.libertasprimordium.othernote.security.SignerNip44OperationResult.Failed>(result)
        assertTrue(failure.safeReason.contains("Remote signer returned encryption error"))
        assertTrue(failure.safeReason.contains("unsupported method"))
        assertFalse(failure.safeReason.contains("must-not-appear"))
    }

    @Test
    fun decryptFailureOnUnrelatedCandidateDoesNotAbortMatchingResponse() = runBlocking {
        val fixture = fixture()
        val session = transportSession(crypto, fixture, listOf("wss://relay.example.com"))
        val badCandidate = malformedResponseEvent(crypto, fixture, session)
        val matching = responseEvent(crypto, fixture, session, requestId = "req-6", result = "pong")
        val client = TransportNostrClient(
            publishStatuses = listOf(RelayStatus("wss://relay.example.com", writable = true, message = "stage=publish outcome=accepted")),
            fetchEvents = listOf(badCandidate, matching),
            fetchStatuses = listOf(RelayStatus("wss://relay.example.com", readable = true, message = "stage=fetch outcome=complete")),
        )
        val transport = RelayNip46RequestTransport(client, crypto, requestTimeoutMs = 2_000, pollIntervalMs = 10)

        val result = transport.sendRequest(session, Nip46RequestPayload("req-6", Nip46Method.Ping.wireName)).getOrThrow()

        val success = assertIs<Nip46Response.Success>(result)
        assertEquals("pong", success.result)
    }

    @Test
    fun matchingResponseOnSecondRelaySucceedsWhenFirstRelayReturnsStaleResponses() = runBlocking {
        val fixture = fixture()
        val session = transportSession(crypto, fixture, listOf("wss://stale.example.com", "wss://fresh.example.com"))
        val stale = responseEvent(crypto, fixture, session, requestId = "old-req", result = "old", createdAt = 4_000_000_002)
        val matching = responseEvent(crypto, fixture, session, requestId = "req-7", result = "pong", createdAt = 4_000_000_001)
        val client = TransportNostrClient(
            publishStatuses = listOf(
                RelayStatus("wss://stale.example.com", writable = true, message = "stage=publish outcome=accepted"),
                RelayStatus("wss://fresh.example.com", writable = true, message = "stage=publish outcome=accepted"),
            ),
            eventsByRelay = mapOf(
                "wss://stale.example.com" to listOf(stale),
                "wss://fresh.example.com" to listOf(matching),
            ),
            fetchStatuses = listOf(
                RelayStatus("wss://stale.example.com", readable = true, message = "stage=fetch outcome=complete"),
                RelayStatus("wss://fresh.example.com", readable = true, message = "stage=fetch outcome=complete"),
            ),
        )
        val transport = RelayNip46RequestTransport(client, crypto, requestTimeoutMs = 2_000, pollIntervalMs = 10)

        val result = transport.sendRequest(session, Nip46RequestPayload("req-7", Nip46Method.Ping.wireName)).getOrThrow()

        val success = assertIs<Nip46Response.Success>(result)
        assertEquals("pong", success.result)
    }

    @Test
    fun successfulRelayIsPreferredForSubsequentRequestsByObservedBehavior() = runBlocking {
        val fixture = fixture()
        val session = transportSession(crypto, fixture, listOf("wss://restricted.example.com", "wss://working.example.com"))
        val firstMatching = responseEvent(crypto, fixture, session, requestId = "req-health-1", result = "pong-1")
        val secondMatching = responseEvent(crypto, fixture, session, requestId = "req-health-2", result = "pong-2")
        val client = TransportNostrClient(
            publishStatuses = listOf(
                RelayStatus("wss://restricted.example.com", writable = false, message = "stage=publish outcome=rejected"),
                RelayStatus("wss://working.example.com", writable = true, message = "stage=publish outcome=accepted"),
            ),
            eventsByRelay = mapOf("wss://working.example.com" to listOf(firstMatching, secondMatching)),
            latencyByRelay = mapOf("wss://working.example.com" to 15),
        )
        val transport = RelayNip46RequestTransport(client, crypto, requestTimeoutMs = 2_000, pollIntervalMs = 10)

        val first = transport.sendRequest(session, Nip46RequestPayload("req-health-1", Nip46Method.Ping.wireName)).getOrThrow()
        val second = transport.sendRequest(session, Nip46RequestPayload("req-health-2", Nip46Method.Ping.wireName)).getOrThrow()

        assertEquals("pong-1", assertIs<Nip46Response.Success>(first).result)
        assertEquals("pong-2", assertIs<Nip46Response.Success>(second).result)
        assertEquals(listOf("wss://restricted.example.com", "wss://working.example.com"), client.liveRelayAttempts.first())
        assertEquals("wss://working.example.com", client.liveRelayAttempts.last().first())
    }

    @Test
    fun switchRelayAdvisoryDoesNotReplaceKnownGoodTokenRelayBeforeItWorks() = runBlocking {
        val fixture = fixture()
        val session = transportSession(
            crypto,
            fixture,
            listOf("wss://token.example.com", "wss://switched.example.com"),
        ).copy(
            relaySource = "token+switch_relays_advisory",
            relaySources = mapOf(
                "wss://token.example.com" to "token",
                "wss://switched.example.com" to "switch_relays_advisory",
            ),
        )
        val firstMatching = responseEvent(crypto, fixture, session, requestId = "req-token-1", result = "pong-1")
        val secondMatching = responseEvent(crypto, fixture, session, requestId = "req-token-2", result = "pong-2")
        val client = TransportNostrClient(
            publishStatuses = listOf(
                RelayStatus("wss://token.example.com", writable = true, message = "stage=publish outcome=accepted"),
                RelayStatus("wss://switched.example.com", writable = true, message = "stage=publish outcome=accepted"),
            ),
            eventsByRelay = mapOf(
                "wss://token.example.com" to listOf(firstMatching, secondMatching),
                "wss://switched.example.com" to emptyList(),
            ),
            latencyByRelay = mapOf("wss://token.example.com" to 10),
        )
        val transport = RelayNip46RequestTransport(client, crypto, requestTimeoutMs = 2_000, pollIntervalMs = 10)

        assertIs<Nip46Response.Success>(
            transport.sendRequest(session, Nip46RequestPayload("req-token-1", Nip46Method.Ping.wireName)).getOrThrow(),
        )
        assertIs<Nip46Response.Success>(
            transport.sendRequest(session, Nip46RequestPayload("req-token-2", Nip46Method.Ping.wireName)).getOrThrow(),
        )

        assertEquals("wss://token.example.com", client.liveRelayAttempts.last().first())
    }

    @Test
    fun allSignerRelayFailuresReportSafePerRelayOutcomes() = runBlocking {
        val fixture = fixture()
        val session = transportSession(crypto, fixture, listOf("wss://restricted.example.com", "wss://offline.example.com"))
        val client = TransportNostrClient(
            publishStatuses = listOf(
                RelayStatus("wss://restricted.example.com", writable = false, message = "stage=publish outcome=rejected"),
                RelayStatus("wss://offline.example.com", writable = false, message = "stage=publish outcome=timeout"),
            ),
        )
        val transport = RelayNip46RequestTransport(client, crypto, requestTimeoutMs = 2_000, pollIntervalMs = 10)

        val result = transport.sendRequest(session, Nip46RequestPayload("req-all-fail", Nip46Method.Ping.wireName))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("stage=relay_publish_failed"))
        assertTrue(message.contains("per_relay_outcomes="))
        assertTrue(message.contains("wss://restricted.example.com"))
        assertTrue(message.contains("reason=publish_rejected"))
        assertTrue(message.contains("wss://offline.example.com"))
        assertFalse(message.contains("relays_subscribed="))
        assertFalse(message.contains("secret="))
    }

    @Test
    fun multiRelayTimeoutReportsPerRelayOutcomesInsteadOfGlobalSubscribedSet() = runBlocking {
        val fixture = fixture()
        val session = transportSession(crypto, fixture, listOf("wss://one.example.com", "wss://two.example.com"))
        val client = TransportNostrClient(
            publishStatuses = listOf(
                RelayStatus("wss://one.example.com", writable = true, message = "stage=publish outcome=accepted"),
                RelayStatus("wss://two.example.com", writable = true, message = "stage=publish outcome=accepted"),
            ),
            eventsByRelay = mapOf(
                "wss://one.example.com" to emptyList(),
                "wss://two.example.com" to emptyList(),
            ),
        )
        val transport = RelayNip46RequestTransport(client, crypto, requestTimeoutMs = 60, pollIntervalMs = 10)

        val result = transport.sendRequest(session, Nip46RequestPayload("req-timeout-all", Nip46Method.Nip44Encrypt.wireName))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("stage=response_fetch_timed_out"))
        assertTrue(message.contains("method=nip44_encrypt"))
        assertTrue(message.contains("attempted_relays=wss://one.example.com|wss://two.example.com"))
        assertTrue(message.contains("per_relay_outcomes="))
        assertTrue(message.contains("wss://one.example.com"))
        assertTrue(message.contains("wss://two.example.com"))
        assertTrue(message.contains("subscribed=true"))
        assertTrue(message.contains("reason=response_timeout"))
        assertFalse(message.contains("relays_subscribed="))
    }

    @Test
    fun signEventRejectsMismatchedPubkeyAndContent() {
        runBlocking {
            val fixture = fixture()
            val requested = unsignedEvent(fixture.userPubkey, "encrypted-content")
            val wrongPubkeySigner = connectedSigner(fixture, FakeNip46Transport(crypto, fixture, wrongSignedPubkey = true))
            val wrongContentSigner = connectedSigner(fixture, FakeNip46Transport(crypto, fixture, wrongSignedContent = true))

            val wrongPubkeyResult = wrongPubkeySigner.signEventAsync(requested, fixture.userPubkey, "nip46")
            val wrongContentResult = wrongContentSigner.signEventAsync(requested, fixture.userPubkey, "nip46")

            assertIs<com.libertasprimordium.othernote.security.SignEventRequestResult.InvalidResponse>(wrongPubkeyResult)
            assertIs<com.libertasprimordium.othernote.security.SignEventRequestResult.InvalidResponse>(wrongContentResult)
        }
    }

    @Test
    fun signEventSendsSpecCompatibleUnsignedEventPayload() = runBlocking {
        val fixture = fixture()
        val transport = FakeNip46Transport(crypto, fixture)
        val signer = connectedSigner(fixture, transport)
        val requested = unsignedEvent(fixture.userPubkey, "encrypted-content")

        val signResult = signer.signEventAsync(requested, fixture.userPubkey, "nip46")

        assertIs<com.libertasprimordium.othernote.security.SignEventRequestResult.Success>(signResult)
        val payload = Json.parseToJsonElement(transport.signEventPayloads.single()).jsonObject
        assertNull(payload["pubkey"])
        assertNull(payload["id"])
        assertNull(payload["sig"])
        assertEquals(30078, payload["kind"]?.jsonPrimitive?.content?.toInt())
        assertEquals("encrypted-content", payload["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun nip44EncryptAndDecryptDelegateToRemoteSigner() {
        runBlocking {
            val fixture = fixture()
            val transport = FakeNip46Transport(crypto, fixture)
            val signer = connectedSigner(fixture, transport)

            val encrypted = signer.encryptToSelfAsync("plain note payload", fixture.userPubkey, "nip46")
            val ciphertext = assertIs<com.libertasprimordium.othernote.security.SignerNip44OperationResult.Encrypted>(encrypted).payload
            val decrypted = signer.decryptFromSelfAsync(ciphertext, "plain note payload", fixture.userPubkey, "nip46")

            assertIs<com.libertasprimordium.othernote.security.SignerNip44OperationResult.Decrypted>(decrypted)
            assertEquals(listOf(fixture.userPubkey, "plain note payload"), transport.nip44EncryptParams.single())
            assertEquals(listOf(fixture.userPubkey, ciphertext), transport.nip44DecryptParams.single())
        }
    }

    @Test
    fun appCanSaveEncryptedNoteWithNip46Signer() = runBlocking {
        val fixture = fixture()
        val remoteSigner = Nip46RemoteSigner(FakeNip46Transport(crypto, fixture), crypto)
        val cache = InMemoryLocalEventCache()
        val pending = InMemoryPendingWriteStore()
        val relayClient = CapturingNostrClient()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = crypto,
                client = relayClient,
                remoteSigner = remoteSigner,
                localEventCache = cache,
                pendingWriteStore = pending,
                relaySettings = com.libertasprimordium.othernote.data.RelaySettingsStore(
                    listOf(RelayConfig("wss://relay.example.com")),
                ),
            ),
        )

        assertTrue(state.connectRemoteSigner("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com"))
        assertEquals(SessionAuthMethod.RemoteSigner, state.session.value?.authMethod)
        assertTrue(state.save(existing = null, markdown = "nip46 private note"))

        val published = relayClient.published.single()
        assertEquals(30078, published.kind)
        assertEquals(fixture.userPubkey, published.pubkey)
        assertFalse(published.content.contains("nip46 private note"))
        assertEquals(listOf(published), cache.loadEvents(fixture.userPubkey))
    }

    @Test
    fun remoteSignerSaveFailureKeepsEditorErrorAndDoesNotCreatePendingWrite() = runBlocking {
        val fixture = fixture()
        val cache = InMemoryLocalEventCache()
        val pending = InMemoryPendingWriteStore()
        val relayClient = CapturingNostrClient()
        val remoteSigner = Nip46RemoteSigner(
            FakeNip46Transport(
                crypto,
                fixture,
                throwByMethod = mapOf(
                    Nip46Method.Nip44Encrypt.wireName to
                        "stage=relay_publish_failed relay_source=token statuses=wss://restricted.example.com writable=false stage=publish outcome=rejected secret=must-not-appear",
                ),
            ),
            crypto,
        )
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = crypto,
                client = relayClient,
                remoteSigner = remoteSigner,
                localEventCache = cache,
                pendingWriteStore = pending,
                relaySettings = com.libertasprimordium.othernote.data.RelaySettingsStore(
                    listOf(RelayConfig("wss://note-relay.example.com")),
                ),
            ),
        )

        assertTrue(state.connectRemoteSigner("bunker://${fixture.remotePubkey}?relay=wss://token.example.com"))
        val saved = state.saveFromEditor(existing = null, markdown = "draft must stay in editor only")

        assertFalse(saved)
        val editorError = state.editorSaveState.value.error.orEmpty()
        assertTrue(editorError.contains("Remote signer relay rejected"))
        assertTrue(editorError.contains("wss://restricted.example.com"))
        assertFalse(editorError.contains("must-not-appear"))
        assertFalse(editorError.contains("draft must stay"))
        assertTrue(state.notes.notes.value.isEmpty())
        assertTrue(relayClient.published.isEmpty())
        assertTrue(cache.loadEvents(fixture.userPubkey).isEmpty())
        assertTrue(pending.loadPendingWrites(fixture.userPubkey).isEmpty())
    }

    @Test
    fun remoteSignerEncryptTimeoutKeepsEditorOpenWithMethodSpecificError() = runBlocking {
        val fixture = fixture()
        val cache = InMemoryLocalEventCache()
        val pending = InMemoryPendingWriteStore()
        val relayClient = CapturingNostrClient()
        val remoteSigner = Nip46RemoteSigner(
            FakeNip46Transport(
                crypto,
                fixture,
                throwByMethod = mapOf(
                    Nip46Method.Nip44Encrypt.wireName to
                        "stage=response_fetch_timed_out reason=no_matching_response method=nip44_encrypt request_id=req-encrypt relay_source=token+switch_relays_advisory attempts=7 candidate_events=28 decrypt_failures=0 mismatched_ids=28 matching_id_found=false elapsed_ms=12000 secret=must-not-appear",
                ),
            ),
            crypto,
        )
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = crypto,
                client = relayClient,
                remoteSigner = remoteSigner,
                localEventCache = cache,
                pendingWriteStore = pending,
                relaySettings = com.libertasprimordium.othernote.data.RelaySettingsStore(
                    listOf(RelayConfig("wss://note-relay.example.com")),
                ),
            ),
        )

        assertTrue(state.connectRemoteSigner("bunker://${fixture.remotePubkey}?relay=wss://token.example.com"))
        val saved = state.saveFromEditor(existing = null, markdown = "draft must stay in editor only")

        assertFalse(saved)
        val editorState = state.editorSaveState.value
        val editorError = editorState.error.orEmpty()
        assertFalse(editorState.inProgress)
        assertTrue(editorError.contains("encryption request"))
        assertTrue(editorError.contains("method=nip44_encrypt"))
        assertTrue(editorError.contains("candidate_events=28"))
        assertFalse(editorError.contains("must-not-appear"))
        assertFalse(editorError.contains("draft must stay"))
        assertTrue(state.notes.notes.value.isEmpty())
        assertTrue(relayClient.published.isEmpty())
        assertTrue(cache.loadEvents(fixture.userPubkey).isEmpty())
        assertTrue(pending.loadPendingWrites(fixture.userPubkey).isEmpty())
    }

    @Test
    fun remoteSignerLoginStartsAsyncAndDoesNotBlockCaller() = runBlocking {
        val fixture = fixture()
        val remoteSigner = Nip46RemoteSigner(FakeNip46Transport(crypto, fixture, delayMs = 150), crypto)
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = crypto,
                client = CapturingNostrClient(),
                remoteSigner = remoteSigner,
            ),
        )

        val accepted = state.startRemoteSignerConnection("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com")

        assertTrue(accepted)
        assertEquals(null, state.session.value)
        assertTrue(state.message.value.contains("Connecting"))
        withTimeout(1_500) {
            while (state.session.value?.authMethod != SessionAuthMethod.RemoteSigner) delay(10)
        }
    }

    private fun connectedSigner(fixture: Fixture, transport: FakeNip46Transport): Nip46RemoteSigner {
        val signer = Nip46RemoteSigner(transport, crypto)
        runBlocking {
            assertIs<Nip46ConnectResult.Connected>(signer.connectAsync("bunker://${fixture.remotePubkey}?relay=wss://relay.example.com"))
        }
        return signer
    }

    private fun fixture(): Fixture {
        val userPrivateKey = crypto.generatePrivateKey().getOrThrow()
        val userPublicKey = crypto.derivePublicKey(userPrivateKey).getOrThrow()
        val remotePrivateKey = crypto.generatePrivateKey().getOrThrow()
        val remotePublicKey = crypto.derivePublicKey(remotePrivateKey).getOrThrow()
        return Fixture(userPrivateKey, userPublicKey.hex, userPublicKey.npub, remotePrivateKey, remotePublicKey.hex)
    }

    private fun unsignedEvent(pubkey: String, content: String): NostrEvent {
        val unsigned = UnsignedNostrEvent(
            pubkey = pubkey,
            createdAt = 123,
            kind = 30078,
            tags = listOf(listOf("d", "other-note:note:nip46"), listOf("t", "other-note")),
            content = content,
        )
        return NostrEvent(
            id = crypto.computeEventId(unsigned).getOrThrow(),
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = "",
        )
    }
}

private data class Fixture(
    val userPrivateKey: NostrPrivateKey,
    val userPubkey: String,
    val userNpub: String,
    val remotePrivateKey: NostrPrivateKey,
    val remotePubkey: String,
)

private fun transportSession(crypto: NostrCrypto, fixture: Fixture, relays: List<String>): Nip46TransportSession {
    val clientPrivateKey = crypto.generatePrivateKey().getOrThrow()
    val clientPubkey = crypto.derivePublicKey(clientPrivateKey).getOrThrow()
    return Nip46TransportSession(
        clientPrivateKey = clientPrivateKey,
        clientPubkey = clientPubkey.hex,
        remoteSignerPubkey = fixture.remotePubkey,
        relays = relays,
    )
}

private fun responseEvent(
    crypto: NostrCrypto,
    fixture: Fixture,
    session: Nip46TransportSession,
    requestId: String,
    result: String,
    createdAt: Long = 4_000_000_000,
    includePTag: Boolean = true,
): NostrEvent {
    val content = crypto.encryptToSelf(
        plaintext = Nip46PayloadJson.encodeResponse(Nip46ResponsePayload(requestId, result = result)),
        privateKey = fixture.remotePrivateKey,
        publicKey = NostrPublicKey(session.clientPubkey, ""),
    ).getOrThrow()
    val unsigned = UnsignedNostrEvent(
        pubkey = fixture.remotePubkey,
        createdAt = createdAt,
        kind = Nip46EventKind,
        tags = if (includePTag) listOf(listOf("p", session.clientPubkey)) else emptyList(),
        content = content,
    )
    return crypto.sign(unsigned, fixture.remotePrivateKey).getOrThrow()
}

private fun malformedResponseEvent(
    crypto: NostrCrypto,
    fixture: Fixture,
    session: Nip46TransportSession,
): NostrEvent {
    val unsigned = UnsignedNostrEvent(
        pubkey = fixture.remotePubkey,
        createdAt = 4_000_000_004,
        kind = Nip46EventKind,
        tags = listOf(listOf("p", session.clientPubkey)),
        content = "not-valid-nip44-ciphertext",
    )
    return crypto.sign(unsigned, fixture.remotePrivateKey).getOrThrow()
}

private class FakeNip46Transport(
    private val crypto: NostrCrypto,
    private val fixture: Fixture,
    private val expectedSecret: String? = null,
    private val wrongSignedPubkey: Boolean = false,
    private val wrongSignedContent: Boolean = false,
    private val timeout: Boolean = false,
    private val throwMessage: String? = null,
    private val switchRelayResult: String = "null",
    private val delayMs: Long = 0,
    private val throwByMethod: Map<String, String> = emptyMap(),
    private val errorByMethod: Map<String, String> = emptyMap(),
) : Nip46RequestTransport {
    private val plaintextByCiphertext = mutableMapOf<String, String>()
    val connectParams = mutableListOf<List<String>>()
    val connectClientPubkeys = mutableListOf<String>()
    val switchRelayParams = mutableListOf<List<String>>()
    val signEventPayloads = mutableListOf<String>()
    val nip44EncryptParams = mutableListOf<List<String>>()
    val nip44DecryptParams = mutableListOf<List<String>>()
    val requestRelaySnapshots = mutableListOf<List<String>>()
    val requestRelaySources = mutableListOf<String>()

    override suspend fun sendRequest(session: Nip46TransportSession, request: Nip46RequestPayload): Result<Nip46Response> = runCatching {
        requestRelaySnapshots += session.relays
        requestRelaySources += session.relaySource
        if (delayMs > 0) delay(delayMs)
        throwMessage?.let { error(it) }
        throwByMethod[request.method]?.let { error(it) }
        errorByMethod[request.method]?.let { return@runCatching Nip46Response.Error(request.id, it) }
        if (timeout) error("request timed out secret=must-not-appear")
        when (request.method) {
            Nip46Method.Connect.wireName -> {
                connectParams += request.params
                connectClientPubkeys += session.clientPubkey
                if (request.params.getOrNull(0) != fixture.remotePubkey || request.params.getOrNull(0) == session.clientPubkey) {
                    Nip46Response.Error(request.id, "Remote signer rejected connect params")
                } else if (request.params.getOrNull(2)?.contains("sign_event:30078") != true) {
                    Nip46Response.Error(request.id, "Remote signer rejected missing permissions")
                } else if (request.params.getOrNull(2)?.contains("nip44_decrypt") != true) {
                    Nip46Response.Error(request.id, "Remote signer rejected missing permissions")
                } else if (expectedSecret != null && request.params.getOrNull(1) != expectedSecret) {
                    Nip46Response.Error(request.id, "Remote signer rejected connect secret")
                } else {
                    Nip46Response.Success(request.id, "ack")
                }
            }
            Nip46Method.Ping.wireName -> Nip46Response.Success(request.id, "pong")
            Nip46Method.GetPublicKey.wireName -> Nip46Response.Success(request.id, fixture.userPubkey)
            Nip46Method.SwitchRelays.wireName -> {
                switchRelayParams += request.params
                if (request.params.isNotEmpty()) {
                    Nip46Response.Error(request.id, "Remote signer rejected switch_relays params")
                } else {
                    Nip46Response.Success(request.id, switchRelayResult)
                }
            }
            Nip46Method.SignEvent.wireName -> {
                val payload = request.params.single()
                signEventPayloads += payload
                val requested = parseUnsignedSignEventPayload(payload)
                val unsigned = UnsignedNostrEvent(
                    pubkey = fixture.userPubkey,
                    createdAt = requested.createdAt,
                    kind = requested.kind,
                    tags = requested.tags,
                    content = requested.content,
                )
                val signed = crypto.sign(unsigned, fixture.userPrivateKey).getOrThrow()
                    .let { if (wrongSignedPubkey) it.copy(pubkey = fixture.remotePubkey) else it }
                    .let { if (wrongSignedContent) it.copy(content = it.content + "-changed") else it }
                Nip46Response.Success(request.id, NostrWireJson.eventJson(signed))
            }
            Nip46Method.Nip44Encrypt.wireName -> {
                nip44EncryptParams += request.params
                val plaintext = request.params.getOrNull(1) ?: error("missing plaintext")
                val ciphertext = "encrypted-nip46-${plaintext.hashCode()}-${plaintext.length}"
                plaintextByCiphertext[ciphertext] = plaintext
                Nip46Response.Success(request.id, ciphertext)
            }
            Nip46Method.Nip44Decrypt.wireName -> {
                nip44DecryptParams += request.params
                val ciphertext = request.params.getOrNull(1) ?: error("missing ciphertext")
                Nip46Response.Success(request.id, plaintextByCiphertext[ciphertext] ?: error("missing plaintext"))
            }
            else -> Nip46Response.Error(request.id, "unsupported method")
        }
    }

    private data class UnsignedSignRequest(
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
    )

    private fun parseUnsignedSignEventPayload(raw: String): UnsignedSignRequest {
        val obj = Json.parseToJsonElement(raw).jsonObject
        require(obj["pubkey"] == null) { "NIP-46 sign_event request must not include pubkey" }
        require(obj["id"] == null) { "NIP-46 sign_event request must not include id" }
        require(obj["sig"] == null) { "NIP-46 sign_event request must not include sig" }
        val tags = obj["tags"]?.jsonArray?.map { tag ->
            tag.jsonArray.map { it.jsonPrimitive.content }
        } ?: emptyList()
        return UnsignedSignRequest(
            createdAt = obj["created_at"]?.jsonPrimitive?.content?.toLong() ?: error("missing created_at"),
            kind = obj["kind"]?.jsonPrimitive?.content?.toInt() ?: error("missing kind"),
            tags = tags,
            content = obj["content"]?.jsonPrimitive?.content ?: error("missing content"),
        )
    }
}

private class TransportNostrClient(
    private val publishStatuses: List<RelayStatus>,
    private val fetchEvents: List<NostrEvent> = emptyList(),
    private val fetchStatuses: List<RelayStatus> = emptyList(),
    private val eventsByRelay: Map<String, List<NostrEvent>> = emptyMap(),
    private val latencyByRelay: Map<String, Long> = emptyMap(),
) : NostrClient, FanoutNostrClient, Nip46LiveNostrClient {
    var fetchCalls = 0
    var fanoutCalls = 0
    var liveCalls = 0
    val liveFilters = mutableListOf<NostrFilter>()
    val liveRelayAttempts = mutableListOf<List<String>>()

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(emptyList(), emptyList())

    override suspend fun fetchEvents(relays: List<String>, filter: NostrFilter): RelayFetchResult {
        fetchCalls++
        return RelayFetchResult(fetchEvents, fetchStatuses)
    }

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult =
        RelayPublishResult(publishStatuses)

    override fun publishBestEffort(
        relays: List<String>,
        event: NostrEvent,
        scope: CoroutineScope,
        onStatus: (List<RelayStatus>) -> Unit,
    ): PublishBestEffortHandle {
        fanoutCalls++
        onStatus(publishStatuses)
        val result = RelayPublishResult(publishStatuses)
        return PublishBestEffortHandle(
            firstAccepted = CompletableDeferred(result),
            complete = CompletableDeferred(result),
        )
    }

    override suspend fun requestNip46Response(
        relays: List<String>,
        requestEvent: NostrEvent,
        filter: NostrFilter,
        timeoutMs: Long,
        onCandidate: suspend (relay: String, event: NostrEvent) -> Boolean,
    ): Nip46LiveRelayResult {
        liveCalls++
        liveFilters += filter
        liveRelayAttempts += relays
        val subscribed = relays.distinct()
        val statuses = publishStatuses.ifEmpty {
            subscribed.map { RelayStatus(it, writable = true, message = "stage=publish outcome=accepted") }
        }
        var matched = false
        val outcomes = mutableListOf<Nip46LiveRelayOutcome>()
        for (relay in subscribed) {
            val status = statuses.firstOrNull { it.url == relay }
                ?: RelayStatus(relay, writable = true, message = "stage=publish outcome=accepted")
            var candidateCount = 0
            var relayMatched = false
            if (status.writable) {
                for (event in eventsByRelay[relay] ?: fetchEvents) {
                    candidateCount++
                    if (onCandidate(relay, event)) {
                        matched = true
                        relayMatched = true
                        break
                    }
                }
            }
            outcomes += Nip46LiveRelayOutcome(
                relay = relay,
                subscribed = true,
                publishStatus = status,
                responseMatched = relayMatched,
                candidateEventCount = candidateCount,
                liveEventAfterPublishCount = candidateCount,
                latencyMs = latencyByRelay[relay] ?: if (relayMatched) 1 else timeoutMs,
                failureReason = when {
                    relayMatched -> null
                    !status.writable && status.message.contains("rejected") -> "publish_rejected"
                    !status.writable -> "publish_failed"
                    else -> "response_timeout"
                },
            )
            if (matched) break
        }
        return Nip46LiveRelayResult(
            responseFound = matched,
            publishStatuses = outcomes.mapNotNull { it.publishStatus },
            candidateEventCount = outcomes.sumOf { it.candidateEventCount },
            liveEventAfterPublishCount = outcomes.sumOf { it.liveEventAfterPublishCount },
            relayOutcomes = outcomes,
        )
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}

private class CapturingNostrClient : NostrClient {
    val published = mutableListOf<NostrEvent>()

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(emptyList(), relays.map { RelayStatus(it, readable = true, message = "ok") })

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult {
        published += event
        return RelayPublishResult(relays.map { RelayStatus(it, writable = true, message = "accepted") })
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}
