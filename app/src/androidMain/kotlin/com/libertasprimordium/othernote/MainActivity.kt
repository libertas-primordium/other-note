package com.libertasprimordium.othernote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.libertasprimordium.othernote.nostr.NonProductionNostrCrypto
import com.libertasprimordium.othernote.nostr.OfflineNostrClient
import com.libertasprimordium.othernote.security.AndroidExternalSignerProvider
import com.libertasprimordium.othernote.ui.AppRuntimeMode
import com.libertasprimordium.othernote.ui.AppServices
import com.libertasprimordium.othernote.ui.OtherNoteApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val services = AppServices(
            mode = AppRuntimeMode.Offline,
            crypto = NonProductionNostrCrypto(),
            client = OfflineNostrClient(),
            externalSignerProvider = AndroidExternalSignerProvider(this),
            startupWarnings = listOf("Android runtime is offline; relay sync is disabled"),
        )
        setContent {
            OtherNoteApp(services)
        }
    }
}
