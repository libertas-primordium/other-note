package com.libertasprimordium.othernote.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.libertasprimordium.othernote.nostr.NostrEvent

class AndroidNip55EventSigner(context: Context) : NostrSignerEventSigner {
    private val appContext = context.applicationContext
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pendingRequest: PendingSignRequest? = null

    fun attachLauncher(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    override fun signEvent(
        unsignedEvent: NostrEvent,
        currentUserPubkey: String,
        signerPackage: String?,
        onResult: (SignEventRequestResult) -> Unit,
    ) {
        if (pendingRequest != null) {
            onResult(SignEventRequestResult.Failed("A signer request is already in progress."))
            return
        }
        val packageName = signerPackage?.takeIf { it.isNotBlank() }
        if (packageName == null) {
            onResult(SignEventRequestResult.Unavailable("Android signer package is unavailable. Log in with the signer again."))
            return
        }
        val launchRequest = SignerSignEventRequestBuilder.build(unsignedEvent, currentUserPubkey, signerPackage)
            .getOrElse {
                onResult(SignEventRequestResult.Failed("Could not build a valid signer request."))
                return
            }
        if (showSafeDiagnostics()) {
            Log.i(
                "OtherNoteNip55",
                (
                    launchRequest.safeDiagnostics +
                        "request_path=content_resolver" +
                        "provider_package_target_present=true" +
                        "provider_authority_suffix=.SIGN_EVENT" +
                        "request_called=true"
                    ).joinToString("; "),
            )
        }
        val result = signWithContentResolver(
            launchRequest = launchRequest,
            currentUserPubkey = currentUserPubkey,
            signerPackage = packageName,
        )
        onResult(result)
    }

    fun handleActivityResult(resultCode: Int, data: Intent?) {
        val pending = pendingRequest ?: return
        pendingRequest = null
        if (showSafeDiagnostics()) {
            Log.i("OtherNoteNip55", resultDiagnostics(resultCode, data).joinToString("; "))
        }
        if (resultCode != Activity.RESULT_OK) {
            pending.onResult(SignEventRequestResult.Cancelled)
            return
        }
        pending.onResult(parseSignEventResult(pending.unsignedEvent, data))
    }

    companion object {
        fun signEventIntent(
            launchRequest: SignEventLaunchRequest,
            requestId: String,
            currentUserPubkey: String,
            signerPackage: String?,
        ): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(launchRequest.uriString)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                signerPackage?.takeIf { it.isNotBlank() }?.let { setPackage(it) }
                putExtra("type", "sign_event")
                putExtra("id", requestId)
                putExtra("current_user", currentUserPubkey)
            }

        fun contentResolverUri(signerPackage: String): Uri =
            Uri.parse("content://$signerPackage.SIGN_EVENT")

        fun parseSignEventResult(requestedEvent: NostrEvent, data: Intent?): SignEventRequestResult {
            if (data == null) {
                return SignEventRequestResult.InvalidResponse("Signer returned no response")
            }
            val signedEventJson = data.getStringExtra("event")
                ?: data.data?.getQueryParameter("event")
            val signature = data.getStringExtra("result")
                ?: data.data?.getQueryParameter("result")
            val id = data.getStringExtra("id")
                ?: data.data?.getQueryParameter("id")
            val signerPackage = data.getStringExtra("package")
                ?: data.getStringExtra("packageName")
                ?: data.`package`
                ?: data.data?.getQueryParameter("package")
                ?: data.data?.getQueryParameter("packageName")
            return SignerSignEventResponseParser.parseAndValidate(
                requestedEvent = requestedEvent,
                eventJson = signedEventJson,
                signature = signature,
                returnedId = id,
                signerPackage = signerPackage,
            )
        }

        private fun showSafeDiagnostics(): Boolean =
            System.getProperty("othernote.showNip55Diagnostics") == "true" ||
                System.getenv("OTHER_NOTE_SHOW_NIP55_DIAGNOSTICS") == "1"

        fun parseContentResolverResult(requestedEvent: NostrEvent, cursor: Cursor?): SignEventRequestResult {
            if (cursor == null) {
                return SignEventRequestResult.InvalidResponse("Signer returned no event to verify")
            }
            cursor.use {
                if (it.getColumnIndex("rejected") >= 0) {
                    return SignEventRequestResult.Failed("Signer rejected the signing request")
                }
                if (!it.moveToFirst()) {
                    return SignEventRequestResult.InvalidResponse("Signer returned no event to verify")
                }
                val signedEventJson = it.stringColumn("event")
                val signature = it.stringColumn("result") ?: it.stringColumn("signature")
                val id = it.stringColumn("id")
                return SignerSignEventResponseParser.parseAndValidate(
                    requestedEvent = requestedEvent,
                    eventJson = signedEventJson,
                    signature = signature,
                    returnedId = id,
                    signerPackage = null,
                )
            }
        }

        private fun resultDiagnostics(resultCode: Int, data: Intent?): List<String> {
            val extraKeys = data?.extras.safeKeys()
            return listOf(
                "sign_event result received=${data != null}",
                "resultCode=$resultCode",
                "result_extras_keys=${extraKeys.joinToString(prefix = "[", postfix = "]")}",
                "result_has_event=${data?.getStringExtra("event") != null || data?.data?.getQueryParameter("event") != null}",
                "result_has_result=${data?.getStringExtra("result") != null || data?.data?.getQueryParameter("result") != null}",
                "result_has_id=${data?.getStringExtra("id") != null || data?.data?.getQueryParameter("id") != null}",
            )
        }

        private fun Bundle?.safeKeys(): List<String> =
            this?.keySet()?.sorted().orEmpty()

        private fun Cursor.stringColumn(name: String): String? {
            val index = getColumnIndex(name)
            if (index < 0) return null
            return getString(index)
        }
    }

    private fun signWithContentResolver(
        launchRequest: SignEventLaunchRequest,
        currentUserPubkey: String,
        signerPackage: String,
    ): SignEventRequestResult {
        val uri = contentResolverUri(signerPackage)
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(launchRequest.eventJson, "", currentUserPubkey),
                null,
                null,
                null,
            ).also { cursor ->
                if (showSafeDiagnostics()) {
                    Log.i(
                        "OtherNoteNip55",
                        contentResolverDiagnostics(cursor).joinToString("; "),
                    )
                }
            }
        }.fold(
            onSuccess = { cursor -> parseContentResolverResult(launchRequest.requestedEvent, cursor) },
            onFailure = { error ->
                if (showSafeDiagnostics()) {
                    Log.i(
                        "OtherNoteNip55",
                        "content_resolver_exception=${error::class.simpleName}; message=${error.message.orEmpty().take(120)}",
                    )
                }
                SignEventRequestResult.Failed("Android signer content resolver failed: ${error::class.simpleName}")
            },
        )
    }

    private fun contentResolverDiagnostics(cursor: Cursor?): List<String> {
        val columns = cursor?.columnNames?.sorted().orEmpty()
        return listOf(
            "content_resolver_result_received=${cursor != null}",
            "result_shape_columns=${columns.joinToString(prefix = "[", postfix = "]")}",
            "result_has_event=${columns.contains("event")}",
            "result_has_result=${columns.contains("result")}",
            "result_has_id=${columns.contains("id")}",
            "result_has_signature=${columns.contains("signature")}",
        )
    }
}

private data class PendingSignRequest(
    val unsignedEvent: NostrEvent,
    val onResult: (SignEventRequestResult) -> Unit,
)
