package com.libertasprimordium.othernote.ui

data class UserFacingError(
    val title: String,
    val message: String,
    val technicalDetails: String? = null,
)

fun userFacingErrorFor(raw: String): UserFacingError {
    val source = raw.trim()
    val lower = source.lowercase()
    val method = source.extractNip46Method()
    return when {
        source.isBlank() -> unexpectedError(source)

        lower.contains("already") && (lower.contains("bunker") || lower.contains("paired") || lower.contains("connect")) ->
            UserFacingError(
                title = "Bunker link already used",
                message = "This bunker link appears to already be paired in the signer app. Delete the old connection in the signer or create a fresh bunker link.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("response_fetch_timed_out") ||
            lower.contains("no_matching_response") ||
            lower.contains("remote signer did not respond") ->
            UserFacingError(
                title = "Remote signer did not respond",
                message = method.remoteSignerTimeoutMessage(),
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("response_decrypt_failed") ->
            UserFacingError(
                title = "Remote signer response could not be decrypted",
                message = "Other Note received a remote signer response, but could not decrypt it. Reconnect the signer and try again.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("relay_publish_failed") && lower.contains("reject") ->
            UserFacingError(
                title = "Remote signer relay rejected the request",
                message = "The relay used for the remote signer rejected Other Note's temporary client key. Use a public signer relay or allow this client key on that relay.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("relay_publish_failed") && (lower.contains("timeout") || lower.contains("connect_failed")) ->
            UserFacingError(
                title = "Remote signer relay did not respond",
                message = "Other Note could not send the request through the remote signer relay. Check that the signer relay is reachable.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("remote signer returned") ->
            UserFacingError(
                title = "Remote signer returned an error",
                message = "The remote signer did not approve or complete the request. Review the request in your signer app and try again.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("remote signer") && (lower.contains("failed") || lower.contains("timeout") || lower.contains("request")) ->
            UserFacingError(
                title = "Remote signer request failed",
                message = "Other Note could not complete the remote signer request. Check that the signer is online and using reachable relays.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("signer") && (lower.contains("cancelled") || lower.contains("canceled") || lower.contains("rejected")) ->
            UserFacingError(
                title = "Signer request cancelled",
                message = "The signer did not approve the request. Try again and approve the request in your signer app.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("relay test") || lower.contains("stage=relay_test") ->
            UserFacingError(
                title = "Relay test failed",
                message = "Other Note could not confirm that this relay can publish and fetch events. You can cancel or add it anyway.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("pending write") ||
            lower.contains("could not be saved") ||
            lower.contains("persistence") ||
            lower.contains("nosuchfileexception") ||
            lower.contains("ioexception") ->
            UserFacingError(
                title = "Could not save local state",
                message = "Other Note could not update its local encrypted cache or pending-write state. Check file permissions and available disk space.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("no relay accepted") ->
            UserFacingError(
                title = "No relay accepted the update",
                message = "Other Note could not publish to any configured relay. Check your relay settings and try again.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("outcome=rejected") || lower.contains("rejected writes") ->
            UserFacingError(
                title = "Relay rejected the update",
                message = "This relay refused the write request. It may require an allowlist, payment, or another policy before accepting events.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("timeout") || lower.contains("timed out") ->
            UserFacingError(
                title = "Relay did not respond",
                message = "Other Note could not reach the relay before the timeout.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        lower.contains("nip-44 v2 encryption is not wired yet") ->
            UserFacingError(
                title = "Encryption is unavailable",
                message = "Other Note could not encrypt the note in this runtime. Plaintext was not published.",
                technicalDetails = source.safeTechnicalDetails(),
            )

        source.looksLikeRawDiagnostic() -> unexpectedError(source)

        else -> UserFacingError(
            title = "Other Note",
            message = source,
            technicalDetails = source.safeTechnicalDetails().takeIf { source.looksLikeTechnicalDetail() },
        )
    }
}

fun String.toUserFacingMessage(): String =
    userFacingErrorFor(this).message

fun String.toRelayTestWarningMessage(): String =
    userFacingErrorFor(this).message

fun Throwable.toUserFacingPersistenceMessage(): String =
    "Other Note could not update its local encrypted cache or pending-write state. Check file permissions and available disk space."

fun String.containsRawDiagnosticKey(): Boolean {
    val lower = lowercase()
    return rawDiagnosticKeys.any { it in lower }
}

private fun unexpectedError(source: String): UserFacingError =
    UserFacingError(
        title = "Something went wrong",
        message = "Other Note could not complete the action. Try again.",
        technicalDetails = source.safeTechnicalDetails().takeIf { source.isNotBlank() },
    )

private fun String?.remoteSignerTimeoutMessage(): String = when (this) {
    "nip44_encrypt" -> "Other Note sent an encryption request to your remote signer, but no response arrived before the timeout. Check that the signer app is online and using reachable relays."
    "sign_event" -> "Other Note sent a signing request to your remote signer, but no response arrived before the timeout. Check that the signer app is online and using reachable relays."
    "nip44_decrypt" -> "Other Note sent a decryption request to your remote signer, but no response arrived before the timeout. Check that the signer app is online and using reachable relays."
    "connect" -> "Other Note sent a connect request to your remote signer, but no response arrived before the timeout. Check that the signer app is online and using reachable relays."
    else -> "Other Note sent a request to your remote signer, but no response arrived before the timeout. Check that the signer app is online and using reachable relays."
}

private fun String.extractNip46Method(): String? =
    Regex("""(?:^|\s)method=([A-Za-z0-9_:-]+)""").find(this)?.groupValues?.getOrNull(1)

private fun String.safeTechnicalDetails(): String =
    replace(Regex("secret=([^&\\s]+)"), "secret=redacted")
        .replace(Regex("privateKey=([^&\\s]+)"), "privateKey=redacted")
        .replace(Regex("nsec[0-9a-zA-Z]+"), "nsec-redacted")
        .replace("body_markdown", "payload_field_redacted")
        .take(1_200)

private fun String.looksLikeRawDiagnostic(): Boolean =
    containsRawDiagnosticKey() || looksLikeTechnicalDetail()

private fun String.looksLikeTechnicalDetail(): Boolean {
    val lower = lowercase()
    return lower.contains("exception") ||
        lower.contains("stacktrace") ||
        lower.contains("stack trace") ||
        Regex("""/[A-Za-z0-9._~/-]+""").containsMatchIn(this)
}

private val rawDiagnosticKeys = listOf(
    "stage=",
    "outcome=",
    "candidate_events",
    "publish_accepted_count",
    "response_fetch_timed_out",
    "response_id_mismatch",
    "decrypt_failures",
    "mismatched_ids",
    "matching_id_found",
    "request_id=",
    "relay_source=",
)
