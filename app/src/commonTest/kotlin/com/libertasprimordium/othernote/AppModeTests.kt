package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.ui.AppMode
import com.libertasprimordium.othernote.ui.AppPlatform
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.AppState
import com.libertasprimordium.othernote.ui.GeneratedIdentityStep
import com.libertasprimordium.othernote.ui.RelayAddResult
import com.libertasprimordium.othernote.ui.SignInOptionEmphasis
import com.libertasprimordium.othernote.ui.SignInOptionKind
import com.libertasprimordium.othernote.ui.buildSignInOptions
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.Nip19
import com.libertasprimordium.othernote.nostr.NostrClient
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrFilter
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.nostr.ProfileMetadata
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.RelayPublishResult
import com.libertasprimordium.othernote.nostr.RelayTester
import com.libertasprimordium.othernote.nostr.RelayTestResult
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.domain.DefaultRelays
import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.security.NostrSignerProvider
import com.libertasprimordium.othernote.security.NostrSignerEventSigner
import com.libertasprimordium.othernote.security.NostrSignerNip44Operator
import com.libertasprimordium.othernote.security.NostrSignerPublicKeyRequester
import com.libertasprimordium.othernote.security.SignEventRequestResult
import com.libertasprimordium.othernote.security.SignerNip44OperationResult
import com.libertasprimordium.othernote.security.SignerPublicKeyRequestResult
import com.libertasprimordium.othernote.security.SignerMode
import com.libertasprimordium.othernote.security.UnavailableExternalSignerProvider
import com.libertasprimordium.othernote.domain.SessionAuthMethod
import com.libertasprimordium.othernote.data.InMemoryLocalEventCache
import com.libertasprimordium.othernote.data.InMemoryPendingWriteStore
import com.libertasprimordium.othernote.data.RelaySettingsStore
import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppModeTests {
    @Test
    fun sharedDefaultRelaysUseMaintainedDevSet() {
        val urls = DefaultRelays.map { it.url }

        assertTrue("wss://nos.lol" in urls)
        assertTrue("wss://relay.ditto.pub" in urls)
        assertFalse(urls.any { it.contains("relay.nostr.band") })
    }

    @Test
    fun localOnlyModeAllowsNoteEditingWithoutSession() = runBlocking {
        val state = AppState()
        state.continueLocalOnly()

        state.save(existing = null, markdown = "local note")

        assertEquals(AppMode.LocalOnly, state.mode.value)
        assertNull(state.session.value)
        assertEquals("local note", state.notes.notes.value.single().bodyMarkdown)
    }

    @Test
    fun localOnlyModeBlocksRelaySync() = runBlocking {
        val state = AppState()
        state.continueLocalOnly()

        state.sync()

        assertTrue(state.syncState.value.errors.single().contains("requires a validated nsec"))
    }

    @Test
    fun androidSignInOptionsPrioritizeAndroidSignerThenRemoteThenSessionOnlyNsec() {
        val options = buildSignInOptions(
            platform = AppPlatform.Android,
            externalSignerAvailable = true,
            remoteSignerAvailable = true,
        )

        assertEquals(
            listOf(
                SignInOptionKind.AndroidSigner,
                SignInOptionKind.RemoteSigner,
                SignInOptionKind.ExistingNsec,
                SignInOptionKind.CreateIdentity,
            ),
            options.map { it.kind },
        )
        assertEquals(SignInOptionEmphasis.Primary, options[0].emphasis)
        assertEquals(SignInOptionEmphasis.Secondary, options[1].emphasis)
        assertEquals(SignInOptionEmphasis.Low, options[2].emphasis)
        assertEquals(SignInOptionEmphasis.Text, options[3].emphasis)
        assertTrue(options[0].supportingCopy.contains("Recommended on Android"))
    }

    @Test
    fun androidSignerOptionIsHiddenWhenUnavailableOnUnsupportedPlatform() {
        val options = buildSignInOptions(
            platform = AppPlatform.Desktop,
            externalSignerAvailable = false,
            remoteSignerAvailable = true,
        )

        assertFalse(options.any { it.kind == SignInOptionKind.AndroidSigner })
        assertEquals(SignInOptionKind.RemoteSigner, options.first().kind)
        assertEquals(SignInOptionKind.CreateIdentity, options.last().kind)
        assertEquals(SignInOptionEmphasis.Text, options.last().emphasis)
    }

    @Test
    fun androidSignerOptionIsHiddenWhenUnavailableOnAndroid() {
        val options = buildSignInOptions(
            platform = AppPlatform.Android,
            externalSignerAvailable = false,
            remoteSignerAvailable = true,
        )

        assertFalse(options.any { it.kind == SignInOptionKind.AndroidSigner })
        assertEquals(SignInOptionKind.RemoteSigner, options.first().kind)
        assertEquals(SignInOptionKind.CreateIdentity, options.last().kind)
    }

    @Test
    fun externalSignerUnavailableIsSurfacedWithoutChangingSessionMode() {
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = UnavailableExternalSignerProvider("No Android NIP-55 signer found."),
            ),
        )

        state.requestExternalSignerPublicKey()

        assertFalse(state.externalSignerAvailable)
        assertTrue(state.message.value.contains("No Android signer found"))
        assertEquals(AppMode.SignedOut, state.mode.value)
        assertNull(state.session.value)
    }

    @Test
    fun signerPublicKeySuccessCreatesExternalSignerSessionWithoutPrivateKey() {
        val pubkeyHex = "02".repeat(32)
        val npub = Nip19.encode("npub", ByteArray(32) { 0x02 }) ?: error("npub encode failed")
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
            ),
        )

        state.requestExternalSignerPublicKey()

        assertTrue(state.externalSignerAvailable)
        assertEquals("External signer detected: Test NIP-55 Signer", state.externalSignerStatus)
        assertTrue(state.message.value.contains("relay sync can run with signer prompts"))
        assertEquals(AppMode.Authenticated, state.mode.value)
        val session = state.session.value ?: error("Missing signer session")
        assertEquals(SessionAuthMethod.ExternalSigner, session.authMethod)
        assertEquals("external-signer", session.nsec)
        assertEquals("", session.privateKeyHex)
        assertEquals(pubkeyHex, session.publicKeyHex)
        assertEquals(npub, session.npub)
        assertEquals("com.example.signer", session.signerPackage)
    }

    @Test
    fun signerLoginStatusDoesNotExposeDevelopmentTestActions() {
        val pubkeyHex = "02".repeat(32)
        val npub = Nip19.encode("npub", ByteArray(32) { 0x02 }) ?: error("npub encode failed")
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
            ),
        )

        state.requestExternalSignerPublicKey()

        assertFalse(state.message.value.contains("Test signer"))
        assertFalse(state.message.value.contains("test signer"))
        assertTrue(state.message.value.contains("relay sync can run"))
    }

    @Test
    fun signerCancellationLeavesUserSignedOut() {
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(SignerPublicKeyRequestResult.Cancelled),
            ),
        )

        state.requestExternalSignerPublicKey()

        assertEquals("Signer request cancelled", state.message.value)
        assertEquals(AppMode.SignedOut, state.mode.value)
        assertNull(state.session.value)
    }

    @Test
    fun signerTestSignatureSuccessLeavesSignerSessionAndShowsVerifiedStatus() {
        val pubkeyHex = "02".repeat(32)
        val npub = Nip19.encode("npub", ByteArray(32) { 0x02 }) ?: error("npub encode failed")
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
                externalSignerEventSigner = TestSignerEventSigner { unsigned ->
                    SignEventRequestResult.Success(unsigned.copy(sig = "valid"), "com.example.signer")
                },
            ),
        )

        state.requestExternalSignerPublicKey()
        state.requestExternalSignerTestSignature()

        assertEquals(AppMode.Authenticated, state.mode.value)
        assertEquals(SessionAuthMethod.ExternalSigner, state.session.value?.authMethod)
        assertTrue(state.message.value.startsWith("Signer test event signed and verified"))
    }

    @Test
    fun signerTestSignatureRejectsWrongPubkeyAndKeepsSession() {
        val pubkeyHex = "02".repeat(32)
        val npub = Nip19.encode("npub", ByteArray(32) { 0x02 }) ?: error("npub encode failed")
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
                externalSignerEventSigner = TestSignerEventSigner {
                    SignEventRequestResult.InvalidResponse("Signer returned event for a different pubkey")
                },
            ),
        )

        state.requestExternalSignerPublicKey()
        state.requestExternalSignerTestSignature()

        assertEquals(AppMode.Authenticated, state.mode.value)
        assertTrue(state.message.value.contains("different pubkey"))
    }

    @Test
    fun signerTestSigningCancelledLeavesSignerSession() {
        val pubkeyHex = "02".repeat(32)
        val npub = Nip19.encode("npub", ByteArray(32) { 0x02 }) ?: error("npub encode failed")
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
                externalSignerEventSigner = TestSignerEventSigner { SignEventRequestResult.Cancelled },
            ),
        )

        state.requestExternalSignerPublicKey()
        state.requestExternalSignerTestSignature()

        assertEquals(AppMode.Authenticated, state.mode.value)
        assertEquals("Signer signing cancelled", state.message.value)
        assertNotNull(state.session.value)
    }

    @Test
    fun signerTestSigningDiagnosticsAreVisibleOnlyWhenEnabledAndSanitized() {
        val pubkeyHex = "02".repeat(32)
        val npub = Nip19.encode("npub", ByteArray(32) { 0x02 }) ?: error("npub encode failed")
        val disabledState = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
                externalSignerEventSigner = TestSignerEventSigner { SignEventRequestResult.Cancelled },
            ),
        )
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                showNip55Diagnostics = true,
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
                externalSignerEventSigner = TestSignerEventSigner { SignEventRequestResult.Cancelled },
            ),
        )

        disabledState.requestExternalSignerPublicKey()
        disabledState.requestExternalSignerTestSignature()
        state.requestExternalSignerPublicKey()
        state.requestExternalSignerTestSignature()

        assertEquals("", disabledState.diagnosticMessage.value)
        val diagnostics = state.diagnosticMessage.value
        assertTrue(diagnostics.contains("request_shape=full_unsigned_event_no_id_sig"))
        assertTrue(diagnostics.contains("event_contains_pubkey=true"))
        assertFalse(diagnostics.contains(pubkeyHex))
        assertFalse(diagnostics.contains("nsec1"))
    }

    @Test
    fun signerNip44RoundTripSuccessShowsVerifiedStatus() {
        val pubkeyHex = "02".repeat(32)
        val npub = Nip19.encode("npub", ByteArray(32) { 0x02 }) ?: error("npub encode failed")
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
                externalSignerNip44Operator = TestNip44Operator(),
            ),
        )

        state.requestExternalSignerPublicKey()
        state.requestExternalSignerNip44Test()

        assertEquals(AppMode.Authenticated, state.mode.value)
        assertEquals("Signer decrypted and verified test payload", state.message.value)
    }

    @Test
    fun signerNip44RoundTripUnavailableDoesNotBreakSession() {
        val pubkeyHex = "02".repeat(32)
        val npub = Nip19.encode("npub", ByteArray(32) { 0x02 }) ?: error("npub encode failed")
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                externalSignerProvider = AvailableTestSignerProvider,
                externalSignerPublicKeyRequester = TestSignerPublicKeyRequester(
                    SignerPublicKeyRequestResult.Success(pubkeyHex, npub, "com.example.signer"),
                ),
                externalSignerNip44Operator = TestNip44Operator(
                    encryptResult = SignerNip44OperationResult.InvalidResponse("Signer returned invalid encryption result"),
                ),
            ),
        )

        state.requestExternalSignerPublicKey()
        state.requestExternalSignerNip44Test()

        assertEquals(AppMode.Authenticated, state.mode.value)
        assertEquals("Signer returned invalid encryption result", state.message.value)
    }

    @Test
    fun generatedIdentityRequiresAcknowledgementAndStartsSessionOnly() = runBlocking {
        val crypto = GeneratedIdentityTestCrypto()
        val cache = InMemoryLocalEventCache()
        val pending = InMemoryPendingWriteStore()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = crypto,
                client = AcceptingGeneratedIdentityClient(),
                localEventCache = cache,
                pendingWriteStore = pending,
            ),
        )

        state.startGeneratedIdentityFlow()
        assertEquals(GeneratedIdentityStep.Explanation, state.generatedIdentityState.value.step)
        assertTrue(state.generateFreshIdentity())
        state.toggleGeneratedIdentityNsecReveal()
        val generated = state.generatedIdentityState.value
        val generatedNsec = generated.nsecForDisplay()
        val generatedPrivateKey = generated.secret?.privateKeyHex ?: error("missing generated private key")

        assertTrue(generatedNsec.startsWith("nsec-test-"))
        assertFalse(generated.toString().contains(generatedNsec))
        assertFalse(generated.toString().contains(generatedPrivateKey))
        assertFalse(state.useGeneratedIdentityForSession())
        state.acknowledgeGeneratedIdentitySaved(true)
        assertFalse(state.useGeneratedIdentityForSession())
        state.acknowledgeGeneratedIdentityLossRisk(true)
        assertTrue(state.useGeneratedIdentityForSession())

        val session = state.session.value ?: error("missing generated session")
        assertEquals(SessionAuthMethod.SessionOnlyNsec, session.authMethod)
        assertEquals("nsec-redacted", session.nsec)
        assertEquals(generatedPrivateKey, session.privateKeyHex)
        assertFalse(session.toString().contains(generatedNsec))
        assertFalse(session.toString().contains(generatedPrivateKey))
        assertEquals(GeneratedIdentityStep.Idle, state.generatedIdentityState.value.step)

        val originalText = "generated identity note"
        val editedText = "generated identity edited note"
        assertTrue(state.save(existing = null, markdown = originalText))
        assertFalse(state.message.value.contains("NIP-44 v2 encryption is not wired yet"))
        assertEquals(originalText, state.notes.notes.value.single().bodyMarkdown)

        val originalNote = state.notes.notes.value.single()
        assertTrue(state.save(existing = originalNote, markdown = editedText))
        assertEquals(editedText, state.notes.notes.value.single().bodyMarkdown)

        val editedNote = state.notes.notes.value.single()
        assertTrue(state.delete(editedNote))
        assertTrue(state.notes.notes.value.isEmpty())

        val cachedEvents = cache.loadEvents(session.publicKeyHex)
        assertEquals(3, cachedEvents.size)
        assertTrue(cachedEvents.all { it.kind == NoteKind })
        val serializedCacheForSafety = cachedEvents.joinToString(" ") { event ->
            listOf(event.id, event.pubkey, event.createdAt.toString(), event.kind.toString(), event.tags.toString(), event.content, event.sig).joinToString(" ")
        }
        assertFalse(serializedCacheForSafety.contains(generatedNsec))
        assertFalse(serializedCacheForSafety.contains(generatedPrivateKey))
        assertFalse(serializedCacheForSafety.contains(originalText))
        assertFalse(serializedCacheForSafety.contains(editedText))
        assertFalse(serializedCacheForSafety.contains("body_markdown"))
        assertTrue(pending.loadPendingWrites(session.publicKeyHex).isEmpty())
    }

    @Test
    fun cancellingGeneratedIdentityFlowClearsDisplayedSecret() {
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = GeneratedIdentityTestCrypto(),
                client = OfflineNostrClient(),
            ),
        )

        state.startGeneratedIdentityFlow()
        assertTrue(state.generateFreshIdentity())
        state.toggleGeneratedIdentityNsecReveal()
        val generatedNsec = state.generatedIdentityState.value.nsecForDisplay()
        state.cancelGeneratedIdentityFlow()

        assertEquals(GeneratedIdentityStep.Idle, state.generatedIdentityState.value.step)
        assertFalse(state.generatedIdentityState.value.toString().contains(generatedNsec))
        assertNull(state.session.value)
    }

    @Test
    fun savedAppRelaySettingsDriveNotePublishRelays() = runBlocking {
        val client = AcceptingGeneratedIdentityClient()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                relaySettings = RelaySettingsStore(),
            ),
        )

        assertTrue(state.saveRelays(listOf("wss://relay.one.example", " WSS://Relay.Two.Example/ ")))
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        assertTrue(state.save(existing = null, markdown = "relay settings publish note"))

        assertEquals(listOf(listOf("wss://relay.one.example", "wss://relay.two.example")), client.publishedRelayBatches)
    }

    @Test
    fun successfulRelayTestAddsNormalizedNakedRelayWithoutCachingTestEvent() = runBlocking {
        val client = RelayTestClient()
        val cache = InMemoryLocalEventCache()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                localEventCache = cache,
            ),
        )

        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        val result = state.testRelayBeforeAdd("relay.example.com", emptyList())

        assertEquals(RelayAddResult.Added("wss://relay.example.com"), result)
        assertFalse(state.relayAddTestState.value.inProgress)
        assertNull(state.relayAddTestState.value.warning)
        assertEquals(1, client.published.size)
        assertTrue(cache.loadEvents(state.session.value?.publicKeyHex.orEmpty()).isEmpty())
        val testEvent = client.published.single()
        assertEquals(1, testEvent.kind)
        assertFalse(testEvent.content.contains("body_markdown"))
        assertFalse(testEvent.content.contains("nsec"))
    }

    @Test
    fun failedRelayTestRequiresUserChoiceBeforeAdding() = runBlocking {
        val client = RelayTestClient(
            publishStatus = RelayStatus(
                "wss://relay.example.com",
                writable = false,
                message = "stage=publish outcome=rejected secret=must-not-appear nsec1leak privateKey=leak body_markdown",
            ),
        )
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
            ),
        )

        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        val result = state.testRelayBeforeAdd("relay.example.com", emptyList())

        assertEquals(RelayAddResult.WaitingForUserChoice, result)
        val warning = state.relayAddTestState.value.warning ?: error("Missing relay warning")
        assertEquals("wss://relay.example.com", warning.relayUrl)
        assertTrue(warning.safeReason.contains("Relay rejected"))
        assertFalse(warning.safeReason.contains("must-not-appear"))
        assertFalse(warning.safeReason.contains("nsec1leak"))
        assertFalse(warning.safeReason.contains("privateKey=leak"))
        assertFalse(warning.safeReason.contains("body_markdown"))

        state.cancelFailedRelayAdd()
        assertNull(state.relayAddTestState.value.warning)

        state.testRelayBeforeAdd("relay.example.com", emptyList())
        assertEquals("wss://relay.example.com", state.continueFailedRelayAdd())
        assertNull(state.relayAddTestState.value.warning)
    }

    @Test
    fun noMatchingRelayTestEventWarnsInsteadOfAdding() = runBlocking {
        val client = RelayTestClient(returnPublishedEvent = false)
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
            ),
        )

        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        val result = state.testRelayBeforeAdd("relay.example.com", emptyList())

        assertEquals(RelayAddResult.WaitingForUserChoice, result)
        val warning = state.relayAddTestState.value.warning ?: error("Missing relay warning")
        assertTrue(warning.safeReason.contains("No matching test event"))
    }

    @Test
    fun relayTestFallsBackToReadConnectWhenNoSessionKeyIsAvailable() = runBlocking {
        val client = RelayTestClient()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
            ),
        )

        val result = state.testRelayBeforeAdd("relay.example.com", emptyList())

        assertEquals(RelayAddResult.Added("wss://relay.example.com"), result)
        assertTrue(client.published.isEmpty())
        assertEquals(1, client.fetchFilters.size)
    }

    @Test
    fun duplicateRelayAddDoesNotRunRelayTest() = runBlocking {
        val client = RelayTestClient()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
            ),
        )

        val result = state.testRelayBeforeAdd("relay.example.com", listOf("wss://relay.example.com"))

        assertEquals(RelayAddResult.Duplicate("wss://relay.example.com"), result)
        assertTrue(client.published.isEmpty())
        assertTrue(client.fetchFilters.isEmpty())
    }

    @Test
    fun relayAddPreventsDuplicateSubmissionWhileTestIsRunning() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = OfflineNostrClient(),
                relayTester = BlockingRelayTester(gate),
            ),
        )

        val first = async { state.testRelayBeforeAdd("relay.example.com", emptyList()) }
        while (!state.relayAddTestState.value.inProgress) yield()

        assertEquals(RelayAddResult.InProgress, state.testRelayBeforeAdd("relay.two.example", emptyList()))

        gate.complete(Unit)
        assertEquals(RelayAddResult.Added("wss://relay.example.com"), first.await())
    }
}

private class TestSignerPublicKeyRequester(
    private val result: SignerPublicKeyRequestResult,
) : NostrSignerPublicKeyRequester {
    override fun requestPublicKey(onResult: (SignerPublicKeyRequestResult) -> Unit) {
        onResult(result)
    }
}

private class TestSignerEventSigner(
    private val sign: (NostrEvent) -> SignEventRequestResult,
) : NostrSignerEventSigner {
    override fun signEvent(
        unsignedEvent: NostrEvent,
        currentUserPubkey: String,
        signerPackage: String?,
        onResult: (SignEventRequestResult) -> Unit,
    ) {
        onResult(sign(unsignedEvent))
    }
}

private class TestNip44Operator(
    private val encryptResult: SignerNip44OperationResult? = null,
) : NostrSignerNip44Operator {
    override fun encryptToSelf(
        plaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult =
        encryptResult ?: SignerNip44OperationResult.Encrypted("nip44-ciphertext", signerPackage)

    override fun decryptFromSelf(
        ciphertext: String,
        expectedPlaintext: String?,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult =
        SignerNip44OperationResult.Decrypted(expectedPlaintext.orEmpty(), signerPackage)
}

private object AvailableTestSignerProvider : NostrSignerProvider {
    override val mode: SignerMode = SignerMode.ExternalSigner
    override val isAvailable: Boolean = true
    override val unavailableReason: String? = null
    override val displayName: String = "Test NIP-55 Signer"
    override val canGetPublicKey: Boolean = true
    override val canSignEvent: Boolean = true
    override val canNip44EncryptDecrypt: Boolean = true
    override val safeDiagnostics: List<String> = listOf("safe test signer available")
}

private class GeneratedIdentityTestCrypto : NostrCrypto {
    override val productionReady: Boolean = true
    private var counter = 1
    private val plaintextByCiphertext = mutableMapOf<String, String>()

    override fun generatePrivateKey(): Result<NostrPrivateKey> = Result.success(
        NostrPrivateKey((counter++).toString(16).padStart(64, '0')),
    )

    override fun encodeNsec(privateKey: NostrPrivateKey): Result<String> =
        Result.success("nsec-test-${privateKey.hex}")

    override fun encodeNpub(publicKey: NostrPublicKey): Result<String> =
        Result.success("npub-test-${publicKey.hex.take(16)}")

    override fun decodeNsec(nsec: String): KeyDecodeResult =
        if (nsec.startsWith("nsec-test-")) {
            KeyDecodeResult.Valid(NostrPrivateKey(nsec.removePrefix("nsec-test-")))
        } else {
            KeyDecodeResult.Invalid("Invalid generated test nsec")
        }

    override fun derivePublicKey(privateKey: NostrPrivateKey): Result<NostrPublicKey> {
        val publicHex = privateKey.hex.reversed()
        return Result.success(NostrPublicKey(publicHex, "npub-test-${publicHex.take(16)}"))
    }

    override fun encryptToSelf(
        plaintext: String,
        privateKey: NostrPrivateKey,
        publicKey: NostrPublicKey,
    ): Result<String> = runCatching {
        val ciphertext = "cipher-${plaintext.hashCode()}-${plaintext.length}"
        plaintextByCiphertext[ciphertext] = plaintext
        ciphertext
    }

    override fun decryptFromSelf(
        ciphertext: String,
        privateKey: NostrPrivateKey,
        publicKey: NostrPublicKey,
    ): Result<String> =
        plaintextByCiphertext[ciphertext]?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException("missing ciphertext"))

    override fun computeEventId(unsigned: UnsignedNostrEvent): Result<String> =
        Result.success("event-${unsigned.pubkey.take(8)}-${unsigned.createdAt}-${unsigned.content.hashCode()}")

    override fun sign(unsigned: UnsignedNostrEvent, privateKey: NostrPrivateKey): Result<NostrEvent> =
        Result.success(
            NostrEvent(
                id = computeEventId(unsigned).getOrThrow(),
                pubkey = unsigned.pubkey,
                createdAt = unsigned.createdAt,
                kind = unsigned.kind,
                tags = unsigned.tags,
                content = unsigned.content,
                sig = "valid",
            ),
        )

    override fun validate(event: NostrEvent): Result<Boolean> = Result.success(event.sig == "valid")
}

private class AcceptingGeneratedIdentityClient : NostrClient {
    val publishedRelayBatches = mutableListOf<List<String>>()

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(emptyList(), relays.map { RelayStatus(it, readable = true, message = "ok") })

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult {
        publishedRelayBatches += relays
        return RelayPublishResult(relays.map { RelayStatus(it, writable = true, message = "accepted") })
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}

private class RelayTestClient(
    private val publishStatus: RelayStatus? = null,
    private val fetchReadable: Boolean = true,
    private val returnPublishedEvent: Boolean = true,
) : NostrClient {
    val published = mutableListOf<NostrEvent>()
    val fetchFilters = mutableListOf<NostrFilter>()

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(emptyList(), relays.map { RelayStatus(it, readable = true, message = "ok") })

    override suspend fun fetchEvents(relays: List<String>, filter: NostrFilter): RelayFetchResult {
        fetchFilters += filter
        val events = if (returnPublishedEvent) published else emptyList()
        return RelayFetchResult(
            events = events,
            statuses = relays.map {
                RelayStatus(it, readable = fetchReadable, message = if (fetchReadable) "stage=fetch outcome=complete" else "stage=fetch outcome=timeout")
            },
        )
    }

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult {
        published += event
        return RelayPublishResult(
            relays.map { relay ->
                publishStatus?.copy(url = relay) ?: RelayStatus(relay, writable = true, message = "stage=publish outcome=accepted")
            },
        )
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}

private class BlockingRelayTester(
    private val gate: CompletableDeferred<Unit>,
) : RelayTester {
    override suspend fun testAppRelay(relayUrl: String, session: com.libertasprimordium.othernote.domain.UserSession?): RelayTestResult {
        gate.await()
        return RelayTestResult.Success(relayUrl, mode = "test")
    }
}
