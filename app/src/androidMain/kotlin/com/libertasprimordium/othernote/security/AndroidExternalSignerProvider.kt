package com.libertasprimordium.othernote.security

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

class AndroidExternalSignerProvider(context: Context) : NostrSignerProvider {
    override val mode: SignerMode = SignerMode.ExternalSigner
    private val discovery = discoverSigner(context.applicationContext.packageManager)

    override val isAvailable: Boolean = discovery != null
    override val unavailableReason: String? = if (discovery == null) "No Android NIP-55 signer found." else null
    override val displayName: String? = discovery?.displayName
    override val canGetPublicKey: Boolean = discovery != null
    override val canSignEvent: Boolean = false
    override val canNip44EncryptDecrypt: Boolean = false
    override val safeDiagnostics: List<String> = listOf(
        if (discovery == null) {
            "NIP-55 discovery found 0 signer apps"
        } else {
            "NIP-55 discovery found signer ${discovery.displayName}"
        },
        "External signer login flow is not implemented yet",
    )

    companion object {
        fun discoverSigner(packageManager: PackageManager): AndroidSignerDiscovery? {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
            return resolveInfos
                .mapNotNull { resolveInfo ->
                    val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                    val label = resolveInfo.loadLabel(packageManager).toString()
                    val displayName = label.takeIf { it.isNotBlank() }
                        ?: activityInfo.packageName
                    AndroidSignerDiscovery(displayName = displayName)
                }
                .distinctBy { it.displayName }
                .sortedBy { it.displayName.lowercase() }
                .firstOrNull()
        }
    }
}

data class AndroidSignerDiscovery(
    val displayName: String,
)
