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
private val RenderableImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")
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

fun isSafeHttpUrl(url: String): Boolean =
    (url.startsWith("https://") || url.startsWith("http://")) &&
        url.none { it.isISOControl() || it.isWhitespace() || it == '<' || it == '>' || it == '"' }

fun isSupportedRemoteImageUrl(url: String): Boolean {
    if (!url.startsWith("https://") || !isSafeHttpUrl(url)) return false
    val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    return extension in RenderableImageExtensions
}
