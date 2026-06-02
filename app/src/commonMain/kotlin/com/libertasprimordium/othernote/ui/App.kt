package com.libertasprimordium.othernote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.SessionAuthMethod
import com.libertasprimordium.othernote.domain.abbreviatedNpub
import com.libertasprimordium.othernote.security.SavedNsecIdentity
import com.libertasprimordium.othernote.security.SavedNip46SessionMetadata
import com.libertasprimordium.othernote.security.SavedNip55SessionMetadata
import com.libertasprimordium.othernote.util.MarkdownBlock
import com.libertasprimordium.othernote.util.detectUrls
import com.libertasprimordium.othernote.util.formatNoteCardUpdatedAt
import com.libertasprimordium.othernote.util.markdownBlocks
import com.libertasprimordium.othernote.util.truncateMarkdown
import kotlinx.coroutines.launch

sealed class Screen {
    data object Login : Screen()
    data object List : Screen()
    data class Display(val note: Note) : Screen()
    data class Edit(val note: Note?) : Screen()
    data object Settings : Screen()
}

@Composable
fun OtherNoteApp(services: AppServices = defaultAppServices()) {
    val appState = remember(services) { AppState(services) }
    OtherNoteTheme {
        var screen by remember { mutableStateOf<Screen>(Screen.Login) }
        val mode by appState.mode.collectAsState()
        if (mode == AppMode.SignedOut && screen !is Screen.Login) screen = Screen.Login
        when (val current = screen) {
            Screen.Login -> LoginScreen(appState) { screen = Screen.List }
            Screen.List -> NotesListScreen(
                appState,
                onOpen = { screen = Screen.Display(it) },
                onEdit = { screen = Screen.Edit(it) },
                onNew = { screen = Screen.Edit(null) },
                onSettings = { screen = Screen.Settings },
            )
            is Screen.Display -> NoteDisplayScreen(appState, current.note, onBack = { screen = Screen.List }, onEdit = { screen = Screen.Edit(current.note) })
            is Screen.Edit -> NoteEditScreen(appState, current.note, onDone = { screen = Screen.List })
            Screen.Settings -> RelaySettingsScreen(appState, onBack = { screen = Screen.List })
        }
    }
}

@Composable
fun LoginScreen(appState: AppState, onLoggedIn: () -> Unit) {
    val message by appState.message.collectAsState()
    val mode by appState.mode.collectAsState()
    val generatedIdentity by appState.generatedIdentityState.collectAsState()
    val savedIdentities by appState.savedIdentityState.collectAsState()
    val keyringSaveConfirmation by appState.keyringSaveConfirmationState.collectAsState()
    val remoteSignerPairing by appState.remoteSignerPairingState.collectAsState()
    val savedRemoteSigners by appState.savedRemoteSignerState.collectAsState()
    val savedAndroidSigners by appState.savedAndroidSignerState.collectAsState()
    val scope = rememberCoroutineScope()
    var nsec by remember { mutableStateOf("") }
    var bunkerToken by remember { mutableStateOf("") }
    val signInOptions = appState.signInOptions
    val androidSignerOption = signInOptions.firstOrNull { it.kind == SignInOptionKind.AndroidSigner }
    val remoteSignerOption = signInOptions.firstOrNull { it.kind == SignInOptionKind.RemoteSigner }
    val nsecOption = signInOptions.first { it.kind == SignInOptionKind.ExistingNsec }
    val generatedIdentityOption = signInOptions.first { it.kind == SignInOptionKind.CreateIdentity }
    LaunchedEffect(mode) {
        when (mode) {
            AppMode.Authenticated -> {
                onLoggedIn()
                val authMethod = appState.session.value?.authMethod
                if (appState.directRelayRuntimeAvailable ||
                    authMethod == SessionAuthMethod.ExternalSigner ||
                    authMethod == SessionAuthMethod.RemoteSigner
                ) {
                    appState.startSync()
                }
            }
            AppMode.LocalOnly -> onLoggedIn()
            AppMode.SignedOut -> Unit
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(OtherNoteBlack).verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("Other Note", color = OtherNoteText, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Private Nostr-backed notes", color = OtherNoteMuted)
        Spacer(Modifier.height(24.dp))

        if (appState.platform == AppPlatform.Desktop) {
            SignInSectionTitle("Saved identities")
            SignInSupportingText("Stored in your desktop keyring. This is device-local and not a backup.")
            Spacer(Modifier.height(8.dp))
            when {
                !appState.secureSecretStoreAvailable -> SignInSupportingText(appState.secureSecretStoreStatus)
                savedIdentities.loading -> SignInSupportingText("Checking desktop keyring...")
                savedIdentities.error != null -> SignInSupportingText(savedIdentities.error.orEmpty())
                savedIdentities.identities.isEmpty() -> SignInSupportingText("No saved identities on this device yet.")
                else -> savedIdentities.identities.forEach { identity ->
                    SavedIdentityRow(
                        identity = identity,
                        onContinue = {
                            scope.launch {
                                appState.loginWithSavedIdentity(identity.accountPubkey)
                            }
                        },
                        onForget = {
                            scope.launch {
                                appState.forgetSavedIdentity(identity.accountPubkey)
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (appState.platform == AppPlatform.Android && (androidSignerOption != null || appState.savedAndroidSignerAvailable)) {
            SignInSectionTitle("Recommended")
            SignInSupportingText(androidSignerOption?.supportingCopy ?: "Use an Android signer so Other Note never stores your nsec.")
            SignInSupportingText("Your private key stays in your signer app. Other Note can remember this signer so you can continue without selecting it every time.")
            Text(appState.externalSignerStatus, color = OtherNoteMuted, fontSize = 12.sp)
            if (appState.savedAndroidSignerAvailable) {
                Spacer(Modifier.height(8.dp))
                Text("Saved Android signers", color = OtherNoteText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                when {
                    savedAndroidSigners.loading -> SignInSupportingText("Checking saved Android signers...")
                    savedAndroidSigners.error != null -> SignInSupportingText(savedAndroidSigners.error.orEmpty())
                    savedAndroidSigners.sessions.isEmpty() -> SignInSupportingText("No saved Android signer on this device yet.")
                    else -> savedAndroidSigners.sessions.forEach { saved ->
                        Spacer(Modifier.height(8.dp))
                        SavedAndroidSignerRow(
                            session = saved,
                            onContinue = {
                                scope.launch {
                                    appState.loginWithSavedAndroidSigner(saved.userPubkey)
                                }
                            },
                            onForget = {
                                scope.launch {
                                    appState.forgetSavedAndroidSigner(saved.userPubkey)
                                }
                            },
                        )
                    }
                }
                SignInSupportingText("Forgetting the signer removes only this app's saved Android signer connection from this device.")
            }
            androidSignerOption?.let { option ->
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { appState.requestExternalSignerPublicKey() },
                    enabled = option.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (savedAndroidSigners.sessions.isEmpty()) option.label else "Choose Android signer")
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        remoteSignerOption?.let { option ->
            SignInSectionTitle("Remote signer")
            SignInSupportingText(option.supportingCopy)
            SignInSupportingText("Your private key stays in the signer. Other Note sends encrypted requests through signer relays after you approve the connection.")
            Spacer(Modifier.height(6.dp))
            RemoteSignerPermissionSummary()
            Text(appState.remoteSignerStatus, color = OtherNoteMuted, fontSize = 12.sp)
            if (appState.savedRemoteSignerAvailable) {
                Spacer(Modifier.height(8.dp))
                Text("Saved remote signers", color = OtherNoteText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                when {
                    savedRemoteSigners.loading -> SignInSupportingText("Checking saved remote signers...")
                    savedRemoteSigners.error != null -> SignInSupportingText(savedRemoteSigners.error.orEmpty())
                    savedRemoteSigners.sessions.isEmpty() -> SignInSupportingText("No saved remote signer sessions on this device yet.")
                    else -> savedRemoteSigners.sessions.forEach { saved ->
                        Spacer(Modifier.height(8.dp))
                        SavedRemoteSignerRow(
                            session = saved,
                            onContinue = {
                                scope.launch {
                                    appState.loginWithSavedRemoteSigner(saved.userPubkey)
                                }
                            },
                            onForget = {
                                scope.launch {
                                    appState.forgetSavedRemoteSigner(saved.userPubkey)
                                }
                            },
                            enabled = !remoteSignerPairing.inProgress,
                        )
                    }
                }
                SignInSupportingText("Other Note stores a reusable remote-signer session so you do not have to pair every time. Forgetting it removes this app's remote-signer session from this device.")
            } else {
                Spacer(Modifier.height(6.dp))
                SignInSupportingText(appState.savedRemoteSignerStatus)
            }
            Spacer(Modifier.height(8.dp))
            Text("Pair a new remote signer", color = OtherNoteText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = bunkerToken,
                onValueChange = { bunkerToken = it },
                label = { Text("Paste bunker:// remote signer token") },
                singleLine = true,
                enabled = !remoteSignerPairing.inProgress,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (appState.startRemoteSignerConnection(bunkerToken)) {
                        bunkerToken = ""
                    }
                },
                enabled = option.enabled && !remoteSignerPairing.inProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (remoteSignerPairing.inProgress) "Waiting for signer..." else option.label)
            }
            RemoteSignerPairingStatus(remoteSignerPairing)
            Spacer(Modifier.height(18.dp))
        }

        SignInSectionTitle("Use existing nsec")
        SignInSupportingText(nsecOption.supportingCopy)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = nsec,
            onValueChange = { nsec = it },
            label = { Text("Paste nsec for this session") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (appState.login(nsec)) {
                    nsec = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(nsecOption.label)
        }
        if (appState.platform == AppPlatform.Desktop) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    appState.requestExistingNsecKeyringSaveConfirmation()
                },
                enabled = nsec.isNotBlank() && appState.secureSecretStoreAvailable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save to this device's keyring")
            }
            SignInSupportingText(
                if (appState.secureSecretStoreAvailable) {
                    KeyringSaveWarningCopy.description
                } else {
                    appState.secureSecretStoreStatus
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        SignInSupportingText(generatedIdentityOption.supportingCopy)
        TextButton(onClick = { appState.startGeneratedIdentityFlow() }) {
            Text(generatedIdentityOption.label)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            nsec = ""
            appState.continueLocalOnly()
        }) {
            Text("Continue local-only")
        }
        Spacer(Modifier.height(12.dp))
        Text(message, color = OtherNoteMuted)
    }
    when (generatedIdentity.step) {
        GeneratedIdentityStep.Idle -> Unit
        GeneratedIdentityStep.Explanation -> GeneratedIdentityExplanationDialog(appState, generatedIdentity)
        GeneratedIdentityStep.Generated -> GeneratedIdentityRevealDialog(appState, generatedIdentity)
    }
    keyringSaveConfirmation.source?.let { source ->
        KeyringSaveConfirmationDialog(
            onCancel = { appState.cancelKeyringSaveConfirmation() },
            onConfirm = {
                scope.launch {
                    val saved = when (source) {
                        KeyringSaveConfirmationSource.ExistingNsec ->
                            appState.confirmExistingNsecKeyringSave(nsec)
                        KeyringSaveConfirmationSource.GeneratedIdentity ->
                            appState.confirmGeneratedIdentityKeyringSave()
                    }
                    if (saved && source == KeyringSaveConfirmationSource.ExistingNsec) {
                        nsec = ""
                    }
                }
            },
        )
    }
}

@Composable
private fun SavedIdentityRow(
    identity: SavedNsecIdentity,
    onContinue: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OtherNotePanel),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(identity.safeDisplayName(), color = OtherNoteText, fontWeight = FontWeight.Bold)
            Text("Saved in desktop keyring", color = OtherNoteMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Continue with saved key")
            }
            TextButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Text("Forget from this device")
            }
        }
    }
}

@Composable
private fun SavedAndroidSignerRow(
    session: SavedNip55SessionMetadata,
    onContinue: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OtherNotePanel),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(session.safeDisplayName(), color = OtherNoteText, fontWeight = FontWeight.Bold)
            Text(session.signerLabel?.takeIf { it.isNotBlank() } ?: session.signerPackage.safePrefix(), color = OtherNoteMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Continue with Android signer")
            }
            TextButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Text("Forget Android signer")
            }
        }
    }
}

@Composable
private fun SavedRemoteSignerRow(
    session: SavedNip46SessionMetadata,
    onContinue: () -> Unit,
    onForget: () -> Unit,
    enabled: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OtherNotePanel),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(session.safeDisplayName(), color = OtherNoteText, fontWeight = FontWeight.Bold)
            Text("Remote signer ${session.remoteSignerPubkey.safePrefix()}", color = OtherNoteMuted, fontSize = 12.sp)
            Text("${session.relays.size} signer relay(s)", color = OtherNoteMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onContinue, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Continue with remote signer")
            }
            TextButton(onClick = onForget, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Forget remote signer")
            }
        }
    }
}

@Composable
private fun SignInSectionTitle(text: String) {
    Text(text, color = OtherNoteText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun SignInSupportingText(text: String) {
    Text(text, color = OtherNoteMuted, fontSize = 13.sp)
}

@Composable
private fun RemoteSignerPermissionSummary() {
    Column(
        Modifier.fillMaxWidth()
            .background(OtherNotePanel, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text("Other Note will ask the signer to:", color = OtherNoteText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("Read your public key", color = OtherNoteMuted, fontSize = 12.sp)
        Text("Encrypt and decrypt your notes", color = OtherNoteMuted, fontSize = 12.sp)
        Text("Sign encrypted note and relay-list events", color = OtherNoteMuted, fontSize = 12.sp)
    }
}

@Composable
private fun RemoteSignerPairingStatus(state: RemoteSignerPairingState) {
    if (state.stage == RemoteSignerPairingStage.Idle) {
        return
    }
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier.fillMaxWidth()
            .background(OtherNotePanel, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(state.title, color = OtherNoteText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(state.message, color = OtherNoteMuted, fontSize = 12.sp)
        if (state.authUrlAvailable) {
            Spacer(Modifier.height(4.dp))
            Text("Some signers require an approval page. Other Note will not open it automatically.", color = OtherNoteMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GeneratedIdentityExplanationDialog(
    appState: AppState,
    generatedIdentity: GeneratedIdentityState,
) {
    AlertDialog(
        onDismissRequest = { appState.cancelGeneratedIdentityFlow() },
        title = { Text("Create new identity") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("This creates a new Nostr private key.")
                Spacer(Modifier.height(8.dp))
                Text("Other Note cannot recover this key for you. If you lose the nsec, you lose access to encrypted notes for this identity.")
                Spacer(Modifier.height(8.dp))
                Text("Save the nsec in a secure password manager, OS credential store, or import it into a signer such as Amber. Other Note will not store the plaintext nsec.")
                Spacer(Modifier.height(8.dp))
                Text("Users who do not want to manage an nsec directly should use Android signer, remote signer, or future OS credential storage.")
                generatedIdentity.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = OtherNotePurple)
                }
            }
        },
        confirmButton = {
            Button(onClick = { appState.generateFreshIdentity() }) {
                Text("Generate nsec")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { appState.cancelGeneratedIdentityFlow() }) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun GeneratedIdentityRevealDialog(
    appState: AppState,
    generatedIdentity: GeneratedIdentityState,
) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { appState.cancelGeneratedIdentityFlow() },
        title = { Text("Save this nsec") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("This nsec is your private key. Other Note will use it only for this session and will not store it.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = generatedIdentity.npub,
                    onValueChange = {},
                    label = { Text("Public key (npub)") },
                    readOnly = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = generatedIdentity.nsecForDisplay(),
                    onValueChange = {},
                    label = { Text("Private key (nsec)") },
                    readOnly = true,
                    singleLine = true,
                    visualTransformation = if (generatedIdentity.nsecRevealed) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { appState.toggleGeneratedIdentityNsecReveal() }) {
                    Text(if (generatedIdentity.nsecRevealed) "Hide nsec" else "Reveal nsec")
                }
                Text("Copy is not automatic. If you manually copy or screenshot this key, the OS or other apps may retain it.")
                Spacer(Modifier.height(8.dp))
                AcknowledgementRow(
                    checked = generatedIdentity.savedAcknowledged,
                    onCheckedChange = appState::acknowledgeGeneratedIdentitySaved,
                    text = "I saved this nsec somewhere secure.",
                )
                AcknowledgementRow(
                    checked = generatedIdentity.lossAcknowledged,
                    onCheckedChange = appState::acknowledgeGeneratedIdentityLossRisk,
                    text = "I understand losing this nsec means losing access to this identity's encrypted notes.",
                )
                if (appState.platform == AppPlatform.Desktop) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            appState.requestGeneratedIdentityKeyringSaveConfirmation()
                        },
                        enabled = generatedIdentity.canUseForSession && appState.secureSecretStoreAvailable,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save to desktop keyring")
                    }
                    Text(
                        if (appState.secureSecretStoreAvailable) {
                            KeyringSaveWarningCopy.description
                        } else {
                            appState.secureSecretStoreStatus
                        },
                        color = OtherNoteMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { appState.useGeneratedIdentityForSession() },
                enabled = generatedIdentity.canUseForSession,
            ) {
                Text("Use for this session")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { appState.cancelGeneratedIdentityFlow() }) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun KeyringSaveConfirmationDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(KeyringSaveWarningCopy.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(KeyringSaveWarningCopy.body)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Save to keyring")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
    )
}

private fun SavedNsecIdentity.safeDisplayName(): String =
    when {
        npub.isNotBlank() -> npub.safePrefix()
        accountPubkey.isNotBlank() -> "pubkey ${accountPubkey.safePrefix()}"
        else -> "Saved identity"
    }

private fun SavedNip46SessionMetadata.safeDisplayName(): String =
    when {
        userNpub.isNotBlank() -> userNpub.safePrefix()
        userPubkey.isNotBlank() -> "pubkey ${userPubkey.safePrefix()}"
        else -> "Saved remote signer"
    }

private fun SavedNip55SessionMetadata.safeDisplayName(): String =
    when {
        userNpub.isNotBlank() -> userNpub.safePrefix()
        userPubkey.isNotBlank() -> "pubkey ${userPubkey.safePrefix()}"
        else -> "Saved Android signer"
    }

private fun String.safePrefix(): String =
    if (length <= 16) this else "${take(12)}..."

@Composable
private fun AcknowledgementRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text, color = OtherNoteMuted)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesListScreen(
    appState: AppState,
    onOpen: (Note) -> Unit,
    onEdit: (Note) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
) {
    val notes by appState.notes.notes.collectAsState()
    val session by appState.session.collectAsState()
    val profileState by appState.profileState.collectAsState()
    val message by appState.message.collectAsState()
    val diagnostics by appState.diagnosticMessage.collectAsState()
    val scope = rememberCoroutineScope()
    var notePendingDelete by remember { mutableStateOf<Note?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Other Note") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OtherNotePurpleDark, titleContentColor = OtherNoteText),
                actions = {
                    TextButton(onClick = { scope.launch { appState.sync() } }) { Text("Sync") }
                    TextButton(onClick = { scope.launch { appState.logout() } }) { Text("Logout") }
                    TextButton(onClick = onSettings) { Text("Relays") }
                },
            )
        },
        containerColor = OtherNoteBlack,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            AccountIdentityHeader(session = session, profileState = profileState)
            if (appState.runtimeMode == AppRuntimeMode.DesktopRelay || appState.runtimeMode == AppRuntimeMode.DesktopDevRelay) {
                Text("Desktop relay runtime", color = OtherNotePurple)
            }
            Text(message, color = OtherNoteMuted)
            if ((appState.showRelayDiagnostics || appState.showNip55Diagnostics) && diagnostics.isNotBlank()) {
                Text(diagnostics, color = OtherNoteMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("New note") }
            Spacer(Modifier.height(12.dp))
            if (notes.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
                    Text("No notes yet", color = OtherNoteMuted)
                }
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val columns = noteGridColumnCount(maxWidth.value.toInt())
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        verticalItemSpacing = 8.dp,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(notes, key = { it.id }) { note ->
                            NoteCard(
                                note = note,
                                platform = appState.platform,
                                onOpen = onOpen,
                                onEdit = onEdit,
                                onDeleteRequest = { notePendingDelete = it },
                            )
                        }
                    }
                }
            }
        }
    }
    notePendingDelete?.let { note ->
        NoteDeleteConfirmationDialog(
            onCancel = { notePendingDelete = null },
            onConfirm = {
                scope.launch {
                    appState.delete(note)
                    notePendingDelete = null
                }
            },
        )
    }
}

@Composable
private fun AccountIdentityHeader(
    session: com.libertasprimordium.othernote.domain.UserSession?,
    profileState: ProfileUiState,
) {
    if (session == null) {
        Text("Local-only session", color = OtherNoteMuted)
        return
    }
    val profile = profileState.metadata?.takeIf { it.pubkey == session.publicKeyHex }
    val fallback = session.abbreviatedNpub()
    val primary = profile?.bestName?.takeIf { it.isNotBlank() } ?: fallback
    Text(
        primary,
        color = OtherNoteText,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(fallback, color = OtherNoteMuted, fontSize = 12.sp)
    when {
        profile?.nip05?.isNotBlank() == true -> Text(profile.nip05, color = OtherNoteMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        profile?.website?.isNotBlank() == true -> Text(profile.website, color = OtherNoteMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        profileState.loading && profile == null -> Text("Loading profile...", color = OtherNoteMuted, fontSize = 12.sp)
    }
    profile?.about?.takeIf { it.isNotBlank() }?.let {
        Text(it, color = OtherNoteMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    platform: AppPlatform,
    onOpen: (Note) -> Unit,
    onEdit: (Note) -> Unit,
    onDeleteRequest: (Note) -> Unit,
) {
    val actions = noteCardActionItems().associateBy { it.action }
    val openAction = actions.getValue(NoteCardAction.Open)
    val editAction = actions.getValue(NoteCardAction.Edit)
    val deleteAction = actions.getValue(NoteCardAction.Delete)
    var showActions by remember(note.id) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val actionPresentation = noteCardActionPresentation(platform, maxWidth.value.toInt())
        val cardModifier = when (actionPresentation) {
            NoteCardActionPresentation.VisibleButtons -> Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = openAction.accessibilityLabel,
                    role = Role.Button,
                ) { onOpen(note) }
            NoteCardActionPresentation.LongPressMenu -> Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = openAction.accessibilityLabel,
                    onLongClickLabel = "Show note actions",
                    role = Role.Button,
                    onClick = { onOpen(note) },
                    onLongClick = { showActions = true },
                )
        }
        Card(
            modifier = cardModifier,
            colors = CardDefaults.cardColors(containerColor = OtherNotePanel),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(truncateMarkdown(note.bodyMarkdown).ifBlank { "Untitled note" }, color = OtherNoteText)
                Text(formatNoteCardUpdatedAt(note.updatedAtMs), color = OtherNoteMuted, fontSize = 12.sp)
                if (actionPresentation == NoteCardActionPresentation.VisibleButtons) {
                    Spacer(Modifier.height(8.dp))
                    NoteCardActionButtons(
                        editAction = editAction,
                        deleteAction = deleteAction,
                        onEdit = { onEdit(note) },
                        onDeleteRequest = { onDeleteRequest(note) },
                    )
                }
            }
        }
        if (showActions) {
            NoteCardActionMenu(
                editAction = editAction,
                deleteAction = deleteAction,
                onDismiss = { showActions = false },
                onEdit = {
                    showActions = false
                    onEdit(note)
                },
                onDeleteRequest = {
                    showActions = false
                    onDeleteRequest(note)
                },
            )
        }
    }
}

@Composable
private fun NoteCardActionMenu(
    editAction: NoteCardActionItem,
    deleteAction: NoteCardActionItem,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val menuText = noteCardActionMenuText()
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .padding(horizontal = 12.dp),
            colors = CardDefaults.cardColors(containerColor = OtherNotePanel),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    menuText.title,
                    color = OtherNoteText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 10.dp),
                )
                Button(
                    onClick = onEdit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .semantics { contentDescription = editAction.accessibilityLabel },
                ) {
                    Text(editAction.label)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onDeleteRequest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .semantics { contentDescription = deleteAction.accessibilityLabel },
                ) {
                    Text(deleteAction.label)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                ) {
                    Text(menuText.cancelLabel)
                }
            }
        }
    }
}

@Composable
private fun NoteCardActionButtons(
    editAction: NoteCardActionItem,
    deleteAction: NoteCardActionItem,
    onEdit: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 180.dp) {
            Column(Modifier.fillMaxWidth()) {
                NoteCardActionButton(editAction, onEdit, Modifier.fillMaxWidth())
                NoteCardActionButton(deleteAction, onDeleteRequest, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                NoteCardActionButton(editAction, onEdit)
                NoteCardActionButton(deleteAction, onDeleteRequest)
            }
        }
    }
}

@Composable
private fun NoteCardActionButton(
    action: NoteCardActionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = action.accessibilityLabel },
    ) {
        Text(action.label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDisplayScreen(appState: AppState, note: Note, onBack: () -> Unit, onEdit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val message by appState.message.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OtherNotePurpleDark, titleContentColor = OtherNoteText),
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = onEdit) { Text("Edit") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                },
            )
        },
        containerColor = OtherNoteBlack,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (message.isNotBlank()) {
                Text(message, color = OtherNoteMuted, modifier = Modifier.padding(bottom = 12.dp))
            }
            RenderMarkdown(note.bodyMarkdown)
            detectUrls(note.bodyMarkdown).forEach { url ->
                Text("${url.type}: ${url.value}", color = OtherNotePurple, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (confirmDelete) {
        NoteDeleteConfirmationDialog(
            onCancel = { confirmDelete = false },
            onConfirm = {
                scope.launch {
                    val deleted = appState.delete(note)
                    confirmDelete = false
                    if (deleted) {
                        onBack()
                    }
                }
            },
        )
    }
}

@Composable
private fun NoteDeleteConfirmationDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val text = noteDeleteConfirmationText()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text.title) },
        text = { Text(text.body) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = "Confirm delete note" },
            ) {
                Text(text.deleteLabel)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = "Cancel delete note" },
            ) {
                Text(text.cancelLabel)
            }
        },
    )
}

@Composable
fun RenderMarkdown(markdown: String) {
    markdownBlocks(markdown).forEach { block ->
        when (block) {
            is MarkdownBlock.Heading -> Text(block.text, color = OtherNoteText, fontSize = (28 - block.level * 2).sp, fontWeight = FontWeight.Bold)
            is MarkdownBlock.Paragraph -> Text(block.text, color = OtherNoteText, modifier = Modifier.padding(bottom = 10.dp))
            is MarkdownBlock.CodeBlock -> Text(block.code, color = Color(0xFFE8D7FF), fontFamily = FontFamily.Monospace, modifier = Modifier.background(Color(0xFF191020)).padding(10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(appState: AppState, note: Note?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saveState by appState.editorSaveState.collectAsState()
    var markdown by remember(note?.id) { mutableStateOf(note?.bodyMarkdown.orEmpty()) }
    LaunchedEffect(note?.id) {
        appState.clearEditorSaveState()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (note == null) "New note" else "Edit note") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OtherNotePurpleDark, titleContentColor = OtherNoteText),
                navigationIcon = {
                    TextButton(onClick = {
                        appState.clearEditorSaveState()
                        onDone()
                    }) { Text("Cancel") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (saveState.inProgress) return@TextButton
                            scope.launch {
                                if (appState.saveFromEditor(note, markdown)) onDone()
                            }
                        },
                        enabled = !saveState.inProgress,
                    ) {
                        Text(if (saveState.inProgress) "Saving..." else "Save")
                    }
                },
            )
        },
        containerColor = OtherNoteBlack,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            when {
                saveState.error != null -> {
                    Text(saveState.error.orEmpty(), color = OtherNotePurple, modifier = Modifier.padding(bottom = 8.dp))
                }
                saveState.inProgress || saveState.message.isNotBlank() -> {
                    Text(saveState.message, color = OtherNoteMuted, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            OutlinedTextField(
                value = markdown,
                onValueChange = {
                    markdown = it
                    if (saveState.error != null) {
                        appState.clearEditorSaveState()
                    }
                },
                modifier = Modifier.fillMaxSize(),
                label = { Text("Markdown") },
                enabled = !saveState.inProgress,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsScreen(appState: AppState, onBack: () -> Unit) {
    val relays by appState.relaySettings.relays.collectAsState()
    val syncState by appState.syncState.collectAsState()
    val session by appState.session.collectAsState()
    val relayAddState by appState.relayAddTestState.collectAsState()
    val relayMigrationState by appState.relayMigrationState.collectAsState()
    val scope = rememberCoroutineScope()
    var draftRelays by remember(relays) { mutableStateOf(relays.map { it.url }) }
    var relayToAdd by remember { mutableStateOf("") }
    var relayError by remember { mutableStateOf<String?>(null) }
    var pendingPublishedRelays by remember { mutableStateOf<RelaySettingsRefreshResult.PublishedListAvailable?>(null) }
    fun refreshPublishedRelayList() {
        scope.launch {
            when (val result = appState.refreshPublishedRelayListForSettings()) {
                is RelaySettingsRefreshResult.PublishedListAvailable -> {
                    val savedRelays = appState.relaySettings.normalizedUrls()
                    if (draftRelays == savedRelays) {
                        if (appState.applyPublishedRelayListFromSettings(result.relays)) {
                            draftRelays = result.relays
                            relayError = null
                        } else {
                            relayError = appState.message.value
                        }
                    } else {
                        pendingPublishedRelays = result
                    }
                }
                is RelaySettingsRefreshResult.Failed -> relayError = result.safeReason
                is RelaySettingsRefreshResult.NoChange -> Unit
                is RelaySettingsRefreshResult.Skipped -> Unit
            }
        }
    }
    LaunchedEffect(Unit) {
        refreshPublishedRelayList()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relays") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OtherNotePurpleDark, titleContentColor = OtherNoteText),
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(
                        onClick = { refreshPublishedRelayList() },
                        enabled = !relayAddState.inProgress && !relayMigrationState.inProgress,
                    ) { Text("Refresh") }
                },
            )
        },
        containerColor = OtherNoteBlack,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("App relays", color = OtherNoteText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("These relays sync encrypted note events. They do not change NIP-46 remote-signer transport relays.", color = OtherNoteMuted)
            Spacer(Modifier.height(6.dp))
            Text("Other Note publishes encrypted kind 30078 note events only. At least one readable and writable relay is needed for relay sync and publishing.", color = OtherNoteMuted)
            Spacer(Modifier.height(6.dp))
            Text("When signed in, relay changes publish public kind 10002 relay-list metadata for write relays while preserving other categories where possible.", color = OtherNoteMuted)
            Spacer(Modifier.height(6.dp))
            Text("Public relays may purge old events. Add a relay you control for stronger long-term retention.", color = OtherNoteMuted)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = relayToAdd,
                onValueChange = {
                    relayToAdd = it
                    relayError = null
                },
                label = { Text("Add relay hostname or URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !relayAddState.inProgress,
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        scope.launch {
                            when (val result = appState.testRelayBeforeAdd(relayToAdd, draftRelays)) {
                                is RelayAddResult.Added -> {
                                    draftRelays = (draftRelays + result.relayUrl).distinct()
                                    relayToAdd = ""
                                    relayError = null
                                }
                                is RelayAddResult.ValidationFailed -> relayError = result.message
                                is RelayAddResult.Duplicate -> relayError = "Relay already exists: ${result.relayUrl}"
                                RelayAddResult.WaitingForUserChoice -> relayError = null
                                RelayAddResult.InProgress -> relayError = "Relay test already in progress"
                            }
                        }
                    },
                    enabled = !relayAddState.inProgress && !relayMigrationState.inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (relayAddState.inProgress) "Testing relay..." else "Add relay") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        draftRelays = appState.defaultRelayUrls
                        relayError = "Default relays staged. Save to migrate and apply."
                    },
                    enabled = !relayMigrationState.inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restore defaults") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            if (appState.syncCurrentRelays()) {
                                relayError = null
                            } else {
                                relayError = appState.message.value
                            }
                        }
                    },
                    enabled = session != null &&
                        draftRelays == appState.relaySettings.normalizedUrls() &&
                        !relayAddState.inProgress &&
                        relayAddState.warning == null &&
                        !relayMigrationState.inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (relayMigrationState.inProgress) "Migrating..." else "Sync/Migrate") }
            }
            relayError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = OtherNotePurple)
            }
            Spacer(Modifier.height(16.dp))
            if (draftRelays.isEmpty()) {
                Text("No app relays configured. Relay sync and publishing require at least one valid wss:// relay.", color = OtherNotePurple)
            } else {
                draftRelays.forEachIndexed { index, relay ->
                    RelaySettingsRow(
                        relay = relay,
                        status = syncState.relayStatuses.firstOrNull { it.url == relay }?.message,
                        onRemove = {
                            draftRelays = draftRelays.toMutableList().also { it.removeAt(index) }
                            relayError = null
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = {
                        scope.launch {
                            if (appState.saveRelays(draftRelays)) {
                                relayError = null
                                onBack()
                            } else {
                                relayError = appState.message.value
                            }
                        }
                    },
                    enabled = draftRelays.isNotEmpty() && !relayMigrationState.inProgress,
                ) { Text(if (relayMigrationState.inProgress) "Migrating..." else "Save") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onBack, enabled = !relayMigrationState.inProgress) { Text("Cancel") }
            }
        }
    }
    relayAddState.warning?.let { warning ->
        AlertDialog(
            onDismissRequest = { appState.cancelFailedRelayAdd() },
            title = { Text("Relay test failed") },
            text = {
                Column {
                    Text("This relay may not work for syncing notes.")
                    Spacer(Modifier.height(8.dp))
                    Text(warning.safeReason)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val relay = appState.continueFailedRelayAdd()
                        if (relay != null && relay !in draftRelays) {
                            draftRelays = (draftRelays + relay).distinct()
                            relayToAdd = ""
                            relayError = null
                        }
                    },
                ) { Text("Continue") }
            },
            dismissButton = {
                OutlinedButton(onClick = { appState.cancelFailedRelayAdd() }) {
                    Text("Cancel")
                }
            },
        )
    }
    relayMigrationState.warning?.let { warning ->
        AlertDialog(
            onDismissRequest = { appState.cancelRelayMigrationWarning() },
            title = { Text(warning.title) },
            text = {
                Text(warning.body)
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            if (appState.continueRelayMigration()) {
                                relayError = null
                                onBack()
                            } else {
                                relayError = appState.message.value
                            }
                        }
                    },
                ) { Text("Continue") }
            },
            dismissButton = {
                OutlinedButton(onClick = { appState.cancelRelayMigrationWarning() }) {
                    Text("Cancel")
                }
            },
        )
    }
    pendingPublishedRelays?.let { published ->
        AlertDialog(
            onDismissRequest = { pendingPublishedRelays = null },
            title = { Text("Reload published relays?") },
            text = {
                Column {
                    Text("A published relay list was found, but you have unsaved local relay edits.")
                    Spacer(Modifier.height(8.dp))
                    Text(published.safeSummary)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            if (appState.applyPublishedRelayListFromSettings(published.relays)) {
                                draftRelays = published.relays
                                relayError = null
                                pendingPublishedRelays = null
                            } else {
                                relayError = appState.message.value
                            }
                        }
                    },
                ) { Text("Reload published list") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingPublishedRelays = null }) {
                    Text("Keep my edits")
                }
            },
        )
    }
}

@Composable
private fun RelaySettingsRow(
    relay: String,
    status: String?,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OtherNotePanel),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(relay, color = OtherNoteText)
            status?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(readableRelayStatusText(it), color = OtherNoteMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRemove) {
                Text("Remove")
            }
        }
    }
}

private fun readableRelayStatusText(raw: String): String {
    val lower = raw.lowercase()
    val fetchedCount = Regex("""with (\d+) event""").find(raw)?.groupValues?.getOrNull(1)
    return when {
        raw.contains("stage=") || raw.contains("outcome=") -> when {
            lower.contains("rejected") -> "Rejected writes"
            lower.contains("timeout") -> "Timed out"
            lower.contains("fetch") && lower.contains("complete") && fetchedCount != null -> "Fetched $fetchedCount events"
            lower.contains("fetch") && lower.contains("complete") -> "Fetch completed"
            lower.contains("publish") && lower.contains("accepted") -> "Published all events"
            lower.contains("fetch") -> "Could not fetch"
            else -> "Checked"
        }
        raw.contains("publish_accepted_count") || raw.contains("candidate_events") -> "Checked"
        else -> raw.take(160)
    }
}
