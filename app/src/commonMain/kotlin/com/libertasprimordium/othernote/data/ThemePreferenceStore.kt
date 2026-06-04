package com.libertasprimordium.othernote.data

interface ThemePreferenceStore {
    suspend fun loadThemeId(): String?
    suspend fun saveThemeId(themeId: String)
}

object NoopThemePreferenceStore : ThemePreferenceStore {
    override suspend fun loadThemeId(): String? = null
    override suspend fun saveThemeId(themeId: String) = Unit
}
