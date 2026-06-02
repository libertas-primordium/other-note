package com.libertasprimordium.othernote

import android.content.Context
import com.libertasprimordium.othernote.data.DurableCachedEventRecord
import com.libertasprimordium.othernote.data.DurablePendingWriteRecord
import com.libertasprimordium.othernote.data.DurableRelayStoreCodec
import com.libertasprimordium.othernote.data.LocalEventCache
import com.libertasprimordium.othernote.data.PendingRelayWrite
import com.libertasprimordium.othernote.data.PendingWriteStatus
import com.libertasprimordium.othernote.data.PendingWriteStore
import com.libertasprimordium.othernote.data.RelaySettingsCodec
import com.libertasprimordium.othernote.data.RelaySettingsPersistence
import com.libertasprimordium.othernote.data.toDurableRecord
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.util.nowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

class AndroidLocalEventCache(
    context: Context,
    private val baseDir: File = File(context.applicationContext.noBackupFilesDir, "event-cache"),
) : LocalEventCache {
    private val lock = Mutex()

    override suspend fun upsertEvents(accountPubkey: String, events: List<NostrEvent>) {
        if (events.isEmpty()) return
        lock.withLock {
            val current = loadRecordsUnlocked(accountPubkey).associateBy { it.event.id }.toMutableMap()
            val now = nowMs()
            events.forEach { event ->
                current[event.id] = DurableCachedEventRecord(event = event.toDurableRecord(), cachedAtMs = now)
            }
            writeRecordsUnlocked(accountPubkey, current.values.sortedBy { it.event.id })
        }
    }

    override suspend fun loadEvents(accountPubkey: String): List<NostrEvent> = lock.withLock {
        loadRecordsUnlocked(accountPubkey).map { it.event.toEvent() }
    }

    override suspend fun clearAccount(accountPubkey: String) {
        lock.withLock {
            runCatching { fileFor(accountPubkey).delete() }
        }
    }

    private fun loadRecordsUnlocked(accountPubkey: String): List<DurableCachedEventRecord> =
        readFile(fileFor(accountPubkey))
            ?.let { DurableRelayStoreCodec.decodeCachedEventsOrEmpty(it) }
            .orEmpty()

    private fun writeRecordsUnlocked(accountPubkey: String, records: List<DurableCachedEventRecord>) {
        atomicWrite(fileFor(accountPubkey), DurableRelayStoreCodec.encodeCachedEvents(records))
    }

    private fun fileFor(accountPubkey: String): File =
        File(baseDir, "${DurableRelayStoreCodec.safeFileName(accountPubkey)}.events.json")
}

class AndroidPendingWriteStore(
    context: Context,
    private val baseDir: File = File(context.applicationContext.noBackupFilesDir, "pending-writes"),
) : PendingWriteStore {
    private val lock = Mutex()
    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes

    override suspend fun enqueuePendingWrite(accountPubkey: String, event: NostrEvent, targetRelays: List<String>) {
        lock.withLock {
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

    override suspend fun removeCompletedWrite(eventId: String) {
        lock.withLock {
            pendingFiles().forEach { file ->
                val records = readPendingRecordsUnlocked(file)
                if (records.any { it.event.id == eventId }) {
                    val remaining = records.filterNot { it.event.id == eventId }
                    atomicWrite(file, DurableRelayStoreCodec.encodePendingWrites(remaining))
                    bump()
                }
            }
        }
    }

    private suspend fun update(eventId: String, transform: (DurablePendingWriteRecord) -> DurablePendingWriteRecord) {
        lock.withLock {
            pendingFiles().forEach { file ->
                val records = readPendingRecordsUnlocked(file)
                if (records.any { it.event.id == eventId }) {
                    val updated = records.map { if (it.event.id == eventId) transform(it) else it }
                    atomicWrite(file, DurableRelayStoreCodec.encodePendingWrites(updated))
                    bump()
                }
            }
        }
    }

    private fun loadRecordsUnlocked(accountPubkey: String): List<DurablePendingWriteRecord> =
        readPendingRecordsUnlocked(fileFor(accountPubkey))

    private fun readPendingRecordsUnlocked(file: File): List<DurablePendingWriteRecord> =
        readFile(file)
            ?.let { DurableRelayStoreCodec.decodePendingWritesOrEmpty(it) }
            .orEmpty()

    private fun writeRecordsUnlocked(accountPubkey: String, records: List<DurablePendingWriteRecord>) {
        atomicWrite(fileFor(accountPubkey), DurableRelayStoreCodec.encodePendingWrites(records))
        bump()
    }

    private fun pendingFiles(): List<File> =
        baseDir.listFiles { file -> file.isFile && file.name.endsWith(".pending.json") }?.toList().orEmpty()

    private fun fileFor(accountPubkey: String): File =
        File(baseDir, "${DurableRelayStoreCodec.safeFileName(accountPubkey)}.pending.json")

    private fun bump() {
        _changes.value = _changes.value + 1
    }
}

class AndroidRelaySettingsPersistence(
    context: Context,
    private val file: File = File(context.applicationContext.noBackupFilesDir, "relay-settings.json"),
) : RelaySettingsPersistence {
    override suspend fun loadRelayUrls(): List<String>? =
        readFile(file)?.let { RelaySettingsCodec.decodeOrNull(it) }

    override suspend fun saveRelayUrls(urls: List<String>) {
        atomicWrite(file, RelaySettingsCodec.encode(urls))
    }
}

private fun readFile(file: File): String? =
    runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()

private fun atomicWrite(file: File, content: String) {
    val parent = file.parentFile ?: error("Durable relay store file has no parent directory")
    parent.mkdirs()
    val tmp = File(parent, "${file.name}.tmp")
    FileOutputStream(tmp).use { output ->
        output.write(content.toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
    if (!tmp.renameTo(file)) {
        if (file.exists()) file.delete()
        check(tmp.renameTo(file)) { "Could not replace durable relay store file" }
    }
}
