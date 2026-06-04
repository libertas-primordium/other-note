package com.libertasprimordium.othernote.nostr

import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.domain.hasSessionPrivateKey
import com.libertasprimordium.othernote.util.nowMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val RelayTestKind = 1
private const val RelayTestContent = "other-note relay test"

enum class RelayTestFailureReason {
    ConnectionFailed,
    PublishRejected,
    PublishTimedOut,
    FetchTimedOut,
    NoMatchingEvent,
    UnsupportedNoSigner,
    UnexpectedSafeError,
}

sealed interface RelayTestResult {
    data class Success(
        val relayUrl: String,
        val mode: String,
    ) : RelayTestResult

    data class Failure(
        val relayUrl: String,
        val reason: RelayTestFailureReason,
        val safeMessage: String,
    ) : RelayTestResult {
        val userMessage: String get() = when (reason) {
            RelayTestFailureReason.ConnectionFailed -> "Relay connection failed. $safeMessage"
            RelayTestFailureReason.PublishRejected -> "Relay rejected the test event. $safeMessage"
            RelayTestFailureReason.PublishTimedOut -> "Relay publish timed out. $safeMessage"
            RelayTestFailureReason.FetchTimedOut -> "Relay fetch timed out. $safeMessage"
            RelayTestFailureReason.NoMatchingEvent -> "No matching test event was returned. $safeMessage"
            RelayTestFailureReason.UnsupportedNoSigner -> "No in-memory signing key is available for a write test. $safeMessage"
            RelayTestFailureReason.UnexpectedSafeError -> "Relay test failed. $safeMessage"
        }
    }
}

interface RelayTester {
    suspend fun testAppRelay(relayUrl: String, session: UserSession?): RelayTestResult
}

class DefaultRelayTester(
    private val client: NostrClient,
    private val crypto: NostrCrypto,
    private val timeoutMs: Long = 12_000,
) : RelayTester {
    override suspend fun testAppRelay(relayUrl: String, session: UserSession?): RelayTestResult =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) {
                if (session?.hasSessionPrivateKey() == true && crypto.productionReady) {
                    testSignedWriteAndRead(relayUrl, session)
                } else {
                    testReadOnly(relayUrl, session)
                }
            } ?: RelayTestResult.Failure(
                relayUrl = relayUrl,
                reason = RelayTestFailureReason.FetchTimedOut,
                safeMessage = "stage=relay_test outcome=timeout timeout_ms=$timeoutMs",
            )
        }

    private suspend fun testSignedWriteAndRead(relayUrl: String, session: UserSession): RelayTestResult {
        val createdAt = nowMs() / 1000
        val unsigned = UnsignedNostrEvent(
            pubkey = session.publicKeyHex,
            createdAt = createdAt,
            kind = RelayTestKind,
            tags = listOf(
                listOf("client", "Other Note"),
                listOf("t", "other-note-relay-test"),
            ),
            content = RelayTestContent,
        )
        val event = crypto.sign(unsigned, NostrPrivateKey(session.privateKeyHex)).getOrElse {
            return RelayTestResult.Failure(
                relayUrl = relayUrl,
                reason = RelayTestFailureReason.UnexpectedSafeError,
                safeMessage = "stage=relay_test_sign outcome=failed ${it.safeRelayTestMessage()}",
            )
        }
        val publish = client.publish(listOf(relayUrl), event)
        val publishStatus = publish.statuses.firstOrNull()
        if (publishStatus?.writable != true) {
            return RelayTestResult.Failure(
                relayUrl = relayUrl,
                reason = classifyPublishFailure(publishStatus?.message),
                safeMessage = "stage=relay_test_publish ${publishStatus?.message.safeRelayTestMessage()}",
            )
        }
        val fetch = client.fetchEvents(
            relays = listOf(relayUrl),
            filter = NostrFilter(
                authors = listOf(session.publicKeyHex),
                kinds = listOf(RelayTestKind),
                tTags = emptyList(),
                since = createdAt - 10,
                limit = 20,
            ),
        )
        val fetchStatus = fetch.statuses.firstOrNull()
        if (fetchStatus?.readable != true) {
            return RelayTestResult.Failure(
                relayUrl = relayUrl,
                reason = classifyFetchFailure(fetchStatus?.message),
                safeMessage = "stage=relay_test_fetch ${fetchStatus?.message.safeRelayTestMessage()}",
            )
        }
        if (fetch.events.none { it.id == event.id }) {
            return RelayTestResult.Failure(
                relayUrl = relayUrl,
                reason = RelayTestFailureReason.NoMatchingEvent,
                safeMessage = "stage=relay_test_fetch outcome=no_matching_event candidates=${fetch.events.size} event=${event.id.take(12)}",
            )
        }
        return RelayTestResult.Success(relayUrl = relayUrl, mode = "signed_write_fetch")
    }

    private suspend fun testReadOnly(relayUrl: String, session: UserSession?): RelayTestResult {
        val fetch = client.fetchEvents(
            relays = listOf(relayUrl),
            filter = NostrFilter(
                authors = if (session?.publicKeyHex?.length == 64) listOf(session.publicKeyHex) else emptyList(),
                kinds = listOf(RelayTestKind),
                tTags = emptyList(),
                limit = 1,
            ),
        )
        val status = fetch.statuses.firstOrNull()
        if (status?.readable == true) {
            return RelayTestResult.Success(relayUrl = relayUrl, mode = "read_connect")
        }
        return RelayTestResult.Failure(
            relayUrl = relayUrl,
            reason = classifyFetchFailure(status?.message),
            safeMessage = "stage=relay_test_read mode=read_connect ${status?.message.safeRelayTestMessage()}",
        )
    }
}

private fun classifyPublishFailure(message: String?): RelayTestFailureReason {
    val lowered = message.orEmpty().lowercase()
    return when {
        "timeout" in lowered -> RelayTestFailureReason.PublishTimedOut
        "rejected" in lowered -> RelayTestFailureReason.PublishRejected
        "connect_failed" in lowered || "connection" in lowered -> RelayTestFailureReason.ConnectionFailed
        else -> RelayTestFailureReason.UnexpectedSafeError
    }
}

private fun classifyFetchFailure(message: String?): RelayTestFailureReason {
    val lowered = message.orEmpty().lowercase()
    return when {
        "timeout" in lowered -> RelayTestFailureReason.FetchTimedOut
        "connect_failed" in lowered || "connection" in lowered -> RelayTestFailureReason.ConnectionFailed
        else -> RelayTestFailureReason.UnexpectedSafeError
    }
}

private fun Throwable.safeRelayTestMessage(): String =
    "${this::class.simpleName}: ${message.safeRelayTestMessage()}"

private fun String?.safeRelayTestMessage(): String =
    orEmpty()
        .replace(Regex("secret=([^&\\s]+)"), "secret=redacted")
        .replace(Regex("privateKey=([^&\\s]+)"), "privateKey=redacted")
        .replace(Regex("nsec[0-9a-zA-Z]+"), "nsec-redacted")
        .replace("body_markdown", "payload_field_redacted")
        .take(180)
