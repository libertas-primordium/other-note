package com.libertasprimordium.othernote.data

import com.libertasprimordium.othernote.domain.DefaultRelays
import com.libertasprimordium.othernote.domain.RelayConfig
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RelaySettingsStore(
    initialRelays: List<RelayConfig> = DefaultRelays,
    private val persistence: RelaySettingsPersistence = NoopRelaySettingsPersistence,
) {
    private val _relays = MutableStateFlow(initialRelays)
    val relays: StateFlow<List<RelayConfig>> = _relays

    fun normalizedUrls(): List<String> = _relays.value.map { it.url }

    fun previewChange(rawRelays: List<String>): Result<List<RelayConfig>> = runCatching {
        val normalized = rawRelays
            .map { normalizeRelayUrl(it).getOrThrow() }
            .distinct()
        require(normalized.isNotEmpty()) { "At least one app relay is required for relay sync and publishing" }
        normalized.map { RelayConfig(it) }
    }

    fun commit(relays: List<RelayConfig>) {
        _relays.value = relays
    }

    suspend fun commitAndPersist(relays: List<RelayConfig>) {
        persistence.saveRelayUrls(relays.map { it.url })
        commit(relays)
    }

    suspend fun loadPersisted() {
        val stored = persistence.loadRelayUrls() ?: return
        val configs = previewChange(stored).getOrNull() ?: return
        commit(configs)
    }

    suspend fun restoreDefaultsAndPersist() {
        commitAndPersist(DefaultRelays)
    }
}

interface RelaySettingsPersistence {
    suspend fun loadRelayUrls(): List<String>?
    suspend fun saveRelayUrls(urls: List<String>)
}

object NoopRelaySettingsPersistence : RelaySettingsPersistence {
    override suspend fun loadRelayUrls(): List<String>? = null
    override suspend fun saveRelayUrls(urls: List<String>) = Unit
}

@Serializable
data class DurableRelaySettingsFile(val relays: List<String>)

object RelaySettingsCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encode(urls: List<String>): String =
        json.encodeToString(DurableRelaySettingsFile.serializer(), DurableRelaySettingsFile(urls))

    fun decodeOrNull(raw: String): List<String>? =
        runCatching { json.decodeFromString(DurableRelaySettingsFile.serializer(), raw).relays }.getOrNull()
}
