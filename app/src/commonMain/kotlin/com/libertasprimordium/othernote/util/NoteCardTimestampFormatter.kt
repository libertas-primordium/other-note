package com.libertasprimordium.othernote.util

expect fun formatNoteCardUpdatedAt(updatedAtMs: Long): String

expect fun formatNoteCardUpdatedAt(updatedAtMs: Long, nowMs: Long, timeZoneId: String, localeTag: String): String
