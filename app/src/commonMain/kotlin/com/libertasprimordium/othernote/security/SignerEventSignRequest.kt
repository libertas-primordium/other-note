package com.libertasprimordium.othernote.security

import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrWireJson
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.sync.RelayListKind
import com.libertasprimordium.othernote.util.nowMs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed class SignEventRequestResult {
    data class Success(val signedEvent: NostrEvent, val signerPackage: String?) : SignEventRequestResult()
    data object Cancelled : SignEventRequestResult()
    data class Unavailable(val safeReason: String) : SignEventRequestResult()
    data class Failed(val safeReason: String) : SignEventRequestResult()
    data class InvalidResponse(val safeReason: String) : SignEventRequestResult()
}

interface NostrSignerEventSigner {
    fun signEvent(
        unsignedEvent: NostrEvent,
        currentUserPubkey: String,
        signerPackage: String?,
        onResult: (SignEventRequestResult) -> Unit,
    )
}

class UnavailableSignerEventSigner(
    private val safeReason: String = "External signer event signing is not implemented for this runtime.",
) : NostrSignerEventSigner {
    override fun signEvent(
        unsignedEvent: NostrEvent,
        currentUserPubkey: String,
        signerPackage: String?,
        onResult: (SignEventRequestResult) -> Unit,
    ) {
        onResult(SignEventRequestResult.Unavailable(safeReason))
    }
}

object SignerTestEventFactory {
    const val TestKind = 1
    const val TestContent = "Other Note signer test"

    fun build(pubkey: String, nowMs: Long = nowMs()): Result<NostrEvent> = runCatching {
        val crypto = ProductionNostrCryptoFactory.createOrNull()
            ?: error(ProductionNostrCryptoFactory.unavailableReason)
        val unsigned = UnsignedNostrEvent(
            pubkey = pubkey,
            createdAt = nowMs / 1000,
            kind = TestKind,
            tags = emptyList(),
            content = TestContent,
        )
        NostrEvent(
            id = crypto.computeEventId(unsigned).getOrThrow(),
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = "",
        )
    }
}

data class SignEventLaunchRequest(
    val eventJson: String,
    val uriString: String,
    val shape: SignEventRequestShape,
    val expectedEventId: String,
    val requestedEvent: NostrEvent,
    val safeDiagnostics: List<String>,
)

enum class SignEventRequestShape(val diagnosticName: String) {
    FullUnsignedEventNoIdSig("full_unsigned_event_no_id_sig"),
    FullEventBlankIdSig("full_event_blank_id_sig"),
    PartialPayload("current_partial_payload"),
}

object SignerSignEventRequestBuilder {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun build(
        requestedEvent: NostrEvent,
        currentUserPubkey: String,
        signerPackage: String?,
        shape: SignEventRequestShape = SignEventRequestShape.FullUnsignedEventNoIdSig,
    ): Result<SignEventLaunchRequest> = runCatching {
        require(requestedEvent.pubkey == currentUserPubkey) { "Requested event pubkey must match current user" }
        require(requestedEvent.id.isNotBlank()) { "Requested event id must not be blank" }
        require(requestedEvent.content.isNotBlank() || requestedEvent.kind == RelayListKind) {
            "Requested event content must not be blank"
        }
        val payload = SignEventPayload.from(requestedEvent, shape)
        val eventJson = json.encodeToString(payload)
        require(eventJson.isNotBlank()) { "NIP-55 sign_event payload must not be blank" }
        val parsed = parseEventPayload(eventJson) ?: error("NIP-55 sign_event payload did not parse")
        require(parsed == payload) { "NIP-55 sign_event payload did not round-trip" }
        require(parsed.pubkey == requestedEvent.pubkey || shape == SignEventRequestShape.PartialPayload) {
            "NIP-55 sign_event payload must include pubkey for this request shape"
        }
        val uriString = "nostrsigner:$eventJson"
        require(uriString.removePrefix("nostrsigner:").isNotBlank()) {
            "NIP-55 sign_event URI must include event payload"
        }
        val hasPubkey = parsed.pubkey != null
        val hasId = parsed.id != null
        val hasSig = parsed.sig != null
        SignEventLaunchRequest(
            eventJson = eventJson,
            uriString = uriString,
            shape = shape,
            expectedEventId = requestedEvent.id,
            requestedEvent = requestedEvent,
            safeDiagnostics = listOf(
                "sign_event button tapped",
                "sign_event request launched",
                "request_shape=${shape.diagnosticName}",
                "event_json_length=${eventJson.length}",
                "uri_payload_length=${uriString.removePrefix("nostrsigner:").length}",
                "event_contains_pubkey=$hasPubkey",
                "event_contains_id=$hasId",
                "event_contains_sig=$hasSig",
                "kind=${requestedEvent.kind}",
                "content_length=${requestedEvent.content.length}",
                "tag_count=${requestedEvent.tags.size}",
                "current_user=${requestedEvent.pubkey.abbreviatedHex()}",
                "event_id=${requestedEvent.id.abbreviatedHex()}",
                "signer_package_target_present=${!signerPackage.isNullOrBlank()}",
                "data_uri_scheme=nostrsigner",
                "data_uri_has_payload=${uriString.removePrefix("nostrsigner:").isNotBlank()}",
            ),
        )
    }

    fun parseEventPayload(raw: String): SignEventPayload? =
        runCatching { json.decodeFromString<SignEventPayload>(raw) }.getOrNull()

    private fun String.abbreviatedHex(): String = take(12)
}

@Serializable
data class SignEventPayload(
    val pubkey: String? = null,
    @SerialName("created_at")
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>> = emptyList(),
    val content: String,
    val id: String? = null,
    val sig: String? = null,
) {
    companion object {
        fun from(event: NostrEvent, shape: SignEventRequestShape): SignEventPayload = when (shape) {
            SignEventRequestShape.FullUnsignedEventNoIdSig -> SignEventPayload(
                pubkey = event.pubkey,
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
            )
            SignEventRequestShape.FullEventBlankIdSig -> SignEventPayload(
                pubkey = event.pubkey,
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
                id = "",
                sig = "",
            )
            SignEventRequestShape.PartialPayload -> SignEventPayload(
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
            )
        }
    }
}

object SignerSignEventResponseParser {
    fun parseAndValidate(
        requestedEvent: NostrEvent,
        eventJson: String?,
        signature: String?,
        returnedId: String?,
        signerPackage: String?,
        crypto: NostrCrypto? = ProductionNostrCryptoFactory.createOrNull(),
    ): SignEventRequestResult {
        if (crypto == null) return SignEventRequestResult.Unavailable(ProductionNostrCryptoFactory.unavailableReason)
        val signedEvent = parseSignedEvent(requestedEvent, eventJson, signature, returnedId)
            ?: return SignEventRequestResult.InvalidResponse("Signer returned no event to verify")
        if (signedEvent.pubkey != requestedEvent.pubkey) {
            return SignEventRequestResult.InvalidResponse("Signer returned event for a different pubkey")
        }
        if (signedEvent.createdAt != requestedEvent.createdAt ||
            signedEvent.kind != requestedEvent.kind ||
            signedEvent.tags != requestedEvent.tags ||
            signedEvent.content != requestedEvent.content
        ) {
            return SignEventRequestResult.InvalidResponse("Signer returned unexpected event content")
        }
        if (signedEvent.id.isBlank() || signedEvent.sig.isBlank()) {
            return SignEventRequestResult.InvalidResponse("Signer returned event without id or signature")
        }
        val valid = crypto.validate(signedEvent).getOrDefault(false)
        if (!valid) {
            return SignEventRequestResult.InvalidResponse("Signer returned invalid event signature")
        }
        return SignEventRequestResult.Success(
            signedEvent = signedEvent,
            signerPackage = signerPackage?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseSignedEvent(
        requestedEvent: NostrEvent,
        eventJson: String?,
        signature: String?,
        returnedId: String?,
    ): NostrEvent? {
        val eventFromJson = eventJson?.takeIf { it.isNotBlank() }?.let(NostrWireJson::parseEventJson)
        if (eventFromJson != null) return eventFromJson
        val sig = signature?.trim().orEmpty()
        if (sig.isBlank()) return null
        return requestedEvent.copy(
            id = returnedId?.takeIf { it.isNotBlank() } ?: requestedEvent.id,
            sig = sig,
        )
    }
}
