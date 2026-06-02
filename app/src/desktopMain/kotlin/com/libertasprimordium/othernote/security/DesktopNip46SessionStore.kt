package com.libertasprimordium.othernote.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DesktopNip46SessionStore(
    private val secretToolPath: String? = findNip46SecretToolPath(),
) : Nip46SessionStore {
    override val isAvailable: Boolean = secretToolPath != null
    override val unavailableReason: String? = if (isAvailable) {
        null
    } else {
        "Desktop keyring helper secret-tool was not found. Remote signer sessions can still be paired for this app session."
    }

    override suspend fun listSessions(): Nip46SessionStoreResult {
        val command = secretToolPath ?: return Nip46SessionStoreResult.Unavailable
        val result = runSecretTool(command, listOf("search") + baseAttributes())
        if (result.timedOut) return Nip46SessionStoreResult.Failed("Desktop keyring did not respond.")
        if (result.exitCode != 0 && result.stdout.isBlank() && result.stderr.isBlank()) {
            return Nip46SessionStoreResult.Listed(emptyList())
        }
        if (result.exitCode != 0) return keyringFailure("Saved remote signer sessions could not be loaded.", result.stderr)
        return Nip46SessionStoreResult.Listed(parseSessionMetadata(result.stdout))
    }

    override suspend fun saveSession(session: SavedNip46Session): Nip46SessionStoreResult {
        val command = secretToolPath ?: return Nip46SessionStoreResult.Unavailable
        val accountPubkey = session.userPubkey.lowercase()
        if (!accountPubkey.matches(hexPubkeyRegex)) {
            return Nip46SessionStoreResult.Failed("Saved remote signer session is corrupted.")
        }
        val result = runSecretTool(
            command = command,
            args = listOf("store", "--label", labelFor(session)) + baseAttributes() + listOf(
                AccountPubkeyAttribute,
                accountPubkey,
            ),
            stdin = Nip46SessionCodec.encodeSession(session.copy(userPubkey = accountPubkey)),
        )
        if (result.timedOut) return Nip46SessionStoreResult.Failed("Desktop keyring did not respond.")
        return if (result.exitCode == 0) {
            Nip46SessionStoreResult.Saved
        } else {
            keyringFailure("Saved remote signer session could not be stored.", result.stderr)
        }
    }

    override suspend fun loadSession(userPubkey: String): Nip46SessionStoreResult {
        val command = secretToolPath ?: return Nip46SessionStoreResult.Unavailable
        val accountPubkey = userPubkey.lowercase()
        if (!accountPubkey.matches(hexPubkeyRegex)) {
            return Nip46SessionStoreResult.Failed("Saved remote signer session could not be loaded.")
        }
        val result = runSecretTool(
            command = command,
            args = listOf("lookup") + baseAttributes() + listOf(AccountPubkeyAttribute, accountPubkey),
        )
        if (result.timedOut) return Nip46SessionStoreResult.Failed("Desktop keyring did not respond.")
        if (result.exitCode != 0) return keyringFailure("Saved remote signer session could not be loaded.", result.stderr)
        val session = Nip46SessionCodec.decodeSessionOrNull(result.stdout.trim())
            ?: return Nip46SessionStoreResult.Failed("Saved remote signer session is corrupted.")
        return Nip46SessionStoreResult.Loaded(session)
    }

    override suspend fun deleteSession(userPubkey: String): Nip46SessionStoreResult {
        val command = secretToolPath ?: return Nip46SessionStoreResult.Unavailable
        val accountPubkey = userPubkey.lowercase()
        if (!accountPubkey.matches(hexPubkeyRegex)) {
            return Nip46SessionStoreResult.Failed("Saved remote signer session could not be forgotten.")
        }
        val result = runSecretTool(
            command = command,
            args = listOf("clear") + baseAttributes() + listOf(AccountPubkeyAttribute, accountPubkey),
        )
        if (result.timedOut) return Nip46SessionStoreResult.Failed("Desktop keyring did not respond.")
        return if (result.exitCode == 0) {
            Nip46SessionStoreResult.Deleted
        } else {
            keyringFailure("Saved remote signer session could not be forgotten.", result.stderr)
        }
    }

    private fun parseSessionMetadata(rawSearchOutput: String): List<SavedNip46SessionMetadata> {
        val fromAttributes = accountPubkeyRegex.findAll(rawSearchOutput)
            .map {
                SavedNip46SessionMetadata(
                    userPubkey = it.groupValues[1].lowercase(),
                    userNpub = "",
                    clientPubkey = "",
                    remoteSignerPubkey = "",
                    relays = emptyList(),
                    label = "Remote signer",
                )
            }
        val fromSecrets = secretJsonRegex.findAll(rawSearchOutput)
            .mapNotNull { Nip46SessionCodec.decodeSessionOrNull(it.groupValues[1].trim())?.metadata() }
        return (fromAttributes + fromSecrets)
            .filter { it.userPubkey.matches(hexPubkeyRegex) }
            .groupBy { it.userPubkey.lowercase() }
            .map { (_, sessions) ->
                sessions.firstOrNull { it.userNpub.isNotBlank() || it.clientPubkey.isNotBlank() } ?: sessions.first()
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
            SecretToolRunResult(process.exitValue(), stdout.get(), stderr.get(), timedOut = false)
        }.getOrElse {
            SecretToolRunResult(exitCode = -1, stdout = "", stderr = "", timedOut = false)
        }
    }

    private fun keyringFailure(fallback: String, stderr: String): Nip46SessionStoreResult.Failed {
        val lower = stderr.lowercase()
        val message = when {
            lower.contains("locked") || lower.contains("denied") || lower.contains("cancel") ->
                "Could not unlock the desktop keyring. Try again or pair for this app session."
            lower.contains("org.freedesktop.secrets") ||
                lower.contains("dbus") ||
                lower.contains("d-bus") ||
                lower.contains("session bus") ||
                lower.contains("serviceunknown") ||
                lower.contains("service unknown") ||
                lower.contains("name has no owner") ->
                "Secret Service is not available on the user session bus. You can pair a remote signer for this app session."
            else -> fallback
        }
        return Nip46SessionStoreResult.Failed(message)
    }

    private fun labelFor(session: SavedNip46Session): String =
        if (session.userNpub.isBlank()) {
            "Other Note remote signer ${session.userPubkey.take(12)}"
        } else {
            "Other Note remote signer ${session.userNpub.take(16)}"
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
        private val secretJsonRegex = Regex("""(?m)^\s*secret\s*[=:]\s*(\{.*})\s*$""")

        private fun baseAttributes(): List<String> = listOf(
            ApplicationAttribute,
            "other-note",
            KeyTypeAttribute,
            "nip46_session",
        )
    }
}

private fun findNip46SecretToolPath(): String? {
    listOfNotNull(
        System.getProperty("othernote.secretTool"),
        System.getenv("OTHER_NOTE_SECRET_TOOL"),
    ).firstOrNull { it.isNotBlank() }?.let { configured ->
        File(configured).takeIf { it.isFile && it.canExecute() }?.let { return it.absolutePath }
    }
    val path = System.getenv("PATH").orEmpty()
    return (path.split(File.pathSeparator) + commonNip46SecretToolPaths)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { candidate ->
            val file = File(candidate)
            if (file.name == "secret-tool") file else File(file, "secret-tool")
        }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
}

private val commonNip46SecretToolPaths = listOf(
    "/usr/bin/secret-tool",
    "/usr/local/bin/secret-tool",
    "/bin/secret-tool",
)
