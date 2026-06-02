package com.libertasprimordium.othernote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.libertasprimordium.othernote.data.RelaySettingsStore
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.ProductionNostrCryptoFactory
import com.libertasprimordium.othernote.security.AndroidExternalSignerProvider
import com.libertasprimordium.othernote.security.AndroidNip55EventSigner
import com.libertasprimordium.othernote.security.AndroidNip55Nip44Operator
import com.libertasprimordium.othernote.security.AndroidNip55PublicKeyRequester
import com.libertasprimordium.othernote.security.nip46RemoteSigner
import com.libertasprimordium.othernote.ui.AppPlatform
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.OtherNoteApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val externalSignerProvider = AndroidExternalSignerProvider(this)
        val publicKeyRequester = AndroidNip55PublicKeyRequester(externalSignerProvider.signerPackage)
        val eventSigner = AndroidNip55EventSigner(this)
        val nip44Operator = AndroidNip55Nip44Operator(this)
        val publicKeyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            publicKeyRequester.handleActivityResult(result.resultCode, result.data)
        }
        val signEventLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            eventSigner.handleActivityResult(result.resultCode, result.data)
        }
        publicKeyRequester.attachLauncher(publicKeyLauncher)
        eventSigner.attachLauncher(signEventLauncher)
        val nostrClient = AndroidNostrClient()
        val crypto = ProductionNostrCryptoFactory.createOrNull() ?: NonProductionNostrCrypto()
        val services = AppServices(
            mode = AppRuntimeMode.Offline,
            platform = AppPlatform.Android,
            crypto = crypto,
            client = nostrClient,
            externalSignerProvider = externalSignerProvider,
            externalSignerPublicKeyRequester = publicKeyRequester,
            externalSignerEventSigner = eventSigner,
            externalSignerNip44Operator = nip44Operator,
            nip55SessionStore = AndroidNip55SessionStore(this),
            remoteSigner = nostrClient.nip46RemoteSigner(),
            nip46SessionStore = AndroidNip46SessionStore(this),
            localEventCache = AndroidLocalEventCache(this),
            pendingWriteStore = AndroidPendingWriteStore(this),
            relaySettings = RelaySettingsStore(persistence = AndroidRelaySettingsPersistence(this)),
            themePreferenceStore = AndroidThemePreferenceStore(this),
            showRelayDiagnostics = showRelayDiagnostics(),
            showNip55Diagnostics = showNip55Diagnostics(),
            startupWarnings = if (crypto.productionReady) {
                listOf("Android relay runtime enabled; direct nsec use is session-only and not persisted")
            } else {
                listOf("Android signer relay runtime enabled; direct nsec fallback remains local-only")
            },
        )
        setContent {
            OtherNoteApp(services)
        }
    }

    private fun showNip55Diagnostics(): Boolean =
        System.getProperty("othernote.showNip55Diagnostics") == "true" ||
            System.getenv("OTHER_NOTE_SHOW_NIP55_DIAGNOSTICS") == "1"

    private fun showRelayDiagnostics(): Boolean =
        System.getProperty("othernote.showRelayDiagnostics") == "true" ||
            System.getenv("OTHER_NOTE_SHOW_RELAY_DIAGNOSTICS") == "1"
}
