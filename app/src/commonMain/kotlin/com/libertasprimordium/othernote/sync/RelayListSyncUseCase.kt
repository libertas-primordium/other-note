package com.libertasprimordium.othernote.sync

import com.libertasprimordium.othernote.domain.RelayConfig
import com.libertasprimordium.othernote.domain.RelayStatus
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrEvent
import com.libertasprimordium.othernote.nostr.NostrFilter
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.nostr.RelayFetchResult
import com.libertasprimordium.othernote.nostr.UnsignedNostrEvent
import com.libertasprimordium.othernote.security.SignEventRequestResult
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import com.libertasprimordium.othernote.util.nowMs

const val RelayListKind = 10002

data class RelayListEntry(
    val url: String,
    val marker: String?,
    val extraFields: List<String> = emptyList(),
) {
    val isWrite: Boolean get() = marker == null || marker == "write"
    val isRead: Boolean get() = marker == null || marker == "read"
    fun toTag(): List<String> = listOfNotNull("r", url, marker) + extraFields
}

data class PublishedRelayList(
    val event: NostrEvent,
    val relayEntries: List<RelayListEntry>,
    val preservedTags: List<List<String>>,
    val malformedRelayTagCount: Int,
) {
    val writeRelayUrls: List<String> get() =
        relayEntries.filter { it.isWrite }.map { it.url }.distinct()
}

data class RelayListImportResult(
    val importedRelays: List<String>,
    val fetchedEventCount: Int,
    val statuses: List<RelayStatus>,
    val warning: String? = null,
) {
    val importedConfigs: List<RelayConfig> get() = importedRelays.map { RelayConfig(it) }
    fun safeSummary(): String =
        if (importedRelays.isNotEmpty()) {
            "Using your published relay list."
        } else {
            warning ?: "No published relay list found."
        }
}

data class RelayListPublishResult(
    val event: NostrEvent?,
    val statuses: List<RelayStatus>,
    val warning: String? = null,
) {
    val fullSuccess: Boolean get() = warning == null && statuses.isNotEmpty() && statuses.all { it.writable }
    fun safeSummary(): String =
        warning ?: "Published updated relay list to ${statuses.count { it.writable }}/${statuses.size} relays"
    fun safeDetails(): String =
        buildList {
            event?.let { add("relay_list_event=${it.id.take(12)} kind=${it.kind}") }
            add(statuses.toRelayListStatusSummary())
            warning?.let { add(it) }
        }.filter { it.isNotBlank() }.joinToString("\n").take(1_500)
}

class RelayListSyncUseCase(
    private val nostr: NostrRepository,
    private val crypto: NostrCrypto,
    private val nowMsProvider: () -> Long = ::nowMs,
) {
    suspend fun importPublishedWriteRelays(
        session: UserSession,
        discoveryRelays: List<String>,
    ): RelayListImportResult {
        val fetch = fetchRelayListEvents(session, discoveryRelays)
        val latest = latestRelayListEvent(fetch.events, session.publicKeyHex, crypto)
        if (latest == null) {
            return RelayListImportResult(
                importedRelays = emptyList(),
                fetchedEventCount = fetch.events.size,
                statuses = fetch.statuses,
                warning = if (fetch.statuses.isNotEmpty() && fetch.statuses.none { it.readable }) {
                    "Published relay list fetch failed; keeping local relay settings."
                } else {
                    null
                },
            )
        }
        val parsed = parseRelayListEvent(latest)
        return RelayListImportResult(
            importedRelays = parsed.writeRelayUrls,
            fetchedEventCount = fetch.events.size,
            statuses = fetch.statuses,
            warning = if (parsed.writeRelayUrls.isEmpty()) "Published relay list had no write relays; keeping local relay settings." else null,
        )
    }

    suspend fun publishUpdatedRelayList(
        session: UserSession,
        discoveryRelays: List<String>,
        targetRelays: List<String>,
        appWriteRelays: List<String>,
        signEvent: suspend (NostrEvent) -> SignEventRequestResult,
    ): RelayListPublishResult {
        val normalizedTargets = targetRelays.mapNotNull { normalizeRelayUrl(it).getOrNull() }.distinct()
        if (normalizedTargets.isEmpty()) {
            return RelayListPublishResult(null, emptyList(), "No relays available for relay-list publish.")
        }
        val latestFetch = fetchRelayListEvents(session, discoveryRelays)
        if (latestFetch.statuses.isNotEmpty() && latestFetch.statuses.none { it.readable }) {
            return RelayListPublishResult(
                null,
                latestFetch.statuses,
                "Could not fetch latest published relay list; not publishing a replacement that might clobber existing relay metadata.",
            )
        }
        val latest = latestRelayListEvent(latestFetch.events, session.publicKeyHex, crypto)
        val unsigned = buildRelayListRequestEvent(
            session = session,
            existing = latest,
            appWriteRelays = appWriteRelays,
        ).getOrElse {
            return RelayListPublishResult(null, emptyList(), "Could not build relay-list event.")
        }
        val signed = signEvent(unsigned)
        val signedEvent = when (signed) {
            is SignEventRequestResult.Success -> signed.signedEvent
            SignEventRequestResult.Cancelled -> return RelayListPublishResult(null, emptyList(), "Signer cancelled relay-list publish.")
            is SignEventRequestResult.Unavailable -> return RelayListPublishResult(null, emptyList(), signed.safeReason)
            is SignEventRequestResult.Failed -> return RelayListPublishResult(null, emptyList(), signed.safeReason)
            is SignEventRequestResult.InvalidResponse -> return RelayListPublishResult(null, emptyList(), signed.safeReason)
        }
        val validation = validateRelayListEventRequest(unsigned, signedEvent, session.publicKeyHex)
        if (validation != null) {
            return RelayListPublishResult(null, emptyList(), validation)
        }
        if (!crypto.validate(signedEvent).getOrDefault(false)) {
            return RelayListPublishResult(null, emptyList(), "Signed relay-list event failed validation.")
        }
        val publish = nostr.publish(normalizedTargets, signedEvent)
        val failed = publish.statuses.filter { !it.writable }
        return RelayListPublishResult(
            event = signedEvent,
            statuses = publish.statuses,
            warning = failed.takeIf { it.isNotEmpty() }
                ?.let { "Could not publish updated relay list to some relays: ${it.toRelayListStatusSummary()}" },
        )
    }

    private suspend fun fetchRelayListEvents(session: UserSession, discoveryRelays: List<String>): RelayFetchResult =
        nostr.fetchEvents(
            relays = discoveryRelays.distinct(),
            filter = NostrFilter(
                authors = listOf(session.publicKeyHex),
                kinds = listOf(RelayListKind),
                tTags = emptyList(),
                limit = 20,
            ),
        )

    private fun buildRelayListRequestEvent(
        session: UserSession,
        existing: NostrEvent?,
        appWriteRelays: List<String>,
    ): Result<NostrEvent> = runCatching {
        val tags = mergeRelayListTags(existing?.let(::parseRelayListEvent), appWriteRelays)
        val unsigned = UnsignedNostrEvent(
            pubkey = session.publicKeyHex,
            createdAt = nowMsProvider() / 1000,
            kind = RelayListKind,
            tags = tags,
            content = "",
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

fun parseRelayListEvent(event: NostrEvent): PublishedRelayList {
    val entries = mutableListOf<RelayListEntry>()
    val preservedTags = mutableListOf<List<String>>()
    var malformed = 0
    event.tags.forEach { tag ->
        if (tag.firstOrNull() != "r") {
            preservedTags += tag
            return@forEach
        }
        val rawUrl = tag.getOrNull(1)
        if (rawUrl.isNullOrBlank()) {
            malformed++
            return@forEach
        }
        val normalized = normalizeRelayUrl(rawUrl).getOrNull()
        if (normalized == null) {
            malformed++
            return@forEach
        }
        val marker = tag.getOrNull(2)?.takeIf { it.isNotBlank() }
        val extra = if (tag.size > 3) tag.drop(3) else emptyList()
        entries += RelayListEntry(normalized, marker, extra)
    }
    return PublishedRelayList(event, entries, preservedTags, malformed)
}

fun latestRelayListEvent(events: List<NostrEvent>, accountPubkey: String, crypto: NostrCrypto): NostrEvent? =
    events
        .filter { event ->
            event.pubkey == accountPubkey &&
                event.kind == RelayListKind &&
                crypto.validate(event).getOrDefault(false)
        }
        .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
        .firstOrNull()

fun mergeRelayListTags(existing: PublishedRelayList?, appWriteRelays: List<String>): List<List<String>> {
    val normalizedWrites = appWriteRelays.mapNotNull { normalizeRelayUrl(it).getOrNull() }.distinct()
    val coveredWrites = mutableSetOf<String>()
    val output = mutableListOf<List<String>>()
    existing?.preservedTags.orEmpty().forEach { output += it }
    existing?.relayEntries.orEmpty().forEach { entry ->
        when {
            entry.marker == "write" -> Unit
            entry.marker == null && entry.url in normalizedWrites -> {
                output += listOf("r", entry.url) + entry.extraFields
                coveredWrites += entry.url
            }
            entry.marker == null -> output += listOf("r", entry.url, "read") + entry.extraFields
            else -> output += entry.toTag()
        }
    }
    normalizedWrites.filterNot { it in coveredWrites }.forEach { relay ->
        output += listOf("r", relay, "write")
    }
    return output.distinct()
}

private fun validateRelayListEventRequest(requested: NostrEvent, signed: NostrEvent, accountPubkey: String): String? =
    when {
        signed.pubkey != accountPubkey -> "Signer returned relay-list event for a different pubkey."
        signed.kind != RelayListKind -> "Signer returned wrong relay-list event kind."
        signed.tags != requested.tags -> "Signer returned unexpected relay-list tags."
        signed.content != "" -> "Signer returned relay-list event with unexpected content."
        signed.createdAt != requested.createdAt -> "Signer returned unexpected relay-list timestamp."
        signed.id.isBlank() || signed.sig.isBlank() -> "Signer returned relay-list event without id or signature."
        else -> null
    }

private fun List<RelayStatus>.toRelayListStatusSummary(): String =
    if (isEmpty()) {
        "none"
    } else {
        joinToString(" | ") { status ->
            "${status.url} read=${status.readable} write=${status.writable} ${status.message.take(160)}"
        }.take(1_000)
    }
