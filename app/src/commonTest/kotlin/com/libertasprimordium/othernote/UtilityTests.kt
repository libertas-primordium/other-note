package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.NotePayload
import com.libertasprimordium.othernote.domain.DefaultRelays
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.data.RelaySettingsCodec
import com.libertasprimordium.othernote.data.RelaySettingsPersistence
import com.libertasprimordium.othernote.data.RelaySettingsStore
import com.libertasprimordium.othernote.nostr.NostrEventSerialization
import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.Nip19
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.sync.planRelayMigration
import com.libertasprimordium.othernote.sync.RelayListKind
import com.libertasprimordium.othernote.sync.latestRelayListEvent
import com.libertasprimordium.othernote.sync.mergeRelayListTags
import com.libertasprimordium.othernote.sync.parseRelayListEvent
import com.libertasprimordium.othernote.sync.reduceNoteEvents
import com.libertasprimordium.othernote.sync.selectLatestSignedEncryptedNoteEvents
import com.libertasprimordium.othernote.ui.AppPlatform
import com.libertasprimordium.othernote.ui.NoteCardAction
import com.libertasprimordium.othernote.ui.NoteCardActionPresentation
import com.libertasprimordium.othernote.ui.SignInInfoTopic
import com.libertasprimordium.othernote.ui.noteCardActionPresentation
import com.libertasprimordium.othernote.ui.noteCardActionMenuText
import com.libertasprimordium.othernote.ui.noteCardActionItems
import com.libertasprimordium.othernote.ui.noteDeleteConfirmationText
import com.libertasprimordium.othernote.ui.signInInfoCopy
import com.libertasprimordium.othernote.ui.noteGridColumnCount
import com.libertasprimordium.othernote.ui.userFacingErrorFor
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec
import com.libertasprimordium.othernote.util.MediaType
import com.libertasprimordium.othernote.util.MarkdownBlock
import com.libertasprimordium.othernote.util.MarkdownSpan
import com.libertasprimordium.othernote.util.detectUrls
import com.libertasprimordium.othernote.util.isSupportedRemoteImageUrl
import com.libertasprimordium.othernote.util.markdownBlocks
import com.libertasprimordium.othernote.util.markdownSpans
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import com.libertasprimordium.othernote.util.noteCardPreview
import com.libertasprimordium.othernote.util.truncateMarkdown
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UtilityTests {
    @Test
    fun relayUrlsNormalizeAndRejectInvalidSchemes() {
        assertEquals("wss://relay.primal.net", normalizeRelayUrl("relay.primal.net").getOrThrow())
        assertEquals("wss://relay.example.com/path", normalizeRelayUrl("relay.example.com/path").getOrThrow())
        assertEquals("wss://relay.example.com", normalizeRelayUrl(" WSS://Relay.Example.com/ ").getOrThrow())
        assertEquals("wss://relay.example.com/nostr", normalizeRelayUrl("wss://relay.example.com/nostr/").getOrThrow())
        assertEquals("ws://localhost:7000", normalizeRelayUrl("ws://localhost:7000/").getOrThrow())
        assertTrue(normalizeRelayUrl("https://relay.example.com").isFailure)
        assertTrue(normalizeRelayUrl("http://relay.example.com").isFailure)
        assertTrue(normalizeRelayUrl("wss://relay.example.com/?token=secret").isFailure)
        assertTrue(normalizeRelayUrl("wss://relay.example.com/#fragment").isFailure)
        assertTrue(normalizeRelayUrl("wss://relay example.com").isFailure)
        assertTrue(normalizeRelayUrl("not a relay").isFailure)
        assertTrue(normalizeRelayUrl("ws://relay.example.com").isFailure)
    }

    @Test
    fun relaySettingsDeduplicateAndRejectEmptyLists() {
        val store = RelaySettingsStore()

        val preview = store.previewChange(
            listOf(
                " WSS://Relay.Example.com/ ",
                "relay.example.com",
                "wss://relay.example.com/nostr/",
            ),
        ).getOrThrow()

        assertEquals(listOf("wss://relay.example.com", "wss://relay.example.com/nostr"), preview.map { it.url })
        assertTrue(store.previewChange(emptyList()).isFailure)
    }

    @Test
    fun relaySettingsPersistenceRoundTripsSafeRelayUrls() = runBlocking {
        val persistence = MemoryRelaySettingsPersistence()
        val store = RelaySettingsStore(persistence = persistence)
        val custom = store.previewChange(listOf("wss://relay.example.com", "wss://relay.example.com/nostr")).getOrThrow()

        store.commitAndPersist(custom)
        val restored = RelaySettingsStore(persistence = persistence)
        restored.loadPersisted()

        assertEquals(custom.map { it.url }, restored.normalizedUrls())
        val serialized = persistence.raw ?: error("Missing serialized relay settings")
        assertTrue(serialized.contains("wss://relay.example.com"))
        assertFalse(serialized.contains("nsec"))
        assertFalse(serialized.contains("privateKey"))
        assertFalse(serialized.contains("body_markdown"))
    }

    @Test
    fun relaySettingsRestoreDefaultsPersistsExpectedDefaultSet() = runBlocking {
        val persistence = MemoryRelaySettingsPersistence()
        val store = RelaySettingsStore(persistence = persistence)
        val custom = store.previewChange(listOf("wss://relay.example.com")).getOrThrow()
        store.commitAndPersist(custom)

        store.restoreDefaultsAndPersist()

        assertEquals(DefaultRelays.map { it.url }, store.normalizedUrls())
        assertEquals(DefaultRelays.map { it.url }, RelaySettingsCodec.decodeOrNull(persistence.raw.orEmpty()))
    }

    @Test
    fun noteCardActionsExposeOpenEditAndGuardedDeleteLabels() {
        val actions = noteCardActionItems()

        assertEquals(listOf(NoteCardAction.Open, NoteCardAction.Edit, NoteCardAction.Delete), actions.map { it.action })
        assertEquals("Open note", actions.single { it.action == NoteCardAction.Open }.accessibilityLabel)
        assertEquals("Edit note", actions.single { it.action == NoteCardAction.Edit }.accessibilityLabel)
        assertEquals("Delete note", actions.single { it.action == NoteCardAction.Delete }.accessibilityLabel)
        assertTrue(actions.single { it.action == NoteCardAction.Delete }.destructive)
    }

    @Test
    fun noteCardActionPresentationUsesLongPressOnAndroidAndVisibleButtonsOnDesktop() {
        assertEquals(
            NoteCardActionPresentation.LongPressMenu,
            noteCardActionPresentation(AppPlatform.Android, availableWidthDp = 400),
        )
        assertEquals(
            NoteCardActionPresentation.VisibleButtons,
            noteCardActionPresentation(AppPlatform.Desktop, availableWidthDp = 400),
        )
        assertEquals(
            NoteCardActionPresentation.LongPressMenu,
            noteCardActionPresentation(AppPlatform.Desktop, availableWidthDp = 180),
        )
    }

    @Test
    fun mobileNoteActionMenuExposesEditDeleteAndCancelLabels() {
        val actions = noteCardActionItems()
        val menuText = noteCardActionMenuText()

        assertEquals("Note actions", menuText.title)
        assertEquals("Edit", actions.single { it.action == NoteCardAction.Edit }.label)
        assertEquals("Delete", actions.single { it.action == NoteCardAction.Delete }.label)
        assertEquals("Cancel", menuText.cancelLabel)
    }

    @Test
    fun deleteConfirmationCopyIsUserFacingAndNonTechnical() {
        val text = noteDeleteConfirmationText()
        val visible = "${text.title}\n${text.body}\n${text.cancelLabel}\n${text.deleteLabel}"

        assertEquals("Delete note?", text.title)
        assertTrue(text.body.contains("syncs a deletion update"))
        assertEquals("Cancel", text.cancelLabel)
        assertEquals("Delete", text.deleteLabel)
        assertFalse(visible.contains("tombstone"))
        assertFalse(visible.contains("kind 30078"))
        assertFalse(visible.contains("d-tag"))
        assertFalse(visible.contains("body_markdown"))
    }

    @Test
    fun signInInfoCopyKeepsDetailedSecurityTextSafeAndOptional() {
        val copies = SignInInfoTopic.values().map(::signInInfoCopy)

        assertEquals(SignInInfoTopic.values().size, copies.size)
        assertTrue(copies.any { it.title == "Android signer" && it.body.contains("Log out ends automatic sign-in") })
        assertTrue(copies.any { it.title == "Remote signer" && it.body.contains("remote-signer session") })
        assertTrue(copies.any { it.title == "Existing nsec" && it.body.contains("current session") })
        assertTrue(copies.any { it.title == "Create identity" && it.body.contains("fresh nsec") })
        assertTrue(copies.any { it.title == "Local-only" && it.body.contains("does not sync to relays") })
        copies.forEach { copy ->
            assertPrimaryErrorCopyIsReadable("${copy.title}\n${copy.body}")
            assertFalse(copy.body.contains("nsec1leak"))
            assertFalse(copy.body.contains("must-not-appear"))
        }
    }

    @Test
    fun nip46TimeoutMapsToReadableUserFacingCopy() {
        val raw = "stage=response_fetch_timed_out reason=no_matching_response method=nip44_encrypt request_id=abc123 relay_source=token publish_accepted_count=1 candidate_events=24"
        val error = userFacingErrorFor(raw)

        assertEquals("Remote signer did not respond", error.title)
        assertTrue(error.message.contains("encryption request"))
        assertPrimaryErrorCopyIsReadable(error.message)
        assertTrue(error.technicalDetails?.contains("response_fetch_timed_out") == true)
    }

    @Test
    fun nip46RelayRejectionMapsToReadableUserFacingCopy() {
        val raw = "stage=relay_publish_failed method=sign_event outcome=rejected relay_source=token publish_accepted_count=0 candidate_events=0"
        val error = userFacingErrorFor(raw)

        assertEquals("Remote signer relay rejected the request", error.title)
        assertTrue(error.message.contains("temporary client key"))
        assertPrimaryErrorCopyIsReadable(error.message)
    }

    @Test
    fun bunkerAlreadyPairedMapsToReadableGuidance() {
        val raw = "Remote signer returned connect error: bunker link already paired stage=connect secret=must-not-appear"
        val error = userFacingErrorFor(raw)

        assertEquals("Bunker link already used", error.title)
        assertTrue(error.message.contains("fresh bunker link"))
        assertPrimaryErrorCopyIsReadable(error.message)
        assertFalse(error.message.contains("must-not-appear"))
    }

    @Test
    fun malformedBunkerLinkMapsToReadableGuidance() {
        val raw = "Unsupported remote signer token scheme secret=must-not-appear"
        val error = userFacingErrorFor(raw)

        assertEquals("Bunker link is invalid", error.title)
        assertTrue(error.message.contains("bunker://"))
        assertPrimaryErrorCopyIsReadable(error.message)
        assertFalse(error.message.contains("must-not-appear"))
    }

    @Test
    fun savedAndroidSignerMismatchMapsToReadableGuidance() {
        val raw = "Saved Android signer returned a different public key. secret=must-not-appear nsec1leak privateKey=leak"
        val error = userFacingErrorFor(raw)

        assertEquals("Saved Android signer does not match", error.title)
        assertTrue(error.message.contains("choose the signer again"))
        assertPrimaryErrorCopyIsReadable(error.message)
        assertFalse(error.message.contains("must-not-appear"))
        assertFalse(error.message.contains("nsec1leak"))
        assertFalse(error.message.contains("privateKey"))
    }

    @Test
    fun relayTestFailureMapsToReadableWarning() {
        val raw = "Relay rejected the test event. stage=relay_test_publish outcome=rejected secret=must-not-appear nsec1leak privateKey=leak body_markdown"
        val error = userFacingErrorFor(raw)

        assertEquals("Relay test failed", error.title)
        assertTrue(error.message.contains("publish and fetch events"))
        assertPrimaryErrorCopyIsReadable(error.message)
        assertFalse(error.message.contains("must-not-appear"))
        assertFalse(error.message.contains("nsec1leak"))
        assertFalse(error.message.contains("privateKey"))
        assertFalse(error.message.contains("body_markdown"))
    }

    @Test
    fun keyringFailuresMapToReadableUserFacingCopy() {
        val raw = "Could not save this identity to the desktop keyring. secret=must-not-appear nsec1leak privateKey=leak"
        val error = userFacingErrorFor(raw)

        assertEquals("Could not save identity", error.title)
        assertEquals("Could not save this identity to the desktop keyring.", error.message)
        assertPrimaryErrorCopyIsReadable(error.message)
        assertFalse(error.message.contains("must-not-appear"))
        assertFalse(error.message.contains("nsec1leak"))
        assertFalse(error.message.contains("privateKey"))
    }

    @Test
    fun persistenceFailureDoesNotExposeExceptionClassOrFilePath() {
        val raw = "Save failed: pending write persistence failed. NoSuchFileException: /home/spencer/.local/share/other-note/pending-writes/test.pending.json.tmp"
        val error = userFacingErrorFor(raw)

        assertEquals("Could not save local state", error.title)
        assertTrue(error.message.contains("file permissions"))
        assertPrimaryErrorCopyIsReadable(error.message)
        assertFalse(error.message.contains("NoSuchFileException"))
        assertFalse(error.message.contains("/home/spencer"))
    }

    @Test
    fun unexpectedTechnicalErrorUsesGenericFallback() {
        val raw = "UnexpectedStateException: stage=decode outcome=failed candidate_events=2"
        val error = userFacingErrorFor(raw)

        assertEquals("Something went wrong", error.title)
        assertEquals("Other Note could not complete the action. Try again.", error.message)
        assertPrimaryErrorCopyIsReadable(error.message)
    }

    @Test
    fun urlDetectionClassifiesSimpleMediaExtensions() {
        val urls = detectUrls("See https://example.com/a.png and https://example.com/watch.mp4")
        assertEquals(MediaType.Image, urls[0].type)
        assertEquals(MediaType.Video, urls[1].type)
    }

    @Test
    fun payloadJsonRoundTripsMarkdown() {
        val payload = NotePayload(
            noteId = "note-1",
            createdAtMs = 1,
            updatedAtMs = 2,
            bodyMarkdown = "# Title\nbody \"quoted\"",
            deleted = false,
        )
        assertEquals(payload, JsonNotePayloadCodec.decode(JsonNotePayloadCodec.encode(payload)).getOrThrow())
    }

    @Test
    fun payloadJsonRoundTripsEscapedUnicodeAndCodeBlocks() {
        val payload = NotePayload(
            noteId = "note-json",
            createdAtMs = 100,
            updatedAtMs = 200,
            bodyMarkdown = "Quote: \"hello\"\nBackslash: \\\nTab:\t\nUnicode: こんにちは\n```kotlin\nprintln(\"x\")\n```",
            deleted = false,
        )
        val encoded = JsonNotePayloadCodec.encode(payload)
        assertTrue(encoded.contains("body_markdown"))
        assertEquals(payload, JsonNotePayloadCodec.decode(encoded).getOrThrow())
    }

    @Test
    fun nip01PreimageDoesNotUseNotePayloadCodec() {
        val preimage = NostrEventSerialization.canonicalPreimage(
            UnsignedNostrEvent(
                pubkey = "pub",
                createdAt = 123,
                kind = 30078,
                tags = listOf(listOf("d", "other-note:note:abc"), listOf("t", "other-note")),
                content = "encrypted-content",
            ),
        )
        assertEquals("""[0,"pub",123,30078,[["d","other-note:note:abc"],["t","other-note"]],"encrypted-content"]""", preimage)
    }

    @Test
    fun nip19RejectsMixedCaseAndAcceptsLowercase() {
        val encoded = Nip19.encode("npub", ByteArray(32) { it.toByte() }) ?: error("npub encode failed")
        assertEquals("npub", Nip19.decode(encoded)?.hrp)
        val mixedCase = encoded.take(8).uppercase() + encoded.drop(8)
        assertEquals(null, Nip19.decode(mixedCase))
    }

    @Test
    fun reducerSelectsNewestPerDTagAndExcludesTombstones() {
        val old = event("a", 10, "note-1", deleted = false, body = "old")
        val newest = event("b", 20, "note-1", deleted = false, body = "new")
        val tombstone = event("c", 30, "note-2", deleted = true, body = "")
        val reduced = reduceNoteEvents(listOf(old, newest, tombstone)) { Result.success(it.content) }
        assertEquals(1, reduced.notes.size)
        assertEquals("new", reduced.notes.single().bodyMarkdown)
        assertEquals("b", reduced.selectedEvents.first { it.dTag() == noteDTag("note-1") }.id)
    }

    @Test
    fun reducerSelectsNewestPerDTagIndependentOfInputOrder() {
        val old = event("a", 10, "note-1", deleted = false, body = "old")
        val edited = event("b", 20, "note-1", deleted = false, body = "edited")
        val other = event("c", 15, "note-2", deleted = false, body = "other")

        val reduced = reduceNoteEvents(listOf(edited, other, old)) { Result.success(it.content) }

        assertEquals(setOf("edited", "other"), reduced.notes.map { it.bodyMarkdown }.toSet())
        assertEquals("b", reduced.selectedEvents.first { it.dTag() == noteDTag("note-1") }.id)
    }

    @Test
    fun reducerUsesEventIdTieBreakerForSameCreatedAt() {
        val low = event("aaa", 20, "note-1", deleted = false, body = "low")
        val high = event("zzz", 20, "note-1", deleted = false, body = "high")
        val reduced = reduceNoteEvents(listOf(high, low)) { Result.success(it.content) }
        assertEquals("low", reduced.notes.single().bodyMarkdown)
        assertEquals("aaa", reduced.selectedEvents.single().id)
    }

    @Test
    fun relayMigrationIdentifiesAddsAndRemovals() {
        val plan = planRelayMigration(listOf("wss://a.test", "wss://b.test"), listOf("wss://b.test", "wss://c.test"))
        assertEquals(listOf("wss://c.test"), plan.addedRelays)
        assertEquals(listOf("wss://a.test"), plan.removedRelays)
        assertEquals(listOf("wss://b.test"), plan.unchangedRelays)
        assertTrue(plan.migrationRequired)
        assertTrue(plan.shouldFetchBeforeRemoval)
        assertTrue(plan.shouldRepublishCurrentEvents)
    }

    @Test
    fun relayMigrationPlansNoOpAndEmptyRequestedLists() {
        val noOp = planRelayMigration(listOf("wss://a.test"), listOf("wss://a.test"))
        assertFalse(noOp.migrationRequired)
        assertFalse(noOp.shouldFetchBeforeRemoval)
        assertFalse(noOp.shouldRepublishCurrentEvents)

        val empty = planRelayMigration(listOf("wss://a.test"), emptyList())
        assertEquals(listOf("wss://a.test"), empty.removedRelays)
        assertTrue(empty.shouldFetchBeforeRemoval)
        assertFalse(empty.shouldRepublishCurrentEvents)
    }

    @Test
    fun relayMigrationLatestSelectionUsesReplaceableEventOrderingWithoutDecrypting() {
        val old = event("b-old", 10, "note-1", deleted = false, body = "cipher-old").copy(content = "cipher-old", sig = "valid")
        val edited = event("c-edited", 20, "note-1", deleted = false, body = "cipher-edited").copy(content = "cipher-edited", sig = "valid")
        val tombstone = event("a-tombstone", 20, "note-1", deleted = true, body = "").copy(content = "cipher-tombstone", sig = "valid")
        val other = event("d-other", 15, "note-2", deleted = false, body = "cipher-other").copy(content = "cipher-other", sig = "valid")

        val selected = selectLatestSignedEncryptedNoteEvents(
            events = listOf(old, edited, other, tombstone),
            accountPubkey = "pub",
            crypto = AcceptingValidationCrypto,
        )

        assertEquals(listOf("a-tombstone", "d-other"), selected.map { it.id })
        assertEquals("cipher-tombstone", selected.first().content)
    }

    @Test
    fun relayMigrationLatestSelectionRejectsInvalidOrWrongAccountEvents() {
        val valid = event("valid", 10, "note-1", deleted = false, body = "cipher").copy(content = "cipher", sig = "valid")
        val invalid = event("invalid", 20, "note-1", deleted = false, body = "cipher-new").copy(content = "cipher-new", sig = "invalid")
        val wrongAccount = valid.copy(id = "wrong", pubkey = "other-pub", createdAt = 30, sig = "valid")

        val selected = selectLatestSignedEncryptedNoteEvents(
            events = listOf(valid, invalid, wrongAccount),
            accountPubkey = "pub",
            crypto = AcceptingValidationCrypto,
        )

        assertEquals(listOf("valid"), selected.map { it.id })
    }

    @Test
    fun relayListParsesWriteReadAndUnmarkedRelays() {
        val parsed = parseRelayListEvent(
            relayListEvent(
                id = "relay-list",
                createdAt = 10,
                tags = listOf(
                    listOf("r", "relay.write.example", "write"),
                    listOf("r", "relay.read.example", "read"),
                    listOf("r", "relay.both.example"),
                    listOf("r", "https://bad.example", "write"),
                    listOf("client", "other-app"),
                ),
            ),
        )

        assertEquals(
            listOf("wss://relay.write.example", "wss://relay.both.example"),
            parsed.writeRelayUrls,
        )
        assertEquals(1, parsed.malformedRelayTagCount)
        assertEquals(listOf(listOf("client", "other-app")), parsed.preservedTags)
    }

    @Test
    fun relayListLatestUsesReplaceableOrdering() {
        val old = relayListEvent("b-old", 10, listOf(listOf("r", "wss://old.example", "write")))
        val newest = relayListEvent("a-new", 20, listOf(listOf("r", "wss://new.example", "write")))
        val sameTimeLowerId = relayListEvent("a-lower", 20, listOf(listOf("r", "wss://tie.example", "write")))
        val selected = latestRelayListEvent(listOf(old, newest, sameTimeLowerId), "pub", AcceptingValidationCrypto)

        assertEquals("a-lower", selected?.id)
    }

    @Test
    fun relayListMergeReplacesWriteRelaysAndPreservesOtherCategories() {
        val existing = parseRelayListEvent(
            relayListEvent(
                id = "existing",
                createdAt = 10,
                tags = listOf(
                    listOf("r", "wss://old-write.example", "write"),
                    listOf("r", "wss://read.example", "read"),
                    listOf("r", "wss://both.example"),
                    listOf("r", "wss://custom.example", "search", "extra"),
                    listOf("p", "some-public-metadata"),
                ),
            ),
        )

        val tags = mergeRelayListTags(existing, listOf("both.example", "new.example"))

        assertFalse(tags.contains(listOf("r", "wss://old-write.example", "write")))
        assertTrue(tags.contains(listOf("r", "wss://read.example", "read")))
        assertTrue(tags.contains(listOf("r", "wss://both.example")))
        assertTrue(tags.contains(listOf("r", "wss://custom.example", "search", "extra")))
        assertTrue(tags.contains(listOf("p", "some-public-metadata")))
        assertTrue(tags.contains(listOf("r", "wss://new.example", "write")))
    }

    @Test
    fun markdownTruncationStripsCommonMarkup() {
        val truncated = truncateMarkdown("# Heading\n\n**bold** ~text~", maxChars = 20)
        assertFalse(truncated.contains("#"))
        assertFalse(truncated.contains("~"))
        assertTrue(truncated.startsWith("Heading"))
    }

    @Test
    fun noteCardPreviewIgnoresLeadingBlankLinesAndUsesFirstContentLine() {
        val preview = noteCardPreview("\n\n  # Launch notes  \n\nFollow-up item")

        assertEquals("Launch notes", preview.title)
        assertEquals("Follow-up item", preview.snippet)
    }

    @Test
    fun noteCardPreviewSoftensCommonMarkdownMarkersForDisplayOnly() {
        val raw = "**Important** update\n> `relay.send` and ~review~"
        val preview = noteCardPreview(raw)

        assertEquals("Important update", preview.title)
        assertEquals("relay.send and review", preview.snippet)
        assertEquals("**Important** update\n> `relay.send` and ~review~", raw)
    }

    @Test
    fun noteCardPreviewKeepsFencedCodeStartReadable() {
        val preview = noteCardPreview("```kotlin\nval count = 2\nprintln(count)\n```")

        assertEquals("Code block", preview.title)
        assertEquals("val count = 2 println(count)", preview.snippet)
    }

    @Test
    fun noteCardPreviewHandlesEmptyOrBlankNotesSafely() {
        assertEquals("Untitled note", noteCardPreview("").title)
        assertEquals("", noteCardPreview(" \n\t ").snippet)
    }

    @Test
    fun noteCardPreviewKeepsImageUrlsAsRawText() {
        val preview = noteCardPreview("https://example.com/image.png\nmore text")

        assertEquals("https://example.com/image.png", preview.title)
        assertEquals("more text", preview.snippet)
    }

    @Test
    fun markdownBlocksPreserveHeadingsAndFencedCodeBlocks() {
        val blocks = markdownBlocks("# Header\n\n```kotlin\n**literal**\n`code`\n```\n\nPlain")

        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "Header"),
                MarkdownBlock.CodeBlock("**literal**\n`code`"),
                MarkdownBlock.Paragraph("Plain"),
            ),
            blocks,
        )
    }

    @Test
    fun markdownBlocksParseBlockquoteWithoutLiteralMarker() {
        val blocks = markdownBlocks("> quoted line\n> second line\n\nnormal")

        assertEquals(
            listOf(
                MarkdownBlock.BlockQuote("quoted line\nsecond line"),
                MarkdownBlock.Paragraph("normal"),
            ),
            blocks,
        )
    }

    @Test
    fun markdownSpansParseCommonInlineStyles() {
        val spans = markdownSpans("this is **bold** and *italic* and ~strike~ and ~~gone~~ and `code`")

        assertEquals(
            listOf(
                MarkdownSpan.Text("this is "),
                MarkdownSpan.Bold("bold"),
                MarkdownSpan.Text(" and "),
                MarkdownSpan.Italic("italic"),
                MarkdownSpan.Text(" and "),
                MarkdownSpan.Strike("strike"),
                MarkdownSpan.Text(" and "),
                MarkdownSpan.Strike("gone"),
                MarkdownSpan.Text(" and "),
                MarkdownSpan.Code("code"),
            ),
            spans,
        )
    }

    @Test
    fun markdownInlineCodeDoesNotParseNestedMarkers() {
        assertEquals(
            listOf(MarkdownSpan.Code("**bold** *italic* ~strike~")),
            markdownSpans("`**bold** *italic* ~strike~`"),
        )
    }

    @Test
    fun markdownSpansLinkifyBareHttpUrlsAndMarkdownLinks() {
        assertEquals(
            listOf(
                MarkdownSpan.Text("Visit "),
                MarkdownSpan.Link("https://example.com/path", "https://example.com/path"),
                MarkdownSpan.Text(" or "),
                MarkdownSpan.Link("docs", "http://example.com/docs"),
                MarkdownSpan.Text("."),
            ),
            markdownSpans("Visit https://example.com/path or [docs](http://example.com/docs)."),
        )
    }

    @Test
    fun markdownSpansRenderMarkdownImagesForSupportedHttpsRasterUrls() {
        assertEquals(
            listOf(
                MarkdownSpan.Text("Look "),
                MarkdownSpan.Image("alt text", "https://example.com/image.png"),
            ),
            markdownSpans("Look ![alt text](https://example.com/image.png)"),
        )
    }

    @Test
    fun markdownSpansRenderBareHttpsImageUrlsAsImages() {
        listOf(
            "https://example.com/image.jpg",
            "https://example.com/image.jpeg",
            "https://example.com/image.png",
            "https://example.com/image.webp",
            "https://example.com/image.gif",
            "https://example.com/image.JPG",
            "https://example.com/image.Png?width=1200",
            "https://example.com/image.WEBP#preview",
        ).forEach { url ->
            assertEquals(listOf(MarkdownSpan.Image("", url)), markdownSpans(url), "Expected image token for $url")
        }
    }

    @Test
    fun markdownSpansRejectUnsafeOrUnsupportedImages() {
        assertEquals(
            listOf(MarkdownSpan.Link("https://example.com/image.svg", "https://example.com/image.svg")),
            markdownSpans("https://example.com/image.svg"),
        )
        assertEquals(
            listOf(MarkdownSpan.Text("![x](data:image/png;base64,AAAA)")),
            markdownSpans("![x](data:image/png;base64,AAAA)"),
        )
        assertEquals(
            listOf(MarkdownSpan.Text("![x](file:///tmp/image.png)")),
            markdownSpans("![x](file:///tmp/image.png)"),
        )
    }

    @Test
    fun profileImageUrlValidationAllowsOnlySupportedHttpsRasterImages() {
        listOf(
            "https://example.com/avatar.jpg",
            "https://example.com/avatar.jpeg",
            "https://example.com/avatar.png",
            "https://example.com/avatar.webp",
            "https://example.com/avatar.gif",
            "https://example.com/avatar.JPG",
            "https://example.com/avatar.Png?size=128",
            "https://example.com/avatar.WEBP#profile",
        ).forEach { url ->
            assertTrue(isSupportedRemoteImageUrl(url), "Expected supported profile image URL: $url")
        }
        listOf(
            "http://example.com/avatar.png",
            "data:image/png;base64,AAAA",
            "file:///tmp/avatar.png",
            "content://avatar.png",
            "javascript:alert(1)",
            "/avatar.png",
            "avatar.png",
            "https://example.com/avatar.svg",
        ).forEach { url ->
            assertFalse(isSupportedRemoteImageUrl(url), "Expected unsupported profile image URL: $url")
        }
    }

    @Test
    fun malformedMarkdownMarkersStayReadable() {
        val spans = markdownSpans("plain **unclosed and *also unclosed")

        assertEquals(
            listOf(
                MarkdownSpan.Text("plain "),
                MarkdownSpan.Text("**"),
                MarkdownSpan.Text("unclosed and "),
                MarkdownSpan.Text("*"),
                MarkdownSpan.Text("also unclosed"),
            ),
            spans,
        )
    }

    @Test
    fun noteGridColumnPolicyUsesTwoColumnsForNormalPhoneWidths() {
        assertEquals(1, noteGridColumnCount(300))
        assertEquals(2, noteGridColumnCount(336))
        assertEquals(2, noteGridColumnCount(600))
    }

    @Test
    fun noteGridColumnPolicyAdaptsForDesktopWidths() {
        assertEquals(3, noteGridColumnCount(840))
        assertEquals(5, noteGridColumnCount(1_440))
        assertEquals(6, noteGridColumnCount(2_400))
    }

    private fun event(id: String, createdAt: Long, noteId: String, deleted: Boolean, body: String): NostrEvent {
        val payload = NotePayload(noteId = noteId, createdAtMs = 1, updatedAtMs = createdAt * 1000, bodyMarkdown = body, deleted = deleted)
        return NostrEvent(
            id = id,
            pubkey = "pub",
            createdAt = createdAt,
            kind = 30078,
            tags = listOf(listOf("d", noteDTag(noteId)), listOf("t", "other-note")),
            content = JsonNotePayloadCodec.encode(payload),
            sig = "sig",
        )
    }

    private fun relayListEvent(id: String, createdAt: Long, tags: List<List<String>>): NostrEvent =
        NostrEvent(
            id = id,
            pubkey = "pub",
            createdAt = createdAt,
            kind = RelayListKind,
            tags = tags,
            content = "",
            sig = "valid",
        )
}

private fun assertPrimaryErrorCopyIsReadable(message: String) {
    val rawKeys = listOf(
        "stage=",
        "outcome=",
        "candidate_events",
        "publish_accepted_count",
        "response_fetch_timed_out",
        "NoSuchFileException",
        "secret=",
        "privateKey",
        "body_markdown",
    )
    rawKeys.forEach { key ->
        assertFalse(message.contains(key), "Primary error copy should not contain $key")
    }
}

private object AcceptingValidationCrypto : NostrCrypto {
    override val productionReady: Boolean = true
    override fun generatePrivateKey(): Result<NostrPrivateKey> = Result.failure(UnsupportedOperationException())
    override fun encodeNsec(privateKey: NostrPrivateKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun encodeNpub(publicKey: NostrPublicKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun decodeNsec(nsec: String): KeyDecodeResult = KeyDecodeResult.Invalid("unused")
    override fun derivePublicKey(privateKey: NostrPrivateKey): Result<NostrPublicKey> = Result.failure(UnsupportedOperationException())
    override fun encryptToSelf(plaintext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun decryptFromSelf(ciphertext: String, privateKey: NostrPrivateKey, publicKey: NostrPublicKey): Result<String> = Result.failure(UnsupportedOperationException())
    override fun computeEventId(unsigned: UnsignedNostrEvent): Result<String> = Result.failure(UnsupportedOperationException())
    override fun sign(unsigned: UnsignedNostrEvent, privateKey: NostrPrivateKey): Result<NostrEvent> = Result.failure(UnsupportedOperationException())
    override fun validate(event: NostrEvent): Result<Boolean> = Result.success(event.sig == "valid")
}

private class MemoryRelaySettingsPersistence : RelaySettingsPersistence {
    var raw: String? = null

    override suspend fun loadRelayUrls(): List<String>? =
        raw?.let { RelaySettingsCodec.decodeOrNull(it) }

    override suspend fun saveRelayUrls(urls: List<String>) {
        raw = RelaySettingsCodec.encode(urls)
    }
}
