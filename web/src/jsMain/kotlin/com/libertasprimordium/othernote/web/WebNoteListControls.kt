package com.libertasprimordium.othernote.web

internal data class WebNoteSortOption(
    val id: String,
    val label: String,
)

internal val DefaultWebNoteSortOption = WebNoteSortOption(
    id = "last-edited-newest",
    label = "Last edited: newest first",
)

internal val BuiltInWebNoteSortOptions: List<WebNoteSortOption> = listOf(
    DefaultWebNoteSortOption,
    WebNoteSortOption(
        id = "last-edited-oldest",
        label = "Last edited: oldest first",
    ),
    WebNoteSortOption(
        id = "created-newest",
        label = "Created: newest first",
    ),
    WebNoteSortOption(
        id = "created-oldest",
        label = "Created: oldest first",
    ),
    WebNoteSortOption(
        id = "title-a-z",
        label = "Title: A-Z",
    ),
    WebNoteSortOption(
        id = "title-z-a",
        label = "Title: Z-A",
    ),
)

internal data class WebNoteListControlsState(
    val searchQuery: String = "",
    val sortId: String = DefaultWebNoteSortOption.id,
)

internal fun resetWebNoteListControls(): WebNoteListControlsState =
    WebNoteListControlsState()

internal fun webNoteSortOptionForId(id: String?): WebNoteSortOption =
    BuiltInWebNoteSortOptions.firstOrNull { it.id == id } ?: DefaultWebNoteSortOption

internal fun webNoteListDisplayNotes(
    notes: List<WebReadOnlyNote>,
    controls: WebNoteListControlsState,
): List<WebReadOnlyNote> {
    val searched = filterVisibleWebNotesBySearchQuery(notes, controls.searchQuery)
    return sortVisibleWebNotes(searched, webNoteSortOptionForId(controls.sortId))
}

internal fun filterVisibleWebNotesBySearchQuery(notes: List<WebReadOnlyNote>, query: String): List<WebReadOnlyNote> {
    val visibleNotes = notes.filterNot { it.deleted }
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return visibleNotes
    return visibleNotes.filter { note ->
        note.bodyMarkdown.contains(trimmedQuery, ignoreCase = true)
    }
}

internal fun sortVisibleWebNotes(notes: List<WebReadOnlyNote>, option: WebNoteSortOption): List<WebReadOnlyNote> {
    val visibleNotes = notes.filterNot { it.deleted }
    val comparator = when (option.id) {
        "last-edited-oldest" -> compareBy<WebReadOnlyNote> { it.updatedAtMs }
        "created-newest" -> compareByDescending<WebReadOnlyNote> { it.createdAtMs }
        "created-oldest" -> compareBy<WebReadOnlyNote> { it.createdAtMs }
        "title-a-z" -> webNoteTitleComparator(descending = false)
        "title-z-a" -> webNoteTitleComparator(descending = true)
        else -> compareByDescending { it.updatedAtMs }
    }
    return visibleNotes.withIndex()
        .sortedWith { left, right ->
            val compared = comparator.compare(left.value, right.value)
            if (compared != 0) compared else left.index.compareTo(right.index)
        }
        .map { it.value }
}

internal fun webNoteDisplayTitle(note: WebReadOnlyNote): String =
    note.bodyMarkdown
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()

private fun webNoteTitleComparator(descending: Boolean): Comparator<WebReadOnlyNote> = Comparator { left, right ->
    val leftTitle = webNoteDisplayTitle(left)
    val rightTitle = webNoteDisplayTitle(right)
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
