package com.libertasprimordium.othernote.sync

data class RelayMigrationPlan(
    val oldRelays: List<String>,
    val newRelays: List<String>,
    val addedRelays: List<String>,
    val removedRelays: List<String>,
) {
    val shouldFetchBeforeRemoval: Boolean get() = removedRelays.isNotEmpty()
    val shouldRepublishCurrentEvents: Boolean get() = addedRelays.isNotEmpty()
}

fun planRelayMigration(oldRelays: List<String>, newRelays: List<String>): RelayMigrationPlan {
    val oldDistinct = oldRelays.distinct()
    val newDistinct = newRelays.distinct()
    return RelayMigrationPlan(
        oldRelays = oldDistinct,
        newRelays = newDistinct,
        addedRelays = newDistinct.filterNot { it in oldDistinct },
        removedRelays = oldDistinct.filterNot { it in newDistinct },
    )
}

class MigrateRelaysUseCase {
    suspend fun migrate(oldRelays: List<String>, newRelays: List<String>): RelayMigrationPlan =
        planRelayMigration(oldRelays, newRelays)
}
