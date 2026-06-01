package com.libertasprimordium.othernote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OtherNoteBlack = Color(0xFF050507)
val OtherNotePanel = Color(0xFF111116)
val OtherNotePurple = Color(0xFF8E44FF)
val OtherNotePurpleDark = Color(0xFF2A0F45)
val OtherNoteText = Color(0xFFF5F1FF)
val OtherNoteMuted = Color(0xFFB8AEC8)

@Composable
fun OtherNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = OtherNoteBlack,
            surface = OtherNotePanel,
            primary = OtherNotePurple,
            secondary = Color(0xFF35D0BA),
            onBackground = OtherNoteText,
            onSurface = OtherNoteText,
            onPrimary = Color.White,
        ),
        content = content,
    )
}
