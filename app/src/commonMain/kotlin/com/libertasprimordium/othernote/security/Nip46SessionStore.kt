package com.libertasprimordium.othernote.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SavedNip46Session(
    val userPubkey: String,
    val userNpub: String,
    val clientPrivateKeyHex: String,
    val clientPubkey: String,
    val remoteSignerPubkey: String,
    val relays: List<String>,
    val label: String? = null,
    val createdAtMs: Long = 0,
    val lastUsedAtMs: Long = 0,
    val version: Int = 1,
) {
    fun metadata(): SavedNip46SessionMetadata =
        SavedNip46SessionMetadata(
            userPubkey = userPubkey,
            userNpub = userNpub,
            clientPubkey = clientPubkey,
            remoteSignerPubkey = remoteSignerPubkey,
            relays = relays,
            label = label,
            createdAtMs = createdAtMs,
            lastUsedAtMs = lastUsedAtMs,
            version = version,
        )

    override fun toString(): String =
        "SavedNip46Session(userPubkey=${userPubkey.safePrefix()}, userNpub=${userNpub.safePrefix()}, clientPrivateKey=redacted, clientPubkey=${clientPubkey.safePrefix()}, remoteSignerPubkey=${remoteSignerPubkey.safePrefix()}, relays=${relays.size}, labelPresent=${label != null}, version=$version)"
}

data class SavedNip46SessionMetadata(
    val userPubkey: String,
    val userNpub: String,
    val clientPubkey: String,
    val remoteSignerPubkey: String,
    val relays: List<String>,
    val label: String? = null,
    val createdAtMs: Long = 0,
    val lastUsedAtMs: Long = 0,
    val version: Int = 1,
) {
    override fun toString(): String =
        "SavedNip46SessionMetadata(userPubkey=${userPubkey.safePrefix()}, userNpub=${userNpub.safePrefix()}, clientPubkey=${clientPubkey.safePrefix()}, remoteSignerPubkey=${remoteSignerPubkey.safePrefix()}, relays=${relays.size}, labelPresent=${label != null}, version=$version)"
}

interface Nip46SessionStore {
    val isAvailable: Boolean
    val unavailableReason: String?
    suspend fun listSessions(): Nip46SessionStoreResult
    suspend fun saveSession(session: SavedNip46Session): Nip46SessionStoreResult
    suspend fun loadSession(userPubkey: String): Nip46SessionStoreResult
    suspend fun deleteSession(userPubkey: String): Nip46SessionStoreResult
}

sealed class Nip46SessionStoreResult {
    data object Unavailable : Nip46SessionStoreResult()
    data object Saved : Nip46SessionStoreResult()
    data object Deleted : Nip46SessionStoreResult()
    data class Listed(val sessions: List<SavedNip46SessionMetadata>) : Nip46SessionStoreResult()
    data class Loaded(val session: SavedNip46Session) : Nip46SessionStoreResult() {
        override fun toString(): String = "Loaded(session=redacted)"
    }
    data class Failed(val safeMessage: String) : Nip46SessionStoreResult()
}

class UnavailableNip46SessionStore(
    override val unavailableReason: String = "Saved remote signer sessions are not implemented for this runtime.",
) : Nip46SessionStore {
    override val isAvailable: Boolean = false

    override suspend fun listSessions(): Nip46SessionStoreResult =
        Nip46SessionStoreResult.Unavailable

    override suspend fun saveSession(session: SavedNip46Session): Nip46SessionStoreResult =
        Nip46SessionStoreResult.Unavailable

    override suspend fun loadSession(userPubkey: String): Nip46SessionStoreResult =
        Nip46SessionStoreResult.Unavailable

    override suspend fun deleteSession(userPubkey: String): Nip46SessionStoreResult =
        Nip46SessionStoreResult.Unavailable
}

class InMemoryNip46SessionStore(
    override val isAvailable: Boolean = true,
    override val unavailableReason: String? = null,
) : Nip46SessionStore {
    private val saved = linkedMapOf<String, SavedNip46Session>()

    override suspend fun listSessions(): Nip46SessionStoreResult =
        if (!isAvailable) {
            Nip46SessionStoreResult.Unavailable
        } else {
            Nip46SessionStoreResult.Listed(saved.values.map { it.metadata() })
        }

    override suspend fun saveSession(session: SavedNip46Session): Nip46SessionStoreResult =
        if (!isAvailable) {
            Nip46SessionStoreResult.Unavailable
        } else {
            saved[session.userPubkey.lowercase()] = session.copy(userPubkey = session.userPubkey.lowercase())
            Nip46SessionStoreResult.Saved
        }

    override suspend fun loadSession(userPubkey: String): Nip46SessionStoreResult =
        if (!isAvailable) {
            Nip46SessionStoreResult.Unavailable
        } else {
            saved[userPubkey.lowercase()]?.let { Nip46SessionStoreResult.Loaded(it) }
                ?: Nip46SessionStoreResult.Failed("Saved remote signer session could not be loaded.")
        }

    override suspend fun deleteSession(userPubkey: String): Nip46SessionStoreResult =
        if (!isAvailable) {
            Nip46SessionStoreResult.Unavailable
        } else {
            saved.remove(userPubkey.lowercase())
            Nip46SessionStoreResult.Deleted
        }
}

@Serializable
data class DurableNip46SessionFile(val sessions: List<DurableNip46SessionRecord>)

@Serializable
data class DurableNip46SessionRecord(
    val userPubkey: String,
    val userNpub: String,
    val clientPrivateKeyHex: String,
    val clientPubkey: String,
    val remoteSignerPubkey: String,
    val relays: List<String>,
    val label: String? = null,
    val createdAtMs: Long = 0,
    val lastUsedAtMs: Long = 0,
    val version: Int = 1,
) {
    fun toSession(): SavedNip46Session =
        SavedNip46Session(
            userPubkey = userPubkey.lowercase(),
            userNpub = userNpub,
            clientPrivateKeyHex = clientPrivateKeyHex.lowercase(),
            clientPubkey = clientPubkey.lowercase(),
            remoteSignerPubkey = remoteSignerPubkey.lowercase(),
            relays = relays.distinct(),
            label = label,
            createdAtMs = createdAtMs,
            lastUsedAtMs = lastUsedAtMs,
            version = version,
        )
}

object Nip46SessionCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encodeSessions(sessions: List<SavedNip46Session>): String =
        json.encodeToString(
            DurableNip46SessionFile.serializer(),
            DurableNip46SessionFile(sessions.map { it.toDurableRecord() }),
        )

    fun decodeSessionsOrEmpty(raw: String): List<SavedNip46Session> =
        runCatching {
            json.decodeFromString(DurableNip46SessionFile.serializer(), raw).sessions.map { it.toSession() }
        }.getOrDefault(emptyList())

    fun encodeSession(session: SavedNip46Session): String =
        json.encodeToString(DurableNip46SessionRecord.serializer(), session.toDurableRecord())

    fun decodeSessionOrNull(raw: String): SavedNip46Session? =
        runCatching { json.decodeFromString(DurableNip46SessionRecord.serializer(), raw).toSession() }.getOrNull()
}

fun SavedNip46Session.toDurableRecord(): DurableNip46SessionRecord =
    DurableNip46SessionRecord(
        userPubkey = userPubkey.lowercase(),
        userNpub = userNpub,
        clientPrivateKeyHex = clientPrivateKeyHex.lowercase(),
        clientPubkey = clientPubkey.lowercase(),
        remoteSignerPubkey = remoteSignerPubkey.lowercase(),
        relays = relays.distinct(),
        label = label,
        createdAtMs = createdAtMs,
        lastUsedAtMs = lastUsedAtMs,
        version = version,
    )

private fun String.safePrefix(): String =
    if (length <= 12) this else take(12)
