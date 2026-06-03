package com.libertasprimordium.othernote.web

private const val WebRelayMigrationNoSignerWarning =
    "Relay-list publish unavailable; encrypted note migration can still continue."
private const val WebRelayMigrationRelayListPublishFailed =
    "Relay-list publish failed on one or more relays."
private const val WebRelayMigrationSourceFetchFailed =
    "Some source relays could not be fetched before migration."
private const val WebRelayMigrationNoSourceEvents =
    "No encrypted note events were found on current relays."
private const val WebRelayMigrationNoSelectedEvents =
    "Fetched relay events were rejected before migration."
private const val WebRelayMigrationTargetPublishFailed =
    "Some target relays did not accept migrated encrypted events."
private const val WebRelayMigrationAllTargetWritesFailed =
    "No target relay accepted migrated encrypted events."

internal data class WebRelayMigrationPlan(
    val oldRelays: List<String>,
    val newRelays: List<String>,
    val addedRelays: List<String>,
    val removedRelays: List<String>,
) {
    val retainedRelays: List<String> get() = newRelays.filter { it in oldRelays }
    val migrationRequired: Boolean get() = addedRelays.isNotEmpty() || removedRelays.isNotEmpty()
}

internal data class WebRelayListPublishResult(
    val event: WebNostrEvent? = null,
    val statuses: List<WebNoteRelayStatus> = emptyList(),
    val warning: String? = null,
) {
    val fullSuccess: Boolean get() =
        warning == null && statuses.isNotEmpty() && statuses.all { it.acceptedWrite }
}

internal data class WebRelayMigrationResult(
    val plan: WebRelayMigrationPlan,
    val fetchStatuses: List<WebNoteRelayStatus>,
    val fetchedEventCount: Int,
    val latestEvents: List<WebNostrEvent>,
    val publishStatusesByEventId: Map<String, List<WebNoteRelayStatus>>,
    val relayListPublish: WebRelayListPublishResult?,
    val warnings: List<String>,
) {
    val fullSuccess: Boolean get() = warnings.isEmpty()
    val onlyNoSourceEventsWarning: Boolean get() =
        warnings == listOf(WebRelayMigrationNoSourceEvents)
    val allTargetWritesFailed: Boolean
        get() {
            if (latestEvents.isEmpty()) return false
            val statuses = publishStatusesByEventId.values.flatten()
            return statuses.isNotEmpty() && statuses.none { it.acceptedWrite }
        }
}

internal data class WebRelayMigrationWarning(
    val title: String,
    val body: String,
    val details: String,
)

internal data class WebRelayMigrationUiState(
    val inProgress: Boolean = false,
    val message: String = "",
    val warning: WebRelayMigrationWarning? = null,
    val pendingSettings: WebNoteRelaySettingsState? = null,
)

internal data class WebRelayMigrationRequest(
    val generation: Int,
    val accountPubkey: String,
    val method: WebAuthMethod,
)

internal data class WebRelayMigrationStart(
    val guard: WebRelayMigrationGuard,
    val request: WebRelayMigrationRequest,
)

internal data class WebRelayMigrationGuard(
    val generation: Int = 0,
) {
    fun start(identity: WebAccountIdentity): WebRelayMigrationStart {
        val next = copy(generation = generation + 1)
        return WebRelayMigrationStart(
            guard = next,
            request = WebRelayMigrationRequest(
                generation = next.generation,
                accountPubkey = identity.publicKeyHex,
                method = identity.method,
            ),
        )
    }

    fun invalidate(): WebRelayMigrationGuard =
        copy(generation = generation + 1)

    fun accepts(request: WebRelayMigrationRequest, authState: WebAuthUiState): Boolean {
        val signedIn = authState.signInState as? WebSignInState.SignedIn ?: return false
        return request.generation == generation &&
            request.accountPubkey == signedIn.identity.publicKeyHex &&
            request.method == signedIn.identity.method
    }
}

internal fun planWebRelayMigration(oldRelays: List<String>, newRelays: List<String>): WebRelayMigrationPlan {
    val oldDistinct = oldRelays.mapNotNull { normalizeWebNoteRelayUrl(it).getOrNull() }.distinct()
    val newDistinct = newRelays.mapNotNull { normalizeWebNoteRelayUrl(it).getOrNull() }.distinct()
    return WebRelayMigrationPlan(
        oldRelays = oldDistinct,
        newRelays = newDistinct,
        addedRelays = newDistinct.filterNot { it in oldDistinct },
        removedRelays = oldDistinct.filterNot { it in newDistinct },
    )
}

internal fun selectLatestSignedEncryptedWebNoteEvents(
    events: List<WebNostrEvent>,
    accountPubkey: String,
    validateEvent: (WebNostrEvent) -> Boolean = ::validateWebMigrationNoteEventAuthenticity,
): List<WebNostrEvent> =
    events
        .distinctBy { it.id }
        .filter { event ->
            event.pubkey == accountPubkey &&
                event.isReadableOtherNoteEvent() &&
                event.dTag() != null &&
                validateEvent(event)
        }
        .groupBy { event -> "${event.pubkey}|${event.kind}|${event.dTag().orEmpty()}" }
        .values
        .map { versions ->
            versions.sortedWith(compareByDescending<WebNostrEvent> { it.createdAt }.thenBy { it.id }).first()
        }
        .sortedWith(compareByDescending<WebNostrEvent> { it.createdAt }.thenBy { it.id })

internal fun webRelayMigrationTargetRelays(plan: WebRelayMigrationPlan): List<String> =
    (plan.addedRelays + plan.retainedRelays).distinct()

internal fun webRelayMigrationWarning(result: WebRelayMigrationResult): WebRelayMigrationWarning {
    val title = when {
        result.allTargetWritesFailed -> "No target relay accepted migrated notes"
        result.warnings.any { it == WebRelayMigrationNoSourceEvents } -> "No encrypted notes found to migrate"
        result.warnings.any { it == WebRelayMigrationNoSelectedEvents } -> "No valid encrypted notes selected"
        result.warnings.any { it == WebRelayMigrationNoSignerWarning } -> "Relay-list publish unavailable"
        result.warnings.any { it == WebRelayMigrationRelayListPublishFailed } -> "Relay-list publish partially failed"
        else -> "Relay migration needs review"
    }
    val body = when {
        result.allTargetWritesFailed ->
            "Other Note could not copy encrypted note events to any requested relay. You can keep the old relays or continue with the requested relays."
        result.warnings.any { it == WebRelayMigrationNoSourceEvents } ->
            "No encrypted notes were found on the current relays. You can continue if this is a fresh relay set."
        result.warnings.any { it == WebRelayMigrationNoSelectedEvents } ->
            "Relay events were fetched, but none matched the signed encrypted note format for this account."
        result.warnings.any { it == WebRelayMigrationNoSignerWarning } ->
            "The signer could not update public relay-list metadata, but encrypted note migration can still continue."
        else ->
            "Some relay migration steps did not fully complete. You can keep the old relays or continue with the requested relays."
    }
    return WebRelayMigrationWarning(title = title, body = body, details = result.safeDetails())
}

internal fun WebRelayMigrationResult.safeDetails(): String =
    buildList {
        add("old_relays=${plan.oldRelays.size} new_relays=${plan.newRelays.size} added=${plan.addedRelays.size} removed=${plan.removedRelays.size}")
        add("fetched_events=$fetchedEventCount selected_events=${latestEvents.size}")
        relayListPublish?.let { publish ->
            add("relay_list_publish=${if (publish.fullSuccess) "success" else "warning"} accepted=${publish.statuses.count { it.acceptedWrite }}/${publish.statuses.size}")
        }
        if (fetchStatuses.isNotEmpty()) {
            add("source_fetch=${fetchStatuses.count { it.connected && !it.failed && !it.timedOut }}/${fetchStatuses.size} relays")
        }
        val publishStatuses = publishStatusesByEventId.values.flatten()
        if (publishStatuses.isNotEmpty()) {
            add("note_publish=${publishStatuses.count { it.acceptedWrite }}/${publishStatuses.size} writes")
        }
        warnings.forEach { add(it) }
    }.filter { it.isNotBlank() }.joinToString("\n").take(1_500)

internal class WebRelayMigrationService {
    private var activeGeneration = 0
    private var completed = true
    private var activeFetcher: WebNoteRelayFetcher? = null
    private var activePublisher: WebNoteRelayPublisher? = null
    private var activeRelayListPublisher: WebNoteRelayPublisher? = null

    fun migrate(
        accountPubkey: String,
        oldRelays: List<String>,
        newRelays: List<String>,
        existingRelayList: WebPublishedRelayList?,
        relayListSigner: WebNoteCrudSigner?,
        onProgress: (String) -> Unit,
        onResult: (WebRelayMigrationResult) -> Unit,
    ) {
        close()
        completed = false
        val generation = ++activeGeneration
        val plan = planWebRelayMigration(oldRelays, newRelays)
        if (!plan.migrationRequired) {
            complete(generation, emptyResult(plan, relayListPublish = null), onResult)
            return
        }
        onProgress("Publishing updated relay list metadata.")
        publishRelayList(generation, accountPubkey, plan, existingRelayList, relayListSigner) { relayListPublish ->
            if (!isCurrent(generation)) return@publishRelayList
            onProgress("Fetching encrypted note events from current relays.")
            fetchSourceEvents(generation, accountPubkey, plan) { fetch ->
                if (!isCurrent(generation)) return@fetchSourceEvents
                val latest = selectLatestSignedEncryptedWebNoteEvents(fetch.events, accountPubkey)
                val targetRelays = webRelayMigrationTargetRelays(plan)
                if (targetRelays.isEmpty() || latest.isEmpty()) {
                    complete(
                        generation,
                        resultFor(plan, fetch, latest, emptyMap(), relayListPublish),
                        onResult,
                    )
                    return@fetchSourceEvents
                }
                onProgress("Republishing signed encrypted note events to requested relays.")
                publishLatestEvents(generation, targetRelays, latest) { publishStatuses ->
                    complete(
                        generation,
                        resultFor(plan, fetch, latest, publishStatuses, relayListPublish),
                        onResult,
                    )
                }
            }
        }
    }

    fun syncCurrentRelays(
        accountPubkey: String,
        relays: List<String>,
        onProgress: (String) -> Unit,
        onResult: (WebRelayMigrationResult) -> Unit,
    ) {
        close()
        completed = false
        val generation = ++activeGeneration
        val currentRelays = relays.mapNotNull { normalizeWebNoteRelayUrl(it).getOrNull() }.distinct()
        val plan = WebRelayMigrationPlan(
            oldRelays = currentRelays,
            newRelays = currentRelays,
            addedRelays = emptyList(),
            removedRelays = emptyList(),
        )
        if (currentRelays.isEmpty()) {
            complete(generation, emptyResult(plan, relayListPublish = null), onResult)
            return
        }
        onProgress("Fetching encrypted note events from current relays.")
        fetchSourceEvents(generation, accountPubkey, plan) { fetch ->
            if (!isCurrent(generation)) return@fetchSourceEvents
            val latest = selectLatestSignedEncryptedWebNoteEvents(fetch.events, accountPubkey)
            if (latest.isEmpty()) {
                complete(
                    generation,
                    resultFor(plan, fetch, latest, emptyMap(), relayListPublish = null),
                    onResult,
                )
                return@fetchSourceEvents
            }
            onProgress("Republishing signed encrypted note events to current relays.")
            publishLatestEvents(generation, currentRelays, latest) { publishStatuses ->
                complete(
                    generation,
                    resultFor(plan, fetch, latest, publishStatuses, relayListPublish = null),
                    onResult,
                )
            }
        }
    }

    fun close() {
        completed = true
        activeGeneration += 1
        activeFetcher?.close()
        activePublisher?.close()
        activeRelayListPublisher?.close()
        activeFetcher = null
        activePublisher = null
        activeRelayListPublisher = null
    }

    private fun publishRelayList(
        generation: Int,
        accountPubkey: String,
        plan: WebRelayMigrationPlan,
        existingRelayList: WebPublishedRelayList?,
        relayListSigner: WebNoteCrudSigner?,
        onResult: (WebRelayListPublishResult) -> Unit,
    ) {
        val signer = relayListSigner
        if (signer == null) {
            onResult(WebRelayListPublishResult(warning = WebRelayMigrationNoSignerWarning))
            return
        }
        val unsigned = buildUnsignedWebRelayListEvent(
            accountPubkey = accountPubkey,
            appWriteRelays = plan.newRelays,
            existing = existingRelayList,
            createdAt = webRelayMigrationNowSeconds(),
        )
        signer.sign(unsigned) { signed ->
            if (!isCurrent(generation)) return@sign
            when (signed) {
                is WebNoteSignResult.Failed -> onResult(WebRelayListPublishResult(warning = WebRelayMigrationRelayListPublishFailed))
                is WebNoteSignResult.Signed -> {
                    if (!validateWebSignedRelayListEvent(unsigned, signed.event, accountPubkey)) {
                        onResult(WebRelayListPublishResult(warning = WebRelayMigrationRelayListPublishFailed))
                        return@sign
                    }
                    val targets = (plan.oldRelays + plan.newRelays).distinct()
                    if (targets.isEmpty()) {
                        onResult(WebRelayListPublishResult(signed.event, emptyList(), WebRelayMigrationRelayListPublishFailed))
                        return@sign
                    }
                    val publisher = WebNoteRelayPublisher(targets).also { activeRelayListPublisher = it }
                    publisher.publish(signed.event) { publish ->
                        val warning = if (publish.statuses.isNotEmpty() && publish.statuses.all { it.acceptedWrite }) {
                            null
                        } else {
                            WebRelayMigrationRelayListPublishFailed
                        }
                        onResult(WebRelayListPublishResult(signed.event, publish.statuses, warning))
                    }
                }
            }
        }
    }

    private fun fetchSourceEvents(
        generation: Int,
        accountPubkey: String,
        plan: WebRelayMigrationPlan,
        onResult: (WebNoteRelayFetchResult) -> Unit,
    ) {
        val fetcher = WebNoteRelayFetcher(plan.oldRelays).also { activeFetcher = it }
        fetcher.fetch(accountPubkey) { result ->
            if (isCurrent(generation)) onResult(result)
        }
    }

    private fun publishLatestEvents(
        generation: Int,
        targetRelays: List<String>,
        events: List<WebNostrEvent>,
        onResult: (Map<String, List<WebNoteRelayStatus>>) -> Unit,
    ) {
        val statusesByEventId = linkedMapOf<String, List<WebNoteRelayStatus>>()
        fun publishAt(index: Int) {
            if (!isCurrent(generation)) return
            if (index >= events.size) {
                onResult(statusesByEventId)
                return
            }
            val event = events[index]
            val publisher = WebNoteRelayPublisher(targetRelays).also { activePublisher = it }
            publisher.publish(event) { result ->
                statusesByEventId[event.id] = result.statuses
                publishAt(index + 1)
            }
        }
        publishAt(0)
    }

    private fun complete(
        generation: Int,
        result: WebRelayMigrationResult,
        onResult: (WebRelayMigrationResult) -> Unit,
    ) {
        if (!isCurrent(generation)) return
        completed = true
        activeGeneration += 1
        activeFetcher?.close()
        activePublisher?.close()
        activeRelayListPublisher?.close()
        activeFetcher = null
        activePublisher = null
        activeRelayListPublisher = null
        onResult(result)
    }

    private fun isCurrent(generation: Int): Boolean =
        !completed && generation == activeGeneration
}

private fun resultFor(
    plan: WebRelayMigrationPlan,
    fetch: WebNoteRelayFetchResult,
    latest: List<WebNostrEvent>,
    publishStatusesByEventId: Map<String, List<WebNoteRelayStatus>>,
    relayListPublish: WebRelayListPublishResult?,
): WebRelayMigrationResult {
    val warnings = buildList {
        relayListPublish?.warning?.let(::add)
        if (fetch.statuses.any { it.failed || it.timedOut }) add(WebRelayMigrationSourceFetchFailed)
        if (fetch.events.isEmpty()) add(WebRelayMigrationNoSourceEvents)
        if (fetch.events.isNotEmpty() && latest.isEmpty()) add(WebRelayMigrationNoSelectedEvents)
        val publishStatuses = publishStatusesByEventId.values.flatten()
        if (publishStatuses.isNotEmpty()) {
            if (publishStatuses.none { it.acceptedWrite }) {
                add(WebRelayMigrationAllTargetWritesFailed)
            } else if (publishStatuses.any { !it.acceptedWrite }) {
                add(WebRelayMigrationTargetPublishFailed)
            }
        }
    }.distinct()
    return WebRelayMigrationResult(
        plan = plan,
        fetchStatuses = fetch.statuses,
        fetchedEventCount = fetch.events.distinctBy { it.id }.size,
        latestEvents = latest,
        publishStatusesByEventId = publishStatusesByEventId,
        relayListPublish = relayListPublish,
        warnings = warnings,
    )
}

private fun emptyResult(
    plan: WebRelayMigrationPlan,
    relayListPublish: WebRelayListPublishResult?,
): WebRelayMigrationResult =
    WebRelayMigrationResult(
        plan = plan,
        fetchStatuses = emptyList(),
        fetchedEventCount = 0,
        latestEvents = emptyList(),
        publishStatusesByEventId = emptyMap(),
        relayListPublish = relayListPublish,
        warnings = emptyList(),
    )

internal fun validateWebMigrationNoteEventAuthenticity(event: WebNostrEvent): Boolean =
    validateWebMigrationNoteEventShape(event) && runCatching {
        val dynamicEvent = event.toDynamicMigrationNostrEvent()
        NostrTools.validateEvent(dynamicEvent) && NostrTools.verifyEvent(dynamicEvent)
    }.getOrDefault(false)

internal fun validateWebMigrationNoteEventShape(event: WebNostrEvent): Boolean =
    event.pubkey.length == 64 &&
        event.id.length == 64 &&
        event.sig.length == 128 &&
        event.pubkey.all { it.isWebMigrationHexDigit() } &&
        event.id.all { it.isWebMigrationHexDigit() } &&
        event.sig.all { it.isWebMigrationHexDigit() }

private fun WebNostrEvent.toDynamicMigrationNostrEvent(): dynamic {
    val event = js("({})")
    event.id = id
    event.pubkey = pubkey
    event.created_at = createdAt.toDouble()
    event.kind = kind
    event.tags = tags.map { it.toTypedArray() }.toTypedArray()
    event.content = content
    event.sig = sig
    return event
}

private fun webRelayMigrationNowSeconds(): Long =
    ((js("Date.now()") as Double).toLong() / 1_000)

private fun Char.isWebMigrationHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
