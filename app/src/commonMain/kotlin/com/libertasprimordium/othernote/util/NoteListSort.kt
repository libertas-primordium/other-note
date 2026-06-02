package com.libertasprimordium.othernote.util

import com.libertasprimordium.othernote.domain.Note

data class NoteSortOption(
    val id: String,
    val label: String,
)

val DefaultNoteSortOption = NoteSortOption(
    id = "last-edited-newest",
    label = "Last edited: newest first",
)

val BuiltInNoteSortOptions: List<NoteSortOption> = listOf(
    DefaultNoteSortOption,
    NoteSortOption(
        id = "last-edited-oldest",
        label = "Last edited: oldest first",
    ),
    NoteSortOption(
        id = "created-newest",
        label = "Created: newest first",
    ),
    NoteSortOption(
        id = "created-oldest",
        label = "Created: oldest first",
    ),
    NoteSortOption(
        id = "title-a-z",
        label = "Title: A-Z",
    ),
    NoteSortOption(
        id = "title-z-a",
        label = "Title: Z-A",
    ),
)

fun noteSortOptionForId(id: String?): NoteSortOption =
    BuiltInNoteSortOptions.firstOrNull { it.id == id } ?: DefaultNoteSortOption

fun noteListDisplayNotes(notes: List<Note>, query: String, sortId: String?): List<Note> {
    val searched = filterVisibleNotesBySearchQuery(notes, query)
    return sortVisibleNotes(searched, noteSortOptionForId(sortId))
}

fun sortVisibleNotes(notes: List<Note>, option: NoteSortOption): List<Note> {
    val visibleNotes = notes.filterNot { it.deleted }
    val comparator = when (option.id) {
        "last-edited-oldest" -> compareBy<Note> { it.updatedAtMs }
        "created-newest" -> compareByDescending<Note> { it.createdAtMs }
        "created-oldest" -> compareBy<Note> { it.createdAtMs }
        "title-a-z" -> titleComparator(descending = false)
        "title-z-a" -> titleComparator(descending = true)
        else -> compareByDescending { it.updatedAtMs }
    }
    return visibleNotes.withIndex()
        .sortedWith { left, right ->
            val compared = comparator.compare(left.value, right.value)
            if (compared != 0) compared else left.index.compareTo(right.index)
        }
        .map { it.value }
}

fun noteDisplayTitle(note: Note): String =
    note.bodyMarkdown
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()

private fun titleComparator(descending: Boolean): Comparator<Note> = Comparator { left, right ->
    val leftTitle = noteDisplayTitle(left)
    val rightTitle = noteDisplayTitle(right)
    val leftBlank = leftTitle.isBlank()
    val rightBlank = rightTitle.isBlank()
    when {
        leftBlank && !rightBlank -> 1
        !leftBlank && rightBlank -> -1
        else -> {
            val compared = leftTitle.lowercase().compareTo(rightTitle.lowercase())
            if (descending) -compared else compared
        }
    }
}
