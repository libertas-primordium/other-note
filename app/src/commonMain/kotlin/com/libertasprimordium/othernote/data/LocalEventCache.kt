package com.libertasprimordium.othernote.data

import com.libertasprimordium.othernote.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CachedNostrEventRecord(
    val accountPubkey: String,
    val event: NostrEvent,
    val cachedAtMs: Long,
)

interface LocalEventCache {
    suspend fun upsertEvents(accountPubkey: String, events: List<NostrEvent>)
    suspend fun loadEvents(accountPubkey: String): List<NostrEvent>
    suspend fun clearAccount(accountPubkey: String)
}

class InMemoryLocalEventCache : LocalEventCache {
    private val lock = Any()
    private val records = mutableMapOf<String, MutableMap<String, CachedNostrEventRecord>>()

    override suspend fun upsertEvents(accountPubkey: String, events: List<NostrEvent>) {
        synchronized(lock) {
            val accountRecords = records.getOrPut(accountPubkey) { mutableMapOf() }
            events.forEach { event ->
                accountRecords[event.id] = CachedNostrEventRecord(accountPubkey, event, com.libertasprimordium.othernote.util.nowMs())
            }
        }
    }

    override suspend fun loadEvents(accountPubkey: String): List<NostrEvent> =
        synchronized(lock) {
            records[accountPubkey]?.values?.map { it.event }.orEmpty()
        }

    override suspend fun clearAccount(accountPubkey: String) {
        synchronized(lock) {
            records.remove(accountPubkey)
        }
    }
}

class UnavailableLocalEventCache : LocalEventCache {
    override suspend fun upsertEvents(accountPubkey: String, events: List<NostrEvent>) = Unit
    override suspend fun loadEvents(accountPubkey: String): List<NostrEvent> = emptyList()
    override suspend fun clearAccount(accountPubkey: String) = Unit
}

enum class PendingWriteStatus {
    Pending,
    Accepted,
    Failed,
}

const val PendingWriteMaxRetryCount = 3

data class PendingRelayWrite(
    val accountPubkey: String,
    val event: NostrEvent,
    val targetRelays: List<String>,
    val relayStatuses: Map<String, PendingWriteStatus>,
    val retryCounts: Map<String, Int> = emptyMap(),
    val lastSafeErrorByRelay: Map<String, String> = emptyMap(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val acceptedRelays: Set<String> get() = relayStatuses.filterValues { it == PendingWriteStatus.Accepted }.keys
    val unfinishedRelays: List<String> get() = targetRelays.filter { relayStatuses[it] != PendingWriteStatus.Accepted }
    val isComplete: Boolean get() = targetRelays.isNotEmpty() && targetRelays.all { relayStatuses[it] == PendingWriteStatus.Accepted }
    fun isTerminallyExhausted(maxRetryCount: Int): Boolean =
        targetRelays.isNotEmpty() && targetRelays.all { relay ->
            relayStatuses[relay] == PendingWriteStatus.Accepted || (retryCounts[relay] ?: 0) >= maxRetryCount
        }
}

interface PendingWriteStore {
    val changes: StateFlow<Long>
    suspend fun enqueuePendingWrite(accountPubkey: String, event: NostrEvent, targetRelays: List<String>)
    suspend fun markRelayAccepted(eventId: String, relayUrl: String)
    suspend fun markRelayRejectedOrFailed(eventId: String, relayUrl: String, safeReason: String)
    suspend fun recordRetry(eventId: String, relayUrl: String)
    suspend fun loadPendingWrites(accountPubkey: String): List<PendingRelayWrite>
    suspend fun removeCompletedWrite(eventId: String)
}

class InMemoryPendingWriteStore : PendingWriteStore {
    private val lock = Any()
    private val writes = mutableMapOf<String, PendingRelayWrite>()
    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes

    override suspend fun enqueuePendingWrite(accountPubkey: String, event: NostrEvent, targetRelays: List<String>) {
        synchronized(lock) {
            val now = com.libertasprimordium.othernote.util.nowMs()
            val existing = writes[event.id]
            writes[event.id] = PendingRelayWrite(
                accountPubkey = accountPubkey,
                event = event,
                targetRelays = targetRelays.distinct(),
                relayStatuses = existing?.relayStatuses.orEmpty() + targetRelays.distinct().associateWith { existing?.relayStatuses?.get(it) ?: PendingWriteStatus.Pending },
                retryCounts = existing?.retryCounts.orEmpty(),
                lastSafeErrorByRelay = existing?.lastSafeErrorByRelay.orEmpty(),
                createdAtMs = existing?.createdAtMs ?: now,
                updatedAtMs = now,
            )
            bump()
        }
    }

    override suspend fun markRelayAccepted(eventId: String, relayUrl: String) {
        update(eventId) { write ->
            write.copy(
                relayStatuses = write.relayStatuses + (relayUrl to PendingWriteStatus.Accepted),
                updatedAtMs = com.libertasprimordium.othernote.util.nowMs(),
            )
        }
    }

    override suspend fun markRelayRejectedOrFailed(eventId: String, relayUrl: String, safeReason: String) {
        update(eventId) { write ->
            write.copy(
                relayStatuses = write.relayStatuses + (relayUrl to PendingWriteStatus.Failed),
                lastSafeErrorByRelay = write.lastSafeErrorByRelay + (relayUrl to safeReason.take(180)),
                updatedAtMs = com.libertasprimordium.othernote.util.nowMs(),
            )
        }
    }

    override suspend fun recordRetry(eventId: String, relayUrl: String) {
        update(eventId) { write ->
            write.copy(
                retryCounts = write.retryCounts + (relayUrl to ((write.retryCounts[relayUrl] ?: 0) + 1)),
                relayStatuses = write.relayStatuses + (relayUrl to PendingWriteStatus.Pending),
                updatedAtMs = com.libertasprimordium.othernote.util.nowMs(),
            )
        }
    }

    override suspend fun loadPendingWrites(accountPubkey: String): List<PendingRelayWrite> =
        synchronized(lock) {
            writes.values.filter { it.accountPubkey == accountPubkey }
        }

    override suspend fun removeCompletedWrite(eventId: String) {
        synchronized(lock) {
            writes.remove(eventId)
            bump()
        }
    }

    private fun update(eventId: String, transform: (PendingRelayWrite) -> PendingRelayWrite) {
        synchronized(lock) {
            writes[eventId]?.let { writes[eventId] = transform(it) }
            bump()
        }
    }

    private fun bump() {
        _changes.value = _changes.value + 1
    }
}

class UnavailablePendingWriteStore : PendingWriteStore {
    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes
    override suspend fun enqueuePendingWrite(accountPubkey: String, event: NostrEvent, targetRelays: List<String>) = Unit
    override suspend fun markRelayAccepted(eventId: String, relayUrl: String) = Unit
    override suspend fun markRelayRejectedOrFailed(eventId: String, relayUrl: String, safeReason: String) = Unit
    override suspend fun recordRetry(eventId: String, relayUrl: String) = Unit
    override suspend fun loadPendingWrites(accountPubkey: String): List<PendingRelayWrite> = emptyList()
    override suspend fun removeCompletedWrite(eventId: String) = Unit
}
