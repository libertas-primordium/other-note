package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.util.formatNoteCardUpdatedAt
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NoteCardTimestampFormatterTests {
    @Test
    fun formatsTodayInLocalTimeWithoutSeconds() {
        val now = millis("2026-06-01T12:00:00")
        val updated = millis("2026-06-01T08:42:35")

        val formatted = formatNoteCardUpdatedAt(updated, now, TestZone, TestLocale)

        assertEquals("Today, 8:42 AM", formatted)
        assertFalse(formatted.contains(":35"))
    }

    @Test
    fun formatsYesterdayInLocalTime() {
        val now = millis("2026-06-01T12:00:00")
        val updated = millis("2026-05-31T18:15:00")

        assertEquals("Yesterday, 6:15 PM", formatNoteCardUpdatedAt(updated, now, TestZone, TestLocale))
    }

    @Test
    fun formatsOlderDateWithMonthDayYearAndTime() {
        val now = millis("2026-06-01T12:00:00")
        val updated = millis("2026-05-30T11:04:00")

        assertEquals("May 30, 2026, 11:04 AM", formatNoteCardUpdatedAt(updated, now, TestZone, TestLocale))
    }

    @Test
    fun returnsFallbackForInvalidTimestamp() {
        val now = millis("2026-06-01T12:00:00")

        assertEquals("Unknown time", formatNoteCardUpdatedAt(0, now, TestZone, TestLocale))
        assertEquals("Unknown time", formatNoteCardUpdatedAt(-1, now, TestZone, TestLocale))
    }

    @Test
    fun handlesMidnightBoundaryInLocalTimezone() {
        val now = millis("2026-06-01T00:10:00")
        val updated = millis("2026-05-31T23:55:00")

        assertEquals("Yesterday, 11:55 PM", formatNoteCardUpdatedAt(updated, now, TestZone, TestLocale))
    }

    private fun millis(localDateTime: String): Long =
        LocalDateTime.parse(localDateTime).atZone(ZoneId.of(TestZone)).toInstant().toEpochMilli()
}

private const val TestZone = "America/Denver"
private const val TestLocale = "en-US"
