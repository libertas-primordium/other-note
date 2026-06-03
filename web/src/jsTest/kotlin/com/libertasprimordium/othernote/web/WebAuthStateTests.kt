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
        assertEquals(listOf("Reload notes", "Note relays", "Theme", "About web preview", "Logout"), WebSignedInMenuItems)
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

class WebThemeTests {
    @Test
    fun builtInThemesUseStableNativeIdsAndLabels() {
        assertEquals(
            listOf("nostr-classic", "urban", "hacker", "papyrus", "harbor", "daylight", "burgundy"),
            BuiltInWebThemes.map { it.id },
        )
        assertEquals("Nostr Classic", DefaultWebTheme.label)
        assertTrue(BuiltInWebThemes.all { it.label.isNotBlank() })
    }

    @Test
    fun validThemeIdsAreAcceptedAndInvalidIdsDefaultSafely() {
        assertEquals("urban", validWebThemeIdOrNull(" urban "))
        assertEquals("hacker", webThemeForId("hacker").id)
        assertEquals(DefaultWebTheme.id, webThemeForId("missing").id)
        assertNull(validWebThemeIdOrNull(""))
        assertNull(validWebThemeIdOrNull(null))
        assertNull(validWebThemeIdOrNull("nsec-or-pubkey-like-value"))
    }

    @Test
    fun themePreferenceKeyIsGenericAndNotAccountScoped() {
        assertEquals("on.web.theme", WebThemePreferenceKey)
        listOf("pubkey", "npub", "nsec", "bunker", "signer", "relay", "note", "event", "profile", "secret").forEach { forbidden ->
            assertTrue(!WebThemePreferenceKey.contains(forbidden))
        }
    }

    @Test
    fun themePreferenceLoadsValidStoredValueAndDefaultsInvalidOrBlankValues() {
        assertEquals("papyrus", loadWebThemePreference(FakeThemeStorage(readValue = "papyrus")))
        assertEquals(DefaultWebTheme.id, loadWebThemePreference(FakeThemeStorage(readValue = "invalid")))
        assertEquals(DefaultWebTheme.id, loadWebThemePreference(FakeThemeStorage(readValue = "")))
        assertEquals(DefaultWebTheme.id, loadWebThemePreference(null))
    }

    @Test
    fun themePreferenceStorageFailuresFallBackWithoutCrashing() {
        assertEquals(DefaultWebTheme.id, loadWebThemePreference(ThrowingThemeStorage))
        assertEquals("urban", saveWebThemePreference(ThrowingThemeStorage, "urban"))
    }

    @Test
    fun saveThemePreferenceWritesOnlyTheAllowedKeyAndValidThemeIds() {
        val validStorage = FakeThemeStorage()
        val invalidStorage = FakeThemeStorage()

        assertEquals("hacker", saveWebThemePreference(validStorage, "hacker"))
        assertEquals(DefaultWebTheme.id, saveWebThemePreference(invalidStorage, "not-a-theme"))

        assertEquals(listOf(WebThemePreferenceKey to "hacker"), validStorage.writes)
        assertEquals(listOf(WebThemePreferenceKey to DefaultWebTheme.id), invalidStorage.writes)
    }

    private class FakeThemeStorage(
        private val readValue: String? = null,
    ) : WebThemePreferenceStorage {
        val writes = mutableListOf<Pair<String, String>>()

        override fun read(key: String): String? {
            assertEquals(WebThemePreferenceKey, key)
            return readValue
        }

        override fun write(key: String, value: String) {
            assertEquals(WebThemePreferenceKey, key)
            assertTrue(value in AllowedWebThemeIds)
            writes += key to value
        }
    }

    private object ThrowingThemeStorage : WebThemePreferenceStorage {
        override fun read(key: String): String? {
            throw IllegalStateException("storage unavailable")
        }

        override fun write(key: String, value: String) {
            throw IllegalStateException("storage unavailable")
        }
    }
}

class WebDirectKeyFoundationTests {
    @Test
    fun signInInfoTopicsCoverSensitiveWebLoginMethods() {
        assertEquals(
            listOf(
                WebSignInInfoTopic.Nip07,
                WebSignInInfoTopic.Nip46,
                WebSignInInfoTopic.RememberedNip46,
                WebSignInInfoTopic.DirectNsec,
                WebSignInInfoTopic.GeneratedIdentity,
            ),
            WebSignInInfoTopics,
        )
        WebSignInInfoTopics.forEach { topic ->
            val copy = webSignInInfoCopy(topic)
            assertTrue(copy.title.isNotBlank())
            assertTrue(copy.body.isNotEmpty())
            assertTrue(copy.body.all { it.isNotBlank() })
        }
    }

    @Test
    fun signInInfoCopyPreservesRequiredSafetyWarnings() {
        val nip07 = webSignInInfoCopy(WebSignInInfoTopic.Nip07).body.joinToString(" ").lowercase()
        val nip46 = webSignInInfoCopy(WebSignInInfoTopic.Nip46).body.joinToString(" ").lowercase()
        val remembered = webSignInInfoCopy(WebSignInInfoTopic.RememberedNip46).body.joinToString(" ").lowercase()
        val direct = webSignInInfoCopy(WebSignInInfoTopic.DirectNsec).body.joinToString(" ").lowercase()
        val generated = webSignInInfoCopy(WebSignInInfoTopic.GeneratedIdentity).body.joinToString(" ").lowercase()

        assertTrue(nip07.contains("extension"))
        assertTrue(nip07.contains("does not receive your private key"))
        assertTrue(nip46.contains("remote signer"))
        assertTrue(nip46.contains("separate from note relays"))
        assertTrue(nip46.contains("plaintext note payloads"))
        assertTrue(remembered.contains("does not store your private key"))
        assertTrue(remembered.contains("communication session record"))
        assertTrue(remembered.contains("sensitive"))
        assertTrue(direct.contains("nsec is your private key"))
        assertTrue(direct.contains("does not save"))
        assertTrue(direct.contains("refreshing or logging out forgets"))
        assertTrue(generated.contains("generated nsec is the private key"))
        assertTrue(generated.contains("cannot recover"))
        assertTrue(generated.contains("losing access"))
    }

    @Test
    fun directNsecInputUsesPasswordStyleAndSafeBrowserHints() {
        assertEquals("Session-only nsec", DirectNsecInputLabel)
        assertEquals("password", DirectNsecInputType)
        assertEquals("off", DirectNsecInputAutocomplete)
        assertTrue(!DirectNsecInputPlaceholder.lowercase().contains("nsec1"))
        assertEquals("Use for this session", DirectNsecSubmitLabel)
    }

    @Test
    fun directNsecDraftUpdatesWithoutMessageAndClearsOnSubmitAttempt() {
        val raw = "nsec-like-sensitive-input"
        val updated = updateWebDirectNsecDraft(WebDirectNsecDraftState(message = "old error"), raw)
        val cleared = clearWebDirectNsecDraft(WebDirectKeyCopy.InvalidKey)

        assertEquals(raw, updated.input)
        assertEquals("", updated.message)
        assertEquals("", cleared.input)
        assertEquals(WebDirectKeyCopy.InvalidKey, cleared.message)
        assertTrue(!cleared.message.contains(raw))
    }

    @Test
    fun generatedIdentityAcknowledgementsGateSessionUseAndClearSecrets() {
        val raw = "generated-sensitive-value"
        val secret = WebGeneratedIdentitySecret.create(raw, "01".repeat(32))
        val generated = WebGeneratedIdentityState(secret = secret)
        val recoverAcknowledged = acknowledgeWebGeneratedIdentityRecovery(generated, true)
        val savedAcknowledged = acknowledgeWebGeneratedIdentitySaved(recoverAcknowledged, true)
        val allAcknowledged = acknowledgeWebGeneratedIdentityLoss(savedAcknowledged, true)
        val cleared = clearWebGeneratedIdentityState(WebDirectKeyCopy.GeneratedIdentityCancelled)

        assertTrue(!generated.canUseForSession)
        assertTrue(!recoverAcknowledged.canUseForSession)
        assertTrue(!savedAcknowledged.canUseForSession)
        assertTrue(allAcknowledged.canUseForSession)
        assertTrue(!cleared.message.contains(raw))
        assertNull(cleared.secret)
        assertTrue(!secret.toString().contains(raw))
    }

    @Test
    fun generatedIdentityCreatesValidSessionOnlyDirectKeyIdentity() {
        val generated = assertIs<WebGeneratedIdentityResult.Success>(
            generateWebDirectKeyIdentity(),
        )
        val raw = generated.secret.revealNsec()
        val login = assertIs<WebDirectKeyLoginResult.Success>(
            createWebDirectKeySession(raw),
        )

        assertTrue(raw.startsWith("nsec1"))
        assertEquals(generated.secret.publicKeyHex, login.identity.publicKeyHex)
        assertEquals(WebAuthMethod.DirectNsec, login.identity.method)
        assertTrue(login.session.active)
        login.session.clear()
    }

    @Test
    fun validNsecCreatesSessionOnlyDirectKeyIdentity() {
        val result = assertIs<WebDirectKeyLoginResult.Success>(
            createWebDirectKeySession(throwawayNsec(lastByte = 1)),
        )

        assertEquals(WebAuthMethod.DirectNsec, result.identity.method)
        assertEquals(result.session.publicKeyHex, result.identity.publicKeyHex)
        assertEquals(64, result.identity.publicKeyHex.length)
        assertTrue(result.session.active)
    }

    @Test
    fun invalidNsecIsRejectedWithoutEchoingInput() {
        val raw = "nsec1-this-is-not-a-valid-key"
        val result = assertIs<WebDirectKeyLoginResult.Invalid>(
            createWebDirectKeySession(raw),
        )

        assertEquals(WebDirectKeyCopy.InvalidKey, result.safeMessage)
        assertTrue(!result.safeMessage.contains(raw))
    }

    @Test
    fun directKeyCanSignAndValidateEncryptedNoteEvent() {
        val session = assertIs<WebDirectKeyLoginResult.Success>(
            createWebDirectKeySession(throwawayNsec(lastByte = 2)),
        ).session
        val note = WebReadOnlyNote(
            id = "direct-note",
            createdAtMs = 1_000,
            updatedAtMs = 2_000,
            bodyMarkdown = "direct key note",
        )
        val plaintextPayload = encodeWebNotePayload(note).getOrThrow()
        val ciphertext = session.encryptToSelf(plaintextPayload).getOrThrow()
        val unsigned = buildUnsignedWebNoteEvent(note, session.publicKeyHex, ciphertext)
        val event = session.sign(unsigned).getOrThrow()

        assertTrue(ciphertext.isNotBlank())
        assertTrue(ciphertext != plaintextPayload)
        assertTrue(!ciphertext.contains(note.bodyMarkdown))
        assertTrue(session.validate(event).getOrThrow())
        assertTrue(validateWebSignedNoteEvent(note, event, session.publicKeyHex))
        assertEquals(plaintextPayload, session.decryptFromSelf(event.content).getOrThrow())
    }

    @Test
    fun directKeySignerPublishesEncryptedEventsWithoutPlaintextFallback() {
        val login = assertIs<WebDirectKeyLoginResult.Success>(
            createWebDirectKeySession(throwawayNsec(lastByte = 3)),
        )
        val publisher = CapturingPublisher()
        val service = WebNoteCrudService(
            publisher = publisher,
            noteIdGenerator = { "direct-publish-note" },
            nowMs = { 5_000 },
        )
        var result: WebNoteSaveResult? = null

        service.save(
            existing = null,
            bodyMarkdown = "plaintext must not be published",
            accountPubkey = login.identity.publicKeyHex,
            signer = WebDirectKeyNoteCrudSigner(login.session),
            onProgress = {},
            onResult = { result = it },
        )

        val published = assertIs<WebNoteSaveResult.Published>(result)
        val event = publisher.events.single()
        assertEquals(event, published.event)
        assertTrue(event.content != published.plaintextPayload)
        assertTrue(!event.content.contains("plaintext must not be published"))
        assertEquals(published.plaintextPayload, login.session.decryptFromSelf(event.content).getOrThrow())
    }

    @Test
    fun clearedDirectKeySessionCannotEncryptDecryptOrSign() {
        val login = assertIs<WebDirectKeyLoginResult.Success>(
            createWebDirectKeySession(throwawayNsec(lastByte = 4)),
        )
        val signer = WebDirectKeyNoteCrudSigner(login.session)
        val decryptor = WebDirectKeyNoteDecryptor(login.session)
        var encryptResult: WebSignerOperationResult? = null
        var signResult: WebNoteSignResult? = null
        var decryptResult: Result<String>? = null

        login.session.clear()
        signer.encrypt("payload") { encryptResult = it }
        signer.sign(
            WebUnsignedNoteEvent(
                pubkey = login.identity.publicKeyHex,
                createdAt = 1,
                kind = WebNoteKind,
                tags = webNoteEventTags("cleared"),
                content = "ciphertext",
            ),
        ) { signResult = it }
        decryptor.decrypt("ciphertext") { decryptResult = it }

        assertIs<WebSignerOperationResult.Failed>(encryptResult)
        assertIs<WebNoteSignResult.Failed>(signResult)
        assertTrue(decryptResult?.isFailure == true)
        assertTrue(!login.session.active)
    }

    @Test
    fun unavailableDirectKeyCryptoDoesNotCreatePublishableSession() {
        val result = assertIs<WebDirectKeyLoginResult.Unavailable>(
            createWebDirectKeySession(throwawayNsec(lastByte = 5), UnavailableDirectKeyCrypto),
        )

        assertEquals(WebDirectKeyCopy.CryptoUnavailable, result.safeMessage)
    }

    private class CapturingPublisher : WebNotePublisher {
        val events = mutableListOf<WebNostrEvent>()

        override fun publish(event: WebNostrEvent, onResult: (WebNotePublishResult) -> Unit) {
            events += event
            onResult(
                WebNotePublishResult(
                    statuses = listOf(WebNoteRelayStatus(url = "wss://relay.example", acceptedWrite = true)),
                ),
            )
        }

        override fun close() = Unit
    }

    private object UnavailableDirectKeyCrypto : WebDirectKeyCrypto {
        override val productionReady: Boolean = false

        override fun generatePrivateKey(): Result<Uint8Array> =
            Result.failure(UnsupportedOperationException("unavailable"))

        override fun decodeNsec(raw: String): Result<Uint8Array> =
            Result.failure(UnsupportedOperationException("unavailable"))

        override fun derivePublicKey(keyBytes: Uint8Array): Result<String> =
            Result.failure(UnsupportedOperationException("unavailable"))

        override fun encryptToSelf(plaintext: String, keyBytes: Uint8Array, publicKeyHex: String): Result<String> =
            Result.failure(UnsupportedOperationException("unavailable"))

        override fun decryptFromSelf(ciphertext: String, keyBytes: Uint8Array, publicKeyHex: String): Result<String> =
            Result.failure(UnsupportedOperationException("unavailable"))

        override fun sign(unsignedEvent: WebUnsignedNoteEvent, keyBytes: Uint8Array): Result<WebNostrEvent> =
            Result.failure(UnsupportedOperationException("unavailable"))

        override fun validate(event: WebNostrEvent): Result<Boolean> =
            Result.failure(UnsupportedOperationException("unavailable"))
    }

    private fun throwawayNsec(lastByte: Int): String =
        WebDirectKeyNip19.encode(
            hrp = "nsec",
            data = ByteArray(32).also { it[31] = lastByte.toByte() },
        ) ?: error("Could not encode throwaway test key")
}

class WebResponsiveNoteGridLayoutTests {
    @Test
    fun signedInShellUsesDistinctWideLayoutClass() {
        assertEquals("shell", WebSignedOutShellClass)
        assertEquals("shell signed-in-shell", WebSignedInShellClass)
        assertNotEquals(WebSignedOutShellClass, WebSignedInShellClass)
    }

    @Test
    fun noteGridUsesDesktopInspiredCardSizingAndSpacing() {
        assertEquals(280, WebNoteGridMinCardWidthPx)
        assertEquals(6, WebNoteGridGapPx)
    }

    @Test
    fun notePanelAndGridHaveDedicatedLayoutClasses() {
        assertEquals("panel notes-panel", WebNotesPanelClass)
        assertEquals("note-list note-lanes", WebNoteGridClass)
        assertEquals("note-lane", WebNoteLaneClass)
        assertEquals("inline-actions note-card-actions", WebNoteCardActionsClass)
    }

    @Test
    fun laneCountMatchesDesktopColumnBreakpoints() {
        assertEquals(1, webNoteLaneCount(319))
        assertEquals(2, webNoteLaneCount(320))
        assertEquals(2, webNoteLaneCount(719))
        assertEquals(2, webNoteLaneCount(720))
        assertEquals(3, webNoteLaneCount(840))
        assertEquals(4, webNoteLaneCount(1_200))
        assertEquals(6, webNoteLaneCount(2_400))
    }

    @Test
    fun emptyNotesDistributeAcrossRequestedLanes() {
        assertEquals(listOf(emptyList<String>(), emptyList()), distributeWebNoteLanes(emptyList<String>(), 2))
    }

    @Test
    fun oneColumnKeepsOriginalOrder() {
        assertEquals(listOf(listOf("n1", "n2", "n3")), distributeWebNoteLanes(listOf("n1", "n2", "n3"), 1))
    }

    @Test
    fun twoColumnsSeedFirstRowHorizontallyThenContinueRoundRobin() {
        assertEquals(
            listOf(listOf("n1", "n3", "n5"), listOf("n2", "n4")),
            distributeWebNoteLanes(listOf("n1", "n2", "n3", "n4", "n5"), 2),
        )
    }

    @Test
    fun threeColumnsSeedFirstRowHorizontallyThenContinueRoundRobin() {
        assertEquals(
            listOf(listOf("n1", "n4"), listOf("n2", "n5"), listOf("n3")),
            distributeWebNoteLanes(listOf("n1", "n2", "n3", "n4", "n5"), 3),
        )
    }

    @Test
    fun invalidLaneCountFallsBackToOneColumn() {
        assertEquals(listOf(listOf("n1", "n2")), distributeWebNoteLanes(listOf("n1", "n2"), 0))
    }
}

class WebNoteListControlsTests {
    @Test
    fun emptyAndWhitespaceSearchShowAllVisibleNotes() {
        val notes = listOf(
            note(id = "alpha", body = "Alpha"),
            note(id = "deleted", body = "Deleted", deleted = true),
            note(id = "bravo", body = "Bravo"),
        )

        assertEquals(listOf("alpha", "bravo"), filterVisibleWebNotesBySearchQuery(notes, "").map { it.id })
        assertEquals(listOf("alpha", "bravo"), filterVisibleWebNotesBySearchQuery(notes, "   ").map { it.id })
    }

    @Test
    fun searchIsTrimmedAndMatchesTitleCaseInsensitively() {
        val notes = listOf(
            note(id = "alpha", body = "Project Plan\nsecond line"),
            note(id = "bravo", body = "Other note"),
        )

        val result = filterVisibleWebNotesBySearchQuery(notes, "  project plan  ")

        assertEquals(listOf("alpha"), result.map { it.id })
    }

    @Test
    fun searchMatchesFullBodyIncludingLongUrlsAndJson() {
        val notes = listOf(
            note(id = "json", body = """{"version":"vpn-marketplace/1","listing":"30402:d9b129"}"""),
            note(id = "url", body = "remote signer\nbunker://token.example?relay=wss://relay.ditto.pub/&secret=redacted"),
            note(id = "plain", body = "ordinary prose"),
        )

        assertEquals(listOf("json"), filterVisibleWebNotesBySearchQuery(notes, "VPN-MARKETPLACE").map { it.id })
        assertEquals(listOf("url"), filterVisibleWebNotesBySearchQuery(notes, "relay.ditto.pub").map { it.id })
    }

    @Test
    fun noMatchSearchReturnsEmptyWithoutMutatingSourceList() {
        val notes = listOf(note(id = "alpha", body = "Alpha"))

        val result = filterVisibleWebNotesBySearchQuery(notes, "missing")

        assertTrue(result.isEmpty())
        assertEquals(listOf("alpha"), notes.map { it.id })
    }

    @Test
    fun lastEditedSortOrdersNewestAndOldestWithStableTies() {
        val notes = listOf(
            note(id = "first-tie", updatedAtMs = 10),
            note(id = "newest", updatedAtMs = 20),
            note(id = "second-tie", updatedAtMs = 10),
        )

        assertEquals(
            listOf("newest", "first-tie", "second-tie"),
            sortVisibleWebNotes(notes, webNoteSortOptionForId("last-edited-newest")).map { it.id },
        )
        assertEquals(
            listOf("first-tie", "second-tie", "newest"),
            sortVisibleWebNotes(notes, webNoteSortOptionForId("last-edited-oldest")).map { it.id },
        )
    }

    @Test
    fun createdSortOrdersNewestAndOldest() {
        val notes = listOf(
            note(id = "older", createdAtMs = 1),
            note(id = "newer", createdAtMs = 2),
        )

        assertEquals(
            listOf("newer", "older"),
            sortVisibleWebNotes(notes, webNoteSortOptionForId("created-newest")).map { it.id },
        )
        assertEquals(
            listOf("older", "newer"),
            sortVisibleWebNotes(notes, webNoteSortOptionForId("created-oldest")).map { it.id },
        )
    }

    @Test
    fun titleSortOrdersBlankTitlesLastAndSupportsDescending() {
        val notes = listOf(
            note(id = "zulu", body = "Zulu"),
            note(id = "blank", body = "\n\n"),
            note(id = "alpha", body = "Alpha"),
        )

        assertEquals(
            listOf("alpha", "zulu", "blank"),
            sortVisibleWebNotes(notes, webNoteSortOptionForId("title-a-z")).map { it.id },
        )
        assertEquals(
            listOf("zulu", "alpha", "blank"),
            sortVisibleWebNotes(notes, webNoteSortOptionForId("title-z-a")).map { it.id },
        )
    }

    @Test
    fun searchAndSortComposeWithoutChangingSourceOrder() {
        val notes = listOf(
            note(id = "older", body = "Match older", updatedAtMs = 1),
            note(id = "other", body = "Other", updatedAtMs = 3),
            note(id = "newer", body = "Match newer", updatedAtMs = 2),
        )
        val controls = WebNoteListControlsState(searchQuery = "match", sortId = "last-edited-newest")

        val result = webNoteListDisplayNotes(notes, controls)

        assertEquals(listOf("newer", "older"), result.map { it.id })
        assertEquals(listOf("older", "other", "newer"), notes.map { it.id })
    }

    @Test
    fun resetControlsClearsSearchAndRestoresDefaultSort() {
        val reset = resetWebNoteListControls()

        assertEquals("", reset.searchQuery)
        assertEquals(DefaultWebNoteSortOption.id, reset.sortId)
        assertEquals("Last edited: newest first", webNoteSortOptionForId(reset.sortId).label)
    }

    private fun note(
        id: String,
        body: String = id,
        createdAtMs: Long = 1,
        updatedAtMs: Long = createdAtMs,
        deleted: Boolean = false,
    ): WebReadOnlyNote =
        WebReadOnlyNote(
            id = id,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
            bodyMarkdown = body,
            deleted = deleted,
        )
}

class WebNoteDetailStateTests {
    @Test
    fun openDetailStoresSelectedNoteId() {
        assertEquals(WebNoteDetailUiState(openNoteId = "note-1"), openWebNoteDetail("note-1"))
    }

    @Test
    fun closeDetailClearsSelectedNoteId() {
        assertEquals(WebNoteDetailUiState(), closeWebNoteDetail())
    }
}

class WebNoteLoadGuardTests {
    private val nip07Identity = WebAccountIdentity("aa".repeat(32), WebAuthMethod.Nip07)
    private val nip46Identity = WebAccountIdentity("bb".repeat(32), WebAuthMethod.Nip46)

    @Test
    fun startedRequestIsAcceptedForMatchingSignedInAccount() {
        val started = WebNoteLoadGuard().start(nip07Identity)
        val authState = WebAuthUiState(
            nip07Available = true,
            signInState = WebSignInState.SignedIn(nip07Identity),
        )

        assertTrue(started.guard.accepts(started.request, authState))
    }

    @Test
    fun staleRequestIsRejectedAfterManualReloadStartsNewGeneration() {
        val first = WebNoteLoadGuard().start(nip07Identity)
        val second = first.guard.start(nip07Identity)
        val authState = WebAuthUiState(
            nip07Available = true,
            signInState = WebSignInState.SignedIn(nip07Identity),
        )

        assertTrue(!second.guard.accepts(first.request, authState))
        assertTrue(second.guard.accepts(second.request, authState))
    }

    @Test
    fun requestIsRejectedAfterLogoutInvalidatesGuard() {
        val started = WebNoteLoadGuard().start(nip07Identity)
        val loggedOut = WebAuthUiState(nip07Available = true)

        assertTrue(!started.guard.invalidate().accepts(started.request, loggedOut))
    }

    @Test
    fun requestIsRejectedAfterAccountSwitch() {
        val started = WebNoteLoadGuard().start(nip07Identity)
        val switchedAccount = WebAuthUiState(
            nip07Available = true,
            signInState = WebSignInState.SignedIn(nip46Identity),
        )

        assertTrue(!started.guard.accepts(started.request, switchedAccount))
    }

    @Test
    fun requestIsRejectedAfterMethodSwitchForSamePubkey() {
        val samePubkeyNip46 = nip07Identity.copy(method = WebAuthMethod.Nip46)
        val started = WebNoteLoadGuard().start(nip07Identity)
        val switchedMethod = WebAuthUiState(
            nip07Available = true,
            signInState = WebSignInState.SignedIn(samePubkeyNip46),
        )

        assertTrue(!started.guard.accepts(started.request, switchedMethod))
    }
}

class WebProfileMetadataTests {
    private val accountPubkey = "aa".repeat(32)
    private val identity = WebAccountIdentity(accountPubkey, WebAuthMethod.Nip46)

    @Test
    fun displayNameIsPreferredOverNameInHeaderSummary() {
        val profile = parseWebProfileMetadata(
            pubkey = accountPubkey,
            content = """{"display_name":"Display Name","name":"legacy-name","about":"short bio","nip05":"user@example.com"}""",
            createdAt = 10,
        )

        val summary = webProfileHeaderSummary(identity, WebProfileUiState(pubkey = accountPubkey, metadata = profile))

        assertEquals("Display Name", summary.primary)
        assertEquals("user@example.com", summary.tertiary)
        assertEquals("short bio", summary.about)
    }

    @Test
    fun nameIsUsedWhenDisplayNameIsMissing() {
        val profile = parseWebProfileMetadata(
            pubkey = accountPubkey,
            content = """{"name":"legacy-name"}""",
        )

        val summary = webProfileHeaderSummary(identity, WebProfileUiState(pubkey = accountPubkey, metadata = profile))

        assertEquals("legacy-name", summary.primary)
    }

    @Test
    fun headerFallsBackToAbbreviatedPubkeyWhenProfileIsUnavailable() {
        val summary = webProfileHeaderSummary(identity, WebProfileUiState(pubkey = accountPubkey))

        assertEquals(identity.displayPublicKey, summary.primary)
        assertTrue(summary.secondary.contains(identity.displayPublicKey))
    }

    @Test
    fun loadingStateUsesSafeCompactProfileCopy() {
        val summary = webProfileHeaderSummary(identity, WebProfileUiState(loading = true, pubkey = accountPubkey))

        assertEquals(WebProfileCopy.Loading, summary.tertiary)
    }

    @Test
    fun invalidProfileJsonIsIgnoredSafely() {
        assertNull(parseWebProfileMetadata(accountPubkey, "{not-json"))
    }

    @Test
    fun profileParserKeepsRemoteImagesAsInertStrings() {
        val profile = parseWebProfileMetadata(
            pubkey = accountPubkey,
            content = """{"picture":"https://example.com/avatar.png","banner":"https://example.com/banner.png"}""",
        )

        assertEquals("https://example.com/avatar.png", profile?.pictureUrl)
        assertEquals("https://example.com/banner.png", profile?.bannerUrl)
    }

    @Test
    fun mismatchedPubkeyIsIgnoredWhenSelectingLatestProfile() {
        val event = profileEvent(
            id = "01".repeat(32),
            pubkey = "bb".repeat(32),
            createdAt = 1,
            content = """{"display_name":"Wrong user"}""",
        )

        assertNull(selectLatestWebProfileMetadata(listOf(event), accountPubkey, validateEvent = { true }))
    }

    @Test
    fun newestValidProfileEventWins() {
        val older = profileEvent(
            id = "01".repeat(32),
            pubkey = accountPubkey,
            createdAt = 1,
            content = """{"display_name":"Older"}""",
        )
        val newer = profileEvent(
            id = "02".repeat(32),
            pubkey = accountPubkey,
            createdAt = 2,
            content = """{"display_name":"Newer"}""",
        )

        val selected = selectLatestWebProfileMetadata(listOf(older, newer), accountPubkey, validateEvent = { true })

        assertEquals("Newer", selected?.displayName)
    }

    @Test
    fun invalidJsonEventDoesNotBeatOlderValidProfile() {
        val older = profileEvent(
            id = "01".repeat(32),
            pubkey = accountPubkey,
            createdAt = 1,
            content = """{"display_name":"Older valid"}""",
        )
        val invalidNewer = profileEvent(
            id = "02".repeat(32),
            pubkey = accountPubkey,
            createdAt = 2,
            content = "{not-json",
        )

        val selected = selectLatestWebProfileMetadata(listOf(older, invalidNewer), accountPubkey, validateEvent = { true })

        assertEquals("Older valid", selected?.displayName)
    }

    @Test
    fun profileLoadGuardRejectsLogoutAndAccountSwitch() {
        val started = WebProfileLoadGuard().start(identity)
        val signedIn = WebAuthUiState(nip07Available = true, signInState = WebSignInState.SignedIn(identity))
        val loggedOut = WebAuthUiState(nip07Available = true)
        val switched = WebAuthUiState(
            nip07Available = true,
            signInState = WebSignInState.SignedIn(WebAccountIdentity("bb".repeat(32), WebAuthMethod.Nip46)),
        )

        assertTrue(started.guard.accepts(started.request, signedIn))
        assertTrue(!started.guard.accepts(started.request, loggedOut))
        assertTrue(!started.guard.accepts(started.request, switched))
    }

    private fun profileEvent(
        id: String,
        pubkey: String,
        createdAt: Long,
        content: String,
        kind: Int = WebProfileKind,
    ): WebNostrEvent =
        WebNostrEvent(
            id = id,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = emptyList(),
            content = content,
            sig = "03".repeat(64),
        )
}

class WebRelayListImportTests {
    private val accountPubkey = "aa".repeat(32)
    private val identity = WebAccountIdentity(accountPubkey, WebAuthMethod.Nip46)

    @Test
    fun relayListRequestTargetsSignedInPubkeyAndKind10002() {
        val message = webRelayListRequestMessage(accountPubkey)

        assertTrue(message.contains(""""authors":["$accountPubkey"]"""))
        assertTrue(message.contains(""""kinds":[10002]"""))
        assertTrue(message.contains(""""limit":20"""))
    }

    @Test
    fun relayListParserImportsWriteAndUnmarkedOutboxRelays() {
        val parsed = parseWebRelayListEvent(
            relayListEvent(
                tags = listOf(
                    listOf("r", "relay-one.example", "write"),
                    listOf("r", "relay-two.example"),
                    listOf("r", "relay-three.example", "outbox"),
                ),
            ),
        )

        assertEquals(
            listOf("wss://relay-one.example", "wss://relay-two.example", "wss://relay-three.example"),
            parsed.writeRelayUrls,
        )
    }

    @Test
    fun relayListParserIgnoresReadAndInboxOnlyRelaysForEditableNoteSettings() {
        val parsed = parseWebRelayListEvent(
            relayListEvent(
                tags = listOf(
                    listOf("r", "read.example", "read"),
                    listOf("r", "inbox.example", "inbox"),
                    listOf("r", "write.example", "write"),
                ),
            ),
        )

        assertEquals(listOf("wss://write.example"), parsed.writeRelayUrls)
    }

    @Test
    fun relayListParserPreservesUnknownAndNonRelayTagsForFutureMetadataSafety() {
        val parsed = parseWebRelayListEvent(
            relayListEvent(
                tags = listOf(
                    listOf("client", "other-note"),
                    listOf("r", "custom.example", "custom", "extra"),
                ),
            ),
        )

        assertEquals(listOf(listOf("client", "other-note")), parsed.preservedTags)
        assertEquals("custom", parsed.relayEntries.single().marker)
        assertEquals(listOf("extra"), parsed.relayEntries.single().extraFields)
        assertTrue(parsed.writeRelayUrls.isEmpty())
    }

    @Test
    fun relayListParserRejectsMalformedHttpQueryAndFragmentRelays() {
        val parsed = parseWebRelayListEvent(
            relayListEvent(
                tags = listOf(
                    listOf("r", "http://relay.example", "write"),
                    listOf("r", "https://relay.example", "write"),
                    listOf("r", "wss://relay.example?x=1", "write"),
                    listOf("r", "wss://relay.example#fragment", "write"),
                    listOf("r", "wss://valid.example", "write"),
                ),
            ),
        )

        assertEquals(listOf("wss://valid.example"), parsed.writeRelayUrls)
        assertEquals(4, parsed.malformedRelayTagCount)
    }

    @Test
    fun relayListParserNormalizesAndDeduplicatesRelays() {
        val parsed = parseWebRelayListEvent(
            relayListEvent(
                tags = listOf(
                    listOf("r", "Relay.Example/nostr/", "write"),
                    listOf("r", "wss://relay.example/nostr", "write"),
                ),
            ),
        )

        assertEquals(listOf("wss://relay.example/nostr"), parsed.writeRelayUrls)
    }

    @Test
    fun newestValidMatchingRelayListWins() {
        val older = relayListEvent(
            id = "01".repeat(32),
            createdAt = 1,
            tags = listOf(listOf("r", "older.example", "write")),
        )
        val newer = relayListEvent(
            id = "02".repeat(32),
            createdAt = 2,
            tags = listOf(listOf("r", "newer.example", "write")),
        )

        val selected = selectLatestWebRelayList(listOf(older, newer), accountPubkey, validateEvent = { true })

        assertEquals(listOf("wss://newer.example"), selected?.writeRelayUrls)
    }

    @Test
    fun mismatchedPubkeyAndWrongKindRelayListsAreIgnored() {
        val wrongPubkey = relayListEvent(pubkey = "bb".repeat(32), tags = listOf(listOf("r", "wrong.example", "write")))
        val wrongKind = relayListEvent(kind = 0, tags = listOf(listOf("r", "profile.example", "write")))

        assertNull(selectLatestWebRelayList(listOf(wrongPubkey, wrongKind), accountPubkey, validateEvent = { true }))
    }

    @Test
    fun invalidEventValidationDoesNotSelectRelayList() {
        val event = relayListEvent(tags = listOf(listOf("r", "relay.example", "write")))

        assertNull(selectLatestWebRelayList(listOf(event), accountPubkey, validateEvent = { false }))
    }

    @Test
    fun publishedRelayImportAppliesUsableWriteRelaysToSessionSettings() {
        val decision = assertIs<WebRelayListImportDecision.Applied>(
            importPublishedWebNoteRelays(
                settings = defaultWebNoteRelaySettings(),
                relayState = WebRelayListUiState(pubkey = accountPubkey),
                publishedRelays = listOf("relay.example"),
            ),
        )

        assertEquals(listOf("wss://relay.example"), selectedWebNoteRelays(decision.settings))
        assertEquals(WebRelayListCopy.Imported, decision.relayState.message)
        assertTrue(decision.changed)
    }

    @Test
    fun noUsablePublishedWriteRelaysKeepsCurrentSessionSettings() {
        val current = WebNoteRelaySettingsState(relays = listOf("wss://current.example"))
        val decision = assertIs<WebRelayListImportDecision.KeptCurrent>(
            importPublishedWebNoteRelays(
                settings = current,
                relayState = WebRelayListUiState(pubkey = accountPubkey),
                publishedRelays = emptyList(),
            ),
        )

        assertEquals(listOf("wss://current.example"), selectedWebNoteRelays(decision.settings))
        assertEquals(WebRelayListCopy.NoPublishedWriteRelays, decision.relayState.message)
    }

    @Test
    fun fetchedRelayListDoesNotClobberUnsavedAddRelayDraft() {
        val current = WebNoteRelaySettingsState(
            relays = listOf("wss://current.example"),
            input = "draft-relay.example",
        )
        val decision = assertIs<WebRelayListImportDecision.Deferred>(
            importPublishedWebNoteRelays(
                settings = current,
                relayState = WebRelayListUiState(pubkey = accountPubkey),
                publishedRelays = listOf("published.example"),
            ),
        )

        assertEquals("draft-relay.example", decision.settings.input)
        assertEquals(listOf("wss://current.example"), selectedWebNoteRelays(decision.settings))
        assertEquals(listOf("wss://published.example"), decision.relayState.pendingPublishedRelays)
        assertEquals(WebRelayListCopy.DeferredForLocalDraft, decision.relayState.message)
    }

    @Test
    fun explicitPendingRelayListApplyReplacesDraftOnlyWhenChosen() {
        val settings = WebNoteRelaySettingsState(
            relays = listOf("wss://current.example"),
            input = "draft-relay.example",
        )
        val relayState = WebRelayListUiState(
            pubkey = accountPubkey,
            pendingPublishedRelays = listOf("wss://published.example"),
        )

        val decision = assertIs<WebRelayListImportDecision.Applied>(
            applyPendingPublishedWebNoteRelays(settings, relayState),
        )

        assertEquals("", decision.settings.input)
        assertEquals(listOf("wss://published.example"), selectedWebNoteRelays(decision.settings))
        assertEquals(WebRelayListCopy.Imported, decision.relayState.message)
    }

    @Test
    fun keepingLocalRelayEditsClearsPendingPublishedListOnly() {
        val state = keepLocalWebRelayEdits(
            WebRelayListUiState(
                pubkey = accountPubkey,
                pendingPublishedRelays = listOf("wss://published.example"),
            ),
        )

        assertTrue(state.pendingPublishedRelays.isEmpty())
        assertEquals(WebRelayListCopy.KeptLocalEdits, state.message)
    }

    @Test
    fun nip46SignerTransportRelaysAreNotImportedWithoutPublishedRelayTags() {
        val signerRelay = "wss://signer-transport.example"
        val encodedRelay = signerRelay.replace(":", "%3A").replace("/", "%2F")
        val parsed = assertIs<WebNip46TokenParseResult.Valid>(
            parseWebNip46BunkerToken("bunker://${"22".repeat(32)}?relay=$encodedRelay"),
        )
        val decision = assertIs<WebRelayListImportDecision.KeptCurrent>(
            importPublishedWebNoteRelays(
                settings = defaultWebNoteRelaySettings(),
                relayState = WebRelayListUiState(pubkey = accountPubkey),
                publishedRelays = emptyList(),
            ),
        )

        assertEquals(listOf(signerRelay), parsed.token.relays)
        assertTrue(signerRelay !in selectedWebNoteRelays(decision.settings))
    }

    @Test
    fun relayListLoadGuardRejectsLogoutAndAccountSwitch() {
        val started = WebRelayListLoadGuard().start(identity)
        val signedIn = WebAuthUiState(nip07Available = true, signInState = WebSignInState.SignedIn(identity))
        val loggedOut = WebAuthUiState(nip07Available = true)
        val switched = WebAuthUiState(
            nip07Available = true,
            signInState = WebSignInState.SignedIn(WebAccountIdentity("bb".repeat(32), WebAuthMethod.Nip46)),
        )

        assertTrue(started.guard.accepts(started.request, signedIn))
        assertTrue(!started.guard.accepts(started.request, loggedOut))
        assertTrue(!started.guard.accepts(started.request, switched))
    }

    private fun relayListEvent(
        id: String = "03".repeat(32),
        pubkey: String = accountPubkey,
        createdAt: Long = 1,
        kind: Int = WebRelayListKind,
        tags: List<List<String>>,
    ): WebNostrEvent =
        WebNostrEvent(
            id = id,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = "",
            sig = "04".repeat(64),
        )
}

class WebRelayStatsTests {
    private val accountPubkey = "ee".repeat(32)

    @Test
    fun relayStatLabelsAreCompactAndSafe() {
        assertEquals("unknown", webRelayEventStatLabel(WebRelayEventStat.Unknown))
        assertEquals("checking...", webRelayEventStatLabel(WebRelayEventStat.Checking))
        assertEquals("0 events found", webRelayEventStatLabel(WebRelayEventStat.Loaded(0)))
        assertEquals("1 event found", webRelayEventStatLabel(WebRelayEventStat.Loaded(1)))
        assertEquals("3 events found", webRelayEventStatLabel(WebRelayEventStat.Loaded(3)))
        assertEquals("unavailable", webRelayEventStatLabel(WebRelayEventStat.Unavailable))
    }

    @Test
    fun relayStatsCountValidMatchingNoteEventsIncludingReplacementsAndTombstones() {
        val first = statsNoteEvent("first", createdAt = 1, noteId = "same")
        val edit = statsNoteEvent("edit", createdAt = 2, noteId = "same")
        val tombstone = statsNoteEvent("delete", createdAt = 3, noteId = "same")
        val other = statsNoteEvent("other", createdAt = 4, noteId = "other")

        val count = webRelayStatsCountValidEvents(
            events = listOf(first, edit, tombstone, other),
            accountPubkey = accountPubkey,
            validateEvent = { true },
        )

        assertEquals(4, count)
    }

    @Test
    fun relayStatsRejectWrongAccountMalformedAndNonOtherNoteEvents() {
        val valid = statsNoteEvent("valid", createdAt = 1, noteId = "valid")
        val wrongAccount = statsNoteEvent("wrong", createdAt = 2, noteId = "wrong", pubkey = "ff".repeat(32))
        val missingDTag = statsNoteEvent("missing-d", createdAt = 3, noteId = "missing").copy(
            tags = listOf(listOf("t", WebOtherNoteTag)),
        )
        val nonOtherNote = statsNoteEvent("non-other", createdAt = 4, noteId = "non").copy(
            tags = listOf(listOf("d", webNoteDTag("non"))),
        )

        val count = webRelayStatsCountValidEvents(
            events = listOf(valid, wrongAccount, missingDTag, nonOtherNote),
            accountPubkey = accountPubkey,
            validateEvent = { it.id != missingDTag.id },
        )

        assertEquals(1, count)
    }

    @Test
    fun relayStatsDeduplicateEventIdsPerRelay() {
        val event = statsNoteEvent("same", createdAt = 1, noteId = "same")

        assertEquals(
            1,
            webRelayStatsCountValidEvents(
                events = listOf(event, event),
                accountPubkey = accountPubkey,
                validateEvent = { true },
            ),
        )
    }

    @Test
    fun relayStatsPartialFailureDoesNotEraseSuccessfulCounts() {
        val success = webRelayEventStatForEvents(
            events = listOf(statsNoteEvent("valid", createdAt = 1, noteId = "valid")),
            accountPubkey = accountPubkey,
            validateEvent = { true },
        )
        val failed = webRelayEventStatForEvents(
            events = emptyList(),
            accountPubkey = accountPubkey,
            failed = true,
            validateEvent = { true },
        )

        assertEquals(WebRelayEventStat.Loaded(1), success)
        assertEquals(WebRelayEventStat.Unavailable, failed)
    }

    @Test
    fun relayStatsGuardRejectsLogoutAccountSwitchAndRelayChange() {
        val identity = WebAccountIdentity(accountPubkey, WebAuthMethod.Nip46)
        val started = WebRelayStatsGuard().start(identity, listOf("wss://one.example"))
        val signedIn = WebAuthUiState(nip07Available = true, signInState = WebSignInState.SignedIn(identity))
        val loggedOut = WebAuthUiState(nip07Available = true)
        val switched = WebAuthUiState(
            nip07Available = true,
            signInState = WebSignInState.SignedIn(WebAccountIdentity("ff".repeat(32), WebAuthMethod.Nip46)),
        )

        assertTrue(started.guard.accepts(started.request, signedIn, listOf("wss://one.example")))
        assertTrue(!started.guard.accepts(started.request, loggedOut, listOf("wss://one.example")))
        assertTrue(!started.guard.accepts(started.request, switched, listOf("wss://one.example")))
        assertTrue(!started.guard.accepts(started.request, signedIn, listOf("wss://two.example")))
    }

    @Test
    fun relayStatsDoNotIncludeSignerTransportRelayUnlessItIsAnActiveNoteRelay() {
        val signerRelay = "wss://signer-transport.example"
        val started = WebRelayStatsGuard().start(
            WebAccountIdentity(accountPubkey, WebAuthMethod.Nip46),
            listOf("wss://note.example"),
        )

        assertTrue(signerRelay !in started.request.relays)
        assertEquals(listOf("wss://note.example"), started.request.relays)
    }

    private fun statsNoteEvent(
        id: String,
        createdAt: Long,
        noteId: String,
        pubkey: String = accountPubkey,
    ): WebNostrEvent =
        WebNostrEvent(
            id = id.padEnd(64, '0').take(64),
            pubkey = pubkey,
            createdAt = createdAt,
            kind = WebNoteKind,
            tags = webNoteEventTags(noteId),
            content = "cipher-$id",
            sig = "14".repeat(64),
        )
}

class WebRelayMigrationTests {
    private val accountPubkey = "cc".repeat(32)

    @Test
    fun migrationPlanDetectsNoChangesAddedRemovedAndRetainedRelays() {
        val unchanged = planWebRelayMigration(
            oldRelays = listOf("wss://one.example"),
            newRelays = listOf("wss://one.example"),
        )
        val changed = planWebRelayMigration(
            oldRelays = listOf("wss://one.example", "wss://two.example"),
            newRelays = listOf("wss://two.example", "wss://three.example"),
        )

        assertTrue(!unchanged.migrationRequired)
        assertEquals(listOf("wss://three.example"), changed.addedRelays)
        assertEquals(listOf("wss://one.example"), changed.removedRelays)
        assertEquals(listOf("wss://two.example"), changed.retainedRelays)
    }

    @Test
    fun migrationTargetRelaysIncludeAddedAndRetainedRequestedRelays() {
        val plan = planWebRelayMigration(
            oldRelays = listOf("wss://old.example", "wss://keep.example"),
            newRelays = listOf("wss://keep.example", "wss://new.example"),
        )

        assertEquals(
            listOf("wss://new.example", "wss://keep.example"),
            webRelayMigrationTargetRelays(plan),
        )
    }

    @Test
    fun manualSyncTargetRelaysUseCurrentRelaysWithoutRelayChanges() {
        val plan = planWebRelayMigration(
            oldRelays = listOf("wss://one.example", "wss://two.example"),
            newRelays = listOf("wss://one.example", "wss://two.example"),
        )

        assertTrue(!plan.migrationRequired)
        assertEquals(
            listOf("wss://one.example", "wss://two.example"),
            webRelayMigrationTargetRelays(plan),
        )
    }

    @Test
    fun manualSyncHasNoTargetsWhenNoActiveRelaysExist() {
        val plan = planWebRelayMigration(oldRelays = emptyList(), newRelays = emptyList())

        assertTrue(!plan.migrationRequired)
        assertEquals(emptyList(), webRelayMigrationTargetRelays(plan))
    }

    @Test
    fun manualSyncNoEventsFoundIsSafeNonFatalStatus() {
        val plan = planWebRelayMigration(
            oldRelays = listOf("wss://one.example"),
            newRelays = listOf("wss://one.example"),
        )
        val result = WebRelayMigrationResult(
            plan = plan,
            fetchStatuses = listOf(WebNoteRelayStatus("wss://one.example", connected = true)),
            fetchedEventCount = 0,
            latestEvents = emptyList(),
            publishStatusesByEventId = emptyMap(),
            relayListPublish = null,
            warnings = listOf("No encrypted note events were found on current relays."),
        )

        assertTrue(result.onlyNoSourceEventsWarning)
        assertEquals("No encrypted notes found to migrate", webRelayMigrationWarning(result).title)
    }

    @Test
    fun manualSyncTargetsCurrentNoteRelaysNotSignerTransportRelays() {
        val signerRelay = "wss://signer-transport.example"
        val plan = planWebRelayMigration(
            oldRelays = listOf("wss://note-one.example", "wss://note-two.example"),
            newRelays = listOf("wss://note-one.example", "wss://note-two.example"),
        )

        assertTrue(signerRelay !in webRelayMigrationTargetRelays(plan))
    }

    @Test
    fun latestSignedEncryptedEventSelectionKeepsNewestPerDTagIncludingTombstones() {
        val old = migrationNoteEvent("old", createdAt = 1, noteId = "same", content = "cipher-old")
        val replacement = migrationNoteEvent("replacement", createdAt = 2, noteId = "same", content = "cipher-new")
        val tombstone = migrationNoteEvent("tombstone", createdAt = 3, noteId = "same", content = "cipher-delete")

        val selected = selectLatestSignedEncryptedWebNoteEvents(
            events = listOf(old, tombstone, replacement),
            accountPubkey = accountPubkey,
            validateEvent = { true },
        )

        assertEquals(listOf(tombstone.id), selected.map { it.id })
        assertEquals("cipher-delete", selected.single().content)
    }

    @Test
    fun latestSignedEncryptedEventSelectionIgnoresWrongAccountMalformedAndInvalidEvents() {
        val valid = migrationNoteEvent("valid", createdAt = 1, noteId = "valid")
        val wrongAccount = migrationNoteEvent("wrong", createdAt = 2, noteId = "wrong", pubkey = "dd".repeat(32))
        val missingDTag = migrationNoteEvent("missing-d", createdAt = 3, noteId = "missing").copy(
            tags = listOf(listOf("t", "other-note")),
        )

        val selected = selectLatestSignedEncryptedWebNoteEvents(
            events = listOf(valid, wrongAccount, missingDTag),
            accountPubkey = accountPubkey,
            validateEvent = { it.id != missingDTag.id },
        )

        assertEquals(listOf(valid.id), selected.map { it.id })
    }

    @Test
    fun migrationWarningClassifiesAllTargetWriteFailure() {
        val plan = planWebRelayMigration(
            oldRelays = listOf("wss://old.example"),
            newRelays = listOf("wss://old.example", "wss://new.example"),
        )
        val result = WebRelayMigrationResult(
            plan = plan,
            fetchStatuses = listOf(WebNoteRelayStatus("wss://old.example", connected = true, returnedEvents = 1)),
            fetchedEventCount = 1,
            latestEvents = listOf(migrationNoteEvent("latest", createdAt = 1, noteId = "note")),
            publishStatusesByEventId = mapOf(
                "latest" to listOf(WebNoteRelayStatus("wss://new.example", connected = true, failed = true)),
            ),
            relayListPublish = null,
            warnings = listOf("No target relay accepted migrated encrypted events."),
        )

        val warning = webRelayMigrationWarning(result)

        assertEquals("No target relay accepted migrated notes", warning.title)
        assertTrue(warning.details.contains("selected_events=1"))
        assertTrue(!warning.details.contains("cipher"))
    }

    @Test
    fun migrationWarningAllowsFreshRelaySetWithNoSourceEvents() {
        val plan = planWebRelayMigration(
            oldRelays = listOf("wss://old.example"),
            newRelays = listOf("wss://new.example"),
        )
        val result = WebRelayMigrationResult(
            plan = plan,
            fetchStatuses = listOf(WebNoteRelayStatus("wss://old.example", connected = true)),
            fetchedEventCount = 0,
            latestEvents = emptyList(),
            publishStatusesByEventId = emptyMap(),
            relayListPublish = null,
            warnings = listOf("No encrypted note events were found on current relays."),
        )

        assertEquals("No encrypted notes found to migrate", webRelayMigrationWarning(result).title)
    }

    @Test
    fun relayListMergeUpdatesWriteRelaysAndPreservesReadCustomAndNonRelayTags() {
        val existing = parseWebRelayListEvent(
            WebNostrEvent(
                id = "11".repeat(32),
                pubkey = accountPubkey,
                createdAt = 1,
                kind = WebRelayListKind,
                tags = listOf(
                    listOf("client", "other-note"),
                    listOf("r", "old-write.example", "write"),
                    listOf("r", "read.example", "read"),
                    listOf("r", "custom.example", "custom", "extra"),
                    listOf("r", "both.example"),
                ),
                content = "",
                sig = "12".repeat(64),
            ),
        )

        val tags = mergeWebRelayListTags(existing, listOf("new-write.example", "both.example"))

        assertTrue(listOf("client", "other-note") in tags)
        assertTrue(listOf("r", "wss://read.example", "read") in tags)
        assertTrue(listOf("r", "wss://custom.example", "custom", "extra") in tags)
        assertTrue(listOf("r", "wss://both.example") in tags)
        assertTrue(listOf("r", "wss://new-write.example", "write") in tags)
        assertTrue(tags.none { it.getOrNull(1) == "wss://old-write.example" && it.getOrNull(2) == "write" })
    }

    @Test
    fun relayListMergeDoesNotIncludeSignerTransportRelaysUnlessRequestedAsNoteRelays() {
        val signerRelay = "wss://signer-transport.example"
        val tags = mergeWebRelayListTags(existing = null, appWriteRelays = listOf("wss://note.example"))

        assertTrue(tags.none { it.contains(signerRelay) })
        assertTrue(listOf("r", "wss://note.example", "write") in tags)
    }

    @Test
    fun relayMigrationGuardRejectsLogoutAndAccountSwitch() {
        val identity = WebAccountIdentity(accountPubkey, WebAuthMethod.Nip07)
        val started = WebRelayMigrationGuard().start(identity)
        val signedIn = WebAuthUiState(nip07Available = true, signInState = WebSignInState.SignedIn(identity))
        val loggedOut = WebAuthUiState(nip07Available = true)
        val switched = WebAuthUiState(
            nip07Available = true,
            signInState = WebSignInState.SignedIn(WebAccountIdentity("dd".repeat(32), WebAuthMethod.Nip07)),
        )

        assertTrue(started.guard.accepts(started.request, signedIn))
        assertTrue(!started.guard.accepts(started.request, loggedOut))
        assertTrue(!started.guard.accepts(started.request, switched))
    }

    private fun migrationNoteEvent(
        id: String,
        createdAt: Long,
        noteId: String,
        pubkey: String = accountPubkey,
        content: String = "cipher-$id",
    ): WebNostrEvent =
        WebNostrEvent(
            id = id.padEnd(64, '0').take(64),
            pubkey = pubkey,
            createdAt = createdAt,
            kind = WebNoteKind,
            tags = webNoteEventTags(noteId),
            content = content,
            sig = "13".repeat(64),
        )
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

class WebRememberedNip46SessionTests {
    @Test
    fun validRememberedSessionRecordRoundTripsAndRestoresTransportSession() {
        val record = testRecord()
        val encoded = encodeRememberedNip46Record(record)
        val decoded = decodeRememberedNip46Record(encoded)
        val session = decoded?.toSession()

        assertEquals(record, decoded)
        assertEquals(WebRememberedNip46StorageKey, "on.web.nip46")
        assertTrue(encoded.contains("clientPrivateKeyHex"))
        assertTrue(!encoded.contains("nsec"))
        assertTrue(!encoded.contains("bunker://"))
        assertTrue(!encoded.contains("noteBody"))
        assertTrue(!record.toString().contains(record.clientPrivateKeyHex))
        assertEquals(record.clientPubkey, session?.clientPubkey)
        assertEquals(record.remoteSignerPubkey, session?.remoteSignerPubkey)
        assertEquals(record.signerRelays, session?.relays)
    }

    @Test
    fun rememberedSessionRejectsMalformedOrOverBroadRecords() {
        val record = testRecord()

        assertNull(decodeRememberedNip46Record("""{"version":999}"""))
        assertNull(decodeRememberedNip46Record("""{"version":1,"unexpected":"field"}"""))
        assertNull(validateRememberedNip46Record(record.copy(userPubkey = "not-a-pubkey")))
        assertNull(validateRememberedNip46Record(record.copy(clientPrivateKeyHex = "00".repeat(32))))
        assertNull(validateRememberedNip46Record(record.copy(clientPubkey = "01".repeat(32))))
        assertNull(validateRememberedNip46Record(record.copy(remoteSignerPubkey = "bad")))
        assertNull(validateRememberedNip46Record(record.copy(signerRelays = listOf("https://relay.example.com"))))
        assertNull(validateRememberedNip46Record(record.copy(signerRelays = listOf("wss://relay.example.com/#frag"))))
    }

    @Test
    fun rememberedSessionStorageUsesOnlyAllowedKeyAndForgetsWithoutTouchingTheme() {
        val storage = FakeRememberedNip46Storage()
        val record = testRecord()

        assertTrue(saveRememberedNip46Record(storage, record))
        val loaded = assertIs<WebRememberedNip46LoadResult.Loaded>(loadRememberedNip46Record(storage))
        assertEquals(record, loaded.record)
        assertEquals(listOf(WebRememberedNip46StorageKey), storage.writes.map { it.first })

        assertTrue(forgetRememberedNip46Record(storage))
        assertEquals(WebRememberedNip46StorageKey, storage.removed.single())
        assertEquals(WebRememberedNip46LoadResult.Empty, loadRememberedNip46Record(storage))
        assertEquals(emptyList(), storage.writes.filter { it.first == WebThemePreferenceKey })
    }

    @Test
    fun rememberedSessionStateDefaultsToEmptyAndInvalidStorageShowsSafeForgetPath() {
        val empty = rememberedNip46StateFromStorage(FakeRememberedNip46Storage())
        val invalid = rememberedNip46StateFromStorage(FakeRememberedNip46Storage("""{"version":999}"""))

        assertNull(empty.record)
        assertTrue(!empty.invalidStoredSession)
        assertNull(invalid.record)
        assertTrue(invalid.invalidStoredSession)
        assertEquals(WebRememberedNip46Copy.InvalidStoredSession, invalid.message)
    }

    @Test
    fun rememberOptInCopyDistinguishesDefaultOffAndSensitiveStoredSession() {
        assertTrue(rememberNip46OptInLabel(false).contains("session-only"))
        assertTrue(rememberNip46OptInLabel(true).contains("does not save your private key"))
        assertEquals("Remember this remote signer on this browser", RememberNip46CheckboxLabel)
    }

    private fun testRecord(): WebRememberedNip46Record {
        val user = keyPair(lastByte = 1)
        val client = keyPair(lastByte = 2)
        val remote = keyPair(lastByte = 3)
        return rememberedNip46RecordFromSession(
            session = WebNip46Session(
                clientPrivateKey = client.keyPair.clientPrivateKey,
                clientPubkey = client.keyPair.clientPubkey,
                remoteSignerPubkey = remote.keyPair.clientPubkey,
                relays = listOf("wss://relay.example.com/", "wss://relay.example.com"),
            ),
            userPubkey = user.keyPair.clientPubkey,
            nowMs = 123,
        ) ?: error("Expected valid remembered NIP-46 test record")
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

    private class FakeRememberedNip46Storage(
        initialValue: String? = null,
    ) : WebRememberedNip46Storage {
        private var value = initialValue
        val writes = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<String>()

        override fun read(key: String): String? {
            assertEquals(WebRememberedNip46StorageKey, key)
            return value
        }

        override fun write(key: String, value: String) {
            assertEquals(WebRememberedNip46StorageKey, key)
            writes += key to value
            this.value = value
        }

        override fun remove(key: String) {
            assertEquals(WebRememberedNip46StorageKey, key)
            removed += key
            value = null
        }
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

    @Test
    fun notePreviewStartsSnippetAfterTitleLine() {
        val preview = webNotePreview("relay check\nsecond detail\nthird detail")

        assertEquals("relay check", preview.title)
        assertEquals("second detail third detail", preview.snippet)
    }

    @Test
    fun notePreviewForSingleLineNoteHasNoSnippet() {
        val preview = webNotePreview("test 2.0")

        assertEquals("test 2.0", preview.title)
        assertEquals("", preview.snippet)
    }

    @Test
    fun notePreviewCompactsLongJsonWithoutMutatingSource() {
        val raw = """{"version":"vpn-marketplace/1","listing":"30402:d9b12978983a0a4f06e4f0719c29c77a7ad1282f1b4b95e61ef5668624633a1a"}"""
        val preview = webNotePreview(raw)

        assertTrue(preview.title.endsWith("..."))
        assertTrue(preview.title.length <= 83)
        assertEquals("", preview.snippet)
        assertTrue(raw.contains("vpn-marketplace/1"))
    }

    @Test
    fun notePreviewCompactsLongUrlLikeContentAfterTitle() {
        val raw = "remote signer\nbunker://a5544e864411bf1735e86b7f37686214d98e9b410ebe61a23dd268254f357d8e?relay=wss://relay.ditto.pub/&relay=wss://relay.nos.lol/&relay=wss://nostr.bitcoiner.social/&secret=1eb7d57a-a304-46ce-b543-a74b8b87df3a"
        val preview = webNotePreview(raw)

        assertEquals("remote signer", preview.title)
        assertTrue(preview.snippet.startsWith("bunker://"))
        assertTrue(preview.snippet.endsWith("..."))
        assertTrue(preview.snippet.length <= 143)
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
    fun typingRelayInputUpdatesDraftWithoutChangingSelectedRelays() {
        val initial = WebNoteRelaySettingsState(
            relays = listOf("wss://notes.example"),
            input = "",
            message = WebNoteCopy.RelayInvalid,
        )

        val typed = updateWebNoteRelayInput(initial, "relay.example.com")

        assertEquals(listOf("wss://notes.example"), selectedWebNoteRelays(typed))
        assertEquals("relay.example.com", typed.input)
        assertEquals("", typed.message)
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
