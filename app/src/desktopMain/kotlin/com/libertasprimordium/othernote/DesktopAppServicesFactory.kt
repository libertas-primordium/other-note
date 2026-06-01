package com.libertasprimordium.othernote

import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.security.DesktopSecureSecretStore
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.defaultAppServices

object DesktopAppServicesFactory {
    fun create(): AppServices {
        if (!isDevRelayRuntimeEnabled()) return defaultAppServices()
        val crypto = ProductionNostrCryptoFactory.createOrNull()
        return if (crypto == null) {
            AppServices(
                mode = AppRuntimeMode.Offline,
                crypto = NonProductionNostrCrypto(),
                client = OfflineNostrClient(),
                showRelayDiagnostics = isRelayDiagnosticsEnabled(),
                secureSecretStore = DesktopSecureSecretStore(),
                startupWarnings = listOf(ProductionNostrCryptoFactory.unavailableReason),
            )
        } else {
            AppServices(
                mode = AppRuntimeMode.DesktopDevRelay,
                crypto = crypto,
                client = DesktopNostrClient(),
                showRelayDiagnostics = isRelayDiagnosticsEnabled(),
                secureSecretStore = DesktopSecureSecretStore(),
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
