package com.libertasprimordium.othernote.util

import com.libertasprimordium.othernote.domain.Note

fun filterVisibleNotesBySearchQuery(notes: List<Note>, query: String): List<Note> {
    val visibleNotes = notes.filterNot { it.deleted }
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return visibleNotes
    return visibleNotes.filter { note ->
        note.bodyMarkdown.contains(trimmedQuery, ignoreCase = true)
    }
}
