package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.ui.AppMode
import com.libertasprimordium.othernote.ui.AppState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppModeTests {
    @Test
    fun localOnlyModeAllowsNoteEditingWithoutSession() = runBlocking {
        val state = AppState()
        state.continueLocalOnly()

        state.save(existing = null, markdown = "local note")

        assertEquals(AppMode.LocalOnly, state.mode.value)
        assertNull(state.session.value)
        assertEquals("local note", state.notes.notes.value.single().bodyMarkdown)
    }

    @Test
    fun localOnlyModeBlocksRelaySync() = runBlocking {
        val state = AppState()
        state.continueLocalOnly()

        state.sync()

        assertTrue(state.syncState.value.errors.single().contains("requires a validated nsec"))
    }
}
