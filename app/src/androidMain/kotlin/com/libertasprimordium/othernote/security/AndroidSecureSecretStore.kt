package com.libertasprimordium.othernote.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.libertasprimordium.othernote.nostr.Nip19
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureSecretStore(context: Context) : SecureSecretStore {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    }
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = false; prettyPrint = false }
    private val availability: String? by lazy {
        runCatching {
            getOrCreateSecretKey()
            null
        }.getOrElse {
            "Android secure storage is not available. You can still sign in for this session."
        }
    }

    override val isAvailable: Boolean
        get() = availability == null

    override val unavailableReason: String?
        get() = availability

    override suspend fun listSavedNsecs(): SecureSecretStoreResult {
        if (!isAvailable) return SecureSecretStoreResult.Unavailable
        return lock.withLock {
            val identities = prefs.all.entries.mapNotNull { (key, value) ->
                if (!key.startsWith(RecordKeyPrefix) || value !is String) return@mapNotNull null
                val record = decodeRecord(value).getOrNull() ?: return@mapNotNull null
                record.toIdentity()
            }.sortedBy { it.accountPubkey }
            SecureSecretStoreResult.Listed(identities)
        }
    }

    override suspend fun saveNsec(accountId: String, nsec: String): SecureSecretStoreResult {
        if (!isAvailable) return SecureSecretStoreResult.Unavailable
        val accountPubkey = accountId.lowercase().takeIf { it.isHexPubkey() }
            ?: return SecureSecretStoreResult.Failed("Could not save this identity to Android secure storage.")
        val trimmed = nsec.trim().takeIf { it.isNotBlank() }
            ?: return SecureSecretStoreResult.Failed("Could not save this identity to Android secure storage.")
        return lock.withLock {
            runCatching {
                val existing = prefs.getString(recordKey(accountPubkey), null)?.let { decodeRecord(it).getOrNull() }
                val encrypted = encrypt(accountPubkey, trimmed)
                val now = System.currentTimeMillis()
                val record = AndroidSavedNsecRecord(
                    version = RecordVersion,
                    accountPubkey = accountPubkey,
                    ciphertext = encrypted.ciphertext,
                    iv = encrypted.iv,
                    createdAtMs = existing?.createdAtMs ?: now,
                    updatedAtMs = now,
                )
                prefs.edit().putString(recordKey(accountPubkey), json.encodeToString(record)).apply()
                SecureSecretStoreResult.Saved
            }.getOrElse {
                SecureSecretStoreResult.Failed("Could not save this identity to Android secure storage.")
            }
        }
    }

    override suspend fun loadNsec(accountId: String): SecureSecretStoreResult {
        if (!isAvailable) return SecureSecretStoreResult.Unavailable
        val accountPubkey = accountId.lowercase().takeIf { it.isHexPubkey() }
            ?: return SecureSecretStoreResult.Failed("Could not load this saved identity.")
        return lock.withLock {
            val raw = prefs.getString(recordKey(accountPubkey), null)
                ?: return@withLock SecureSecretStoreResult.Failed("Could not load this saved identity.")
            val record = decodeRecord(raw).getOrElse {
                return@withLock SecureSecretStoreResult.Failed("This saved identity is invalid. Remove it and sign in again.")
            }
            if (record.accountPubkey != accountPubkey || record.version != RecordVersion) {
                return@withLock SecureSecretStoreResult.Failed("This saved identity is invalid. Remove it and sign in again.")
            }
            runCatching {
                SecureSecretStoreResult.Loaded(decrypt(record))
            }.getOrElse {
                SecureSecretStoreResult.Failed("Could not load this saved identity.")
            }
        }
    }

    override suspend fun deleteNsec(accountId: String): SecureSecretStoreResult {
        if (!isAvailable) return SecureSecretStoreResult.Unavailable
        val accountPubkey = accountId.lowercase().takeIf { it.isHexPubkey() }
            ?: return SecureSecretStoreResult.Failed("Could not forget this saved identity.")
        return lock.withLock {
            prefs.edit().remove(recordKey(accountPubkey)).apply()
            SecureSecretStoreResult.Deleted
        }
    }

    private fun encrypt(accountPubkey: String, nsec: String): EncryptedNsec {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        cipher.updateAAD(accountPubkey.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(nsec.toByteArray(Charsets.UTF_8))
        return EncryptedNsec(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    private fun decrypt(record: AndroidSavedNsecRecord): String {
        val cipher = Cipher.getInstance(Transformation)
        val iv = Base64.decode(record.iv, Base64.NO_WRAP)
        val ciphertext = Base64.decode(record.ciphertext, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(record.accountPubkey.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        val spec = KeyGenParameterSpec.Builder(
            KeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun decodeRecord(raw: String): Result<AndroidSavedNsecRecord> = runCatching {
        val record = json.decodeFromString<AndroidSavedNsecRecord>(raw)
        require(record.version == RecordVersion)
        require(record.accountPubkey.isHexPubkey())
        require(record.ciphertext.isNotBlank())
        require(record.iv.isNotBlank())
        record.copy(accountPubkey = record.accountPubkey.lowercase())
    }

    private fun AndroidSavedNsecRecord.toIdentity(): SavedNsecIdentity =
        SavedNsecIdentity(
            accountPubkey = accountPubkey,
            npub = accountPubkey.hexToNpub().orEmpty(),
            label = "Android secure storage identity",
        )

    private fun String.hexToNpub(): String? {
        if (!isHexPubkey()) return null
        val bytes = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Nip19.encode("npub", bytes)
    }

    private fun String.isHexPubkey(): Boolean =
        length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun recordKey(accountPubkey: String): String = "$RecordKeyPrefix$accountPubkey"

    private data class EncryptedNsec(
        val ciphertext: String,
        val iv: String,
    )

    @Serializable
    private data class AndroidSavedNsecRecord(
        val version: Int,
        val accountPubkey: String,
        val ciphertext: String,
        val iv: String,
        val createdAtMs: Long,
        val updatedAtMs: Long,
    )

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "other_note_android_saved_nsec_v1"
        const val PreferencesName = "other_note_android_secure_nsec_v1"
        const val RecordKeyPrefix = "identity_"
        const val RecordVersion = 1
        const val Transformation = "AES/GCM/NoPadding"
    }
}
