package com.libertasprimordium.othernote.ui

enum class NoteCardAction {
    Open,
    Edit,
    Delete,
}

enum class NoteCardActionPresentation {
    VisibleButtons,
    LongPressMenu,
}

data class NoteCardActionItem(
    val action: NoteCardAction,
    val label: String,
    val accessibilityLabel: String,
    val destructive: Boolean = false,
)

data class DeleteNoteConfirmationText(
    val title: String = "Delete note?",
    val body: String = "This hides the note and syncs a deletion update to your relays.",
    val cancelLabel: String = "Cancel",
    val deleteLabel: String = "Delete",
)

data class NoteCardActionMenuText(
    val title: String = "Note actions",
    val cancelLabel: String = "Cancel",
)

fun noteCardActionItems(): List<NoteCardActionItem> = listOf(
    NoteCardActionItem(
        action = NoteCardAction.Open,
        label = "Open",
        accessibilityLabel = "Open note",
    ),
    NoteCardActionItem(
        action = NoteCardAction.Edit,
        label = "Edit",
        accessibilityLabel = "Edit note",
    ),
    NoteCardActionItem(
        action = NoteCardAction.Delete,
        label = "Delete",
        accessibilityLabel = "Delete note",
        destructive = true,
    ),
)

fun noteDeleteConfirmationText(): DeleteNoteConfirmationText = DeleteNoteConfirmationText()

fun noteCardActionMenuText(): NoteCardActionMenuText = NoteCardActionMenuText()

fun noteCardActionPresentation(
    platform: AppPlatform,
    availableWidthDp: Int,
): NoteCardActionPresentation =
    when {
        platform == AppPlatform.Android -> NoteCardActionPresentation.LongPressMenu
        availableWidthDp < 220 -> NoteCardActionPresentation.LongPressMenu
        else -> NoteCardActionPresentation.VisibleButtons
    }
