package com.libertasprimordium.othernote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.security.AndroidExternalSignerProvider
import com.libertasprimordium.othernote.security.AndroidNip55EventSigner
import com.libertasprimordium.othernote.security.AndroidNip55Nip44Operator
import com.libertasprimordium.othernote.security.AndroidNip55PublicKeyRequester
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.OtherNoteApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val publicKeyRequester = AndroidNip55PublicKeyRequester()
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
        val services = AppServices(
            mode = AppRuntimeMode.Offline,
            crypto = NonProductionNostrCrypto(),
            client = OfflineNostrClient(),
            externalSignerProvider = AndroidExternalSignerProvider(this),
            externalSignerPublicKeyRequester = publicKeyRequester,
            externalSignerEventSigner = eventSigner,
            externalSignerNip44Operator = nip44Operator,
            showNip55Diagnostics = showNip55Diagnostics(),
            startupWarnings = listOf("Android runtime is offline; relay sync is disabled"),
        )
        setContent {
            OtherNoteApp(services)
        }
    }

    private fun showNip55Diagnostics(): Boolean =
        System.getProperty("othernote.showNip55Diagnostics") == "true" ||
            System.getenv("OTHER_NOTE_SHOW_NIP55_DIAGNOSTICS") == "1"
}
