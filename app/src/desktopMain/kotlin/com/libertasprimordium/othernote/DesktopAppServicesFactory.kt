package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.security.DesktopSecureSecretStore
import com.libertasprimordium.othernote.security.nip46RemoteSigner
import com.libertasprimordium.othernote.ui.AppPlatform
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices

object DesktopAppServicesFactory {
    fun create(): AppServices {
        val crypto = ProductionNostrCryptoFactory.createOrNull()
        return if (crypto == null) {
            AppServices(
                mode = AppRuntimeMode.Offline,
                platform = AppPlatform.Desktop,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                showRelayDiagnostics = isRelayDiagnosticsEnabled(),
                secureSecretStore = DesktopSecureSecretStore(),
                localEventCache = DesktopLocalEventCache(),
                pendingWriteStore = DesktopPendingWriteStore(),
                startupWarnings = listOf(ProductionNostrCryptoFactory.unavailableReason),
            )
        } else {
            val relayClient = DesktopNostrClient()
            val developerFlagEnabled = isDevRelayRuntimeEnabled()
            AppServices(
                mode = if (developerFlagEnabled) AppRuntimeMode.DesktopDevRelay else AppRuntimeMode.DesktopRelay,
                platform = AppPlatform.Desktop,
                crypto = crypto,
                client = relayClient,
                showRelayDiagnostics = isRelayDiagnosticsEnabled(),
                secureSecretStore = DesktopSecureSecretStore(),
                remoteSigner = relayClient.nip46RemoteSigner(),
                localEventCache = DesktopLocalEventCache(),
                pendingWriteStore = DesktopPendingWriteStore(),
                startupWarnings = listOf(
                    if (developerFlagEnabled) {
                        "Desktop relay runtime enabled; developer relay flag is no longer required"
                    } else {
                        "Desktop relay runtime enabled"
                    },
                ),
            )
        }
    }

    fun isDevRelayRuntimeEnabled(): Boolean =
        System.getenv("OTHER_NOTE_ENABLE_DEV_RELAY_RUNTIME") == "1" ||
            System.getProperty("othernote.devRelayRuntime") == "true"

    fun isRelayDiagnosticsEnabled(): Boolean =
        System.getenv("OTHER_NOTE_SHOW_RELAY_DIAGNOSTICS") == "1" ||
            System.getProperty("othernote.showRelayDiagnostics") == "true"
}
