package com.libertasprimordium.othernote.security

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher

class AndroidNip55PublicKeyRequester(
    private val defaultSignerPackage: String? = null,
) : TargetedNostrSignerPublicKeyRequester {
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pendingCallback: ((SignerPublicKeyRequestResult) -> Unit)? = null
    private var pendingSignerPackage: String? = null

    fun attachLauncher(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    override fun requestPublicKey(onResult: (SignerPublicKeyRequestResult) -> Unit) {
        requestPublicKeyInternal(defaultSignerPackage, onResult)
    }

    override fun requestPublicKeyForSigner(signerPackage: String, onResult: (SignerPublicKeyRequestResult) -> Unit) {
        val target = signerPackage.takeIf { it.isNotBlank() }
        if (target == null) {
            onResult(SignerPublicKeyRequestResult.Unavailable("Saved Android signer session is corrupted."))
            return
        }
        requestPublicKeyInternal(target, onResult)
    }

    private fun requestPublicKeyInternal(signerPackage: String?, onResult: (SignerPublicKeyRequestResult) -> Unit) {
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
        pendingSignerPackage = signerPackage
        runCatching {
            currentLauncher.launch(publicKeyRequestIntent(signerPackage))
        }.onFailure {
            pendingCallback = null
            pendingSignerPackage = null
            val message = if (signerPackage.isNullOrBlank()) {
                "Could not open Android signer."
            } else {
                "The saved Android signer is not installed or is no longer available."
            }
            onResult(SignerPublicKeyRequestResult.Failed(message))
        }
    }

    fun handleActivityResult(resultCode: Int, data: Intent?) {
        val callback = pendingCallback ?: return
        val targetPackage = pendingSignerPackage
        pendingCallback = null
        pendingSignerPackage = null
        if (resultCode != Activity.RESULT_OK) {
            callback(SignerPublicKeyRequestResult.Cancelled)
            return
        }
        callback(parsePublicKeyResult(data, targetPackage))
    }

    companion object {
        fun publicKeyRequestIntent(signerPackage: String? = null): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                signerPackage?.takeIf { it.isNotBlank() }?.let { setPackage(it) }
                putExtra("type", "get_public_key")
                putExtra(
                    "permissions",
                    """[{"type":"sign_event","kind":1},{"type":"sign_event","kind":30078},{"type":"sign_event","kind":10002},{"type":"nip44_encrypt"},{"type":"nip44_decrypt"}]""",
                )
            }

        fun parsePublicKeyResult(data: Intent?, fallbackSignerPackage: String? = null): SignerPublicKeyRequestResult {
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
                ?: fallbackSignerPackage
            return SignerPublicKeyResponseParser.parse(
                result = result,
                pubkey = pubkey,
                npub = npub,
                signerPackage = signerPackage,
            )
        }
    }
}
