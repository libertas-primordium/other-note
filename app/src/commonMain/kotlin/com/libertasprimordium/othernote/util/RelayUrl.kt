package com.libertasprimordium.othernote.util

fun normalizeRelayUrl(raw: String): Result<String> {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Relay URL is blank"))
    if (trimmed.any { it.isWhitespace() }) {
        return Result.failure(IllegalArgumentException("Relay URL must not contain spaces"))
    }
    val withScheme = if ("://" in trimmed) trimmed else "wss://$trimmed"
    val scheme = withScheme.substringBefore("://", missingDelimiterValue = "")
    val rest = withScheme.substringAfter("://", missingDelimiterValue = "")
    if (scheme.isBlank() || rest.isBlank()) {
        return Result.failure(IllegalArgumentException("Relay URL is malformed"))
    }
    val normalizedScheme = scheme.lowercase()
    if (normalizedScheme == "http" || normalizedScheme == "https") {
        return Result.failure(IllegalArgumentException("Relay URL must use wss://, not http(s)://"))
    }
    if (normalizedScheme !in setOf("wss", "ws")) {
        return Result.failure(IllegalArgumentException("Relay URL must use wss://"))
    }
    val withoutQuery = rest.substringBefore('?')
    if (withoutQuery != rest || "#" in rest) {
        return Result.failure(IllegalArgumentException("Relay URL must not include query strings or fragments"))
    }
    val authority = withoutQuery.substringBefore('/')
    if (authority.isBlank()) return Result.failure(IllegalArgumentException("Relay URL must include a host"))
    val host = authority.substringBefore(':').lowercase()
    if (host.isBlank() || ("." !in host && !isLocalDevelopmentRelayHost(host))) {
        return Result.failure(IllegalArgumentException("Relay URL must include a host"))
    }
    val port = authority.substringAfter(':', missingDelimiterValue = "")
    if (port.isNotBlank() && port.toIntOrNull() == null) {
        return Result.failure(IllegalArgumentException("Relay URL port is invalid"))
    }
    if (normalizedScheme == "ws" && !isLocalDevelopmentRelayHost(host)) {
        return Result.failure(IllegalArgumentException("ws:// relays are only allowed for local development"))
    }
    val path = withoutQuery.substringAfter('/', missingDelimiterValue = "")
        .trimEnd('/')
    val normalizedAuthority = if (port.isBlank()) host else "$host:$port"
    val normalized = if (path.isBlank()) {
        "$normalizedScheme://$normalizedAuthority"
    } else {
        "$normalizedScheme://$normalizedAuthority/$path"
    }
    return Result.success(normalized)
}

private fun isLocalDevelopmentRelayHost(host: String): Boolean =
    host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".localhost")
