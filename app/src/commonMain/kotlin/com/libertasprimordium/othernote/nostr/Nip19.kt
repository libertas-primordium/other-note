package com.libertasprimordium.othernote.nostr

data class Nip19Data(
    val hrp: String,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Nip19Data && hrp == other.hrp && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * hrp.hashCode() + data.contentHashCode()
}

object Nip19 {
    private const val Charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun decode(value: String): Nip19Data? {
        val normalized = value.lowercase()
        val separator = normalized.lastIndexOf('1')
        if (separator <= 0 || separator + 7 > normalized.length) return null
        val hrp = normalized.substring(0, separator)
        val data = normalized.substring(separator + 1).map { Charset.indexOf(it) }
        if (data.any { it < 0 }) return null
        if (!verifyChecksum(hrp, data)) return null
        val payload5 = data.dropLast(6)
        val payload8 = convertBits(payload5, 5, 8, false) ?: return null
        return Nip19Data(hrp, payload8.map { it.toByte() }.toByteArray())
    }

    private fun verifyChecksum(hrp: String, values: List<Int>): Boolean =
        polymod(expandHrp(hrp) + values) == 1

    private fun expandHrp(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

    private fun polymod(values: List<Int>): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (value in values) {
            val top = chk shr 25
            chk = (chk and 0x1ffffff) shl 5 xor value
            for (i in generators.indices) {
                if (((top shr i) and 1) == 1) chk = chk xor generators[i]
            }
        }
        return chk
    }

    private fun convertBits(input: List<Int>, fromBits: Int, toBits: Int, pad: Boolean): List<Int>? {
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val output = mutableListOf<Int>()
        for (value in input) {
            if (value < 0 || (value shr fromBits) != 0) return null
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                output += (acc shr bits) and maxv
            }
        }
        if (pad && bits > 0) {
            output += (acc shl (toBits - bits)) and maxv
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }
        return output
    }
}
