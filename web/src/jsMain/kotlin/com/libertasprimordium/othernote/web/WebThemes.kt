package com.libertasprimordium.othernote.web

internal const val WebThemePreferenceKey = "on.web.theme"

internal data class WebThemeDefinition(
    val id: String,
    val label: String,
    val dark: Boolean,
)

internal val DefaultWebTheme = WebThemeDefinition(
    id = "nostr-classic",
    label = "Nostr Classic",
    dark = true,
)

internal val BuiltInWebThemes: List<WebThemeDefinition> = listOf(
    DefaultWebTheme,
    WebThemeDefinition(id = "urban", label = "Urban", dark = false),
    WebThemeDefinition(id = "hacker", label = "Hacker", dark = true),
    WebThemeDefinition(id = "papyrus", label = "Papyrus", dark = false),
    WebThemeDefinition(id = "harbor", label = "Harbor", dark = true),
    WebThemeDefinition(id = "daylight", label = "Daylight", dark = false),
    WebThemeDefinition(id = "burgundy", label = "Burgundy", dark = true),
)

internal val AllowedWebThemeIds: Set<String> =
    BuiltInWebThemes.mapTo(linkedSetOf()) { it.id }

internal fun webThemeForId(id: String?): WebThemeDefinition =
    BuiltInWebThemes.firstOrNull { it.id == id } ?: DefaultWebTheme

internal fun validWebThemeIdOrNull(id: String?): String? {
    val trimmed = id?.trim().orEmpty()
    return trimmed.takeIf { it in AllowedWebThemeIds }
}

internal interface WebThemePreferenceStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

internal fun loadWebThemePreference(storage: WebThemePreferenceStorage?): String =
    runCatching {
        validWebThemeIdOrNull(storage?.read(WebThemePreferenceKey)) ?: DefaultWebTheme.id
    }.getOrDefault(DefaultWebTheme.id)

internal fun saveWebThemePreference(storage: WebThemePreferenceStorage?, themeId: String): String {
    val validThemeId = validWebThemeIdOrNull(themeId) ?: DefaultWebTheme.id
    runCatching {
        storage?.write(WebThemePreferenceKey, validThemeId)
    }
    return validThemeId
}
