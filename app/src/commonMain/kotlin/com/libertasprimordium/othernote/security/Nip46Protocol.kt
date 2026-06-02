package com.libertasprimordium.othernote.security

import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import com.libertasprimordium.othernote.util.stableRandomId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val Nip46EventKind = 24133

enum class Nip46Method(val wireName: String) {
    Connect("connect"),
    Ping("ping"),
    GetPublicKey("get_public_key"),
    SignEvent("sign_event"),
    Nip44Encrypt("nip44_encrypt"),
    Nip44Decrypt("nip44_decrypt"),
    SwitchRelays("switch_relays"),
}

data class Nip46Permissions(
    val methods: List<Nip46Method>,
) {
    fun toWireString(): String = methods.joinToString(",") { it.wireName }

    companion object {
        val OtherNoteRequired = Nip46Permissions(
            listOf(
                Nip46Method.Connect,
                Nip46Method.Ping,
                Nip46Method.GetPublicKey,
                Nip46Method.SignEvent,
                Nip46Method.Nip44Encrypt,
                Nip46Method.Nip44Decrypt,
                Nip46Method.SwitchRelays,
            ),
        )

        fun parse(raw: String?): Nip46Permissions {
            val methods = raw.orEmpty()
                .split(",", " ")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { value -> Nip46Method.entries.firstOrNull { it.wireName == value.substringBefore(":") } }
                .distinct()
            return Nip46Permissions(methods)
        }

        fun otherNoteConnectPermissions(): String = listOf(
            Nip46Method.GetPublicKey.wireName,
            "${Nip46Method.SignEvent.wireName}:$NoteKind",
            Nip46Method.Nip44Encrypt.wireName,
            Nip46Method.Nip44Decrypt.wireName,
            Nip46Method.Ping.wireName,
            Nip46Method.SwitchRelays.wireName,
        ).joinToString(",")
    }
}

sealed class Nip46ConnectionToken {
    abstract val relays: List<String>

    data class Bunker(
        val remoteSignerPubkey: String,
        override val relays: List<String>,
        val secret: String?,
    ) : Nip46ConnectionToken() {
        override fun toString(): String =
            "Nip46ConnectionToken.Bunker(remoteSignerPubkey=${remoteSignerPubkey.take(12)}, relays=$relays, secret=${secret.redactedSecret()})"
    }

    data class NostrConnect(
        val clientPubkey: String,
        override val relays: List<String>,
        val secret: String,
        val permissions: Nip46Permissions,
        val name: String?,
    ) : Nip46ConnectionToken() {
        override fun toString(): String =
            "Nip46ConnectionToken.NostrConnect(clientPubkey=${clientPubkey.take(12)}, relays=$relays, secret=${secret.redactedSecret()}, perms=${permissions.toWireString()}, name=${name.orEmpty().take(40)})"
    }
}

object Nip46ConnectionTokenParser {
    fun parse(raw: String): Result<Nip46ConnectionToken> = runCatching {
        val trimmed = raw.trim()
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
        require(scheme == "bunker" || scheme == "nostrconnect") { "Unsupported remote signer token scheme" }
        val rest = trimmed.substringAfter("://", missingDelimiterValue = "")
        require(rest.isNotBlank()) { "Remote signer token is missing a public key" }
        val pubkey = percentDecode(rest.substringBefore("?")).lowercase()
        require(pubkey.isValidHexPubkey()) { "Remote signer token has malformed public key" }
        val params = parseParams(rest.substringAfter("?", missingDelimiterValue = ""))
        val relays = params.values("relay").map { relay ->
            normalizeRelayUrl(percentDecode(relay)).getOrElse { error("Remote signer relay URL is invalid") }
        }.distinct()
        require(relays.isNotEmpty()) { "Remote signer token must include at least one relay" }
        require(relays.all { it.startsWith("wss://") }) { "Remote signer relays must use wss://" }
        when (scheme) {
            "bunker" -> Nip46ConnectionToken.Bunker(
                remoteSignerPubkey = pubkey,
                relays = relays,
                secret = params.first("secret")?.let(::percentDecode)?.takeIf { it.isNotBlank() },
            )
            "nostrconnect" -> {
                val secret = params.first("secret")?.let(::percentDecode)?.takeIf { it.isNotBlank() }
                    ?: error("Remote signer pairing token is missing a required secret")
                Nip46ConnectionToken.NostrConnect(
                    clientPubkey = pubkey,
                    relays = relays,
                    secret = secret,
                    permissions = Nip46Permissions.parse(params.first("perms")?.let(::percentDecode)),
                    name = params.first("name")?.let(::percentDecode)?.takeIf { it.isNotBlank() },
                )
            }
            else -> error("Unsupported remote signer token scheme")
        }
    }

    fun generateNostrConnectUri(
        clientPubkey: String,
        relays: List<String>,
        secret: String = stableRandomId(),
        permissions: Nip46Permissions = Nip46Permissions.OtherNoteRequired,
        name: String = "Other Note",
    ): Result<String> = runCatching {
        require(clientPubkey.isValidHexPubkey()) { "Client pubkey must be 32-byte hex" }
        require(secret.isNotBlank()) { "Remote signer pairing secret must not be blank" }
        val normalized = relays.map { normalizeRelayUrl(it).getOrThrow() }.distinct()
        require(normalized.isNotEmpty()) { "Remote signer pairing requires at least one relay" }
        require(normalized.all { it.startsWith("wss://") }) { "Remote signer relays must use wss://" }
        buildString {
            append("nostrconnect://").append(clientPubkey.lowercase())
            append("?")
            append(normalized.joinToString("&") { "relay=${percentEncode(it)}" })
            append("&secret=").append(percentEncode(secret))
            append("&perms=").append(percentEncode(permissions.toWireString()))
            append("&name=").append(percentEncode(name))
        }
    }

    private fun parseParams(raw: String): Map<String, List<String>> =
        raw.split("&")
            .filter { it.isNotBlank() }
            .map { part ->
                val key = percentDecode(part.substringBefore("="))
                val value = part.substringAfter("=", missingDelimiterValue = "")
                key to value
            }
            .groupBy({ it.first }, { it.second })

    private fun Map<String, List<String>>.values(key: String): List<String> = this[key].orEmpty()
    private fun Map<String, List<String>>.first(key: String): String? = this[key]?.firstOrNull()
}

@Serializable
data class Nip46RequestPayload(
    val id: String,
    val method: String,
    val params: List<String> = emptyList(),
)

@Serializable
data class Nip46ResponsePayload(
    val id: String,
    val result: String? = null,
    val error: String? = null,
)

@Serializable
data class Nip46UnsignedSignEventPayload(
    @SerialName("created_at")
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
) {
    companion object {
        fun from(event: NostrEvent): Nip46UnsignedSignEventPayload =
            Nip46UnsignedSignEventPayload(
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
            )
    }
}

sealed class Nip46Response {
    data class Success(val id: String, val result: String) : Nip46Response()
    data class Error(val id: String, val safeMessage: String) : Nip46Response()
    data class AuthChallenge(val id: String, val safeUrl: String) : Nip46Response()
    data class TransportFailure(val id: String, val reason: Nip46FailureReason, val safeMessage: String) : Nip46Response()
}

enum class Nip46FailureReason {
    TokenParseFailure,
    TransportKeyGenerationFailure,
    SignerRelayPublishRejected,
    SignerRelayPublishTimedOut,
    SignerRelayConnectionFailed,
    NoSignerResponse,
    SignerResponseDecryptFailed,
    SignerResponseIdMismatch,
    SignerRequestTimedOut,
    SignerRejectedRequest,
    SignerTransportFailed,
    AppNoteRelayPublishFailed,
}

object Nip46PayloadJson {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encodeRequest(payload: Nip46RequestPayload): String =
        json.encodeToString(Nip46RequestPayload.serializer(), payload)

    fun decodeRequest(raw: String): Result<Nip46RequestPayload> =
        runCatching { json.decodeFromString(Nip46RequestPayload.serializer(), raw) }

    fun encodeUnsignedSignEvent(event: NostrEvent): String =
        json.encodeToString(Nip46UnsignedSignEventPayload.serializer(), Nip46UnsignedSignEventPayload.from(event))

    fun encodeResponse(payload: Nip46ResponsePayload): String =
        json.encodeToString(Nip46ResponsePayload.serializer(), payload)

    fun decodeResponsePayload(raw: String): Result<Nip46ResponsePayload> =
        runCatching { json.decodeFromString(Nip46ResponsePayload.serializer(), raw) }

    fun decodeResponse(raw: String, expectedRequestId: String): Result<Nip46Response> = runCatching {
        val payload = json.decodeFromString(Nip46ResponsePayload.serializer(), raw)
        require(payload.id == expectedRequestId) { "Remote signer response id mismatch" }
        val error = payload.error?.takeIf { it.isNotBlank() }
        val result = payload.result?.takeIf { it.isNotBlank() }
        when {
            result == "auth_url" && error != null -> Nip46Response.AuthChallenge(payload.id, error.take(180))
            error != null -> Nip46Response.Error(payload.id, error.take(180))
            result != null -> Nip46Response.Success(payload.id, result)
            else -> Nip46Response.Error(payload.id, "Remote signer returned an empty response")
        }
    }
}

private fun String?.redactedSecret(): String = if (this.isNullOrBlank()) "absent" else "present"

internal fun String.isValidHexPubkey(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

private fun percentDecode(raw: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val hex = raw.substring(i + 1, i + 3)
            val byte = hex.toIntOrNull(16)
            if (byte != null) {
                out.append(byte.toChar())
                i += 3
                continue
            }
        }
        out.append(if (c == '+') ' ' else c)
        i++
    }
    return out.toString()
}

private fun percentEncode(raw: String): String =
    buildString {
        raw.encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xff
            val c = value.toChar()
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~') {
                append(c)
            } else {
                append('%')
                append(value.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
