package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.ui.AppMode
import com.libertasprimordium.othernote.ui.AppPlatform
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.AppState
import com.libertasprimordium.othernote.ui.GeneratedIdentityStep
import com.libertasprimordium.othernote.ui.RelayAddResult
import com.libertasprimordium.othernote.ui.RelaySettingsRefreshResult
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
import com.libertasprimordium.othernote.domain.RelayConfig
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
import com.libertasprimordium.othernote.sync.RelayListKind
import com.libertasprimordium.othernote.sync.selectLatestSignedEncryptedNoteEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
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

        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        assertTrue(state.saveRelays(listOf("wss://relay.one.example", " WSS://Relay.Two.Example/ ")))
        assertTrue(state.save(existing = null, markdown = "relay settings publish note"))

        assertEquals(listOf("wss://relay.one.example", "wss://relay.two.example"), client.publishedRelayBatches.last())
        assertEquals(NoteKind, client.published.last().kind)
    }

    @Test
    fun syncImportsPublishedWriteRelaysBeforeFetchingNotes() = runBlocking {
        val privateKey = "1".padStart(64, '0')
        val publicKey = privateKey.reversed()
        val relayList = NostrEvent(
            id = "relay-list",
            pubkey = publicKey,
            createdAt = 10,
            kind = RelayListKind,
            tags = listOf(
                listOf("r", "wss://published-write.example", "write"),
                listOf("r", "wss://published-both.example"),
                listOf("r", "wss://published-read.example", "read"),
            ),
            content = "",
            sig = "valid",
        )
        val client = MigrationRelayClient(relayListEvents = listOf(relayList))
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://local.example"))),
            ),
        )

        assertTrue(state.login("nsec-test-$privateKey"))
        state.sync()

        assertEquals(listOf("wss://published-write.example", "wss://published-both.example"), state.relaySettings.normalizedUrls())
        assertEquals(listOf("wss://published-write.example", "wss://published-both.example"), client.fetchRelayBatches.single())
    }

    @Test
    fun relaySettingsRefreshImportsPublishedWriteRelaysWhenSignedIn() = runBlocking {
        val privateKey = "1".padStart(64, '0')
        val publicKey = privateKey.reversed()
        val client = MigrationRelayClient(
            relayListEvents = listOf(
                relayListEvent(
                    pubkey = publicKey,
                    tags = listOf(
                        listOf("r", "wss://amethyst-write.example", "write"),
                        listOf("r", "wss://amethyst-both.example"),
                        listOf("r", "wss://amethyst-read.example", "read"),
                    ),
                ),
            ),
        )
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://local.example"))),
            ),
        )

        assertTrue(state.login("nsec-test-$privateKey"))
        val result = state.refreshPublishedRelayListForSettings()
        assertTrue(result is RelaySettingsRefreshResult.PublishedListAvailable)
        assertTrue(state.applyPublishedRelayListFromSettings(result.relays))

        assertEquals(listOf("wss://local.example"), client.fetchEventRelayBatches.single())
        assertEquals(listOf("wss://amethyst-write.example", "wss://amethyst-both.example"), state.relaySettings.normalizedUrls())
    }

    @Test
    fun relaySettingsRefreshLeavesLocalRelaysWhenNoPublishedListOrFetchFails() = runBlocking {
        val privateKey = "1".padStart(64, '0')
        val noListState = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = MigrationRelayClient(),
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://local.example"))),
            ),
        )
        assertTrue(noListState.login("nsec-test-$privateKey"))

        assertTrue(noListState.refreshPublishedRelayListForSettings() is RelaySettingsRefreshResult.NoChange)
        assertEquals(listOf("wss://local.example"), noListState.relaySettings.normalizedUrls())

        val failedState = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = MigrationRelayClient(unreadableRelays = setOf("wss://local.example")),
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://local.example"))),
            ),
        )
        assertTrue(failedState.login("nsec-test-$privateKey"))

        assertTrue(failedState.refreshPublishedRelayListForSettings() is RelaySettingsRefreshResult.NoChange)
        assertEquals(listOf("wss://local.example"), failedState.relaySettings.normalizedUrls())
    }

    @Test
    fun relaySettingsRefreshDoesNotOverwriteUnsavedEditsUnlessApplied() = runBlocking {
        val privateKey = "1".padStart(64, '0')
        val publicKey = privateKey.reversed()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = MigrationRelayClient(
                    relayListEvents = listOf(
                        relayListEvent(
                            pubkey = publicKey,
                            tags = listOf(listOf("r", "wss://published.example", "write")),
                        ),
                    ),
                ),
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://local.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-$privateKey"))

        val result = state.refreshPublishedRelayListForSettings()

        assertTrue(result is RelaySettingsRefreshResult.PublishedListAvailable)
        assertEquals(listOf("wss://local.example"), state.relaySettings.normalizedUrls())
    }

    @Test
    fun relaySettingsRefreshSkipsWhileMigrationIsInProgress() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = BlockingMigrationRelayClient(gate),
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://old.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        assertTrue(state.save(existing = null, markdown = "migration skip refresh note"))
        val migration = async { state.saveRelays(listOf("wss://old.example", "wss://new.example")) }
        withTimeout(5_000) {
            while (!state.relayMigrationState.value.inProgress) yield()
        }

        val result = state.refreshPublishedRelayListForSettings()

        assertTrue(result is RelaySettingsRefreshResult.Skipped)
        gate.complete(Unit)
        migration.await()
        Unit
    }

    @Test
    fun manualRelaySyncRepublishesCachedNotesToImportedPublishedRelays() = runBlocking {
        val privateKey = "1".padStart(64, '0')
        val publicKey = privateKey.reversed()
        val cache = InMemoryLocalEventCache()
        val client = MigrationRelayClient(
            relayListEvents = listOf(
                relayListEvent(
                    pubkey = publicKey,
                    tags = listOf(listOf("r", "wss://imported.example", "write")),
                ),
            ),
        )
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                localEventCache = cache,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://old.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-$privateKey"))
        assertTrue(state.save(existing = null, markdown = "manual migration imported relay note"))
        val savedEvent = cache.loadEvents(publicKey).single()
        val result = state.refreshPublishedRelayListForSettings()
        assertTrue(result is RelaySettingsRefreshResult.PublishedListAvailable)
        assertTrue(state.applyPublishedRelayListFromSettings(result.relays))
        val publishCountBeforeManualSync = client.published.size

        assertTrue(state.syncCurrentRelays())

        assertEquals(listOf("wss://imported.example"), state.relaySettings.normalizedUrls())
        assertEquals(listOf("wss://imported.example"), client.fetchRelayBatches.last())
        assertEquals(savedEvent.id, client.published.drop(publishCountBeforeManualSync).single().id)
        assertEquals(listOf("wss://imported.example"), client.publishedRelayBatches.last())
        assertEquals("Published all events", state.syncState.value.relayStatuses.single { it.url == "wss://imported.example" }.message)
        assertReadableRelayStatus(state.syncState.value.relayStatuses.single { it.url == "wss://imported.example" }.message)
        assertFalse(client.published.last().content.contains("manual migration imported relay note"))
        assertFalse(client.published.last().content.contains("body_markdown"))
    }

    @Test
    fun manualRelaySyncIsBlockedSafelyWhenSignedOut() = runBlocking {
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = MigrationRelayClient(),
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://relay.example"))),
            ),
        )

        assertFalse(state.syncCurrentRelays())

        assertTrue(state.message.value.contains("Sign in"))
    }

    @Test
    fun manualRelaySyncCompletesCleanlyWhenNoNotesExist() = runBlocking {
        val client = MigrationRelayClient()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://relay.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))

        assertTrue(state.syncCurrentRelays())

        assertEquals("No encrypted notes to migrate.", state.message.value)
        assertEquals(listOf("wss://relay.example"), client.fetchRelayBatches.single())
        assertTrue(client.published.isEmpty())
    }

    @Test
    fun manualRelaySyncRepublishesLatestTombstoneState() = runBlocking {
        val cache = InMemoryLocalEventCache()
        val client = MigrationRelayClient()
        val crypto = GeneratedIdentityTestCrypto()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = crypto,
                client = client,
                localEventCache = cache,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://relay.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        val session = state.session.value ?: error("missing session")
        assertTrue(state.save(existing = null, markdown = "manual migration deleted note"))
        val note = state.notes.notes.value.single()
        assertTrue(state.delete(note))
        val latest = selectLatestSignedEncryptedNoteEvents(cache.loadEvents(session.publicKeyHex), session.publicKeyHex, crypto).single()
        val publishCountBeforeManualSync = client.published.size

        assertTrue(state.syncCurrentRelays())

        assertEquals(latest.id, client.published.drop(publishCountBeforeManualSync).single().id)
        assertEquals(listOf("wss://relay.example"), client.publishedRelayBatches.last())
        assertFalse(client.published.last().content.contains("manual migration deleted note"))
    }

    @Test
    fun manualRelaySyncWarnsAndQueuesFailedRelayWrites() = runBlocking {
        val pending = InMemoryPendingWriteStore()
        val cache = InMemoryLocalEventCache()
        val client = MigrationRelayClient(rejectedRelays = setOf("wss://reject.example"))
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                localEventCache = cache,
                pendingWriteStore = pending,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://ok.example"), RelayConfig("wss://reject.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        val session = state.session.value ?: error("missing session")
        assertTrue(state.save(existing = null, markdown = "manual migration rejected relay note"))
        pending.loadPendingWrites(session.publicKeyHex).forEach { pending.removeCompletedWrite(it.event.id) }

        assertFalse(state.syncCurrentRelays())

        assertNotNull(state.relayMigrationState.value.warning)
        assertEquals("Rejected writes", state.syncState.value.relayStatuses.single { it.url == "wss://reject.example" }.message)
        assertReadableRelayStatus(state.syncState.value.relayStatuses.single { it.url == "wss://reject.example" }.message)
        assertEquals(listOf("wss://ok.example", "wss://reject.example"), state.relaySettings.normalizedUrls())
        assertTrue(state.continueRelayMigration())
        val queued = pending.loadPendingWrites(session.publicKeyHex)
        assertEquals(1, queued.size)
        assertEquals(listOf("wss://reject.example"), queued.single().targetRelays)
        assertFalse(queued.single().event.content.contains("manual migration rejected relay note"))
        assertFalse(queued.single().event.content.contains("body_markdown"))
    }

    @Test
    fun relayMigrationRepublishesLatestCachedEncryptedEventsToAddedRelays() = runBlocking {
        val client = MigrationRelayClient()
        val cache = InMemoryLocalEventCache()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                localEventCache = cache,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://old.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        assertTrue(state.save(existing = null, markdown = "relay migration note"))
        val savedEvent = cache.loadEvents(state.session.value?.publicKeyHex.orEmpty()).single()
        val publishCountBeforeMigration = client.published.size

        assertTrue(state.saveRelays(listOf("wss://old.example", "wss://new.example")))

        assertEquals(listOf("wss://old.example", "wss://new.example"), state.relaySettings.normalizedUrls())
        assertEquals(RelayListKind, client.published[publishCountBeforeMigration].kind)
        assertEquals(listOf("wss://old.example", "wss://new.example"), client.publishedRelayBatches[publishCountBeforeMigration])
        assertEquals(listOf("wss://new.example"), client.publishedRelayBatches.last())
        assertEquals(savedEvent.id, client.published.last().id)
        assertEquals("Published all events", state.syncState.value.relayStatuses.single { it.url == "wss://new.example" }.message)
        assertReadableRelayStatus(state.syncState.value.relayStatuses.single { it.url == "wss://new.example" }.message)
        assertFalse(client.published.last().content.contains("relay migration note"))
        assertTrue(state.relayMigrationState.value.warning == null)
    }

    @Test
    fun relayMigrationFetchesRemovedRelaysBeforePersistingRequestedSettings() = runBlocking {
        val client = MigrationRelayClient()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://old.example"), RelayConfig("wss://stay.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))

        assertTrue(state.saveRelays(listOf("wss://stay.example")))

        assertEquals(listOf("wss://old.example", "wss://stay.example"), client.fetchRelayBatches.single())
        assertEquals(listOf("wss://stay.example"), state.relaySettings.normalizedUrls())
    }

    @Test
    fun relayMigrationWarningCanCancelWithoutChangingSettings() = runBlocking {
        val client = MigrationRelayClient(unreadableRelays = setOf("wss://old.example"))
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://old.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))

        assertFalse(state.saveRelays(listOf("wss://new.example")))

        val warning = assertNotNull(state.relayMigrationState.value.warning)
        assertEquals("Some relays did not respond", warning.title)
        assertTrue(warning.body.contains("could not be reached"))
        assertPlainRelayMigrationWarning(warning.title, warning.body)
        assertTrue(warning.details.contains("stage=fetch"))
        assertEquals("Timed out", state.syncState.value.relayStatuses.single { it.url == "wss://old.example" }.message)
        assertReadableRelayStatus(state.syncState.value.relayStatuses.single { it.url == "wss://old.example" }.message)
        assertEquals(listOf("wss://old.example"), state.relaySettings.normalizedUrls())
        state.cancelRelayMigrationWarning()
        assertEquals(listOf("wss://old.example"), state.relaySettings.normalizedUrls())
        assertNull(state.relayMigrationState.value.warning)
    }

    @Test
    fun relayMigrationContinuePersistsSettingsAndQueuesFailedEncryptedRepublish() = runBlocking {
        val pending = InMemoryPendingWriteStore()
        val cache = InMemoryLocalEventCache()
        val client = MigrationRelayClient(rejectedRelays = setOf("wss://new.example"))
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                localEventCache = cache,
                pendingWriteStore = pending,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://old.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        val session = state.session.value ?: error("missing session")
        assertTrue(state.save(existing = null, markdown = "pending migration note"))
        pending.loadPendingWrites(session.publicKeyHex).forEach { pending.removeCompletedWrite(it.event.id) }

        assertFalse(state.saveRelays(listOf("wss://old.example", "wss://new.example")))
        assertEquals(listOf("wss://old.example"), state.relaySettings.normalizedUrls())
        val warning = assertNotNull(state.relayMigrationState.value.warning)
        assertEquals("Some relays rejected the update", warning.title)
        assertTrue(warning.body.contains("could not publish"))
        assertPlainRelayMigrationWarning(warning.title, warning.body)
        assertTrue(warning.details.contains("stage=publish"))
        assertEquals("Rejected writes", state.syncState.value.relayStatuses.single { it.url == "wss://new.example" }.message)
        assertReadableRelayStatus(state.syncState.value.relayStatuses.single { it.url == "wss://new.example" }.message)

        assertTrue(state.continueRelayMigration())

        assertEquals(listOf("wss://old.example", "wss://new.example"), state.relaySettings.normalizedUrls())
        val queued = pending.loadPendingWrites(session.publicKeyHex)
        assertEquals(1, queued.size)
        assertEquals(listOf("wss://new.example"), queued.single().targetRelays)
        assertFalse(queued.single().event.content.contains("pending migration note"))
        assertFalse(queued.single().event.content.contains("body_markdown"))
    }

    @Test
    fun relayMigrationStatusShowsPartialPublishCounts() = runBlocking {
        val cache = InMemoryLocalEventCache()
        val client = MigrationRelayClient()
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                localEventCache = cache,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://old.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        assertTrue(state.save(existing = null, markdown = "partial status one"))
        assertTrue(state.save(existing = null, markdown = "partial status two"))
        val events = cache.loadEvents(state.session.value?.publicKeyHex.orEmpty())
        client.rejectedNoteEventIds += events.first().id

        assertFalse(state.saveRelays(listOf("wss://old.example", "wss://new.example")))

        val message = state.syncState.value.relayStatuses.single { it.url == "wss://new.example" }.message
        assertEquals("Published 1 of 2 events", message)
        assertReadableRelayStatus(message)
    }

    @Test
    fun relayMigrationWarningDistinguishesRelayListPublishFailureFromContentSuccess() = runBlocking {
        val cache = InMemoryLocalEventCache()
        val client = MigrationRelayClient(rejectedRelayListRelays = setOf("wss://new.example"))
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                localEventCache = cache,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://old.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        assertTrue(state.save(existing = null, markdown = "relay-list warning note"))

        assertFalse(state.saveRelays(listOf("wss://old.example", "wss://new.example")))

        val warning = assertNotNull(state.relayMigrationState.value.warning)
        assertTrue(warning.body.contains("encrypted notes were migrated"))
        assertTrue(warning.body.contains("could not publish the updated relay list"))
        assertPlainRelayMigrationWarning(warning.title, warning.body)
    }

    @Test
    fun relayMigrationWarningDistinguishesContentMigrationFailureFromRelayListSuccess() = runBlocking {
        val cache = InMemoryLocalEventCache()
        val client = MigrationRelayClient(rejectedNoteRelays = setOf("wss://new.example"))
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = client,
                localEventCache = cache,
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://old.example"))),
            ),
        )
        assertTrue(state.login("nsec-test-${"1".padStart(64, '0')}"))
        assertTrue(state.save(existing = null, markdown = "content warning note"))

        assertFalse(state.saveRelays(listOf("wss://old.example", "wss://new.example")))

        val warning = assertNotNull(state.relayMigrationState.value.warning)
        assertTrue(warning.body.contains("relay list was updated"))
        assertTrue(warning.body.contains("could not copy all encrypted note events"))
        assertPlainRelayMigrationWarning(warning.title, warning.body)
    }

    @Test
    fun relayMigrationWithoutSignedInSessionRequiresExplicitContinue() = runBlocking {
        val state = AppState(
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = GeneratedIdentityTestCrypto(),
                client = MigrationRelayClient(),
                relaySettings = RelaySettingsStore(initialRelays = listOf(RelayConfig("wss://old.example"))),
            ),
        )

        assertFalse(state.saveRelays(listOf("wss://new.example")))
        assertEquals(listOf("wss://old.example"), state.relaySettings.normalizedUrls())
        assertNotNull(state.relayMigrationState.value.warning)

        assertTrue(state.continueRelayMigration())
        assertEquals(listOf("wss://new.example"), state.relaySettings.normalizedUrls())
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
        assertTrue(warning.safeReason.contains("publish and fetch events"))
        assertPlainRelayMigrationWarning("Relay test failed", warning.safeReason)
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
        assertTrue(warning.safeReason.contains("publish and fetch events"))
        assertPlainRelayMigrationWarning("Relay test failed", warning.safeReason)
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
    val published = mutableListOf<NostrEvent>()

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult =
        RelayFetchResult(emptyList(), relays.map { RelayStatus(it, readable = true, message = "ok") })

    override suspend fun fetchEvents(relays: List<String>, filter: NostrFilter): RelayFetchResult =
        RelayFetchResult(emptyList(), relays.map { RelayStatus(it, readable = true, message = "ok") })

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult {
        publishedRelayBatches += relays
        published += event
        return RelayPublishResult(relays.map { RelayStatus(it, writable = true, message = "accepted") })
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}

private class MigrationRelayClient(
    private val rejectedRelays: Set<String> = emptySet(),
    private val rejectedRelayListRelays: Set<String> = emptySet(),
    private val rejectedNoteRelays: Set<String> = emptySet(),
    private val unreadableRelays: Set<String> = emptySet(),
    private val fetchedEvents: List<NostrEvent> = emptyList(),
    private val relayListEvents: List<NostrEvent> = emptyList(),
) : NostrClient {
    val rejectedNoteEventIds = mutableSetOf<String>()
    val publishedRelayBatches = mutableListOf<List<String>>()
    val published = mutableListOf<NostrEvent>()
    val fetchRelayBatches = mutableListOf<List<String>>()
    val fetchEventRelayBatches = mutableListOf<List<String>>()

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult {
        fetchRelayBatches += relays
        return RelayFetchResult(
            events = fetchedEvents.filter { it.pubkey == authorPubkey },
            statuses = relays.map { relay ->
                RelayStatus(
                    url = relay,
                    readable = relay !in unreadableRelays,
                    message = if (relay in unreadableRelays) "stage=fetch outcome=timeout" else "stage=fetch outcome=complete",
                )
            },
        )
    }

    override suspend fun fetchEvents(relays: List<String>, filter: NostrFilter): RelayFetchResult {
        fetchEventRelayBatches += relays
        return RelayFetchResult(
            events = relayListEvents.filter { event ->
                (filter.authors.isEmpty() || event.pubkey in filter.authors) &&
                    (filter.kinds.isEmpty() || event.kind in filter.kinds)
            },
            statuses = relays.map { relay ->
                RelayStatus(
                    url = relay,
                    readable = relay !in unreadableRelays,
                    message = if (relay in unreadableRelays) "stage=fetch_events outcome=timeout" else "stage=fetch_events outcome=complete",
                )
            },
        )
    }

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult {
        publishedRelayBatches += relays
        published += event
        return RelayPublishResult(
            relays.map { relay ->
                val rejected = relay in rejectedRelays ||
                    (event.kind == RelayListKind && relay in rejectedRelayListRelays) ||
                    (event.kind == NoteKind && relay in rejectedNoteRelays) ||
                    (event.kind == NoteKind && event.id in rejectedNoteEventIds)
                RelayStatus(
                    url = relay,
                    writable = !rejected,
                    message = if (rejected) "stage=publish outcome=rejected" else "stage=publish outcome=accepted",
                )
            },
        )
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}

private class BlockingMigrationRelayClient(
    private val gate: CompletableDeferred<Unit>,
) : NostrClient {
    val published = mutableListOf<NostrEvent>()

    override suspend fun fetchNotes(relays: List<String>, authorPubkey: String): RelayFetchResult {
        gate.await()
        return RelayFetchResult(emptyList(), relays.map { RelayStatus(it, readable = true, message = "stage=fetch outcome=complete") })
    }

    override suspend fun fetchEvents(relays: List<String>, filter: NostrFilter): RelayFetchResult =
        RelayFetchResult(emptyList(), relays.map { RelayStatus(it, readable = true, message = "stage=fetch_events outcome=complete") })

    override suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult {
        published += event
        return RelayPublishResult(relays.map { RelayStatus(it, writable = true, message = "stage=publish outcome=accepted") })
    }

    override suspend fun fetchProfile(relays: List<String>, pubkey: String): ProfileMetadata? = null
}

private fun relayListEvent(
    pubkey: String,
    tags: List<List<String>>,
    id: String = "relay-list",
    createdAt: Long = 10,
): NostrEvent =
    NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = RelayListKind,
        tags = tags,
        content = "",
        sig = "valid",
    )

private fun assertPlainRelayMigrationWarning(title: String, body: String) {
    val visible = "$title\n$body"
    listOf(
        "stage=",
        "publish_accepted_count",
        "candidate_events",
        "migration_fetch_failed",
        "relay_migration_result",
        "fetch_relays=",
        "publish_statuses=",
    ).forEach { rawKey ->
        assertFalse(visible.contains(rawKey), "Visible warning should not contain raw diagnostic key $rawKey")
    }
}

private fun assertReadableRelayStatus(message: String) {
    listOf(
        "stage=",
        "outcome=",
        "publish_accepted_count",
        "candidate_events",
        "migration_fetch_failed",
    ).forEach { rawKey ->
        assertFalse(message.contains(rawKey), "Visible relay status should not contain raw diagnostic key $rawKey")
    }
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
