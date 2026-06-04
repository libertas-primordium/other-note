package com.libertasprimordium.othernote

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.libertasprimordium.othernote.ui.ExternalUrlOpener
import com.libertasprimordium.othernote.ui.NoteImageLoadResult
import com.libertasprimordium.othernote.ui.NoteImageLoader
import com.libertasprimordium.othernote.util.isSafeHttpUrl
import com.libertasprimordium.othernote.util.isSupportedRemoteImageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

internal interface DesktopBrowserLauncher {
    fun browse(uri: URI): Boolean
}

internal object AwtDesktopBrowserLauncher : DesktopBrowserLauncher {
    override fun browse(uri: URI): Boolean =
        runCatching {
            if (!Desktop.isDesktopSupported()) return false
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
            desktop.browse(uri)
            true
        }.getOrDefault(false)
}

internal interface DesktopProcessLauncher {
    fun start(command: List<String>): Boolean
}

internal object ProcessBuilderDesktopProcessLauncher : DesktopProcessLauncher {
    override fun start(command: List<String>): Boolean =
        runCatching {
            ProcessBuilder(command).start()
            true
        }.getOrDefault(false)
}

internal class DesktopExternalUrlOpener(
    private val browserLauncher: DesktopBrowserLauncher = AwtDesktopBrowserLauncher,
    private val processLauncher: DesktopProcessLauncher = ProcessBuilderDesktopProcessLauncher,
    private val osName: String = System.getProperty("os.name").orEmpty(),
) : ExternalUrlOpener {
    override fun open(url: String): Boolean {
        val uri = safeDesktopHttpUriOrNull(url) ?: return false
        if (browserLauncher.browse(uri)) return true
        return isLinuxDesktop() && processLauncher.start(listOf("xdg-open", uri.toString()))
    }

    private fun isLinuxDesktop(): Boolean =
        osName.contains("linux", ignoreCase = true)
}

class DesktopNoteImageLoader : NoteImageLoader {
    override suspend fun load(url: String): NoteImageLoadResult = withContext(Dispatchers.IO) {
        if (!isSupportedRemoteImageUrl(url)) return@withContext NoteImageLoadResult.Failed
        runCatching {
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                if (this is HttpURLConnection) {
                    instanceFollowRedirects = false
                }
            }
            connection.getInputStream().use { stream ->
                val bytes = ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MaxImageBytes) return@withContext NoteImageLoadResult.Failed
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                NoteImageLoadResult.Loaded(Image.makeFromEncoded(bytes).toComposeImageBitmap())
            }
        }.getOrDefault(NoteImageLoadResult.Failed)
    }

    private companion object {
        const val MaxImageBytes = 8 * 1024 * 1024
    }
}

internal fun safeDesktopHttpUriOrNull(url: String): URI? {
    if (!isSafeHttpUrl(url)) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "https" && scheme != "http") return null
    if (uri.host.isNullOrBlank()) return null
    return uri
}
