package com.libertasprimordium.othernote.util

import com.libertasprimordium.othernote.nostr.Nip19

private val NpubRegex = Regex("""\bnpub1[023456789acdefghjklmnpqrstuvwxyz]{20,}\b""")

fun detectNpubs(text: String): List<String> =
    NpubRegex.findAll(text).map { it.value }.filter { value ->
        val decoded = Nip19.decode(value)
        decoded?.hrp == "npub" && decoded.data.size == 32
    }.distinct().toList()

fun abbreviateNpub(npub: String): String =
    if (npub.length <= 18) npub else "${npub.take(10)}...${npub.takeLast(6)}"
