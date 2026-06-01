package com.libertasprimordium.othernote.security

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

class AndroidNip55Nip44Operator(context: Context) : NostrSignerNip44Operator {
    private val appContext = context.applicationContext

    override fun encryptToSelf(
        plaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult {
        val request = SignerNip44RequestBuilder.build(
            operation = SignerNip44Operation.Encrypt,
            payload = plaintext,
            peerPubkey = currentUserPubkey,
            currentUserPubkey = currentUserPubkey,
            signerPackage = signerPackage,
        ).getOrElse {
            return SignerNip44OperationResult.Failed("Could not build signer encryption request.")
        }
        val result = querySigner(request)
        return when (result) {
            is ContentResolverResult.Success -> SignerNip44ResponseParser.parseEncryptResult(
                result = result.result,
                plaintext = plaintext,
                signerPackage = signerPackage,
            )
            is ContentResolverResult.Failed -> result.toOperationResult()
        }
    }

    override fun decryptFromSelf(
        ciphertext: String,
        expectedPlaintext: String,
        currentUserPubkey: String,
        signerPackage: String?,
    ): SignerNip44OperationResult {
        val request = SignerNip44RequestBuilder.build(
            operation = SignerNip44Operation.Decrypt,
            payload = ciphertext,
            peerPubkey = currentUserPubkey,
            currentUserPubkey = currentUserPubkey,
            signerPackage = signerPackage,
        ).getOrElse {
            return SignerNip44OperationResult.Failed("Could not build signer decryption request.")
        }
        val result = querySigner(request)
        return when (result) {
            is ContentResolverResult.Success -> SignerNip44ResponseParser.parseDecryptResult(
                result = result.result,
                expectedPlaintext = expectedPlaintext,
                signerPackage = signerPackage,
            )
            is ContentResolverResult.Failed -> result.toOperationResult()
        }
    }

    private fun querySigner(request: SignerNip44Request): ContentResolverResult {
        val signerPackage = request.signerPackage
            ?: return ContentResolverResult.Failed("Android signer package is unavailable. Log in with the signer again.")
        val uri = contentResolverUri(signerPackage, request.operation)
        if (showSafeDiagnostics()) {
            Log.i("OtherNoteNip55", (request.safeDiagnostics + "request_called=true").joinToString("; "))
        }
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(request.payload, request.peerPubkey, request.currentUserPubkey),
                null,
                null,
                null,
            ).also { cursor ->
                if (showSafeDiagnostics()) {
                    Log.i("OtherNoteNip55", diagnostics(request.operation, cursor).joinToString("; "))
                }
            }
        }.fold(
            onSuccess = { cursor -> parseCursor(request.operation, cursor) },
            onFailure = { error ->
                if (showSafeDiagnostics()) {
                    Log.i(
                        "OtherNoteNip55",
                        "operation_exception=${error::class.simpleName}; message=${error.message.orEmpty().take(120)}",
                    )
                }
                ContentResolverResult.Failed("Android signer NIP-44 request failed: ${error::class.simpleName}")
            },
        )
    }

    private fun parseCursor(operation: SignerNip44Operation, cursor: Cursor?): ContentResolverResult {
        if (cursor == null) {
            return ContentResolverResult.Failed(operation.emptyResultMessage())
        }
        cursor.use {
            if (it.getColumnIndex("rejected") >= 0) {
                return ContentResolverResult.Failed("Signer rejected the NIP-44 request")
            }
            if (!it.moveToFirst()) {
                return ContentResolverResult.Failed(operation.emptyResultMessage())
            }
            return ContentResolverResult.Success(it.stringColumn("result"))
        }
    }

    private fun SignerNip44Operation.emptyResultMessage(): String = when (this) {
        SignerNip44Operation.Encrypt -> "Signer returned invalid encryption result"
        SignerNip44Operation.Decrypt -> "Signer decryption failed"
    }

    private fun diagnostics(operation: SignerNip44Operation, cursor: Cursor?): List<String> {
        val columns = cursor?.columnNames?.sorted().orEmpty()
        return listOf(
            "operation=${operation.diagnosticName}",
            "result_received=${cursor != null}",
            "result_columns=${columns.joinToString(prefix = "[", postfix = "]")}",
            "result_has_result=${columns.contains("result")}",
            "result_has_rejected=${columns.contains("rejected")}",
        )
    }

    private fun Cursor.stringColumn(name: String): String? {
        val index = getColumnIndex(name)
        if (index < 0) return null
        return getString(index)
    }

    private fun ContentResolverResult.Failed.toOperationResult(): SignerNip44OperationResult =
        SignerNip44OperationResult.Failed(safeReason)

    private sealed class ContentResolverResult {
        data class Success(val result: String?) : ContentResolverResult()
        data class Failed(val safeReason: String) : ContentResolverResult()
    }

    companion object {
        fun contentResolverUri(signerPackage: String, operation: SignerNip44Operation): Uri =
            Uri.parse("content://$signerPackage.${operation.contentProviderMethod}")

        private fun showSafeDiagnostics(): Boolean =
            System.getProperty("othernote.showNip55Diagnostics") == "true" ||
                System.getenv("OTHER_NOTE_SHOW_NIP55_DIAGNOSTICS") == "1"
    }
}
