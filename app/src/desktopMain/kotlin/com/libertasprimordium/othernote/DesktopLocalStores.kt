package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.LocalEventCache
import com.libertasprimordium.othernote.data.PendingRelayWrite
import com.libertasprimordium.othernote.data.PendingWriteStatus
import com.libertasprimordium.othernote.data.PendingWriteStore
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.util.nowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

object DesktopLocalStorePaths {
    fun dataDir(): Path {
        val home = System.getProperty("user.home")
        return Path.of(home, ".local", "share", "other-note")
    }
}

class DesktopLocalEventCache(
    private val baseDir: Path = DesktopLocalStorePaths.dataDir().resolve("event-cache"),
) : LocalEventCache {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    override suspend fun upsertEvents(accountPubkey: String, events: List<NostrEvent>) {
        if (events.isEmpty()) return
        val current = loadRecords(accountPubkey).associateBy { it.event.id }.toMutableMap()
        val now = nowMs()
        events.forEach { event ->
            current[event.id] = CachedEventFileRecord(event = event.toDto(), cachedAtMs = now)
        }
        writeRecords(accountPubkey, current.values.sortedBy { it.event.id })
    }

    override suspend fun loadEvents(accountPubkey: String): List<NostrEvent> =
        loadRecords(accountPubkey).map { it.event.toEvent() }

    override suspend fun clearAccount(accountPubkey: String) {
        runCatching { Files.deleteIfExists(fileFor(accountPubkey)) }
    }

    private fun loadRecords(accountPubkey: String): List<CachedEventFileRecord> =
        readFile(fileFor(accountPubkey))
            ?.let { runCatching { json.decodeFromString(CachedEventFile.serializer(), it).events }.getOrNull() }
            .orEmpty()

    private fun writeRecords(accountPubkey: String, records: List<CachedEventFileRecord>) {
        atomicWrite(fileFor(accountPubkey), json.encodeToString(CachedEventFile.serializer(), CachedEventFile(records)))
    }

    private fun fileFor(accountPubkey: String): Path = baseDir.resolve("${accountPubkey.safeFileName()}.events.json")

    private fun readFile(file: Path): String? =
        runCatching { if (file.exists()) file.readText() else null }.getOrNull()
}

class DesktopPendingWriteStore(
    private val baseDir: Path = DesktopLocalStorePaths.dataDir().resolve("pending-writes"),
) : PendingWriteStore {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes

    override suspend fun enqueuePendingWrite(accountPubkey: String, event: NostrEvent, targetRelays: List<String>) {
        val current = loadRecords(accountPubkey).associateBy { it.event.id }.toMutableMap()
        val now = nowMs()
        val existing = current[event.id]
        current[event.id] = PendingWriteFileRecord(
            accountPubkey = accountPubkey,
            event = event.toDto(),
            targetRelays = targetRelays.distinct(),
            relayStatuses = existing?.relayStatuses.orEmpty() + targetRelays.distinct().associateWith {
                existing?.relayStatuses?.get(it) ?: PendingWriteStatus.Pending.name
            },
            retryCounts = existing?.retryCounts.orEmpty(),
            lastSafeErrorByRelay = existing?.lastSafeErrorByRelay.orEmpty(),
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
        )
        writeRecords(accountPubkey, current.values.sortedBy { it.event.id })
    }

    override suspend fun markRelayAccepted(eventId: String, relayUrl: String) {
        update(eventId) { write ->
            write.copy(
                relayStatuses = write.relayStatuses + (relayUrl to PendingWriteStatus.Accepted.name),
                updatedAtMs = nowMs(),
            )
        }
    }

    override suspend fun markRelayRejectedOrFailed(eventId: String, relayUrl: String, safeReason: String) {
        update(eventId) { write ->
            write.copy(
                relayStatuses = write.relayStatuses + (relayUrl to PendingWriteStatus.Failed.name),
                lastSafeErrorByRelay = write.lastSafeErrorByRelay + (relayUrl to safeReason.take(180)),
                updatedAtMs = nowMs(),
            )
        }
    }

    override suspend fun recordRetry(eventId: String, relayUrl: String) {
        update(eventId) { write ->
            write.copy(
                retryCounts = write.retryCounts + (relayUrl to ((write.retryCounts[relayUrl] ?: 0) + 1)),
                relayStatuses = write.relayStatuses + (relayUrl to PendingWriteStatus.Pending.name),
                updatedAtMs = nowMs(),
            )
        }
    }

    override suspend fun loadPendingWrites(accountPubkey: String): List<PendingRelayWrite> =
        loadRecords(accountPubkey).map { it.toPendingWrite() }

    override suspend fun removeCompletedWrite(eventId: String) {
        val files = runCatching { Files.list(baseDir).use { it.toList() } }.getOrDefault(emptyList())
        files.filter { it.name.endsWith(".pending.json") }.forEach { file ->
            val records = readPendingRecords(file)
            if (records.any { it.event.id == eventId }) {
                val remaining = records.filterNot { it.event.id == eventId }
                atomicWrite(file, json.encodeToString(PendingWriteFile.serializer(), PendingWriteFile(remaining)))
                bump()
            }
        }
    }

    private fun update(eventId: String, transform: (PendingWriteFileRecord) -> PendingWriteFileRecord) {
        val files = runCatching { Files.list(baseDir).use { it.toList() } }.getOrDefault(emptyList())
        files.filter { it.name.endsWith(".pending.json") }.forEach { file ->
            val records = readPendingRecords(file)
            if (records.any { it.event.id == eventId }) {
                val updated = records.map { if (it.event.id == eventId) transform(it) else it }
                atomicWrite(file, json.encodeToString(PendingWriteFile.serializer(), PendingWriteFile(updated)))
                bump()
            }
        }
    }

    private fun loadRecords(accountPubkey: String): List<PendingWriteFileRecord> =
        readPendingRecords(fileFor(accountPubkey))

    private fun readPendingRecords(file: Path): List<PendingWriteFileRecord> =
        readFile(file)
            ?.let { runCatching { json.decodeFromString(PendingWriteFile.serializer(), it).writes }.getOrNull() }
            .orEmpty()

    private fun writeRecords(accountPubkey: String, records: List<PendingWriteFileRecord>) {
        atomicWrite(fileFor(accountPubkey), json.encodeToString(PendingWriteFile.serializer(), PendingWriteFile(records)))
        bump()
    }

    private fun fileFor(accountPubkey: String): Path = baseDir.resolve("${accountPubkey.safeFileName()}.pending.json")

    private fun readFile(file: Path): String? =
        runCatching { if (file.exists()) file.readText() else null }.getOrNull()

    private fun bump() {
        _changes.value = _changes.value + 1
    }
}

private fun atomicWrite(file: Path, content: String) {
    file.parent.createDirectories()
    val tmp = file.resolveSibling("${file.name}.tmp")
    tmp.writeText(content)
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

private fun String.safeFileName(): String = filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(96)

@Serializable
private data class CachedEventFile(val events: List<CachedEventFileRecord>)

@Serializable
private data class CachedEventFileRecord(val event: NostrEventDto, val cachedAtMs: Long)

@Serializable
private data class PendingWriteFile(val writes: List<PendingWriteFileRecord>)

@Serializable
private data class PendingWriteFileRecord(
    val accountPubkey: String,
    val event: NostrEventDto,
    val targetRelays: List<String>,
    val relayStatuses: Map<String, String>,
    val retryCounts: Map<String, Int>,
    val lastSafeErrorByRelay: Map<String, String>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    fun toPendingWrite(): PendingRelayWrite = PendingRelayWrite(
        accountPubkey = accountPubkey,
        event = event.toEvent(),
        targetRelays = targetRelays,
        relayStatuses = relayStatuses.mapValues { (_, value) ->
            runCatching { PendingWriteStatus.valueOf(value) }.getOrDefault(PendingWriteStatus.Pending)
        },
        retryCounts = retryCounts,
        lastSafeErrorByRelay = lastSafeErrorByRelay,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )
}

@Serializable
private data class NostrEventDto(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun toEvent(): NostrEvent = NostrEvent(id, pubkey, createdAt, kind, tags, content, sig)
}

private fun NostrEvent.toDto(): NostrEventDto = NostrEventDto(id, pubkey, createdAt, kind, tags, content, sig)
