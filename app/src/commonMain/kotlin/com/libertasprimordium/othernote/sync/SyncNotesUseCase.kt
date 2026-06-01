package com.libertasprimordium.othernote.sync

import com.libertasprimordium.othernote.data.NoteRepository
import com.libertasprimordium.othernote.domain.SyncState
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.NostrPrivateKey
import com.libertasprimordium.othernote.nostr.NostrPublicKey
import com.libertasprimordium.othernote.nostr.NostrRepository
import com.libertasprimordium.othernote.util.nowMs

class SyncNotesUseCase(
    private val notes: NoteRepository,
    private val nostr: NostrRepository,
    private val crypto: NostrCrypto,
) {
    suspend fun sync(session: UserSession?, relays: List<String>): SyncState {
        if (session == null) return SyncState(errors = listOf("Log in before syncing relays"))
        if (!crypto.productionReady) {
            return SyncState(warnings = listOf("Production Nostr crypto is not wired; relay sync is disabled"))
        }
        val fetch = nostr.fetch(relays, session.publicKeyHex)
        if (fetch.statuses.none { it.readable }) {
            return SyncState(
                relayStatuses = fetch.statuses,
                errors = listOf("No relay completed a successful read; local notes were preserved"),
            )
        }
        val privateKey = NostrPrivateKey(session.privateKeyHex)
        val publicKey = NostrPublicKey(session.publicKeyHex, session.npub)
        val reduced = reduceNoteEvents(fetch.events) { event ->
            crypto.decryptFromSelf(event.content, privateKey, publicKey)
        }
        val remoteIds = reduced.notes.map { it.id }.toSet()
        val localOnlyNotes = notes.notes.value.filter { it.sourceEventId == null && it.id !in remoteIds }
        notes.replaceFromSync(reduced.notes + localOnlyNotes)
        notes.pendingEvents.value.forEach { pending ->
            val publish = nostr.publish(relays, pending)
            if (publish.allSucceeded) notes.markPublished(pending.id)
        }
        val warnings = buildList {
            if (reduced.rejectedCount > 0) add("Rejected ${reduced.rejectedCount} malformed or undecryptable events")
        }
        return SyncState(lastSyncMs = nowMs(), relayStatuses = fetch.statuses, warnings = warnings)
    }
}
