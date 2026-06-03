package com.libertasprimordium.othernote.web

import kotlin.js.Promise

external val document: WebDocument
external val window: WebWindow

external interface WebDocument {
    fun getElementById(id: String): WebElement?
    fun createElement(tagName: String): WebElement
}

external interface WebWindow {
    val nostr: Nip07Signer?
}

external interface Nip07Signer {
    val nip44: Nip07Nip44?
    fun getPublicKey(): Promise<String?>
    fun signEvent(event: dynamic): Promise<dynamic>
}

external interface WebElement {
    var className: String
    var textContent: String?
    var innerHTML: String
    var value: String
    fun appendChild(child: WebElement): WebElement
    fun setAttribute(name: String, value: String)
    fun addEventListener(type: String, listener: (dynamic) -> Unit)
}

internal const val Nip46TokenInputLabel = "Remote signer token"
internal const val Nip46TokenInputType = "password"

private lateinit var rootElement: WebElement
private var authState = WebAuthUiState(nip07Available = isNip07Available())
private var noteState: WebNoteLoadState = WebNoteLoadState.Idle
private var loadedNoteEvents: List<WebNostrEvent> = emptyList()
private var loadedNotePlaintexts: Map<String, String> = emptyMap()
private var noteEditMode: WebNoteEditMode = WebNoteEditMode.Idle
private var noteEditBody = ""
private var noteEditMessage = ""
private var noteRelaySettings = defaultWebNoteRelaySettings()
private var nip46TokenInput = ""
private var activeNip46RemoteSigner = WebNip46RemoteSigner()
private var activeNoteLoader = noteLoaderForCurrentRelays()
private var activeNoteCrudService = noteCrudServiceForCurrentRelays()

private sealed interface WebNoteEditMode {
    data object Idle : WebNoteEditMode
    data object Creating : WebNoteEditMode
    data class Editing(val note: WebReadOnlyNote) : WebNoteEditMode
    data class ConfirmingDelete(val note: WebReadOnlyNote) : WebNoteEditMode
    data class Busy(val message: String) : WebNoteEditMode
}

fun main() {
    rootElement = document.getElementById("root") ?: return
    render()
}

private fun render() {
    authState = authState.copy(nip07Available = isNip07Available())
    rootElement.innerHTML = ""
    rootElement.appendChild(appShell(authState))
}

private fun appShell(state: WebAuthUiState): WebElement = element("main", "shell") {
        appendChild(element("section", "hero") {
            appendChild(textElement("p", "eyebrow", "Web client preview"))
            appendChild(textElement("h1", "title", "Other Note"))
            appendChild(textElement("p", "lede", "This web client preview supports in-memory NIP-07 and NIP-46 sign-in with signer-backed note loading and basic note saves."))
        })
    appendChild(element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Security boundary"))
        appendChild(textElement("p", "body", "Your signer keeps your private key. Other Note only asks for your public key in this preview, and signing, encryption, and decryption will remain client-side or signer-delegated."))
    })
    when (val signInState = state.signInState) {
        is WebSignInState.SignedIn -> {
            appendChild(accountPanel(signInState.identity))
            appendChild(noteRelaySettingsPanel())
            appendChild(notesPanel(signInState.identity, noteState))
        }
        else -> {
            appendChild(nip07SignInPanel(state))
            appendChild(nip46SignInPanel(state))
        }
    }
    appendChild(element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Not implemented yet"))
        appendChild(textElement("p", "body", "This web preview does not persist web sessions, remote signer sessions, note caches, drafts, note relay preferences, or pending writes. It has no direct nsec input and no full relay-list sync yet."))
    })
    appendChild(element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Planned sign-in paths"))
        appendChild(element("ul", "method-list") {
            appendChild(textElement("li", "method", "NIP-07 browser extension"))
            appendChild(textElement("li", "method", "NIP-46 remote signer"))
            appendChild(textElement("li", "method", "Session-only direct nsec fallback later"))
        })
    })
    appendChild(textElement("p", "footer", "Android and Debian/Linux desktop are the current active targets."))
}

private fun accountPanel(identity: WebAccountIdentity): WebElement =
    element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Signed in"))
        appendChild(textElement("p", "body", "Signed in with ${identity.method.displayName} as ${identity.displayPublicKey}."))
        appendChild(textElement("p", "body small-gap", "Notes, drafts, and signer sessions are in memory only. Refreshing this page may clear this session."))
        appendChild(buttonElement(text = "Logout", enabled = true, onClick = ::logout))
    }

private fun noteRelaySettingsPanel(): WebElement =
    element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Note relays"))
        appendChild(textElement("p", "body", "These relays fetch and publish your encrypted notes."))
        appendChild(textElement("p", "body small-gap", "Relay choices are kept in memory for this browser session."))
        appendChild(element("div", "relay-list") {
            selectedWebNoteRelays(noteRelaySettings).forEach { relay ->
                appendChild(element("div", "relay-row") {
                    appendChild(textElement("span", "relay-url", relay))
                    appendChild(buttonElement(text = "Remove", enabled = true, onClick = { removeNoteRelay(relay) }))
                })
            }
        })
        appendChild(textInputElement(
            label = "Add note relay",
            value = noteRelaySettings.input,
            enabled = true,
            inputType = "text",
            placeholder = "relay.example.com",
            onInput = ::updateNoteRelayInput,
        ))
        if (noteRelaySettings.message.isNotBlank()) {
            val style = if (noteRelaySettings.message == WebNoteCopy.RelayInvalid) "body error small-gap" else "body small-gap"
            appendChild(textElement("p", style, noteRelaySettings.message))
        }
        appendChild(element("div", "inline-actions") {
            appendChild(buttonElement(text = "Add relay", enabled = true, onClick = ::addNoteRelay))
            appendChild(buttonElement(text = "Restore defaults", enabled = true, onClick = ::restoreDefaultNoteRelays))
        })
    }

private fun notesPanel(identity: WebAccountIdentity, notes: WebNoteLoadState): WebElement =
    element("section", "panel") {
        val crudSigner = crudSignerFor(identity)
        val canCrud = crudSigner != null
        appendChild(textElement("h2", "section-title", "Notes"))
        appendChild(textElement("p", "body", "Loads encrypted Other Note events from note relays and uses the active signer for note encryption, decryption, and signing. No note data is saved in the browser."))
        appendChild(
            buttonElement(
                text = when (notes) {
                    is WebNoteLoadState.Loading -> "Loading notes..."
                    else -> "Reload notes"
                },
                enabled = notes !is WebNoteLoadState.Loading,
                onClick = { loadReadOnlyNotes(identity) },
            ),
        )
        appendChild(
            buttonElement(
                text = "New note",
                enabled = canCrud && noteEditMode !is WebNoteEditMode.Busy,
                onClick = ::startCreateNote,
            ),
        )
        if (!canCrud) {
            appendChild(textElement("p", "body error small-gap", WebNoteCopy.CrudCapabilityUnavailable))
        }
        appendChild(noteEditorPanel(identity, crudSigner))
        when (notes) {
            WebNoteLoadState.Idle -> appendChild(textElement("p", "body small-gap", "Notes are not loaded yet."))
            is WebNoteLoadState.Loading -> appendChild(textElement("p", "body small-gap", notes.message))
            is WebNoteLoadState.Empty -> {
                appendChild(textElement("p", "body small-gap", WebNoteCopy.NoNotes))
                notes.status?.let { appendChild(textElement("p", "body muted", it)) }
            }
            is WebNoteLoadState.Failed -> appendChild(textElement("p", "body error small-gap", notes.message))
            is WebNoteLoadState.SignerUnsupported -> appendChild(textElement("p", "body error small-gap", notes.message))
            is WebNoteLoadState.Loaded -> {
                notes.status?.let { appendChild(textElement("p", "body muted small-gap", it)) }
                appendChild(element("div", "note-list") {
                    notes.notes.forEach { note -> appendChild(noteCard(note, canCrud)) }
                })
            }
        }
    }

private fun noteCard(note: WebReadOnlyNote, canCrud: Boolean): WebElement =
    element("article", "note-card") {
        val preview = webNotePreview(note.bodyMarkdown)
        appendChild(textElement("h3", "note-title", preview.title))
        if (preview.snippet.isNotBlank()) {
            appendChild(textElement("p", "note-snippet", preview.snippet))
        }
        appendChild(textElement("p", "note-meta", "Last edited ${formatWebNoteTimestamp(note.updatedAtMs)}"))
        if (canCrud) {
            appendChild(element("div", "inline-actions") {
                appendChild(buttonElement(text = "Edit", enabled = true, onClick = { startEditNote(note) }))
                appendChild(buttonElement(text = "Delete", enabled = true, onClick = { confirmDeleteNote(note) }))
            })
        }
        appendChild(renderMarkdown(note.bodyMarkdown))
    }

private fun noteEditorPanel(identity: WebAccountIdentity, signer: WebNoteCrudSigner?): WebElement =
    when (val mode = noteEditMode) {
        WebNoteEditMode.Idle -> element("div", "")
        WebNoteEditMode.Creating,
        is WebNoteEditMode.Editing,
        -> element("section", "editor-panel") {
            appendChild(textElement("h3", "section-title", if (mode is WebNoteEditMode.Editing) "Edit note" else "New note"))
            if (noteEditMessage.isNotBlank()) appendChild(textElement("p", "body error", noteEditMessage))
            appendChild(textAreaElement(
                label = "Raw Markdown",
                value = noteEditBody,
                enabled = signer != null,
                onInput = { value -> noteEditBody = value },
            ))
            appendChild(element("div", "inline-actions") {
                appendChild(buttonElement(text = "Save note", enabled = signer != null, onClick = { saveCurrentDraft(identity, signer) }))
                appendChild(buttonElement(text = "Cancel", enabled = true, onClick = ::cancelNoteEdit))
            })
        }
        is WebNoteEditMode.ConfirmingDelete -> element("section", "editor-panel") {
            appendChild(textElement("h3", "section-title", "Delete note?"))
            appendChild(textElement("p", "body", "This publishes an encrypted tombstone event for the selected note."))
            if (noteEditMessage.isNotBlank()) appendChild(textElement("p", "body error", noteEditMessage))
            appendChild(element("div", "inline-actions") {
                appendChild(buttonElement(text = "Delete note", enabled = signer != null, onClick = { deleteConfirmedNote(identity, mode.note, signer) }))
                appendChild(buttonElement(text = "Cancel", enabled = true, onClick = ::cancelNoteEdit))
            })
        }
        is WebNoteEditMode.Busy -> element("section", "editor-panel") {
            appendChild(textElement("h3", "section-title", "Saving note"))
            appendChild(textElement("p", "body", mode.message))
        }
    }

private fun nip07SignInPanel(state: WebAuthUiState): WebElement =
    element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Browser extension sign-in"))
        when (val signInState = state.signInState) {
            WebSignInState.SignedOut -> {
                appendChild(
                    textElement(
                        "p",
                        "body",
                        if (state.nip07Available) {
                            "NIP-07 signer detected. Sign in to identify the account for this browser session."
                        } else {
                            "Install or enable a Nostr browser extension to sign in with NIP-07."
                        },
                    ),
                )
                appendChild(
                    buttonElement(
                        text = "Sign in with browser extension",
                        enabled = state.nip07Available,
                        onClick = ::requestNip07PublicKey,
                    ),
                )
            }
            is WebSignInState.SigningIn -> if (signInState.method == WebAuthMethod.Nip07) {
                appendChild(textElement("p", "body", "Waiting for the browser extension..."))
                appendChild(buttonElement(text = "Sign-in request pending", enabled = false))
            } else {
                appendChild(textElement("p", "body", "Browser extension sign-in is available separately."))
                appendChild(buttonElement(text = "Sign in with browser extension", enabled = false))
            }
            is WebSignInState.Failed -> if (signInState.method == WebAuthMethod.Nip07) {
                appendChild(textElement("p", "body error", signInState.message))
                appendChild(
                    buttonElement(
                        text = "Sign in with browser extension",
                        enabled = state.nip07Available,
                        onClick = ::requestNip07PublicKey,
                    ),
                )
            } else {
                appendChild(
                    textElement(
                        "p",
                        "body",
                        if (state.nip07Available) {
                            "NIP-07 signer detected. Sign in to identify the account for this browser session."
                        } else {
                            "Install or enable a Nostr browser extension to sign in with NIP-07."
                        },
                    ),
                )
                appendChild(
                    buttonElement(
                        text = "Sign in with browser extension",
                        enabled = state.nip07Available,
                        onClick = ::requestNip07PublicKey,
                    ),
                )
            }
            is WebSignInState.SignedIn -> Unit
        }
    }

private fun nip46SignInPanel(state: WebAuthUiState): WebElement =
    element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Remote signer / NIP-46"))
        appendChild(textElement("p", "body", "Your private key stays in the remote signer. This web preview does not save remote signer sessions yet."))
        appendChild(textInputElement(
            label = Nip46TokenInputLabel,
            value = nip46TokenInput,
            enabled = state.signInState !is WebSignInState.SigningIn,
            onInput = { value -> nip46TokenInput = value },
        ))
        if (state.nip46Message.isNotBlank()) {
            val style = if (state.nip46Status == WebNip46Status.Failed) "body error small-gap" else "body small-gap"
            appendChild(textElement("p", style, state.nip46Message))
        }
        appendChild(
            buttonElement(
                text = if (state.signInState is WebSignInState.SigningIn && state.signInState.method == WebAuthMethod.Nip46) {
                    "Remote signer request pending"
                } else {
                    "Sign in with remote signer"
                },
                enabled = state.signInState !is WebSignInState.SigningIn,
                onClick = ::requestNip46PublicKey,
            ),
        )
    }

private fun requestNip07PublicKey() {
    val signer = window.nostr
    if (signer == null) {
        authState = beginNip07SignIn(authState.copy(nip07Available = false))
        render()
        return
    }

    authState = beginNip07SignIn(authState.copy(nip07Available = true))
    render()

    try {
        signer.getPublicKey().then(
            { publicKey ->
                noteState = WebNoteLoadState.Idle
                resetLoadedNotes()
                cancelNoteEdit()
                authState = completeNip07SignIn(authState, publicKey)
                render()
            },
            {
                authState = failNip07SignIn(authState)
                render()
            },
        )
    } catch (_: Throwable) {
        authState = failNip07SignIn(authState)
        render()
    }
}

private fun requestNip46PublicKey() {
    authState = beginNip46SignIn(authState)
    render()
    activeNip46RemoteSigner.disconnect()
    activeNip46RemoteSigner = WebNip46RemoteSigner()
    activeNip46RemoteSigner.connectWithBunkerToken(
        rawToken = nip46TokenInput,
        onProgress = { status, message ->
            authState = updateNip46Progress(authState, status, message)
            render()
        },
        onResult = { result ->
            when (result) {
                is WebNip46ConnectResult.Connected -> {
                    nip46TokenInput = ""
                    noteState = WebNoteLoadState.Idle
                    resetLoadedNotes()
                    cancelNoteEdit()
                    authState = completeNip46SignIn(authState, result.userPubkey)
                }
                is WebNip46ConnectResult.Failed -> {
                    authState = failNip46SignIn(authState, result.safeMessage)
                }
            }
            render()
        },
    )
}

private fun loadReadOnlyNotes(identity: WebAccountIdentity) {
    activeNoteLoader.close()
    activeNoteLoader = noteLoaderForCurrentRelays()
    val decryptor = when (identity.method) {
        WebAuthMethod.Nip46 -> WebNip46NoteDecryptor(activeNip46RemoteSigner, identity.publicKeyHex)
        WebAuthMethod.Nip07 -> {
            val nip44 = window.nostr?.nip44
            if (nip44 == null) null else WebNip07NoteDecryptor(nip44, identity.publicKeyHex)
        }
    }
    noteState = WebNoteLoadState.Loading("Preparing read-only note load.")
    render()
    activeNoteLoader.load(
        accountPubkey = identity.publicKeyHex,
        decryptor = decryptor,
        onProgress = { message ->
            noteState = WebNoteLoadState.Loading(message)
            render()
        },
        onResult = { result ->
            noteState = when (result) {
                is WebNoteLoadResult.Loaded ->
                    if (result.state.notes.isEmpty()) {
                        loadedNoteEvents = result.events
                        loadedNotePlaintexts = result.decryptedByEventId
                        WebNoteLoadState.Empty(result.relayStatus)
                    } else {
                        loadedNoteEvents = result.events
                        loadedNotePlaintexts = result.decryptedByEventId
                        WebNoteLoadState.Loaded(result.state.notes, result.relayStatus)
                    }
                is WebNoteLoadResult.Failed -> WebNoteLoadState.Failed(result.safeMessage)
                is WebNoteLoadResult.SignerUnsupported -> WebNoteLoadState.SignerUnsupported(result.safeMessage)
            }
            render()
        },
    )
}

private fun crudSignerFor(identity: WebAccountIdentity): WebNoteCrudSigner? =
    when (identity.method) {
        WebAuthMethod.Nip46 -> WebNip46NoteCrudSigner(activeNip46RemoteSigner, identity.publicKeyHex)
        WebAuthMethod.Nip07 -> {
            val signer = window.nostr
            if (signer.hasWebNoteCrudCapability()) WebNip07NoteCrudSigner(signer, identity.publicKeyHex) else null
        }
    }

private fun startCreateNote() {
    noteEditMode = WebNoteEditMode.Creating
    noteEditBody = ""
    noteEditMessage = ""
    render()
}

private fun startEditNote(note: WebReadOnlyNote) {
    noteEditMode = WebNoteEditMode.Editing(note)
    noteEditBody = note.bodyMarkdown
    noteEditMessage = ""
    render()
}

private fun confirmDeleteNote(note: WebReadOnlyNote) {
    noteEditMode = WebNoteEditMode.ConfirmingDelete(note)
    noteEditBody = ""
    noteEditMessage = ""
    render()
}

private fun cancelNoteEdit() {
    noteEditMode = WebNoteEditMode.Idle
    noteEditBody = ""
    noteEditMessage = ""
}

private fun saveCurrentDraft(identity: WebAccountIdentity, signer: WebNoteCrudSigner?) {
    val existing = (noteEditMode as? WebNoteEditMode.Editing)?.note
    val body = noteEditBody
    noteEditMode = WebNoteEditMode.Busy("Preparing note save.")
    render()
    activeNoteCrudService.save(
        existing = existing,
        bodyMarkdown = body,
        accountPubkey = identity.publicKeyHex,
        signer = signer,
        onProgress = { message ->
            noteEditMode = WebNoteEditMode.Busy(message)
            render()
        },
        onResult = { result ->
            when (result) {
                is WebNoteSaveResult.Failed -> {
                    noteEditMode = if (existing == null) WebNoteEditMode.Creating else WebNoteEditMode.Editing(existing)
                    noteEditBody = body
                    noteEditMessage = result.safeMessage
                }
                is WebNoteSaveResult.Published -> {
                    applyPublishedNote(result)
                    cancelNoteEdit()
                }
            }
            render()
        },
    )
}

private fun deleteConfirmedNote(identity: WebAccountIdentity, note: WebReadOnlyNote, signer: WebNoteCrudSigner?) {
    noteEditMode = WebNoteEditMode.Busy("Preparing note tombstone.")
    render()
    activeNoteCrudService.delete(
        note = note,
        accountPubkey = identity.publicKeyHex,
        signer = signer,
        onProgress = { message ->
            noteEditMode = WebNoteEditMode.Busy(message)
            render()
        },
        onResult = { result ->
            when (result) {
                is WebNoteSaveResult.Failed -> {
                    noteEditMode = WebNoteEditMode.ConfirmingDelete(note)
                    noteEditMessage = result.safeMessage
                }
                is WebNoteSaveResult.Published -> {
                    applyPublishedNote(result)
                    cancelNoteEdit()
                }
            }
            render()
        },
    )
}

private fun applyPublishedNote(result: WebNoteSaveResult.Published) {
    val eventMap = linkedMapOf<String, WebNostrEvent>()
    loadedNoteEvents.forEach { eventMap[it.id] = it }
    eventMap[result.event.id] = result.event
    loadedNoteEvents = eventMap.values.toList()
    loadedNotePlaintexts = loadedNotePlaintexts + (result.event.id to result.plaintextPayload)
    val reduced = reduceDecryptedWebNoteEvents(loadedNoteEvents, loadedNotePlaintexts)
    noteState = if (reduced.notes.isEmpty()) {
        WebNoteLoadState.Empty(result.status)
    } else {
        WebNoteLoadState.Loaded(reduced.notes, result.status)
    }
}

private fun resetLoadedNotes() {
    loadedNoteEvents = emptyList()
    loadedNotePlaintexts = emptyMap()
}

private fun updateNoteRelayInput(value: String) {
    noteRelaySettings = updateWebNoteRelayInput(noteRelaySettings, value)
    render()
}

private fun addNoteRelay() {
    updateNoteRelaySettings(addWebNoteRelay(noteRelaySettings))
}

private fun removeNoteRelay(relay: String) {
    updateNoteRelaySettings(removeWebNoteRelay(noteRelaySettings, relay))
}

private fun restoreDefaultNoteRelays() {
    updateNoteRelaySettings(restoreDefaultWebNoteRelays(noteRelaySettings))
}

private fun updateNoteRelaySettings(next: WebNoteRelaySettingsState) {
    val previousRelays = selectedWebNoteRelays(noteRelaySettings)
    noteRelaySettings = next
    if (previousRelays != selectedWebNoteRelays(noteRelaySettings)) {
        rebuildNoteRelayClients()
    }
    render()
}

private fun rebuildNoteRelayClients() {
    activeNoteLoader.close()
    activeNoteCrudService.close()
    activeNoteLoader = noteLoaderForCurrentRelays()
    activeNoteCrudService = noteCrudServiceForCurrentRelays()
}

private fun noteLoaderForCurrentRelays(): WebNoteLoader =
    WebNoteLoader(WebNoteRelayFetcher(selectedWebNoteRelays(noteRelaySettings)))

private fun noteCrudServiceForCurrentRelays(): WebNoteCrudService =
    WebNoteCrudService(WebNoteRelayPublisher(selectedWebNoteRelays(noteRelaySettings)))

private fun logout() {
    activeNip46RemoteSigner.disconnect()
    activeNoteLoader.close()
    activeNoteCrudService.close()
    noteRelaySettings = defaultWebNoteRelaySettings()
    activeNoteLoader = noteLoaderForCurrentRelays()
    activeNoteCrudService = noteCrudServiceForCurrentRelays()
    nip46TokenInput = ""
    noteState = WebNoteLoadState.Idle
    resetLoadedNotes()
    cancelNoteEdit()
    authState = logoutWebAccount(authState)
    render()
}

private fun isNip07Available(): Boolean = window.nostr != null

private fun buttonElement(
    text: String,
    enabled: Boolean,
    onClick: (() -> Unit)? = null,
): WebElement =
    element("button", "action-button").also { button ->
        button.textContent = text
        if (!enabled) {
            button.setAttribute("disabled", "disabled")
        } else if (onClick != null) {
            button.addEventListener("click") { onClick() }
        }
    }

private fun textInputElement(
    label: String,
    value: String,
    enabled: Boolean,
    inputType: String = Nip46TokenInputType,
    placeholder: String = "bunker://...",
    onInput: (String) -> Unit,
): WebElement =
    element("label", "field-label") {
        appendChild(textElement("span", "field-label-text", label))
        appendChild(
            element("input", "text-input").also { input ->
                input.value = value
                input.setAttribute("type", inputType)
                input.setAttribute("autocomplete", "off")
                input.setAttribute("autocapitalize", "none")
                input.setAttribute("autocorrect", "off")
                input.setAttribute("spellcheck", "false")
                input.setAttribute("placeholder", placeholder)
                if (!enabled) input.setAttribute("disabled", "disabled")
                input.addEventListener("input") {
                    onInput(input.value)
                }
            },
        )
    }

private fun textAreaElement(
    label: String,
    value: String,
    enabled: Boolean,
    onInput: (String) -> Unit,
): WebElement =
    element("label", "field-label") {
        appendChild(textElement("span", "field-label-text", label))
        appendChild(
            element("textarea", "text-input note-editor-input").also { input ->
                input.value = value
                input.setAttribute("rows", "10")
                input.setAttribute("spellcheck", "true")
                if (!enabled) input.setAttribute("disabled", "disabled")
                input.addEventListener("input") {
                    onInput(input.value)
                }
            },
        )
    }

private fun element(tagName: String, className: String, build: WebElement.() -> Unit = {}): WebElement =
    document.createElement(tagName).also { element ->
        element.className = className
        element.build()
    }

private fun textElement(tagName: String, className: String, text: String): WebElement =
    element(tagName, className).also { it.textContent = text }

private fun renderMarkdown(markdown: String): WebElement =
    element("div", "markdown-view") {
        webMarkdownBlocks(markdown).forEach { block ->
            appendChild(
                when (block) {
                    is WebMarkdownBlock.Heading -> inlineElement("h${block.level.coerceIn(1, 6)}", "markdown-heading", block.text)
                    is WebMarkdownBlock.Paragraph -> inlineElement("p", "markdown-paragraph", block.text)
                    is WebMarkdownBlock.BlockQuote -> inlineElement("blockquote", "markdown-quote", block.text)
                    is WebMarkdownBlock.CodeBlock -> textElement("pre", "markdown-code-block", block.code)
                },
            )
        }
    }

private fun inlineElement(tagName: String, className: String, markdown: String): WebElement =
    element(tagName, className) {
        webMarkdownSpans(markdown).forEach { span ->
            appendChild(
                when (span) {
                    is WebMarkdownSpan.Text -> textElement("span", "", span.text)
                    is WebMarkdownSpan.Bold -> textElement("strong", "", span.text)
                    is WebMarkdownSpan.Italic -> textElement("em", "", span.text)
                    is WebMarkdownSpan.Strike -> textElement("s", "", span.text)
                    is WebMarkdownSpan.Code -> textElement("code", "inline-code", span.text)
                },
            )
        }
    }

private fun formatWebNoteTimestamp(updatedAtMs: Long): String =
    if (updatedAtMs <= 0) "unknown" else "${updatedAtMs / 1000}s"
