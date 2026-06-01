package com.libertasprimordium.othernote

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.libertasprimordium.othernote.ui.OtherNoteApp

fun main() = application {
    val services = DesktopAppServicesFactory.create()
    Window(
        onCloseRequest = ::exitApplication,
        title = "Other Note",
    ) {
        OtherNoteApp(services)
    }
}
