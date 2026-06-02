package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.data.DurableCachedEventRecord
import com.libertasprimordium.othernote.data.DurablePendingWriteRecord
import com.libertasprimordium.othernote.data.DurableRelayStoreCodec
import com.libertasprimordium.othernote.data.LocalEventCache
import com.libertasprimordium.othernote.data.PendingRelayWrite
import com.libertasprimordium.othernote.data.PendingWriteStatus
import com.libertasprimordium.othernote.data.PendingWriteStore
import com.libertasprimordium.othernote.data.RelaySettingsCodec
import com.libertasprimordium.othernote.data.RelaySettingsPersistence
import com.libertasprimordium.othernote.data.ThemePreferenceStore
import com.libertasprimordium.othernote.data.toDurableRecord
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.util.nowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

object DesktopLocalStorePaths {
    fun dataDir(): Path {
        val home = System.getProperty("user.home")
        return Path.of(home, ".local", "share", "other-note")
    }
}

class DesktopLocalEventCache(
    private val baseDir: Path = DesktopLocalStorePaths.dataDir().resolve("event-cache"),
) : LocalEventCache {
    override suspend fun upsertEvents(accountPubkey: String, events: List<NostrEvent>) {
        if (events.isEmpty()) return
        val current = loadRecords(accountPubkey).associateBy { it.event.id }.toMutableMap()
        val now = nowMs()
        events.forEach { event ->
            current[event.id] = DurableCachedEventRecord(event = event.toDurableRecord(), cachedAtMs = now)
        }
        writeRecords(accountPubkey, current.values.sortedBy { it.event.id })
    }

    override suspend fun loadEvents(accountPubkey: String): List<NostrEvent> =
        loadRecords(accountPubkey).map { it.event.toEvent() }

    override suspend fun clearAccount(accountPubkey: String) {
        runCatching { Files.deleteIfExists(fileFor(accountPubkey)) }
    }

    private fun loadRecords(accountPubkey: String): List<DurableCachedEventRecord> =
        readFile(fileFor(accountPubkey))
            ?.let { DurableRelayStoreCodec.decodeCachedEventsOrEmpty(it) }
            .orEmpty()

    private fun writeRecords(accountPubkey: String, records: List<DurableCachedEventRecord>) {
        atomicWrite(fileFor(accountPubkey), DurableRelayStoreCodec.encodeCachedEvents(records))
    }

    private fun fileFor(accountPubkey: String): Path =
        baseDir.resolve("${DurableRelayStoreCodec.safeFileName(accountPubkey)}.events.json")

    private fun readFile(file: Path): String? =
        runCatching { if (file.exists()) file.readText() else null }.getOrNull()
}

class DesktopPendingWriteStore(
    private val baseDir: Path = DesktopLocalStorePaths.dataDir().resolve("pending-writes"),
) : PendingWriteStore {
    private val lock = Mutex()
    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes

    override suspend fun enqueuePendingWrite(accountPubkey: String, event: NostrEvent, targetRelays: List<String>) = lock.withLock {
        val current = loadRecordsUnlocked(accountPubkey).associateBy { it.event.id }.toMutableMap()
        val now = nowMs()
        val existing = current[event.id]
        val distinctRelays = targetRelays.distinct()
        current[event.id] = DurablePendingWriteRecord(
            accountPubkey = accountPubkey,
            event = event.toDurableRecord(),
            targetRelays = distinctRelays,
            relayStatuses = existing?.relayStatuses.orEmpty() + distinctRelays.associateWith {
                existing?.relayStatuses?.get(it) ?: PendingWriteStatus.Pending.name
            },
            retryCounts = existing?.retryCounts.orEmpty(),
            lastSafeErrorByRelay = existing?.lastSafeErrorByRelay.orEmpty(),
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
        )
        writeRecordsUnlocked(accountPubkey, current.values.sortedBy { it.event.id })
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
                lastSafeErrorByRelay = write.lastSafeErrorByRelay + (relayUrl to DurableRelayStoreCodec.safeRelayError(safeReason)),
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

    override suspend fun loadPendingWrites(accountPubkey: String): List<PendingRelayWrite> = lock.withLock {
        loadRecordsUnlocked(accountPubkey)
            .filter { it.accountPubkey == accountPubkey }
            .map { it.toPendingWrite() }
    }

    override suspend fun removeCompletedWrite(eventId: String) = lock.withLock {
        pendingFiles().forEach { file ->
            val records = readPendingRecordsUnlocked(file)
            if (records.any { it.event.id == eventId }) {
                val remaining = records.filterNot { it.event.id == eventId }
                atomicWrite(file, DurableRelayStoreCodec.encodePendingWrites(remaining))
                bump()
            }
        }
    }

    private suspend fun update(eventId: String, transform: (DurablePendingWriteRecord) -> DurablePendingWriteRecord) = lock.withLock {
        pendingFiles().forEach { file ->
            val records = readPendingRecordsUnlocked(file)
            if (records.any { it.event.id == eventId }) {
                val updated = records.map { if (it.event.id == eventId) transform(it) else it }
                atomicWrite(file, DurableRelayStoreCodec.encodePendingWrites(updated))
                bump()
            }
        }
    }

    private fun loadRecordsUnlocked(accountPubkey: String): List<DurablePendingWriteRecord> =
        readPendingRecordsUnlocked(fileFor(accountPubkey))

    private fun readPendingRecordsUnlocked(file: Path): List<DurablePendingWriteRecord> =
        readFile(file)
            ?.let { DurableRelayStoreCodec.decodePendingWritesOrEmpty(it) }
            .orEmpty()

    private fun writeRecordsUnlocked(accountPubkey: String, records: List<DurablePendingWriteRecord>) {
        atomicWrite(fileFor(accountPubkey), DurableRelayStoreCodec.encodePendingWrites(records))
        bump()
    }

    private fun pendingFiles(): List<Path> {
        baseDir.createDirectories()
        return runCatching {
            Files.list(baseDir).use { files -> files.filter { it.name.endsWith(".pending.json") }.toList() }
        }.getOrDefault(emptyList())
    }

    private fun fileFor(accountPubkey: String): Path =
        baseDir.resolve("${DurableRelayStoreCodec.safeFileName(accountPubkey)}.pending.json")

    private fun readFile(file: Path): String? =
        runCatching { if (file.exists()) file.readText() else null }.getOrNull()

    private fun bump() {
        _changes.value = _changes.value + 1
    }
}

class DesktopRelaySettingsPersistence(
    private val file: Path = DesktopLocalStorePaths.dataDir().resolve("relay-settings.json"),
) : RelaySettingsPersistence {
    override suspend fun loadRelayUrls(): List<String>? =
        readFile(file)?.let { RelaySettingsCodec.decodeOrNull(it) }

    override suspend fun saveRelayUrls(urls: List<String>) {
        atomicWrite(file, RelaySettingsCodec.encode(urls))
    }

    private fun readFile(file: Path): String? =
        runCatching { if (file.exists()) file.readText() else null }.getOrNull()
}

class DesktopThemePreferenceStore(
    private val file: Path = DesktopLocalStorePaths.dataDir().resolve("theme-preference.txt"),
) : ThemePreferenceStore {
    override suspend fun loadThemeId(): String? =
        readFile(file)?.trim()?.takeIf { it.isNotBlank() }

    override suspend fun saveThemeId(themeId: String) {
        atomicWrite(file, themeId.trim())
    }

    private fun readFile(file: Path): String? =
        runCatching { if (file.exists()) file.readText() else null }.getOrNull()
}

private fun atomicWrite(file: Path, content: String) {
    file.parent.createDirectories()
    val tmp = file.resolveSibling("${file.name}.${UUID.randomUUID()}.tmp")
    FileChannel.open(
        tmp,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    ).use { channel ->
        val bytes = content.encodeToByteArray()
        var written = 0
        while (written < bytes.size) {
            written += channel.write(ByteBuffer.wrap(bytes, written, bytes.size - written))
        }
        runCatching { channel.force(true) }
    }
    try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        runCatching { Files.deleteIfExists(tmp) }
    }
}
