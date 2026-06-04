package com.libertasprimordium.othernote.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SavedNip55Session(
    val userPubkey: String,
    val userNpub: String,
    val signerPackage: String,
    val signerLabel: String? = null,
    val active: Boolean = false,
    val createdAtMs: Long = 0,
    val lastUsedAtMs: Long = 0,
    val version: Int = 1,
) {
    fun metadata(): SavedNip55SessionMetadata =
        SavedNip55SessionMetadata(
            userPubkey = userPubkey,
            userNpub = userNpub,
            signerPackage = signerPackage,
            signerLabel = signerLabel,
            active = active,
            createdAtMs = createdAtMs,
            lastUsedAtMs = lastUsedAtMs,
            version = version,
        )

    override fun toString(): String =
        "SavedNip55Session(userPubkey=${userPubkey.safePrefix()}, userNpub=${userNpub.safePrefix()}, signerPackage=${signerPackage.safePackage()}, signerLabelPresent=${signerLabel != null}, active=$active, version=$version)"
}

data class SavedNip55SessionMetadata(
    val userPubkey: String,
    val userNpub: String,
    val signerPackage: String,
    val signerLabel: String? = null,
    val active: Boolean = false,
    val createdAtMs: Long = 0,
    val lastUsedAtMs: Long = 0,
    val version: Int = 1,
) {
    override fun toString(): String =
        "SavedNip55SessionMetadata(userPubkey=${userPubkey.safePrefix()}, userNpub=${userNpub.safePrefix()}, signerPackage=${signerPackage.safePackage()}, signerLabelPresent=${signerLabel != null}, active=$active, version=$version)"
}

interface Nip55SessionStore {
    val isAvailable: Boolean
    val unavailableReason: String?
    suspend fun listSessions(): Nip55SessionStoreResult
    suspend fun saveSession(session: SavedNip55Session): Nip55SessionStoreResult
    suspend fun loadSession(userPubkey: String): Nip55SessionStoreResult
    suspend fun deleteSession(userPubkey: String): Nip55SessionStoreResult
}

sealed class Nip55SessionStoreResult {
    data object Unavailable : Nip55SessionStoreResult()
    data object Saved : Nip55SessionStoreResult()
    data object Deleted : Nip55SessionStoreResult()
    data class Listed(val sessions: List<SavedNip55SessionMetadata>) : Nip55SessionStoreResult()
    data class Loaded(val session: SavedNip55Session) : Nip55SessionStoreResult()
    data class Failed(val safeMessage: String) : Nip55SessionStoreResult()
}

class UnavailableNip55SessionStore(
    override val unavailableReason: String = "Saved Android signer sessions are not implemented for this runtime.",
) : Nip55SessionStore {
    override val isAvailable: Boolean = false

    override suspend fun listSessions(): Nip55SessionStoreResult =
        Nip55SessionStoreResult.Unavailable

    override suspend fun saveSession(session: SavedNip55Session): Nip55SessionStoreResult =
        Nip55SessionStoreResult.Unavailable

    override suspend fun loadSession(userPubkey: String): Nip55SessionStoreResult =
        Nip55SessionStoreResult.Unavailable

    override suspend fun deleteSession(userPubkey: String): Nip55SessionStoreResult =
        Nip55SessionStoreResult.Unavailable
}

class InMemoryNip55SessionStore(
    override val isAvailable: Boolean = true,
    override val unavailableReason: String? = null,
) : Nip55SessionStore {
    private val saved = linkedMapOf<String, SavedNip55Session>()

    override suspend fun listSessions(): Nip55SessionStoreResult =
        if (!isAvailable) {
            Nip55SessionStoreResult.Unavailable
        } else {
            Nip55SessionStoreResult.Listed(saved.values.map { it.metadata() })
        }

    override suspend fun saveSession(session: SavedNip55Session): Nip55SessionStoreResult =
        if (!isAvailable) {
            Nip55SessionStoreResult.Unavailable
        } else {
            saved[session.userPubkey.lowercase()] = session.copy(userPubkey = session.userPubkey.lowercase())
            Nip55SessionStoreResult.Saved
        }

    override suspend fun loadSession(userPubkey: String): Nip55SessionStoreResult =
        if (!isAvailable) {
            Nip55SessionStoreResult.Unavailable
        } else {
            saved[userPubkey.lowercase()]?.let { Nip55SessionStoreResult.Loaded(it) }
                ?: Nip55SessionStoreResult.Failed("Saved Android signer session could not be loaded.")
        }

    override suspend fun deleteSession(userPubkey: String): Nip55SessionStoreResult =
        if (!isAvailable) {
            Nip55SessionStoreResult.Unavailable
        } else {
            saved.remove(userPubkey.lowercase())
            Nip55SessionStoreResult.Deleted
        }
}

@Serializable
data class DurableNip55SessionFile(val sessions: List<DurableNip55SessionRecord>)

@Serializable
data class DurableNip55SessionRecord(
    val userPubkey: String,
    val userNpub: String,
    val signerPackage: String,
    val signerLabel: String? = null,
    val active: Boolean = true,
    val createdAtMs: Long = 0,
    val lastUsedAtMs: Long = 0,
    val version: Int = 1,
) {
    fun toSession(): SavedNip55Session =
        SavedNip55Session(
            userPubkey = userPubkey.lowercase(),
            userNpub = userNpub,
            signerPackage = signerPackage,
            signerLabel = signerLabel,
            active = active,
            createdAtMs = createdAtMs,
            lastUsedAtMs = lastUsedAtMs,
            version = version,
        )
}

object Nip55SessionCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encodeSessions(sessions: List<SavedNip55Session>): String =
        json.encodeToString(
            DurableNip55SessionFile.serializer(),
            DurableNip55SessionFile(sessions.map { it.toDurableRecord() }),
        )

    fun decodeSessionsOrEmpty(raw: String): List<SavedNip55Session> =
        runCatching {
            json.decodeFromString(DurableNip55SessionFile.serializer(), raw).sessions.map { it.toSession() }
        }.getOrDefault(emptyList())
}

fun SavedNip55Session.toDurableRecord(): DurableNip55SessionRecord =
    DurableNip55SessionRecord(
        userPubkey = userPubkey.lowercase(),
        userNpub = userNpub,
        signerPackage = signerPackage,
        signerLabel = signerLabel,
        active = active,
        createdAtMs = createdAtMs,
        lastUsedAtMs = lastUsedAtMs,
        version = version,
    )

private fun String.safePrefix(): String =
    if (length <= 12) this else take(12)

private fun String.safePackage(): String =
    if (isBlank()) "missing" else take(80)
