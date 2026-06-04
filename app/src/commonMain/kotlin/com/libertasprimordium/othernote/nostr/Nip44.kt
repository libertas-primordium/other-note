package com.libertasprimordium.othernote.nostr

object Nip44 {
    const val RequiredVersion = 2
    const val IntegrationStatus =
        "NIP-44 v2 encryption is required before publishing real notes. The current adapter refuses to emit plaintext."
}
