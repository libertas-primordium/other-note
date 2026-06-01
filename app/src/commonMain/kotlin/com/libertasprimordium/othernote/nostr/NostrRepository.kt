package com.libertasprimordium.othernote.nostr

import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.NoteKind
import com.libertasprimordium.othernote.domain.UserSession
import com.libertasprimordium.othernote.domain.noteDTag
import com.libertasprimordium.othernote.domain.toPayload
import com.libertasprimordium.othernote.util.JsonNotePayloadCodec

class NostrRepository(
    private val crypto: NostrCrypto,
    private val client: NostrClient,
) {
    fun buildSignedNoteEvent(note: Note, session: UserSession): Result<NostrEvent> {
        val privateKey = NostrPrivateKey(session.privateKeyHex)
        val publicKey = NostrPublicKey(session.publicKeyHex, session.npub)
        return crypto.encryptToSelf(JsonNotePayloadCodec.encode(note.toPayload()), privateKey, publicKey)
            .mapCatching { encrypted ->
                UnsignedNostrEvent(
                    pubkey = session.publicKeyHex,
                    createdAt = note.updatedAtMs / 1000,
                    kind = NoteKind,
                    tags = noteEventTags(noteDTag(note.id)),
                    content = encrypted,
                )
            }
            .fold(
                onSuccess = { crypto.sign(it, privateKey) },
                onFailure = { Result.failure(it) },
            )
    }

    suspend fun publish(relays: List<String>, event: NostrEvent): RelayPublishResult = client.publish(relays, event)
    suspend fun fetch(relays: List<String>, pubkey: String): RelayFetchResult = client.fetchNotes(relays, pubkey)
}
