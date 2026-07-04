package com.libertasprimordium.othernote.sync

import com.libertasprimordium.othernote.domain.RelayStatus

private const val SyncLogTag = "OtherNoteSync"

internal fun logSafeSync(message: String) {
    println("$SyncLogTag: $message")
}

internal fun List<RelayStatus>.safeRelayLogSummary(): String =
    joinToString(prefix = "[", postfix = "]") { status ->
        "${status.url}:read=${status.readable},write=${status.writable},message=${status.message.safeLogField(120)}"
    }

private fun String.safeLogField(maxLength: Int): String =
    replace('\n', ' ')
        .replace('\r', ' ')
        .take(maxLength)
