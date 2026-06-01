package com.libertasprimordium.othernote.domain

data class SyncState(
    val syncing: Boolean = false,
    val lastSyncMs: Long? = null,
    val relayStatuses: List<RelayStatus> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val summary: String
        get() = when {
            syncing -> "Syncing"
            errors.isNotEmpty() -> errors.first()
            warnings.isNotEmpty() -> warnings.first()
            lastSyncMs != null -> "Last sync completed"
            else -> "Not synced"
        }
}
