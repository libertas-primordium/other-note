package com.libertasprimordium.othernote

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.libertasprimordium.othernote.ui.OtherNoteApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Other Note",
    ) {
        OtherNoteApp()
    }
}
