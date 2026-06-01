package com.libertasprimordium.othernote.util

fun normalizeRelayUrl(raw: String): Result<String> {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Relay URL is blank"))
    val withScheme = when {
        trimmed.startsWith("wss://", ignoreCase = true) -> "wss://" + trimmed.removePrefixIgnoreCase("wss://")
        trimmed.startsWith("ws://", ignoreCase = true) -> "ws://" + trimmed.removePrefixIgnoreCase("ws://")
        "://" !in trimmed -> "wss://$trimmed"
        else -> return Result.failure(IllegalArgumentException("Relay URL must use ws:// or wss://"))
    }
    if (!withScheme.startsWith("wss://") && !withScheme.startsWith("ws://")) {
        return Result.failure(IllegalArgumentException("Relay URL must use ws:// or wss://"))
    }
    val withoutTrailing = withScheme.trimEnd('/')
    val host = withoutTrailing.substringAfter("://").substringBefore('/').substringBefore(':')
    if (host.isBlank() || "." !in host) return Result.failure(IllegalArgumentException("Relay URL must include a host"))
    if (host.any { it.isWhitespace() }) return Result.failure(IllegalArgumentException("Relay URL contains whitespace"))
    return Result.success(withoutTrailing)
}

private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this
