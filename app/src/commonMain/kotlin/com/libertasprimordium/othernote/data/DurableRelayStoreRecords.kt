package com.libertasprimordium.othernote.data

import com.libertasprimordium.othernote.nostr.NostrEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DurableCachedEventFile(val events: List<DurableCachedEventRecord>)

@Serializable
data class DurableCachedEventRecord(
    val event: DurableNostrEventRecord,
    val cachedAtMs: Long,
)

@Serializable
data class DurablePendingWriteFile(val writes: List<DurablePendingWriteRecord>)

@Serializable
data class DurablePendingWriteRecord(
    val accountPubkey: String,
    val event: DurableNostrEventRecord,
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
        targetRelays = targetRelays.distinct(),
        relayStatuses = relayStatuses.mapValues { (_, value) ->
            runCatching { PendingWriteStatus.valueOf(value) }.getOrDefault(PendingWriteStatus.Pending)
        },
        retryCounts = retryCounts,
        lastSafeErrorByRelay = lastSafeErrorByRelay.mapValues { (_, value) -> value.take(MaxSafeRelayErrorLength) },
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )
}

@Serializable
data class DurableNostrEventRecord(
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

object DurableRelayStoreCodec {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    fun encodeCachedEvents(records: List<DurableCachedEventRecord>): String =
        json.encodeToString(DurableCachedEventFile.serializer(), DurableCachedEventFile(records))

    fun decodeCachedEventsOrEmpty(raw: String): List<DurableCachedEventRecord> =
        runCatching { json.decodeFromString(DurableCachedEventFile.serializer(), raw).events }.getOrDefault(emptyList())

    fun encodePendingWrites(records: List<DurablePendingWriteRecord>): String =
        json.encodeToString(DurablePendingWriteFile.serializer(), DurablePendingWriteFile(records))

    fun decodePendingWritesOrEmpty(raw: String): List<DurablePendingWriteRecord> =
        runCatching { json.decodeFromString(DurablePendingWriteFile.serializer(), raw).writes }.getOrDefault(emptyList())

    fun safeFileName(accountPubkey: String): String =
        accountPubkey.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(96)

    fun safeRelayError(reason: String): String = reason.take(MaxSafeRelayErrorLength)
}

fun NostrEvent.toDurableRecord(): DurableNostrEventRecord =
    DurableNostrEventRecord(id, pubkey, createdAt, kind, tags, content, sig)

fun PendingRelayWrite.toDurableRecord(): DurablePendingWriteRecord = DurablePendingWriteRecord(
    accountPubkey = accountPubkey,
    event = event.toDurableRecord(),
    targetRelays = targetRelays.distinct(),
    relayStatuses = relayStatuses.mapValues { (_, status) -> status.name },
    retryCounts = retryCounts,
    lastSafeErrorByRelay = lastSafeErrorByRelay.mapValues { (_, value) -> DurableRelayStoreCodec.safeRelayError(value) },
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
)

private const val MaxSafeRelayErrorLength = 180
