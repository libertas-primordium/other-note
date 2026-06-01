package com.libertasprimordium.othernote.util

data class DetectedUrl(
    val value: String,
    val type: MediaType,
)

enum class MediaType {
    Link,
    Image,
    Video,
}

private val UrlRegex = Regex("""https?://[^\s<>()"]+""")
private val ImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "avif")
private val VideoExtensions = setOf("mp4", "webm", "mov", "m4v")

fun detectUrls(text: String): List<DetectedUrl> =
    UrlRegex.findAll(text).map { match ->
        val cleaned = match.value.trimEnd('.', ',', ')', ']')
        DetectedUrl(cleaned, mediaTypeFor(cleaned))
    }.toList()

fun mediaTypeFor(url: String): MediaType {
    val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    return when (extension) {
        in ImageExtensions -> MediaType.Image
        in VideoExtensions -> MediaType.Video
        else -> MediaType.Link
    }
}
