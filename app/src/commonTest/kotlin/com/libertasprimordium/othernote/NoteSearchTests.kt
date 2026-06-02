package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.util.filterVisibleNotesBySearchQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteSearchTests {
    @Test
    fun emptyAndWhitespaceQueriesReturnVisibleNotesInOriginalOrder() {
        val notes = listOf(
            note("newest", "Newest note", updatedAtMs = 3),
            note("middle", "Middle note", updatedAtMs = 2),
            note("oldest", "Oldest note", updatedAtMs = 1),
        )

        assertEquals(notes, filterVisibleNotesBySearchQuery(notes, ""))
        assertEquals(notes, filterVisibleNotesBySearchQuery(notes, "   \n\t  "))
    }

    @Test
    fun searchIsCaseInsensitiveAndTrimsQuery() {
        val notes = listOf(
            note("one", "Mixed CASE body"),
            note("two", "different body"),
        )

        assertEquals(listOf("one"), filterVisibleNotesBySearchQuery(notes, "  case  ").map { it.id })
    }

    @Test
    fun noMatchReturnsEmptyListAndClearingQueryRestoresList() {
        val notes = listOf(
            note("one", "alpha"),
            note("two", "beta"),
        )

        assertTrue(filterVisibleNotesBySearchQuery(notes, "gamma").isEmpty())
        assertEquals(notes, filterVisibleNotesBySearchQuery(notes, ""))
    }

    @Test
    fun matchedNotesKeepTheirRelativeOrder() {
        val notes = listOf(
            note("first", "project apple"),
            note("second", "project banana"),
            note("third", "personal apple"),
            note("fourth", "project cherry"),
        )

        assertEquals(
            listOf("first", "second", "fourth"),
            filterVisibleNotesBySearchQuery(notes, "project").map { it.id },
        )
    }

    @Test
    fun searchMatchesRawMarkdownSourceText() {
        val notes = listOf(
            note("heading", "# Launch Plan"),
            note("bold", "Remember **Important Detail**"),
            note("code", "Use `relay.send()` here"),
            note("other", "Plain text"),
        )

        assertEquals(listOf("heading"), filterVisibleNotesBySearchQuery(notes, "launch").map { it.id })
        assertEquals(listOf("bold"), filterVisibleNotesBySearchQuery(notes, "important detail").map { it.id })
        assertEquals(listOf("code"), filterVisibleNotesBySearchQuery(notes, "relay.send").map { it.id })
    }

    @Test
    fun deletedNotesAreNeverReturnedBySearch() {
        val notes = listOf(
            note("visible", "matching text"),
            note("deleted", "matching text", deleted = true),
        )

        assertEquals(listOf("visible"), filterVisibleNotesBySearchQuery(notes, "matching").map { it.id })
        assertEquals(listOf("visible"), filterVisibleNotesBySearchQuery(notes, "").map { it.id })
    }

    @Test
    fun accountScopedOrLocalOnlyVisibleListsFilterLocally() {
        val currentAccountVisibleNotes = listOf(
            note("local", "local-only draft"),
            note("current", "current account note"),
        )

        assertEquals(
            listOf("current"),
            filterVisibleNotesBySearchQuery(currentAccountVisibleNotes, "account").map { it.id },
        )
    }

    private fun note(
        id: String,
        bodyMarkdown: String,
        updatedAtMs: Long = 1,
        deleted: Boolean = false,
    ): Note = Note(
        id = id,
        createdAtMs = 1,
        updatedAtMs = updatedAtMs,
        bodyMarkdown = bodyMarkdown,
        deleted = deleted,
    )
}
