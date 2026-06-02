package com.libertasprimordium.othernote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libertasprimordium.othernote.domain.Note
import com.libertasprimordium.othernote.domain.SessionAuthMethod
import com.libertasprimordium.othernote.domain.abbreviatedNpub
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
            Screen.List -> NotesListScreen(appState, onOpen = { screen = Screen.Display(it) }, onNew = { screen = Screen.Edit(null) }, onSettings = { screen = Screen.Settings })
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

        androidSignerOption?.let { option ->
            SignInSectionTitle("Recommended")
            SignInSupportingText(option.supportingCopy)
            Text(appState.externalSignerStatus, color = OtherNoteMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { appState.requestExternalSignerPublicKey() },
                enabled = option.enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(option.label)
            }
            Spacer(Modifier.height(18.dp))
        }

        remoteSignerOption?.let { option ->
            SignInSectionTitle("Remote signer")
            SignInSupportingText(option.supportingCopy)
            Text(appState.remoteSignerStatus, color = OtherNoteMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = bunkerToken,
                onValueChange = { bunkerToken = it },
                label = { Text("Paste bunker:// remote signer token") },
                singleLine = true,
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
                enabled = option.enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(option.label)
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(appState: AppState, onOpen: (Note) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val notes by appState.notes.notes.collectAsState()
    val session by appState.session.collectAsState()
    val message by appState.message.collectAsState()
    val diagnostics by appState.diagnosticMessage.collectAsState()
    val scope = rememberCoroutineScope()
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
            Text(session?.abbreviatedNpub() ?: "Local-only session", color = OtherNoteMuted)
            if (appState.runtimeMode == AppRuntimeMode.DesktopDevRelay) {
                Text("Developer relay runtime", color = OtherNotePurple)
            }
            Text(message, color = OtherNoteMuted)
            if ((appState.showRelayDiagnostics || appState.showNip55Diagnostics) && diagnostics.isNotBlank()) {
                Text(diagnostics, color = OtherNoteMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("New note") }
            Spacer(Modifier.height(12.dp))
            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("No notes yet", color = OtherNoteMuted)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(notes, key = { it.id }) { note ->
                        NoteCard(note, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
fun NoteCard(note: Note, onOpen: (Note) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(note) },
        colors = CardDefaults.cardColors(containerColor = OtherNotePanel),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(truncateMarkdown(note.bodyMarkdown).ifBlank { "Untitled note" }, color = OtherNoteText)
            Text(formatNoteCardUpdatedAt(note.updatedAtMs), color = OtherNoteMuted, fontSize = 12.sp)
        }
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
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete note?") },
            text = { Text("This creates an app-level tombstone event. Relay DELETE is not required.") },
            confirmButton = {
                Button(onClick = {
                        scope.launch {
                            val deleted = appState.delete(note)
                            confirmDelete = false
                            if (deleted) {
                                onBack()
                            }
                        }
                }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
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
    val scope = rememberCoroutineScope()
    var relayText by remember(relays) { mutableStateOf(relays.joinToString("\n") { it.url }) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relays") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OtherNotePurpleDark, titleContentColor = OtherNoteText),
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        containerColor = OtherNoteBlack,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Public relays may purge old events. Add a relay you control for stronger long-term retention.", color = OtherNoteMuted)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = relayText,
                onValueChange = { relayText = it },
                label = { Text("One relay URL per line") },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = {
                    scope.launch {
                        appState.saveRelays(relayText.lines().filter { it.isNotBlank() })
                        onBack()
                    }
                }) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onBack) { Text("Cancel") }
            }
        }
    }
}
