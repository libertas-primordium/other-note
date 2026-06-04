package com.libertasprimordium.othernote.util

import kotlin.random.Random

expect fun nowMs(): Long

fun stableRandomId(): String {
    val bytes = ByteArray(16)
    Random.Default.nextBytes(bytes)
    bytes[6] = (bytes[6].toInt() and 0x0f or 0x40).toByte()
    bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
    val hex = bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
