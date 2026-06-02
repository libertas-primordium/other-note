package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.NoteListPreferenceStore
import com.libertasprimordium.othernote.data.ThemePreferenceStore
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.security.InMemoryNip46SessionStore
import com.libertasprimordium.othernote.security.InMemoryNip55SessionStore
import com.libertasprimordium.othernote.security.Nip46SessionStoreResult
import com.libertasprimordium.othernote.security.Nip55SessionStoreResult
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.AppState
import com.libertasprimordium.othernote.util.BuiltInNoteSortOptions
import com.libertasprimordium.othernote.util.DefaultNoteSortOption
import com.libertasprimordium.othernote.util.noteDisplayTitle
import com.libertasprimordium.othernote.util.noteListDisplayNotes
import com.libertasprimordium.othernote.util.noteSortOptionForId
import com.libertasprimordium.othernote.util.sortVisibleNotes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteListSortTests {
    @Test
    fun defaultSortIsLastEditedNewestFirst() {
        val notes = listOf(
            note("old", updatedAtMs = 1),
            note("new", updatedAtMs = 3),
            note("middle", updatedAtMs = 2),
        )

        assertEquals(listOf("new", "middle", "old"), sortVisibleNotes(notes, DefaultNoteSortOption).map { it.id })
    }

    @Test
    fun sortOptionsHaveStableIdsAndLabels() {
        assertEquals(
            listOf(
                "last-edited-newest",
                "last-edited-oldest",
                "created-newest",
                "created-oldest",
                "title-a-z",
                "title-z-a",
            ),
            BuiltInNoteSortOptions.map { it.id },
        )
        assertEquals(
            listOf(
                "Last edited: newest first",
                "Last edited: oldest first",
                "Created: newest first",
                "Created: oldest first",
                "Title: A-Z",
                "Title: Z-A",
            ),
            BuiltInNoteSortOptions.map { it.label },
        )
        assertEquals(BuiltInNoteSortOptions.size, BuiltInNoteSortOptions.map { it.id }.toSet().size)
    }

    @Test
    fun unknownSortIdFallsBackToDefault() {
        assertEquals(DefaultNoteSortOption, noteSortOptionForId("not-real"))
        assertEquals(DefaultNoteSortOption, noteSortOptionForId(null))
    }

    @Test
    fun lastEditedOldestFirstReversesTimestampOrder() {
        val notes = listOf(
            note("new", updatedAtMs = 3),
            note("old", updatedAtMs = 1),
            note("middle", updatedAtMs = 2),
        )

        assertEquals(
            listOf("old", "middle", "new"),
            sortVisibleNotes(notes, noteSortOptionForId("last-edited-oldest")).map { it.id },
        )
    }

    @Test
    fun equalTimestampTieKeepsInputOrder() {
        val notes = listOf(
            note("first", updatedAtMs = 2),
            note("second", updatedAtMs = 2),
            note("third", updatedAtMs = 2),
        )

        assertEquals(listOf("first", "second", "third"), sortVisibleNotes(notes, DefaultNoteSortOption).map { it.id })
    }

    @Test
    fun createdDateSortUsesCreatedTimestampNotUpdatedTimestamp() {
        val notes = listOf(
            note("created-old-edited-new", createdAtMs = 1, updatedAtMs = 10),
            note("created-new-edited-old", createdAtMs = 5, updatedAtMs = 6),
            note("created-middle", createdAtMs = 3, updatedAtMs = 7),
        )

        assertEquals(
            listOf("created-new-edited-old", "created-middle", "created-old-edited-new"),
            sortVisibleNotes(notes, noteSortOptionForId("created-newest")).map { it.id },
        )
        assertEquals(
            listOf("created-old-edited-new", "created-middle", "created-new-edited-old"),
            sortVisibleNotes(notes, noteSortOptionForId("created-oldest")).map { it.id },
        )
    }

    @Test
    fun titleSortUsesFirstNonBlankLineAndHandlesBlankBodies() {
        val notes = listOf(
            note("blank", bodyMarkdown = "   \n "),
            note("banana", bodyMarkdown = "\n\nBanana note\nbody"),
            note("apple", bodyMarkdown = "apple note"),
            note("cherry", bodyMarkdown = "Cherry note"),
        )

        assertEquals("Banana note", noteDisplayTitle(notes[1]))
        assertEquals(
            listOf("apple", "banana", "cherry", "blank"),
            sortVisibleNotes(notes, noteSortOptionForId("title-a-z")).map { it.id },
        )
        assertEquals(
            listOf("cherry", "banana", "apple", "blank"),
            sortVisibleNotes(notes, noteSortOptionForId("title-z-a")).map { it.id },
        )
    }

    @Test
    fun sortDoesNotReturnDeletedNotes() {
        val notes = listOf(
            note("visible", bodyMarkdown = "Visible", updatedAtMs = 1),
            note("deleted", bodyMarkdown = "Deleted", updatedAtMs = 5, deleted = true),
        )

        assertEquals(listOf("visible"), sortVisibleNotes(notes, DefaultNoteSortOption).map { it.id })
    }

    @Test
    fun searchResultsRespectSelectedSortAndMarkdownSourceSearchStillWorks() {
        val notes = listOf(
            note("older-heading", bodyMarkdown = "# Project", updatedAtMs = 1),
            note("newer-bold", bodyMarkdown = "**Project** details", updatedAtMs = 5),
            note("other", bodyMarkdown = "`code`", updatedAtMs = 9),
        )

        assertEquals(
            listOf("older-heading", "newer-bold"),
            noteListDisplayNotes(notes, "project", "last-edited-oldest").map { it.id },
        )
        assertEquals(
            listOf("other"),
            noteListDisplayNotes(notes, "code", "last-edited-newest").map { it.id },
        )
    }

    @Test
    fun clearingSearchKeepsSelectedSortOrder() {
        val notes = listOf(
            note("zebra", bodyMarkdown = "Zebra"),
            note("apple", bodyMarkdown = "Apple"),
        )

        assertEquals(listOf("apple", "zebra"), noteListDisplayNotes(notes, "", "title-a-z").map { it.id })
    }

    @Test
    fun accountScopedOrLocalOnlyVisibleListsSortLocally() {
        val currentVisibleNotes = listOf(
            note("local", bodyMarkdown = "Local", updatedAtMs = 1),
            note("current", bodyMarkdown = "Current account", updatedAtMs = 2),
        )

        assertEquals(listOf("current", "local"), noteListDisplayNotes(currentVisibleNotes, "", null).map { it.id })
    }

    @Test
    fun defaultPreferenceIsLastEditedNewestFirst() {
        val state = AppState()

        assertEquals(DefaultNoteSortOption.id, state.selectedNoteSortId.value)
    }

    @Test
    fun sortPreferencePersistsAndReloads() = runBlocking {
        val store = MemoryNoteListPreferenceStore()
        val firstState = AppState(sortServices(store))

        firstState.selectNoteSort("title-z-a")
        withTimeout(2_000) {
            while (store.savedSortId != "title-z-a") yield()
        }

        val restarted = AppState(sortServices(store))
        withTimeout(2_000) {
            while (restarted.selectedNoteSortId.value != "title-z-a") yield()
        }

        assertEquals("title-z-a", restarted.selectedNoteSortId.value)
    }

    @Test
    fun corruptSortPreferenceFallsBackToDefault() = runBlocking {
        val state = AppState(sortServices(MemoryNoteListPreferenceStore(initialSortId = "bad-sort")))

        withTimeout(2_000) {
            while (state.selectedNoteSortId.value != DefaultNoteSortOption.id) yield()
        }

        assertEquals(DefaultNoteSortOption.id, state.selectedNoteSortId.value)
    }

    @Test
    fun sortPreferenceIsIndependentOfThemeAndSignerSessionStores() = runBlocking {
        val sortStore = MemoryNoteListPreferenceStore()
        val themeStore = SortTestThemePreferenceStore()
        val nip55Store = InMemoryNip55SessionStore()
        val nip46Store = InMemoryNip46SessionStore()
        val state = AppState(
            sortServices(
                sortStore = sortStore,
                themeStore = themeStore,
                nip55Store = nip55Store,
                nip46Store = nip46Store,
            ),
        )

        state.selectNoteSort("created-oldest")
        withTimeout(2_000) {
            while (sortStore.savedSortId != "created-oldest") yield()
        }

        assertEquals(null, themeStore.savedThemeId)
        assertTrue((nip55Store.listSessions() as Nip55SessionStoreResult.Listed).sessions.isEmpty())
        assertTrue((nip46Store.listSessions() as Nip46SessionStoreResult.Listed).sessions.isEmpty())
    }

    private fun note(
        id: String,
        bodyMarkdown: String = id,
        createdAtMs: Long = 1,
        updatedAtMs: Long = 1,
        deleted: Boolean = false,
    ): Note = Note(
        id = id,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        bodyMarkdown = bodyMarkdown,
        deleted = deleted,
    )

    private fun sortServices(
        sortStore: NoteListPreferenceStore,
        themeStore: ThemePreferenceStore = SortTestThemePreferenceStore(),
        nip55Store: InMemoryNip55SessionStore = InMemoryNip55SessionStore(),
        nip46Store: InMemoryNip46SessionStore = InMemoryNip46SessionStore(),
    ): AppServices = AppServices(
        mode = AppRuntimeMode.Offline,
        crypto = NonProductionNostrCrypto(),
        client = OfflineNostrClient(),
        noteListPreferenceStore = sortStore,
        themePreferenceStore = themeStore,
        nip55SessionStore = nip55Store,
        nip46SessionStore = nip46Store,
    )
}

private class MemoryNoteListPreferenceStore(initialSortId: String? = null) : NoteListPreferenceStore {
    var savedSortId: String? = initialSortId
        private set

    override suspend fun loadSortId(): String? = savedSortId

    override suspend fun saveSortId(sortId: String) {
        savedSortId = sortId
    }
}

private class SortTestThemePreferenceStore(initialThemeId: String? = null) : ThemePreferenceStore {
    var savedThemeId: String? = initialThemeId
        private set

    override suspend fun loadThemeId(): String? = savedThemeId

    override suspend fun saveThemeId(themeId: String) {
        savedThemeId = themeId
    }
}
