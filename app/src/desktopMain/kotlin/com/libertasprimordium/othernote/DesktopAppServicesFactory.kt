package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.security.DesktopSecureSecretStore
import com.libertasprimordium.othernote.ui.AppPlatform
import com.libertasprimordium.othernote.security.nip46RemoteSigner
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.defaultAppServices

object DesktopAppServicesFactory {
    fun create(): AppServices {
        if (!isDevRelayRuntimeEnabled()) return defaultAppServices(platform = AppPlatform.Desktop)
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
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                platform = AppPlatform.Desktop,
                crypto = crypto,
                client = relayClient,
                showRelayDiagnostics = isRelayDiagnosticsEnabled(),
                secureSecretStore = DesktopSecureSecretStore(),
                remoteSigner = relayClient.nip46RemoteSigner(),
                localEventCache = DesktopLocalEventCache(),
                pendingWriteStore = DesktopPendingWriteStore(),
                startupWarnings = listOf("Developer relay runtime enabled"),
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
