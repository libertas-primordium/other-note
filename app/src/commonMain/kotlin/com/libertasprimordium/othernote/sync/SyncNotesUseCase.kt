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
        val candidateEvents = fetch.events.filter { event ->
            event.pubkey == session.publicKeyHex && crypto.validate(event).getOrDefault(false)
        }
        val rejectedBeforeDecrypt = fetch.events.size - candidateEvents.size
        val reduced = reduceNoteEvents(candidateEvents) { event ->
            crypto.decryptFromSelf(event.content, privateKey, publicKey)
        }
        val selectedNoteIds = reduced.selectedNoteIds
        val preservedNotes = notes.notes.value.filter { it.id !in selectedNoteIds }
        notes.replaceFromSync(reduced.notes + preservedNotes)
        notes.pendingEvents.value.forEach { pending ->
            val publish = nostr.publish(relays, pending)
            if (publish.allSucceeded) notes.markPublished(pending.id)
        }
        val warnings = buildList {
            val rejectedCount = rejectedBeforeDecrypt + reduced.rejectedCount
            if (rejectedCount > 0) add("Rejected $rejectedCount invalid, malformed, or undecryptable events")
        }
        return SyncState(lastSyncMs = nowMs(), relayStatuses = fetch.statuses, warnings = warnings)
    }
}
