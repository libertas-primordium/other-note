package com.libertasprimordium.othernote.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

actual fun formatNoteCardUpdatedAt(updatedAtMs: Long): String =
    formatNoteCardUpdatedAt(updatedAtMs, nowMs(), ZoneId.systemDefault().id, Locale.getDefault().toLanguageTag())

actual fun formatNoteCardUpdatedAt(updatedAtMs: Long, nowMs: Long, timeZoneId: String, localeTag: String): String =
    formatNoteCardTimestamp(updatedAtMs, nowMs, timeZoneId, Locale.forLanguageTag(localeTag))

private fun formatNoteCardTimestamp(updatedAtMs: Long, nowMs: Long, timeZoneId: String, locale: Locale): String {
    if (updatedAtMs <= 0 || nowMs <= 0) return UnknownTime
    val zone = runCatching { ZoneId.of(timeZoneId) }.getOrElse { return UnknownTime }
    val updated = runCatching { Instant.ofEpochMilli(updatedAtMs).atZone(zone) }.getOrElse { return UnknownTime }
    val now = runCatching { Instant.ofEpochMilli(nowMs).atZone(zone) }.getOrElse { return UnknownTime }
    val time = DateTimeFormatter.ofPattern("h:mm a", locale).format(updated)
    return when (updated.toLocalDate()) {
        now.toLocalDate() -> "Today, $time"
        now.toLocalDate().minusDays(1) -> "Yesterday, $time"
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", locale).format(updated)
    }
}

private const val UnknownTime = "Unknown time"
