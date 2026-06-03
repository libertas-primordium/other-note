package com.libertasprimordium.othernote.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal const val WebRememberedNip46StorageKey = "on.web.nip46"
internal const val WebRememberedNip46Version = 1
internal const val RememberNip46CheckboxLabel = "Remember this remote signer on this browser"

internal object WebRememberedNip46Copy {
    const val Stored = "Remote signer remembered on this browser."
    const val StoreFailed = "Remote signer connected, but this browser could not remember it."
    const val ForgetFailed = "Remembered remote signer could not be forgotten."
    const val Forgotten = "Remembered remote signer forgotten."
    const val ForgottenActive = "Remembered remote signer forgotten. Current remote-signer session was cleared."
    const val InvalidStoredSession = "Remembered remote signer data is invalid. Forget it and pair again."
    const val ReconnectFailed = "This remembered remote signer could not be reached or no longer accepts this session."
}

@Serializable
internal data class WebRememberedNip46Record(
    val version: Int,
    val userPubkey: String,
    val clientPrivateKeyHex: String,
    val clientPubkey: String,
    val remoteSignerPubkey: String,
    val signerRelays: List<String>,
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0,
) {
    override fun toString(): String =
        "WebRememberedNip46Record(version=$version, userPubkey=${userPubkey.safePrefix()}, clientPrivateKey=redacted, clientPubkey=${clientPubkey.safePrefix()}, remoteSignerPubkey=${remoteSignerPubkey.safePrefix()}, signerRelays=${signerRelays.size})"
}

internal data class WebRememberedNip46UiState(
    val record: WebRememberedNip46Record? = null,
    val invalidStoredSession: Boolean = false,
    val message: String = "",
)

internal sealed interface WebRememberedNip46LoadResult {
    data object Empty : WebRememberedNip46LoadResult
    data class Loaded(val record: WebRememberedNip46Record) : WebRememberedNip46LoadResult
    data class Invalid(val safeMessage: String) : WebRememberedNip46LoadResult
}

internal interface WebRememberedNip46Storage {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

internal fun rememberedNip46StateFromStorage(storage: WebRememberedNip46Storage?): WebRememberedNip46UiState =
    when (val loaded = loadRememberedNip46Record(storage)) {
        WebRememberedNip46LoadResult.Empty -> WebRememberedNip46UiState()
        is WebRememberedNip46LoadResult.Loaded -> WebRememberedNip46UiState(record = loaded.record)
        is WebRememberedNip46LoadResult.Invalid -> WebRememberedNip46UiState(
            invalidStoredSession = true,
            message = loaded.safeMessage,
        )
    }

internal fun loadRememberedNip46Record(storage: WebRememberedNip46Storage?): WebRememberedNip46LoadResult =
    runCatching {
        val raw = storage?.read(WebRememberedNip46StorageKey)?.takeIf { it.isNotBlank() }
            ?: return WebRememberedNip46LoadResult.Empty
        val decoded = decodeRememberedNip46Record(raw)
            ?: return WebRememberedNip46LoadResult.Invalid(WebRememberedNip46Copy.InvalidStoredSession)
        WebRememberedNip46LoadResult.Loaded(decoded)
    }.getOrDefault(WebRememberedNip46LoadResult.Invalid(WebRememberedNip46Copy.InvalidStoredSession))

internal fun saveRememberedNip46Record(
    storage: WebRememberedNip46Storage?,
    record: WebRememberedNip46Record,
): Boolean =
    runCatching {
        val valid = validateRememberedNip46Record(record) ?: return false
        storage?.write(WebRememberedNip46StorageKey, encodeRememberedNip46Record(valid))
        true
    }.getOrDefault(false)

internal fun forgetRememberedNip46Record(storage: WebRememberedNip46Storage?): Boolean =
    runCatching {
        storage?.remove(WebRememberedNip46StorageKey)
        true
    }.getOrDefault(false)

internal fun encodeRememberedNip46Record(record: WebRememberedNip46Record): String =
    rememberedNip46Json.encodeToString(WebRememberedNip46Record.serializer(), record)

internal fun decodeRememberedNip46Record(raw: String): WebRememberedNip46Record? =
    try {
        validateRememberedNip46Record(
            rememberedNip46Json.decodeFromString(WebRememberedNip46Record.serializer(), raw),
        )
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

internal fun validateRememberedNip46Record(record: WebRememberedNip46Record): WebRememberedNip46Record? {
    if (record.version != WebRememberedNip46Version) return null
    val userPubkey = record.userPubkey.lowercase()
    val clientPrivateKeyHex = record.clientPrivateKeyHex.lowercase()
    val clientPubkey = record.clientPubkey.lowercase()
    val remoteSignerPubkey = record.remoteSignerPubkey.lowercase()
    if (!isValidHexPublicKey(userPubkey)) return null
    if (!isValidHexPrivateKey(clientPrivateKeyHex)) return null
    if (!isValidHexPublicKey(clientPubkey)) return null
    if (!isValidHexPublicKey(remoteSignerPubkey)) return null
    val privateKey = uint8ArrayFromHex(clientPrivateKeyHex) ?: return null
    val derivedClientPubkey = runCatching { NostrTools.getPublicKey(privateKey).lowercase() }.getOrNull()
    if (derivedClientPubkey != clientPubkey) return null
    val signerRelays = record.signerRelays.mapNotNull(::normalizeSignerRelay).distinct()
    if (signerRelays.isEmpty()) return null
    return WebRememberedNip46Record(
        version = WebRememberedNip46Version,
        userPubkey = userPubkey,
        clientPrivateKeyHex = clientPrivateKeyHex,
        clientPubkey = clientPubkey,
        remoteSignerPubkey = remoteSignerPubkey,
        signerRelays = signerRelays,
        createdAtMs = record.createdAtMs.coerceAtLeast(0),
        updatedAtMs = record.updatedAtMs.coerceAtLeast(0),
    )
}

internal fun rememberedNip46RecordFromSession(
    session: WebNip46Session,
    userPubkey: String,
    nowMs: Long,
): WebRememberedNip46Record? =
    validateRememberedNip46Record(
        WebRememberedNip46Record(
            version = WebRememberedNip46Version,
            userPubkey = userPubkey,
            clientPrivateKeyHex = session.clientPrivateKey.toFixedHex(32),
            clientPubkey = session.clientPubkey,
            remoteSignerPubkey = session.remoteSignerPubkey,
            signerRelays = session.relays,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        ),
    )

internal fun WebRememberedNip46Record.toSession(): WebNip46Session? {
    val valid = validateRememberedNip46Record(this) ?: return null
    return WebNip46Session(
        clientPrivateKey = uint8ArrayFromHex(valid.clientPrivateKeyHex) ?: return null,
        clientPubkey = valid.clientPubkey,
        remoteSignerPubkey = valid.remoteSignerPubkey,
        relays = valid.signerRelays,
    )
}

internal fun rememberNip46OptInLabel(remember: Boolean): String =
    if (remember) {
        "This saves the app's remote-signer communication session on this browser. It does not save your private key, but this browser may request signer actions until the signer revokes the connection or you forget it here."
    } else {
        "New remote-signer pairings are session-only unless you explicitly remember them on this browser."
    }

private val rememberedNip46Json = Json {
    ignoreUnknownKeys = false
    prettyPrint = false
}

private fun String.safePrefix(): String =
    if (length <= 12) this else take(12)
