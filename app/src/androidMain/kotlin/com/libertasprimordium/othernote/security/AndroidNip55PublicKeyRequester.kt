package com.libertasprimordium.othernote.security

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher

class AndroidNip55PublicKeyRequester : NostrSignerPublicKeyRequester {
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pendingCallback: ((SignerPublicKeyRequestResult) -> Unit)? = null

    fun attachLauncher(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    override fun requestPublicKey(onResult: (SignerPublicKeyRequestResult) -> Unit) {
        val currentLauncher = launcher
        if (currentLauncher == null) {
            onResult(SignerPublicKeyRequestResult.Unavailable("Android signer launcher is not available."))
            return
        }
        if (pendingCallback != null) {
            onResult(SignerPublicKeyRequestResult.Failed("A signer request is already in progress."))
            return
        }
        pendingCallback = onResult
        runCatching {
            currentLauncher.launch(publicKeyRequestIntent())
        }.onFailure {
            pendingCallback = null
            onResult(SignerPublicKeyRequestResult.Failed("Could not open Android signer."))
        }
    }

    fun handleActivityResult(resultCode: Int, data: Intent?) {
        val callback = pendingCallback ?: return
        pendingCallback = null
        if (resultCode != Activity.RESULT_OK) {
            callback(SignerPublicKeyRequestResult.Cancelled)
            return
        }
        callback(parsePublicKeyResult(data))
    }

    companion object {
        fun publicKeyRequestIntent(): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                putExtra("type", "get_public_key")
                putExtra("permissions", """[{"type":"sign_event","kind":1}]""")
            }

        fun parsePublicKeyResult(data: Intent?): SignerPublicKeyRequestResult {
            if (data == null) {
                return SignerPublicKeyRequestResult.InvalidResponse("Signer returned no response")
            }
            val result = data.getStringExtra("result") ?: data.data?.getQueryParameter("result")
            val pubkey = data.getStringExtra("pubkey") ?: data.data?.getQueryParameter("pubkey")
            val npub = data.getStringExtra("npub") ?: data.data?.getQueryParameter("npub")
            val signerPackage = data.getStringExtra("package")
                ?: data.getStringExtra("packageName")
                ?: data.`package`
                ?: data.data?.getQueryParameter("package")
                ?: data.data?.getQueryParameter("packageName")
            return SignerPublicKeyResponseParser.parse(
                result = result,
                pubkey = pubkey,
                npub = npub,
                signerPackage = signerPackage,
            )
        }
    }
}
