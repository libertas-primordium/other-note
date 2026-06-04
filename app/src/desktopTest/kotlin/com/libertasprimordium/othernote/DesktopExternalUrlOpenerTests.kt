package com.libertasprimordium.othernote

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopExternalUrlOpenerTests {
    @Test
    fun safeDesktopHttpUriAcceptsHttpAndHttps() {
        assertNotNull(safeDesktopHttpUriOrNull("https://example.com/path?query=1#section"))
        assertNotNull(safeDesktopHttpUriOrNull("http://example.com/path"))
    }

    @Test
    fun safeDesktopHttpUriRejectsUnsafeSchemesAndMalformedUrls() {
        listOf(
            "javascript:alert(1)",
            "data:text/plain,hello",
            "file:///tmp/file",
            "content://example/file",
            "blob:https://example.com/id",
            "/local/path",
            "https://",
            "https://exa mple.com",
        ).forEach { url ->
            assertNull(safeDesktopHttpUriOrNull(url), "Expected rejected desktop URL: $url")
        }
    }

    @Test
    fun desktopOpenerUsesPrimaryBrowserLauncherWhenAvailable() {
        val browser = RecordingBrowserLauncher(result = true)
        val process = RecordingProcessLauncher(result = true)
        val opener = DesktopExternalUrlOpener(browser, process, osName = "Linux")

        assertTrue(opener.open("https://example.com/path"))

        assertEquals(listOf(URI("https://example.com/path")), browser.opened)
        assertEquals(emptyList(), process.commands)
    }

    @Test
    fun desktopOpenerFallsBackToXdgOpenOnLinux() {
        val browser = RecordingBrowserLauncher(result = false)
        val process = RecordingProcessLauncher(result = true)
        val opener = DesktopExternalUrlOpener(browser, process, osName = "Linux")

        assertTrue(opener.open("https://example.com/path?x=1"))

        assertEquals(listOf(URI("https://example.com/path?x=1")), browser.opened)
        assertEquals(listOf(listOf("xdg-open", "https://example.com/path?x=1")), process.commands)
    }

    @Test
    fun desktopOpenerFailsSafelyWhenAllLaunchersFail() {
        val opener = DesktopExternalUrlOpener(
            browserLauncher = RecordingBrowserLauncher(result = false),
            processLauncher = RecordingProcessLauncher(result = false),
            osName = "Linux",
        )

        assertFalse(opener.open("https://example.com/path"))
    }

    @Test
    fun desktopOpenerDoesNotPassUnsafeUrlsToLaunchers() {
        val browser = RecordingBrowserLauncher(result = true)
        val process = RecordingProcessLauncher(result = true)
        val opener = DesktopExternalUrlOpener(browser, process, osName = "Linux")

        assertFalse(opener.open("javascript:alert(1)"))
        assertFalse(opener.open("https://exa mple.com"))

        assertEquals(emptyList(), browser.opened)
        assertEquals(emptyList(), process.commands)
    }

    @Test
    fun desktopOpenerUsesArgumentListForXdgOpen() {
        val process = RecordingProcessLauncher(result = true)
        val opener = DesktopExternalUrlOpener(
            browserLauncher = RecordingBrowserLauncher(result = false),
            processLauncher = process,
            osName = "Linux",
        )
        val url = "https://example.com/path?x=1;touch=/tmp/should-not-run"

        assertTrue(opener.open(url))

        assertEquals(listOf(listOf("xdg-open", url)), process.commands)
    }

    private class RecordingBrowserLauncher(private val result: Boolean) : DesktopBrowserLauncher {
        val opened = mutableListOf<URI>()

        override fun browse(uri: URI): Boolean {
            opened += uri
            return result
        }
    }

    private class RecordingProcessLauncher(private val result: Boolean) : DesktopProcessLauncher {
        val commands = mutableListOf<List<String>>()

        override fun start(command: List<String>): Boolean {
            commands += command
            return result
        }
    }
}
