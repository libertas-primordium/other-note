package com.libertasprimordium.othernote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
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
import com.libertasprimordium.othernote.util.MarkdownSpan
import com.libertasprimordium.othernote.util.detectUrls
import com.libertasprimordium.othernote.util.filterVisibleNotesBySearchQuery
import com.libertasprimordium.othernote.util.formatNoteCardUpdatedAt
import com.libertasprimordium.othernote.util.markdownBlocks
import com.libertasprimordium.othernote.util.markdownSpans
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
    var infoTopic by remember { mutableStateOf<SignInInfoTopic?>(null) }
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
            SignInSectionHeader("Saved identities", SignInInfoTopic.DesktopKeyring) { infoTopic = it }
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
            SignInSectionHeader("Android signer", SignInInfoTopic.AndroidSigner) { infoTopic = it }
            Text(appState.externalSignerStatus, color = OtherNoteMuted, fontSize = 12.sp)
            if (appState.savedAndroidSignerAvailable) {
                Spacer(Modifier.height(8.dp))
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
            SignInSectionHeader("Remote signer", SignInInfoTopic.RemoteSigner) { infoTopic = it }
            Text(appState.remoteSignerStatus, color = OtherNoteMuted, fontSize = 12.sp)
            if (appState.savedRemoteSignerAvailable) {
                Spacer(Modifier.height(8.dp))
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
            } else {
                Spacer(Modifier.height(6.dp))
                SignInSupportingText(appState.savedRemoteSignerStatus)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = bunkerToken,
                onValueChange = { bunkerToken = it },
                label = { Text("Paste bunker:// token") },
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

        SignInSectionHeader("Existing nsec", SignInInfoTopic.ExistingNsec) { infoTopic = it }
        SignInSupportingText("Session-only")
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
            if (!appState.secureSecretStoreAvailable) SignInSupportingText(appState.secureSecretStoreStatus)
        }
        Spacer(Modifier.height(12.dp))
        Text("Other options", color = OtherNoteText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { appState.startGeneratedIdentityFlow() }, modifier = Modifier.weight(1f)) {
                Text(generatedIdentityOption.label)
            }
            InfoButton(label = "About create identity") { infoTopic = SignInInfoTopic.CreateIdentity }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    nsec = ""
                    appState.continueLocalOnly()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Continue local-only")
            }
            InfoButton(label = "About local-only") { infoTopic = SignInInfoTopic.LocalOnly }
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
    infoTopic?.let { topic ->
        SignInInfoDialog(
            topic = topic,
            onDismiss = { infoTopic = null },
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
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(identity.safeDisplayName(), color = OtherNoteText, fontWeight = FontWeight.Bold)
                Text("Desktop keyring", color = OtherNoteMuted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onContinue) {
                    Text("Continue")
                }
                TextButton(onClick = onForget) {
                    Text("Forget")
                }
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
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(session.safeDisplayName(), color = OtherNoteText, fontWeight = FontWeight.Bold)
                Text(session.signerLabel?.takeIf { it.isNotBlank() } ?: session.signerPackage.safePrefix(), color = OtherNoteMuted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onContinue) {
                    Text("Continue")
                }
                TextButton(onClick = onForget) {
                    Text("Forget")
                }
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
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(session.safeDisplayName(), color = OtherNoteText, fontWeight = FontWeight.Bold)
                Text("Remote signer ${session.remoteSignerPubkey.safePrefix()}", color = OtherNoteMuted, fontSize = 12.sp)
                Text("${session.relays.size} relay(s)", color = OtherNoteMuted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onContinue, enabled = enabled) {
                    Text("Continue")
                }
                TextButton(onClick = onForget, enabled = enabled) {
                    Text("Forget")
                }
            }
        }
    }
}

@Composable
private fun SignInSectionHeader(
    text: String,
    infoTopic: SignInInfoTopic,
    onInfo: (SignInInfoTopic) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, color = OtherNoteText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        InfoButton(label = "About $text") { onInfo(infoTopic) }
    }
}

@Composable
private fun InfoButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text("?")
    }
}

@Composable
private fun SignInSupportingText(text: String) {
    Text(text, color = OtherNoteMuted, fontSize = 13.sp)
}

@Composable
private fun SignInInfoDialog(topic: SignInInfoTopic, onDismiss: () -> Unit) {
    val copy = signInInfoCopy(topic)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(copy.title) },
        text = { Text(copy.body) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
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
    var showAbout by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchedNotes = remember(notes, searchQuery) {
        filterVisibleNotesBySearchQuery(notes, searchQuery)
    }
    val searchActive = searchQuery.trim().isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Other Note") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OtherNotePurpleDark, titleContentColor = OtherNoteText),
                actions = {
                    MainActionsMenu(
                        onSync = { scope.launch { appState.sync() } },
                        onRelays = onSettings,
                        onAbout = { showAbout = true },
                        onLogout = { scope.launch { appState.logout() } },
                    )
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
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search notes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.semantics { contentDescription = "Clear note search" },
                        ) {
                            Text("X")
                        }
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            if (searchedNotes.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
                    Text(if (searchActive) "No matching notes" else "No notes yet", color = OtherNoteMuted)
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
                        items(searchedNotes, key = { it.id }) { note ->
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
    if (showAbout) {
        AboutOtherNoteDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun MainActionsMenu(
    onSync: () -> Unit,
    onRelays: () -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Main menu" },
        ) {
            Text("...", color = OtherNoteText, fontSize = 20.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Sync now") },
                onClick = {
                    expanded = false
                    onSync()
                },
            )
            DropdownMenuItem(
                text = { Text("Relays") },
                onClick = {
                    expanded = false
                    onRelays()
                },
            )
            DropdownMenuItem(
                text = { Text("About Other Note") },
                onClick = {
                    expanded = false
                    onAbout()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Logout") },
                onClick = {
                    expanded = false
                    onLogout()
                },
            )
        }
    }
}

@Composable
private fun AboutOtherNoteDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About Other Note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Other Note", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Version 0.1.0", color = OtherNoteMuted)
                Spacer(Modifier.height(12.dp))
                Text("GPLv3 Nostr-backed encrypted notes app for Android and Debian/Linux desktop.")
                Spacer(Modifier.height(10.dp))
                Text("Key safety", fontWeight = FontWeight.Bold)
                Text("Android NIP-55 keeps the private key in the signer app. NIP-46 keeps the private key in the remote signer. Desktop keyring identities are saved only after explicit confirmation. Session-only nsec sign-in is not persisted.")
                Spacer(Modifier.height(10.dp))
                Text("Platform status", fontWeight = FontWeight.Bold)
                Text("Android and Debian/Linux desktop are the active tested targets. Windows, macOS, iOS, and web are future work.")
                Spacer(Modifier.height(10.dp))
                Text("Support and diagnostics", fontWeight = FontWeight.Bold)
                Text("Share only safe summaries when troubleshooting. Do not share nsec values, private keys, signer tokens, decrypted notes, or raw relay payloads.")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
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
            is MarkdownBlock.Heading -> Text(
                renderInlineMarkdown(block.text),
                color = OtherNoteText,
                fontSize = (28 - block.level * 2).sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            is MarkdownBlock.Paragraph -> Text(
                renderInlineMarkdown(block.text),
                color = OtherNoteText,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            is MarkdownBlock.BlockQuote -> Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.width(3.dp).height(28.dp).background(OtherNotePurple))
                Text(renderInlineMarkdown(block.text), color = OtherNoteMuted, modifier = Modifier.weight(1f))
            }
            is MarkdownBlock.CodeBlock -> Text(
                block.code,
                color = Color(0xFFE8D7FF),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF191020)).padding(10.dp),
            )
        }
    }
}

private fun renderInlineMarkdown(markdown: String) = buildAnnotatedString {
    markdownSpans(markdown).forEach { span ->
        when (span) {
            is MarkdownSpan.Text -> append(span.text)
            is MarkdownSpan.Bold -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(span.text)
                pop()
            }
            is MarkdownSpan.Italic -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(span.text)
                pop()
            }
            is MarkdownSpan.Strike -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(span.text)
                pop()
            }
            is MarkdownSpan.Code -> {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFE8D7FF),
                        background = Color(0xFF191020),
                    ),
                )
                append(span.text)
                pop()
            }
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
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(12.dp)) {
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
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
