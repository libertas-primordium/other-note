package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.ThemePreferenceStore
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.security.InMemoryNip46SessionStore
import com.libertasprimordium.othernote.security.InMemoryNip55SessionStore
import com.libertasprimordium.othernote.security.Nip46SessionStoreResult
import com.libertasprimordium.othernote.security.Nip55SessionStoreResult
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.AppState
import com.libertasprimordium.othernote.ui.BuiltInOtherNoteThemes
import com.libertasprimordium.othernote.ui.NostrClassicTheme
import com.libertasprimordium.othernote.ui.otherNoteThemeForId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeSelectionTests {
    @Test
    fun defaultThemeIsNostrClassic() {
        val state = AppState()

        assertEquals(NostrClassicTheme.id, state.selectedThemeId.value)
        assertEquals("Nostr Classic", otherNoteThemeForId(state.selectedThemeId.value).displayName)
    }

    @Test
    fun builtInThemeIdsAndDisplayNamesAreStableUniqueAndBounded() {
        assertEquals(
            listOf(
                "nostr-classic",
                "urban",
                "hacker",
                "papyrus",
                "harbor",
                "daylight",
                "burgundy",
            ),
            BuiltInOtherNoteThemes.map { it.id },
        )
        assertEquals(
            listOf(
                "Nostr Classic",
                "Urban",
                "Hacker",
                "Papyrus",
                "Harbor",
                "Daylight",
                "Burgundy",
            ),
            BuiltInOtherNoteThemes.map { it.displayName },
        )
        assertEquals(BuiltInOtherNoteThemes.size, BuiltInOtherNoteThemes.map { it.id }.toSet().size)
        assertEquals(BuiltInOtherNoteThemes.size, BuiltInOtherNoteThemes.map { it.displayName }.toSet().size)
        assertTrue(BuiltInOtherNoteThemes.size <= 7)
    }

    @Test
    fun requiredThemesExist() {
        val names = BuiltInOtherNoteThemes.map { it.displayName }.toSet()

        assertTrue("Nostr Classic" in names)
        assertTrue("Urban" in names)
        assertTrue("Hacker" in names)
        assertTrue("Papyrus" in names)
    }

    @Test
    fun unknownStoredThemeFallsBackToNostrClassic() {
        assertEquals(NostrClassicTheme, otherNoteThemeForId("missing-theme"))
        assertEquals(NostrClassicTheme, otherNoteThemeForId(null))
    }

    @Test
    fun selectingEachThemeUpdatesAppState() {
        val state = AppState()

        BuiltInOtherNoteThemes.forEach { theme ->
            state.selectTheme(theme.id)

            assertEquals(theme.id, state.selectedThemeId.value)
        }
    }

    @Test
    fun selectedThemePersistsAndReloads() = runBlocking {
        val store = MemoryThemePreferenceStore()
        val firstState = AppState(themeServices(store))

        firstState.selectTheme("hacker")
        withTimeout(2_000) {
            while (store.savedThemeId != "hacker") yield()
        }

        val restarted = AppState(themeServices(store))
        withTimeout(2_000) {
            while (restarted.selectedThemeId.value != "hacker") yield()
        }

        assertEquals("hacker", restarted.selectedThemeId.value)
    }

    @Test
    fun corruptPersistedThemeIdFallsBackToNostrClassicOnReload() = runBlocking {
        val state = AppState(themeServices(MemoryThemePreferenceStore(initialThemeId = "not-a-real-theme")))

        withTimeout(2_000) {
            while (state.selectedThemeId.value != NostrClassicTheme.id) yield()
        }

        assertEquals(NostrClassicTheme.id, state.selectedThemeId.value)
    }

    @Test
    fun themePreferenceIsSeparateFromSignerSessionStores() = runBlocking {
        val themeStore = MemoryThemePreferenceStore()
        val nip55Store = InMemoryNip55SessionStore()
        val nip46Store = InMemoryNip46SessionStore()
        val state = AppState(
            themeServices(
                themeStore = themeStore,
                nip55Store = nip55Store,
                nip46Store = nip46Store,
            ),
        )

        state.selectTheme("urban")
        withTimeout(2_000) {
            while (themeStore.savedThemeId != "urban") yield()
        }

        assertTrue((nip55Store.listSessions() as Nip55SessionStoreResult.Listed).sessions.isEmpty())
        assertTrue((nip46Store.listSessions() as Nip46SessionStoreResult.Listed).sessions.isEmpty())
    }

    private fun themeServices(
        themeStore: ThemePreferenceStore,
        nip55Store: InMemoryNip55SessionStore = InMemoryNip55SessionStore(),
        nip46Store: InMemoryNip46SessionStore = InMemoryNip46SessionStore(),
    ): AppServices = AppServices(
        mode = AppRuntimeMode.Offline,
        crypto = NonProductionNostrCrypto(),
        client = OfflineNostrClient(),
        themePreferenceStore = themeStore,
        nip55SessionStore = nip55Store,
        nip46SessionStore = nip46Store,
    )
}

private class MemoryThemePreferenceStore(initialThemeId: String? = null) : ThemePreferenceStore {
    var savedThemeId: String? = initialThemeId
        private set

    override suspend fun loadThemeId(): String? = savedThemeId

    override suspend fun saveThemeId(themeId: String) {
        savedThemeId = themeId
    }
}
