package com.libertasprimordium.othernote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class OtherNoteThemeColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val appBar: Color,
    val buttonBackground: Color,
    val buttonText: Color,
    val border: Color,
    val textFieldBackground: Color,
    val textFieldBorder: Color,
    val error: Color,
    val codeBackground: Color,
    val codeText: Color,
)

data class OtherNoteThemeDefinition(
    val id: String,
    val displayName: String,
    val dark: Boolean,
    val colors: OtherNoteThemeColors,
)

val NostrClassicTheme = OtherNoteThemeDefinition(
    id = "nostr-classic",
    displayName = "Nostr Classic",
    dark = true,
    colors = OtherNoteThemeColors(
        background = Color(0xFF050507),
        surface = Color(0xFF111116),
        text = Color(0xFFF5F1FF),
        muted = Color(0xFFB8AEC8),
        accent = Color(0xFF8E44FF),
        appBar = Color(0xFF2A0F45),
        buttonBackground = Color(0xFF8E44FF),
        buttonText = Color.White,
        border = Color(0xFF6C4A90),
        textFieldBackground = Color(0xFF111116),
        textFieldBorder = Color(0xFF6C4A90),
        error = Color(0xFFFF6B8A),
        codeBackground = Color(0xFF191020),
        codeText = Color(0xFFE8D7FF),
    ),
)

val BuiltInOtherNoteThemes: List<OtherNoteThemeDefinition> = listOf(
    NostrClassicTheme,
    OtherNoteThemeDefinition(
        id = "urban",
        displayName = "Urban",
        dark = false,
        colors = OtherNoteThemeColors(
            background = Color(0xFFE7E7E3),
            surface = Color(0xFFF7F7F3),
            text = Color(0xFF121212),
            muted = Color(0xFF525252),
            accent = Color(0xFFD96514),
            appBar = Color(0xFFC9CBC7),
            buttonBackground = Color(0xFFD96514),
            buttonText = Color(0xFF111111),
            border = Color(0xFF4A4D50),
            textFieldBackground = Color(0xFFF7F7F3),
            textFieldBorder = Color(0xFF4A4D50),
            error = Color(0xFFB3261E),
            codeBackground = Color(0xFF2B2D2F),
            codeText = Color(0xFFFFD2A8),
        ),
    ),
    OtherNoteThemeDefinition(
        id = "hacker",
        displayName = "Hacker",
        dark = true,
        colors = OtherNoteThemeColors(
            background = Color(0xFF020403),
            surface = Color(0xFF07100B),
            text = Color(0xFFD7F9DF),
            muted = Color(0xFF8AB796),
            accent = Color(0xFF3CD45B),
            appBar = Color(0xFF050A07),
            buttonBackground = Color(0xFF07100B),
            buttonText = Color(0xFF3CD45B),
            border = Color(0xFF2AA847),
            textFieldBackground = Color(0xFF07100B),
            textFieldBorder = Color(0xFF2AA847),
            error = Color(0xFFFF5F5F),
            codeBackground = Color(0xFF000000),
            codeText = Color(0xFFA7FFB8),
        ),
    ),
    OtherNoteThemeDefinition(
        id = "papyrus",
        displayName = "Papyrus",
        dark = false,
        colors = OtherNoteThemeColors(
            background = Color(0xFFFBF5E7),
            surface = Color(0xFFFFFAEF),
            text = Color(0xFF17130D),
            muted = Color(0xFF574B38),
            accent = Color(0xFFD9BE82),
            appBar = Color(0xFFE7D2A0),
            buttonBackground = Color(0xFFD9BE82),
            buttonText = Color(0xFF17130D),
            border = Color(0xFF1F1A12),
            textFieldBackground = Color(0xFFFFFAEF),
            textFieldBorder = Color(0xFF1F1A12),
            error = Color(0xFF9F2D22),
            codeBackground = Color(0xFFEFE1C0),
            codeText = Color(0xFF17130D),
        ),
    ),
    OtherNoteThemeDefinition(
        id = "harbor",
        displayName = "Harbor",
        dark = true,
        colors = OtherNoteThemeColors(
            background = Color(0xFF06131A),
            surface = Color(0xFF0D2029),
            text = Color(0xFFE9F8FF),
            muted = Color(0xFFA3C1CC),
            accent = Color(0xFF38C6D9),
            appBar = Color(0xFF082531),
            buttonBackground = Color(0xFF38C6D9),
            buttonText = Color(0xFF06131A),
            border = Color(0xFF338C9C),
            textFieldBackground = Color(0xFF0D2029),
            textFieldBorder = Color(0xFF338C9C),
            error = Color(0xFFFF7B7B),
            codeBackground = Color(0xFF021018),
            codeText = Color(0xFFB3F4FF),
        ),
    ),
    OtherNoteThemeDefinition(
        id = "daylight",
        displayName = "Daylight",
        dark = false,
        colors = OtherNoteThemeColors(
            background = Color(0xFFF8FAFC),
            surface = Color.White,
            text = Color(0xFF111827),
            muted = Color(0xFF4B5563),
            accent = Color(0xFF2563EB),
            appBar = Color(0xFFE5EAF2),
            buttonBackground = Color(0xFF2563EB),
            buttonText = Color.White,
            border = Color(0xFF334155),
            textFieldBackground = Color.White,
            textFieldBorder = Color(0xFF334155),
            error = Color(0xFFB91C1C),
            codeBackground = Color(0xFFE5E7EB),
            codeText = Color(0xFF111827),
        ),
    ),
    OtherNoteThemeDefinition(
        id = "burgundy",
        displayName = "Burgundy",
        dark = true,
        colors = OtherNoteThemeColors(
            background = Color(0xFF16070B),
            surface = Color(0xFF251016),
            text = Color(0xFFFFF1F3),
            muted = Color(0xFFD8A9B1),
            accent = Color(0xFFE05264),
            appBar = Color(0xFF3A111B),
            buttonBackground = Color(0xFFE05264),
            buttonText = Color(0xFF16070B),
            border = Color(0xFFA63D50),
            textFieldBackground = Color(0xFF251016),
            textFieldBorder = Color(0xFFA63D50),
            error = Color(0xFFFFB000),
            codeBackground = Color(0xFF120409),
            codeText = Color(0xFFFFCED6),
        ),
    ),
)

private val LocalOtherNoteThemeColors = staticCompositionLocalOf { NostrClassicTheme.colors }

fun otherNoteThemeForId(id: String?): OtherNoteThemeDefinition =
    BuiltInOtherNoteThemes.firstOrNull { it.id == id } ?: NostrClassicTheme

val OtherNoteBlack: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalOtherNoteThemeColors.current.background

val OtherNotePanel: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalOtherNoteThemeColors.current.surface

val OtherNotePurple: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalOtherNoteThemeColors.current.accent

val OtherNotePurpleDark: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalOtherNoteThemeColors.current.appBar

val OtherNoteText: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalOtherNoteThemeColors.current.text

val OtherNoteMuted: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalOtherNoteThemeColors.current.muted

val OtherNoteCodeBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalOtherNoteThemeColors.current.codeBackground

val OtherNoteCodeText: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalOtherNoteThemeColors.current.codeText

val OtherNoteButtonBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalOtherNoteThemeColors.current.buttonBackground

val OtherNoteButtonText: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalOtherNoteThemeColors.current.buttonText

@Composable
fun OtherNoteTheme(
    theme: OtherNoteThemeDefinition = NostrClassicTheme,
    content: @Composable () -> Unit,
) {
    val colors = theme.colors
    val scheme = if (theme.dark) {
        darkColorScheme(
            background = colors.background,
            surface = colors.surface,
            surfaceVariant = colors.textFieldBackground,
            primary = colors.accent,
            secondary = colors.border,
            outline = colors.textFieldBorder,
            error = colors.error,
            onBackground = colors.text,
            onSurface = colors.text,
            onSurfaceVariant = colors.text,
            onPrimary = colors.buttonText,
            onSecondary = colors.buttonText,
            onError = Color.Black,
        )
    } else {
        lightColorScheme(
            background = colors.background,
            surface = colors.surface,
            surfaceVariant = colors.textFieldBackground,
            primary = colors.accent,
            secondary = colors.border,
            outline = colors.textFieldBorder,
            error = colors.error,
            onBackground = colors.text,
            onSurface = colors.text,
            onSurfaceVariant = colors.text,
            onPrimary = colors.buttonText,
            onSecondary = colors.buttonText,
            onError = Color.White,
        )
    }
    CompositionLocalProvider(LocalOtherNoteThemeColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
