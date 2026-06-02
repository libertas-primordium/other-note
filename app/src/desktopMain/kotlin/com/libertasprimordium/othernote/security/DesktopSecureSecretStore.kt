package com.libertasprimordium.othernote.security

import com.libertasprimordium.othernote.nostr.KeyDecodeResult
import com.libertasprimordium.othernote.nostr.NostrCrypto
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DesktopSecureSecretStore(
    private val secretToolPath: String? = findSecretToolPath(),
    private val crypto: NostrCrypto? = ProductionNostrCryptoFactory.createOrNull(),
) : SecureSecretStore {
    override val isAvailable: Boolean = secretToolPath != null
    override val unavailableReason: String? = if (isAvailable) {
        null
    } else {
        "Desktop keyring helper secret-tool was not found. Install libsecret tools or use session-only sign-in."
    }

    override suspend fun listSavedNsecs(): SecureSecretStoreResult {
        val command = secretToolPath ?: return SecureSecretStoreResult.Unavailable
        val result = runSecretTool(command, listOf("search") + baseAttributes())
        if (result.timedOut) return SecureSecretStoreResult.Failed("Desktop keyring did not respond.")
        if (result.exitCode != 0 && result.stdout.isBlank() && result.stderr.isBlank()) {
            return SecureSecretStoreResult.Listed(emptyList())
        }
        if (result.exitCode != 0) {
            return keyringFailure("Could not load saved identities from the desktop keyring.", result.stderr)
        }
        return SecureSecretStoreResult.Listed(parseSavedIdentities(result.stdout))
    }

    override suspend fun saveNsec(accountId: String, nsec: String): SecureSecretStoreResult {
        val command = secretToolPath ?: return SecureSecretStoreResult.Unavailable
        val accountPubkey = accountId.lowercase()
        if (!accountPubkey.matches(hexPubkeyRegex)) {
            return SecureSecretStoreResult.Failed("Could not save this identity to the desktop keyring.")
        }
        val result = runSecretTool(
            command = command,
            args = listOf("store", "--label", labelFor(accountPubkey, nsec)) + baseAttributes() + listOf(
                AccountPubkeyAttribute,
                accountPubkey,
            ),
            stdin = nsec.trim(),
        )
        if (result.timedOut) return SecureSecretStoreResult.Failed("Desktop keyring did not respond.")
        return if (result.exitCode == 0) {
            SecureSecretStoreResult.Saved
        } else {
            keyringFailure("Could not save this identity to the desktop keyring.", result.stderr)
        }
    }

    override suspend fun loadNsec(accountId: String): SecureSecretStoreResult {
        val command = secretToolPath ?: return SecureSecretStoreResult.Unavailable
        val accountPubkey = accountId.lowercase()
        if (!accountPubkey.matches(hexPubkeyRegex)) {
            return SecureSecretStoreResult.Failed("Could not load this saved identity.")
        }
        val result = runSecretTool(
            command = command,
            args = listOf("lookup") + baseAttributes() + listOf(AccountPubkeyAttribute, accountPubkey),
        )
        if (result.timedOut) return SecureSecretStoreResult.Failed("Desktop keyring did not respond.")
        if (result.exitCode != 0) return keyringFailure("Could not load this saved identity.", result.stderr)
        val loadedNsec = result.stdout.trim()
        return if (loadedNsec.isBlank()) {
            SecureSecretStoreResult.Failed("Could not load this saved identity.")
        } else {
            SecureSecretStoreResult.Loaded(loadedNsec)
        }
    }

    override suspend fun deleteNsec(accountId: String): SecureSecretStoreResult {
        val command = secretToolPath ?: return SecureSecretStoreResult.Unavailable
        val accountPubkey = accountId.lowercase()
        if (!accountPubkey.matches(hexPubkeyRegex)) {
            return SecureSecretStoreResult.Failed("Could not forget this saved identity.")
        }
        val result = runSecretTool(
            command = command,
            args = listOf("clear") + baseAttributes() + listOf(AccountPubkeyAttribute, accountPubkey),
        )
        if (result.timedOut) return SecureSecretStoreResult.Failed("Desktop keyring did not respond.")
        return if (result.exitCode == 0) {
            SecureSecretStoreResult.Deleted
        } else {
            keyringFailure("Could not forget this saved identity.", result.stderr)
        }
    }

    private fun parseSavedIdentities(rawSearchOutput: String): List<SavedNsecIdentity> {
        val attributeIdentities = accountPubkeyRegex.findAll(rawSearchOutput)
            .map {
                SavedNsecIdentity(
                    accountPubkey = it.groupValues[1].lowercase(),
                    npub = "",
                    label = "Desktop keyring identity",
                )
            }
        val secretIdentities = (secretLineRegex.findAll(rawSearchOutput) + rawNsecLineRegex.findAll(rawSearchOutput))
            .mapNotNull { identityFromStoredNsec(it.groupValues[1].trim()) }
        return (attributeIdentities + secretIdentities)
            .filter { it.accountPubkey.matches(hexPubkeyRegex) }
            .groupBy { it.accountPubkey.lowercase() }
            .map { (_, identities) ->
                identities.firstOrNull { it.npub.isNotBlank() } ?: identities.first()
            }
    }

    private fun identityFromStoredNsec(nsec: String): SavedNsecIdentity? {
        val activeCrypto = crypto ?: return null
        val privateKey = when (val decoded = activeCrypto.decodeNsec(nsec)) {
            is KeyDecodeResult.Valid -> decoded.privateKey
            is KeyDecodeResult.Invalid -> return null
        }
        val publicKey = activeCrypto.derivePublicKey(privateKey).getOrNull() ?: return null
        return SavedNsecIdentity(
            accountPubkey = publicKey.hex.lowercase(),
            npub = publicKey.npub,
            label = "Desktop keyring identity",
        )
    }

    private fun labelFor(accountPubkey: String, nsec: String): String {
        val npubPrefix = identityFromStoredNsec(nsec)?.npub?.take(16)
        return if (npubPrefix.isNullOrBlank()) {
            "Other Note identity ${accountPubkey.take(12)}"
        } else {
            "Other Note identity $npubPrefix"
        }
    }

    private suspend fun runSecretTool(
        command: String,
        args: List<String>,
        stdin: String? = null,
    ): SecretToolRunResult = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(listOf(command) + args).start()
            val stdout = AtomicReference("")
            val stderr = AtomicReference("")
            val stdoutReader = thread(start = true) { stdout.set(process.inputStream.bufferedReader().readText()) }
            val stderrReader = thread(start = true) { stderr.set(process.errorStream.bufferedReader().readText()) }
            process.outputStream.bufferedWriter().use { writer ->
                if (stdin != null) writer.write(stdin)
            }
            val completed = process.waitFor(12, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return@withContext SecretToolRunResult(exitCode = -1, stdout = "", stderr = "", timedOut = true)
            }
            stdoutReader.join(1_000)
            stderrReader.join(1_000)
            SecretToolRunResult(
                exitCode = process.exitValue(),
                stdout = stdout.get(),
                stderr = stderr.get(),
                timedOut = false,
            )
        }.getOrElse {
            SecretToolRunResult(exitCode = -1, stdout = "", stderr = "", timedOut = false)
        }
    }

    private fun keyringFailure(fallback: String, stderr: String): SecureSecretStoreResult.Failed {
        val lower = stderr.lowercase()
        val message = when {
            lower.contains("locked") || lower.contains("denied") || lower.contains("cancel") ->
                "Could not unlock the desktop keyring. Try again or use session-only sign-in."
            lower.contains("org.freedesktop.secrets") ||
                lower.contains("dbus") ||
                lower.contains("d-bus") ||
                lower.contains("session bus") ||
                lower.contains("serviceunknown") ||
                lower.contains("service unknown") ||
                lower.contains("name has no owner") ->
                "Secret Service is not available on the user session bus. You can still sign in for this session."
            lower.contains("no such secret") || lower.contains("not found") -> fallback
            else -> fallback
        }
        return SecureSecretStoreResult.Failed(message)
    }

    private data class SecretToolRunResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    private companion object {
        private const val ApplicationAttribute = "application"
        private const val KeyTypeAttribute = "key_type"
        private const val AccountPubkeyAttribute = "account_pubkey"
        private val hexPubkeyRegex = Regex("^[0-9a-f]{64}$")
        private val accountPubkeyRegex = Regex("""(?:attribute\.)?account_pubkey\s*[=:]\s*([0-9a-fA-F]{64})""")
        private val secretLineRegex = Regex("""(?m)^\s*secret\s*[=:]\s*(nsec[0-9a-zA-Z]+)\s*$""")
        private val rawNsecLineRegex = Regex("""(?m)^\s*(nsec[0-9a-zA-Z]+)\s*$""")

        private fun baseAttributes(): List<String> = listOf(
            ApplicationAttribute,
            "other-note",
            KeyTypeAttribute,
            "nostr_nsec",
        )
    }
}

private fun findSecretToolPath(): String? {
    listOfNotNull(
        System.getProperty("othernote.secretTool"),
        System.getenv("OTHER_NOTE_SECRET_TOOL"),
    ).firstOrNull { it.isNotBlank() }?.let { configured ->
        File(configured).takeIf { it.isFile && it.canExecute() }?.let { return it.absolutePath }
    }
    val path = System.getenv("PATH").orEmpty()
    return (path.split(File.pathSeparator) + commonSecretToolPaths)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { candidate ->
            val file = File(candidate)
            if (file.name == "secret-tool") file else File(file, "secret-tool")
        }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
}

private val commonSecretToolPaths = listOf(
    "/usr/bin/secret-tool",
    "/usr/local/bin/secret-tool",
    "/bin/secret-tool",
)
