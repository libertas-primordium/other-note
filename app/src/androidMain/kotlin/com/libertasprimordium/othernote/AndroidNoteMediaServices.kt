package com.libertasprimordium.othernote

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import com.libertasprimordium.othernote.ui.ExternalUrlOpener
import com.libertasprimordium.othernote.ui.NoteImageLoadResult
import com.libertasprimordium.othernote.ui.NoteImageLoader
import com.libertasprimordium.othernote.util.isSafeHttpUrl
import com.libertasprimordium.othernote.util.isSupportedRemoteImageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class AndroidExternalUrlOpener(private val context: Context) : ExternalUrlOpener {
    override fun open(url: String): Boolean {
        if (!isSafeHttpUrl(url)) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

class AndroidNoteImageLoader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) : NoteImageLoader {
    override suspend fun load(url: String): NoteImageLoadResult = withContext(Dispatchers.IO) {
        if (!isSupportedRemoteImageUrl(url)) return@withContext NoteImageLoadResult.Failed
        runCatching {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            response.use {
                val body = it.body
                if (!it.isSuccessful || body.contentLength() > MaxImageBytes) return@withContext NoteImageLoadResult.Failed
                val bytes = body.byteStream().use { stream ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MaxImageBytes) return@withContext NoteImageLoadResult.Failed
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext NoteImageLoadResult.Failed
                NoteImageLoadResult.Loaded(bitmap.asImageBitmap())
            }
        }.getOrDefault(NoteImageLoadResult.Failed)
    }

    private companion object {
        const val MaxImageBytes = 8L * 1024L * 1024L
    }
}
