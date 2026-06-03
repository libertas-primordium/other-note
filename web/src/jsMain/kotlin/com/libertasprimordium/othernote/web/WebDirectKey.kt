package com.libertasprimordium.othernote.web

private const val WebDirectKeyByteCount = 32

internal const val DirectNsecInputLabel = "Session-only nsec"
internal const val DirectNsecInputType = "password"
internal const val DirectNsecInputAutocomplete = "off"
internal const val DirectNsecInputPlaceholder = "Paste session-only key"
internal const val DirectNsecSubmitLabel = "Use for this session"

internal data class WebDirectNsecDraftState(
    val input: String = "",
    val message: String = "",
)

internal fun updateWebDirectNsecDraft(state: WebDirectNsecDraftState, input: String): WebDirectNsecDraftState =
    state.copy(input = input, message = "")

internal fun clearWebDirectNsecDraft(message: String = ""): WebDirectNsecDraftState =
    WebDirectNsecDraftState(message = message)

internal object WebDirectKeyCopy {
    const val CryptoUnavailable = "Session-only direct key crypto is unavailable in this web build."
    const val InvalidKey = "That nsec could not be used. Check the key and try again."
    const val PublicKeyFailed = "Could not derive the direct-key account public key."
    const val SessionCleared = "The session-only direct key is no longer active."
    const val EncryptFailed = "Could not encrypt the note with the session-only direct key."
    const val DecryptFailed = "Could not decrypt the note with the session-only direct key."
    const val SignFailed = "Could not sign the encrypted note event with the session-only direct key."
    const val EventValidationFailed = "The session-only direct key produced an invalid note event."
}

internal sealed interface WebDirectKeyLoginResult {
    data class Success(
        val session: WebDirectKeySession,
        val identity: WebAccountIdentity,
    ) : WebDirectKeyLoginResult

    data class Invalid(val safeMessage: String) : WebDirectKeyLoginResult
    data class Unavailable(val safeMessage: String) : WebDirectKeyLoginResult
}

internal interface WebDirectKeyCrypto {
    val productionReady: Boolean
    fun decodeNsec(raw: String): Result<Uint8Array>
    fun derivePublicKey(keyBytes: Uint8Array): Result<String>
    fun encryptToSelf(plaintext: String, keyBytes: Uint8Array, publicKeyHex: String): Result<String>
    fun decryptFromSelf(ciphertext: String, keyBytes: Uint8Array, publicKeyHex: String): Result<String>
    fun sign(unsignedEvent: WebUnsignedNoteEvent, keyBytes: Uint8Array): Result<WebNostrEvent>
    fun validate(event: WebNostrEvent): Result<Boolean>
}

internal object WebNostrToolsDirectKeyCrypto : WebDirectKeyCrypto {
    override val productionReady: Boolean
        get() = true

    override fun decodeNsec(raw: String): Result<Uint8Array> = runCatching {
        val decoded = WebDirectKeyNip19.decode(raw.trim()) ?: throw IllegalArgumentException("invalid")
        require(decoded.hrp == "nsec")
        require(decoded.data.size == WebDirectKeyByteCount)
        decoded.data.toUint8Array()
    }

    override fun derivePublicKey(keyBytes: Uint8Array): Result<String> =
        runCatching { NostrTools.getPublicKey(keyBytes).lowercase() }

    override fun encryptToSelf(plaintext: String, keyBytes: Uint8Array, publicKeyHex: String): Result<String> = runCatching {
        val conversationKey = NostrTools.nip44.v2.utils.getConversationKey(keyBytes, publicKeyHex)
        NostrTools.nip44.v2.encrypt(plaintext, conversationKey) as String
    }

    override fun decryptFromSelf(ciphertext: String, keyBytes: Uint8Array, publicKeyHex: String): Result<String> = runCatching {
        val conversationKey = NostrTools.nip44.v2.utils.getConversationKey(keyBytes, publicKeyHex)
        NostrTools.nip44.v2.decrypt(ciphertext, conversationKey) as String
    }

    override fun sign(unsignedEvent: WebUnsignedNoteEvent, keyBytes: Uint8Array): Result<WebNostrEvent> = runCatching {
        parseDirectKeySignedEvent(NostrTools.finalizeEvent(unsignedEvent.toDirectKeyTemplate(), keyBytes))
            ?: throw IllegalStateException("invalid signed event")
    }

    override fun validate(event: WebNostrEvent): Result<Boolean> = runCatching {
        val dynamicEvent = event.toDirectKeyTemplate(includeIdentity = true)
        NostrTools.validateEvent(dynamicEvent) && NostrTools.verifyEvent(dynamicEvent)
    }
}

internal class WebDirectKeySession internal constructor(
    keyBytes: Uint8Array,
    val publicKeyHex: String,
    private val crypto: WebDirectKeyCrypto,
) {
    private var activeKeyBytes: Uint8Array? = keyBytes.copyUint8Array(WebDirectKeyByteCount)

    val active: Boolean get() = activeKeyBytes != null

    fun clear() {
        activeKeyBytes?.fillUint8Array(0, WebDirectKeyByteCount)
        activeKeyBytes = null
    }

    internal fun encryptToSelf(plaintext: String): Result<String> =
        withActiveKey { keyBytes -> crypto.encryptToSelf(plaintext, keyBytes, publicKeyHex).getOrThrow() }

    internal fun decryptFromSelf(ciphertext: String): Result<String> =
        withActiveKey { keyBytes -> crypto.decryptFromSelf(ciphertext, keyBytes, publicKeyHex).getOrThrow() }

    internal fun sign(unsignedEvent: WebUnsignedNoteEvent): Result<WebNostrEvent> =
        withActiveKey { keyBytes -> crypto.sign(unsignedEvent, keyBytes).getOrThrow() }

    internal fun validate(event: WebNostrEvent): Result<Boolean> =
        runCatching { crypto.validate(event).getOrThrow() }

    private fun <T> withActiveKey(operation: (Uint8Array) -> T): Result<T> = runCatching {
        val keyBytes = activeKeyBytes ?: throw IllegalStateException(WebDirectKeyCopy.SessionCleared)
        operation(keyBytes)
    }
}

internal class WebDirectKeyNoteDecryptor(
    private val session: WebDirectKeySession,
) : WebNoteDecryptor {
    override fun decrypt(ciphertext: String, onResult: (Result<String>) -> Unit) {
        onResult(
            session.decryptFromSelf(ciphertext).fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(IllegalStateException(WebDirectKeyCopy.DecryptFailed)) },
            ),
        )
    }
}

internal class WebDirectKeyNoteCrudSigner(
    private val session: WebDirectKeySession,
) : WebNoteCrudSigner {
    override fun encrypt(plaintext: String, onResult: (WebSignerOperationResult) -> Unit) {
        onResult(
            session.encryptToSelf(plaintext).fold(
                onSuccess = { WebSignerOperationResult.Success(it) },
                onFailure = { WebSignerOperationResult.Failed(WebDirectKeyCopy.EncryptFailed) },
            ),
        )
    }

    override fun sign(unsignedEvent: WebUnsignedNoteEvent, onResult: (WebNoteSignResult) -> Unit) {
        val event = session.sign(unsignedEvent).getOrElse {
            onResult(WebNoteSignResult.Failed(WebDirectKeyCopy.SignFailed))
            return
        }
        val valid = session.validate(event).getOrDefault(false)
        onResult(
            if (valid) {
                WebNoteSignResult.Signed(event)
            } else {
                WebNoteSignResult.Failed(WebDirectKeyCopy.EventValidationFailed)
            },
        )
    }
}

internal fun createWebDirectKeySession(
    rawNsec: String,
    crypto: WebDirectKeyCrypto = WebNostrToolsDirectKeyCrypto,
): WebDirectKeyLoginResult {
    if (!crypto.productionReady) {
        return WebDirectKeyLoginResult.Unavailable(WebDirectKeyCopy.CryptoUnavailable)
    }
    val keyBytes = crypto.decodeNsec(rawNsec).getOrElse {
        return WebDirectKeyLoginResult.Invalid(WebDirectKeyCopy.InvalidKey)
    }
    val publicKeyHex = crypto.derivePublicKey(keyBytes).getOrElse {
        keyBytes.fillUint8Array(0, WebDirectKeyByteCount)
        return WebDirectKeyLoginResult.Unavailable(WebDirectKeyCopy.PublicKeyFailed)
    }
    if (!isValidDirectKeyPublicKey(publicKeyHex)) {
        keyBytes.fillUint8Array(0, WebDirectKeyByteCount)
        return WebDirectKeyLoginResult.Unavailable(WebDirectKeyCopy.PublicKeyFailed)
    }
    val session = WebDirectKeySession(keyBytes, publicKeyHex.lowercase(), crypto)
    keyBytes.fillUint8Array(0, WebDirectKeyByteCount)
    return WebDirectKeyLoginResult.Success(
        session = session,
        identity = WebAccountIdentity(publicKeyHex.lowercase(), WebAuthMethod.DirectNsec),
    )
}

internal data class WebDirectKeyNip19Data(
    val hrp: String,
    val data: ByteArray,
)

internal object WebDirectKeyNip19 {
    private const val Charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun decode(value: String): WebDirectKeyNip19Data? {
        if (value.any { it.isLetter() } && value.any { it.isUpperCase() } && value.any { it.isLowerCase() }) {
            return null
        }
        val normalized = value.lowercase()
        val separator = normalized.lastIndexOf('1')
        if (separator <= 0 || separator + 7 > normalized.length) return null
        val hrp = normalized.substring(0, separator)
        val data = normalized.substring(separator + 1).map { Charset.indexOf(it) }
        if (data.any { it < 0 }) return null
        if (!verifyChecksum(hrp, data)) return null
        val payload5 = data.dropLast(6)
        val payload8 = convertBits(payload5, 5, 8, false) ?: return null
        return WebDirectKeyNip19Data(hrp, payload8.map { it.toByte() }.toByteArray())
    }

    fun encode(hrp: String, data: ByteArray): String? {
        val normalizedHrp = hrp.lowercase()
        if (normalizedHrp.isBlank() || normalizedHrp.any { it.code < 33 || it.code > 126 }) return null
        val payload5 = convertBits(data.map { it.toInt() and 0xff }, 8, 5, true) ?: return null
        val checksum = createChecksum(normalizedHrp, payload5)
        return normalizedHrp + "1" + (payload5 + checksum).joinToString("") { Charset[it].toString() }
    }

    private fun verifyChecksum(hrp: String, values: List<Int>): Boolean =
        polymod(expandHrp(hrp) + values) == 1

    private fun createChecksum(hrp: String, values: List<Int>): List<Int> {
        val polymod = polymod(expandHrp(hrp) + values + List(6) { 0 }) xor 1
        return (0 until 6).map { index -> (polymod shr (5 * (5 - index))) and 31 }
    }

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

private fun ByteArray.toUint8Array(): Uint8Array =
    Uint8Array(size).also { bytes ->
        forEachIndexed { index, byte -> writeUint8ArrayByte(bytes, index, byte.toInt() and 0xff) }
    }

private fun Uint8Array.copyUint8Array(byteCount: Int): Uint8Array =
    Uint8Array(byteCount).also { copy ->
        repeat(byteCount) { index -> writeUint8ArrayByte(copy, index, readUint8ArrayByte(this, index) and 0xff) }
    }

private fun isValidDirectKeyPublicKey(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

private fun Uint8Array.fillUint8Array(value: Int, byteCount: Int) {
    repeat(byteCount) { index -> writeUint8ArrayByte(this, index, value) }
}

private fun WebUnsignedNoteEvent.toDirectKeyTemplate(): dynamic =
    toDirectKeyTemplate(includeIdentity = false)

private fun WebUnsignedNoteEvent.toDirectKeyTemplate(includeIdentity: Boolean): dynamic {
    val template = js("({})")
    if (includeIdentity) {
        template.pubkey = pubkey
    }
    template.created_at = createdAt.toDouble()
    template.kind = kind
    template.tags = tags.map { it.toTypedArray() }.toTypedArray()
    template.content = content
    return template
}

private fun WebNostrEvent.toDirectKeyTemplate(includeIdentity: Boolean): dynamic {
    val template = WebUnsignedNoteEvent(
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
    ).toDirectKeyTemplate(includeIdentity)
    template.id = id
    template.sig = sig
    return template
}

private fun parseDirectKeySignedEvent(value: dynamic): WebNostrEvent? =
    runCatching {
        val tags = (value.tags as Array<*>).map { rawTag ->
            (rawTag as Array<*>).mapNotNull { it as? String }
        }
        WebNostrEvent(
            id = directKeyJsString(value.id),
            pubkey = directKeyJsString(value.pubkey).lowercase(),
            createdAt = directKeyJsLong(value.created_at),
            kind = directKeyJsInt(value.kind),
            tags = tags,
            content = directKeyJsString(value.content),
            sig = directKeyJsString(value.sig),
        )
    }.getOrNull()

private fun directKeyJsString(value: dynamic): String = value.unsafeCast<String>()
private fun directKeyJsLong(value: dynamic): Long = value.unsafeCast<Double>().toLong()
private fun directKeyJsInt(value: dynamic): Int = value.unsafeCast<Double>().toInt()
