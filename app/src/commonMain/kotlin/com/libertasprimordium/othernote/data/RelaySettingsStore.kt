package com.libertasprimordium.othernote.data

import com.libertasprimordium.othernote.domain.DefaultRelays
import com.libertasprimordium.othernote.domain.RelayConfig
import com.libertasprimordium.othernote.util.normalizeRelayUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RelaySettingsStore {
    private val _relays = MutableStateFlow(DefaultRelays)
    val relays: StateFlow<List<RelayConfig>> = _relays

    fun normalizedUrls(): List<String> = _relays.value.map { it.url }

    fun previewChange(rawRelays: List<String>): Result<List<RelayConfig>> = runCatching {
        rawRelays.map { normalizeRelayUrl(it).getOrThrow() }
            .distinct()
            .map { RelayConfig(it) }
    }

    fun commit(relays: List<RelayConfig>) {
        _relays.value = relays
    }
}
