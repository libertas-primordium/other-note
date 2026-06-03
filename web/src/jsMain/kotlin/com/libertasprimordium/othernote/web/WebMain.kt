package com.libertasprimordium.othernote.web

import kotlin.js.Promise

external val document: WebDocument
external val window: WebWindow
external val localStorage: WebStorage

external interface WebDocument {
    val documentElement: WebElement
    fun getElementById(id: String): WebElement?
    fun createElement(tagName: String): WebElement
}

external interface WebWindow {
    val nostr: Nip07Signer?
    val innerWidth: Int
    fun addEventListener(type: String, listener: (dynamic) -> Unit)
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

external interface WebStorage {
    fun getItem(key: String): String?
    fun setItem(key: String, value: String)
    fun removeItem(key: String)
}

internal const val Nip46TokenInputLabel = "Remote signer token"
internal const val Nip46TokenInputType = "password"
private const val WebNotesResultsContainerId = "web-notes-results"

private lateinit var rootElement: WebElement
private var authState = WebAuthUiState(nip07Available = isNip07Available())
private var noteState: WebNoteLoadState = WebNoteLoadState.Idle
private var loadedNoteEvents: List<WebNostrEvent> = emptyList()
private var loadedNotePlaintexts: Map<String, String> = emptyMap()
private var noteListControls = WebNoteListControlsState()
private var selectedThemeId = loadInitialWebThemeId()
private var noteEditMode: WebNoteEditMode = WebNoteEditMode.Idle
private var noteEditBody = ""
private var noteEditMessage = ""
private var noteDetailState = WebNoteDetailUiState()
private var profileState = WebProfileUiState()
private var profileLoadGuard = WebProfileLoadGuard()
private var relayListState = WebRelayListUiState()
private var relayListLoadGuard = WebRelayListLoadGuard()
private var relayMigrationState = WebRelayMigrationUiState()
private var relayMigrationGuard = WebRelayMigrationGuard()
private var relayStatsState = WebRelayStatsUiState()
private var relayStatsGuard = WebRelayStatsGuard()
private var noteRelaySettings = defaultWebNoteRelaySettings()
private var nip46TokenInput = ""
private var rememberNip46Session = false
private var rememberedNip46State = loadInitialRememberedNip46State()
private var directNsecDraft = WebDirectNsecDraftState()
private var generatedIdentityState = WebGeneratedIdentityState()
private var activeSignInInfoTopic: WebSignInInfoTopic? = null
private var webMenuState = WebMenuUiState()
private var noteLaneCount = webNoteLaneCountForViewport()
private var noteLoadGuard = WebNoteLoadGuard()
private var activeNip46RemoteSigner = WebNip46RemoteSigner()
private var activeDirectKeySession: WebDirectKeySession? = null
private var activeNoteLoader = noteLoaderForCurrentRelays()
private var activeNoteCrudService = noteCrudServiceForCurrentRelays()
private var activeProfileFetcher = profileFetcherForCurrentRelays()
private var activeRelayListFetcher = relayListFetcherForCurrentRelays()
private var activeRelayMigrationService = WebRelayMigrationService()
private var activeRelayStatsFetcher = relayStatsFetcherForCurrentRelays()
private var authAttemptGeneration = 0

private sealed interface WebNoteEditMode {
    data object Idle : WebNoteEditMode
    data object Creating : WebNoteEditMode
    data class Editing(val note: WebReadOnlyNote) : WebNoteEditMode
    data class ConfirmingDelete(val note: WebReadOnlyNote) : WebNoteEditMode
    data class Busy(val message: String) : WebNoteEditMode
}

fun main() {
    rootElement = document.getElementById("root") ?: return
    applyWebTheme(selectedThemeId)
    window.addEventListener("resize") {
        val nextLaneCount = webNoteLaneCountForViewport()
        if (nextLaneCount != noteLaneCount) {
            noteLaneCount = nextLaneCount
            render()
        }
    }
    window.addEventListener("keydown") { event ->
        val key = event.asDynamic().key as? String
        if (key == "Escape" && activeSignInInfoTopic != null) {
            activeSignInInfoTopic = null
            render()
        }
    }
    render()
}

private fun render() {
    authState = authState.copy(nip07Available = isNip07Available())
    rootElement.innerHTML = ""
    rootElement.appendChild(appShell(authState))
}

private fun appShell(state: WebAuthUiState): WebElement = element("main", appShellClass(state)) {
    val signedIn = state.signInState as? WebSignInState.SignedIn
    if (signedIn != null) {
        appendChild(signedInHeader(signedIn.identity, profileState))
        appendChild(notesPanel(signedIn.identity, noteState))
        activeMenuPanel(signedIn.identity)?.let(::appendChild)
        viewedNoteForCurrentState()?.let { appendChild(noteDetailPanel(it)) }
        return@element
    }

    appendChild(element("section", "hero") {
        appendChild(textElement("p", "eyebrow", "Web client preview"))
        appendChild(textElement("h1", "title", "Other Note"))
        appendChild(textElement("p", "lede", "Private Nostr-backed notes for this browser session."))
    })
    appendChild(themeSelectorPanel())
    appendChild(nip07SignInPanel(state))
    appendChild(nip46SignInPanel(state))
    appendChild(directNsecSignInPanel(state))
    appendChild(generatedIdentityPanel(state))
    appendChild(textElement("p", "footer", "Android and Debian/Linux desktop are the current active targets."))
    activeSignInInfoTopic?.let { appendChild(signInInfoModal(it)) }
}

private fun appShellClass(state: WebAuthUiState): String =
    if (state.signInState is WebSignInState.SignedIn) WebSignedInShellClass else WebSignedOutShellClass

private fun signedInHeader(identity: WebAccountIdentity, profile: WebProfileUiState): WebElement =
    element("header", "app-header") {
        val summary = webProfileHeaderSummary(identity, profile)
        appendChild(element("div", "app-header-copy") {
            appendChild(textElement("p", "eyebrow", "Web client preview"))
            appendChild(textElement("h1", "app-title", "Other Note"))
            appendChild(textElement("h2", "profile-name", summary.primary))
            appendChild(textElement("p", "body", summary.secondary))
            summary.tertiary?.let { appendChild(textElement("p", "body muted profile-line", it)) }
            summary.about?.let { appendChild(textElement("p", "body muted profile-about", it)) }
            appendChild(textElement("p", "body muted small-gap", "In-memory web session. Refreshing this page may clear auth, notes, drafts, and relay choices."))
        })
        appendChild(signedInMenu(identity))
    }

private fun signedInMenu(identity: WebAccountIdentity): WebElement =
    element("div", "menu-shell") {
        appendChild(buttonElement(
            text = "Menu",
            enabled = true,
            onClick = {
                webMenuState = toggleWebMenu(webMenuState)
                render()
            },
        ))
        if (webMenuState.open) {
            appendChild(element("div", "menu-popover") {
                appendChild(menuItemElement("Reload notes") {
                    webMenuState = closeWebMenu(webMenuState)
                    loadReadOnlyNotes(identity)
                })
                appendChild(menuItemElement("Note relays") {
                    webMenuState = openWebMenuPanel(webMenuState, WebMenuPanel.NoteRelays)
                    render()
                    refreshRelayListForSignedInSession(reloadNotesOnImport = true)
                    refreshRelayStatsForSignedInSession()
                })
                appendChild(menuItemElement("Theme") {
                    webMenuState = openWebMenuPanel(webMenuState, WebMenuPanel.Theme)
                    render()
                })
                appendChild(menuItemElement("About web preview") {
                    webMenuState = openWebMenuPanel(webMenuState, WebMenuPanel.About)
                    render()
                })
                appendChild(element("div", "menu-divider"))
                appendChild(menuItemElement("Logout", danger = true) {
                    webMenuState = closeWebMenu(webMenuState)
                    logout()
                })
            })
        }
    }

private fun activeMenuPanel(identity: WebAccountIdentity): WebElement? =
    when (webMenuState.activePanel) {
        WebMenuPanel.None -> null
        WebMenuPanel.NoteRelays -> modalPanel("Note relays", closeEnabled = !relayMigrationState.inProgress) {
            appendChild(noteRelaySettingsContent())
        }
        WebMenuPanel.Theme -> modalPanel("Theme") {
            appendChild(themeSelectorContent())
        }
        WebMenuPanel.About -> modalPanel("About web preview") {
            appendChild(aboutWebPreviewContent(identity))
        }
    }

private fun modalPanel(title: String, closeEnabled: Boolean = true, build: WebElement.() -> Unit): WebElement =
    element("div", "modal-backdrop") {
        appendChild(element("section", "panel modal-panel") {
            appendChild(element("div", "modal-header") {
                appendChild(textElement("h2", "section-title", title))
                appendChild(buttonElement(text = "Close", enabled = closeEnabled, onClick = ::closeActivePanel))
            })
            build()
        })
    }

private fun noteRelaySettingsContent(): WebElement =
    element("div", "panel-content") {
        appendChild(textElement("p", "body", "These relays fetch and publish your encrypted notes."))
        appendChild(textElement("p", "body small-gap", "Relay choices are kept in memory for this browser session."))
        relayListStatusCopy()?.let { copy ->
            appendChild(textElement("p", relayListStatusClass(), copy))
        }
        relayMigrationStatusCopy()?.let { copy ->
            appendChild(textElement("p", relayMigrationStatusClass(), copy))
        }
        relayMigrationState.warning?.let { warning ->
            appendChild(element("section", "editor-panel relay-migration-warning") {
                appendChild(textElement("h3", "section-title", warning.title))
                appendChild(textElement("p", "body", warning.body))
                if (relayMigrationState.pendingSettings != null) {
                    appendChild(element("div", "inline-actions") {
                        appendChild(buttonElement(text = "Cancel", enabled = true, onClick = ::cancelRelayMigration))
                        appendChild(buttonElement(text = "Continue", enabled = true, onClick = ::continueRelayMigration))
                    })
                }
            })
        }
        if (relayListState.pendingPublishedRelays.isNotEmpty()) {
            appendChild(element("div", "inline-actions") {
                appendChild(buttonElement(text = "Keep local edits", enabled = !relayMigrationState.inProgress, onClick = ::keepCurrentRelaySettings))
                appendChild(buttonElement(text = "Reload published list", enabled = !relayMigrationState.inProgress, onClick = ::applyPendingPublishedRelays))
            })
        }
        appendChild(element("div", "relay-list") {
            selectedWebNoteRelays(noteRelaySettings).forEach { relay ->
                appendChild(element("div", "relay-row") {
                    appendChild(element("div", "relay-row-main") {
                        appendChild(textElement("span", "relay-url", relay))
                        appendChild(textElement("span", "relay-stat", webRelayEventStatLabel(relayStatsState.stats[relay])))
                    })
                    appendChild(buttonElement(text = "Remove", enabled = !relayMigrationState.inProgress, onClick = { removeNoteRelay(relay) }))
                })
            }
        })
        appendChild(textInputElement(
            label = "Add note relay",
            value = noteRelaySettings.input,
            enabled = !relayMigrationState.inProgress,
            inputType = "text",
            placeholder = "relay.example.com",
            onInput = ::updateNoteRelayInput,
        ))
        if (noteRelaySettings.message.isNotBlank()) {
            val style = if (noteRelaySettings.message == WebNoteCopy.RelayInvalid) "body error small-gap" else "body small-gap"
            appendChild(textElement("p", style, noteRelaySettings.message))
        }
        appendChild(element("div", "inline-actions") {
            appendChild(buttonElement(text = "Add relay", enabled = !relayMigrationState.inProgress, onClick = ::addNoteRelay))
            appendChild(buttonElement(text = "Restore defaults", enabled = !relayMigrationState.inProgress, onClick = ::restoreDefaultNoteRelays))
            appendChild(buttonElement(text = "Sync/Migrate", enabled = canRunManualRelaySync(), onClick = ::syncCurrentNoteRelays))
        })
        manualRelaySyncDisabledCopy()?.let { copy ->
            appendChild(textElement("p", "body muted small-gap", copy))
        }
    }

private fun aboutWebPreviewContent(identity: WebAccountIdentity): WebElement =
    element("div", "panel-content") {
        appendChild(textElement("p", "body", "Signed in with ${identity.method.displayName} as ${identity.displayPublicKey}."))
        appendChild(textElement("p", "body", "Signing, encryption, and decryption remain client-side or signer-delegated."))
        appendChild(textElement("p", "body", "This web preview keeps auth, notes, drafts, relay choices, and pending writes in memory only. Remembered NIP-46 remote-signer reconnect is available only by explicit opt-in."))
        appendChild(textElement("p", "body", "Direct nsec fallback and generated identities are session-only. The web client has no durable browser storage for direct keys, notes, note relays, profile, search, or sort, no service worker, no tracking/reporting services, and no backend note processing."))
        appendChild(textElement("p", "body small-gap", "Android and Debian/Linux desktop are the current active native targets."))
    }

private fun themeSelectorPanel(): WebElement =
    element("section", "panel theme-panel") {
        appendChild(textElement("h2", "section-title", "Theme"))
        appendChild(themeSelectorContent())
    }

private fun themeSelectorContent(): WebElement =
    element("div", "panel-content theme-selector") {
        appendChild(textElement("p", "body", "Choose a built-in visual theme. This stores only a generic theme ID in this browser."))
        appendChild(themeSelectElement())
    }

private fun themeSelectElement(): WebElement =
    element("label", "field-label theme-select-field") {
        appendChild(textElement("span", "field-label-text", "Visual theme"))
        appendChild(
            element("select", "text-input theme-select").also { select ->
                select.setAttribute("aria-label", "Visual theme")
                BuiltInWebThemes.forEach { theme ->
                    select.appendChild(element("option", "") {
                        setAttribute("value", theme.id)
                        if (theme.id == selectedThemeId) {
                            setAttribute("selected", "selected")
                        }
                        textContent = theme.label
                    })
                }
                select.value = webThemeForId(selectedThemeId).id
                select.addEventListener("change") {
                    selectWebTheme(select.value)
                }
            },
        )
    }

private fun notesPanel(identity: WebAccountIdentity, notes: WebNoteLoadState): WebElement =
    element("section", WebNotesPanelClass) {
        val crudSigner = crudSignerFor(identity)
        val canCrud = crudSigner != null
        appendChild(textElement("h2", "section-title", "Notes"))
        appendChild(textElement("p", "body", "Read and edit encrypted notes in this in-memory web session."))
        appendChild(element("div", "inline-actions notes-actions") {
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
        })
        if (!canCrud) {
            appendChild(textElement("p", "body error small-gap", WebNoteCopy.CrudCapabilityUnavailable))
        }
        appendChild(noteListControlsPanel())
        appendChild(noteEditorPanel(identity, crudSigner))
        appendChild(element("div", "notes-results") {
            setAttribute("id", WebNotesResultsContainerId)
            appendChild(notesResultsContent(notes, canCrud))
        })
    }

private fun noteListControlsPanel(): WebElement =
    element("div", "note-list-controls") {
        appendChild(textInputElement(
            label = "Search notes",
            value = noteListControls.searchQuery,
            enabled = true,
            inputType = "text",
            placeholder = "Search loaded notes",
            onInput = ::updateNoteSearchQuery,
        ))
        appendChild(noteSortSelectElement())
    }

private fun noteSortSelectElement(): WebElement =
    element("label", "field-label note-sort-field") {
        appendChild(textElement("span", "field-label-text", "Sort notes"))
        appendChild(
            element("select", "text-input sort-select").also { select ->
                select.setAttribute("aria-label", "Sort notes")
                BuiltInWebNoteSortOptions.forEach { option ->
                    select.appendChild(element("option", "") {
                        setAttribute("value", option.id)
                        if (option.id == noteListControls.sortId) {
                            setAttribute("selected", "selected")
                        }
                        textContent = option.label
                    })
                }
                select.value = webNoteSortOptionForId(noteListControls.sortId).id
                select.addEventListener("change") {
                    updateNoteSortOrder(select.value)
                }
            },
        )
    }

private fun notesResultsContent(notes: WebNoteLoadState, canCrud: Boolean): WebElement =
    element("div", "notes-results-content") {
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
                val displayNotes = webNoteListDisplayNotes(notes.notes, noteListControls)
                if (displayNotes.isEmpty() && noteListControls.searchQuery.trim().isNotEmpty()) {
                    appendChild(textElement("p", "body muted small-gap", "No matching notes."))
                } else {
                    appendChild(noteLanes(displayNotes, canCrud, noteLaneCount))
                }
            }
        }
    }

private fun noteLanes(notes: List<WebReadOnlyNote>, canCrud: Boolean, laneCount: Int): WebElement =
    element("div", WebNoteGridClass) {
        distributeWebNoteLanes(notes, laneCount).forEach { laneNotes ->
            appendChild(element("div", WebNoteLaneClass) {
                laneNotes.forEach { note -> appendChild(noteCard(note, canCrud)) }
            })
        }
    }

private fun noteCard(note: WebReadOnlyNote, canCrud: Boolean): WebElement =
    element("article", "note-card") {
        val preview = webNotePreview(note.bodyMarkdown)
        appendChild(element("div", "note-card-open") {
            setAttribute("role", "button")
            setAttribute("tabindex", "0")
            setAttribute("aria-label", "Open note ${preview.title}")
            appendChild(textElement("h3", "note-title", preview.title))
            if (preview.snippet.isNotBlank()) {
                appendChild(textElement("p", "note-snippet", preview.snippet))
            }
            appendChild(textElement("p", "note-meta", "Last edited ${formatWebNoteTimestamp(note.updatedAtMs)}"))
            addEventListener("click") {
                openNoteDetail(note)
            }
            addEventListener("keydown") { event ->
                val key = event.asDynamic().key as? String
                if (key == "Enter" || key == " ") {
                    event.asDynamic().preventDefault()
                    openNoteDetail(note)
                }
            }
        })
        if (canCrud) {
            appendChild(element("div", WebNoteCardActionsClass) {
                appendChild(buttonElement(text = "Edit", enabled = true, onClick = { startEditNote(note) }))
                appendChild(buttonElement(text = "Delete", enabled = true, onClick = { confirmDeleteNote(note) }))
            })
        }
    }

private fun noteDetailPanel(note: WebReadOnlyNote): WebElement =
    element("div", "modal-backdrop") {
        val preview = webNotePreview(note.bodyMarkdown)
        appendChild(element("section", "panel modal-panel note-detail-panel") {
            appendChild(element("div", "modal-header") {
                appendChild(textElement("h2", "section-title", preview.title))
                appendChild(buttonElement(text = "Close", enabled = true, onClick = ::closeNoteDetail))
            })
            appendChild(textElement("p", "note-meta", "Last edited ${formatWebNoteTimestamp(note.updatedAtMs)}"))
            appendChild(element("div", "note-detail-body") {
                appendChild(renderMarkdown(note.bodyMarkdown))
            })
        })
    }

private fun viewedNoteForCurrentState(): WebReadOnlyNote? {
    val noteId = noteDetailState.openNoteId ?: return null
    val loaded = noteState as? WebNoteLoadState.Loaded ?: return null
    return loaded.notes.firstOrNull { it.id == noteId }
}

private fun openNoteDetail(note: WebReadOnlyNote) {
    noteDetailState = openWebNoteDetail(note.id)
    render()
}

private fun closeNoteDetail() {
    noteDetailState = closeWebNoteDetail()
    render()
}

private fun cancelNoteEditAndRender() {
    cancelNoteEdit()
    render()
}

private fun clearNoteDetail() {
    noteDetailState = closeWebNoteDetail()
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
                appendChild(buttonElement(text = "Cancel", enabled = true, onClick = ::cancelNoteEditAndRender))
            })
        }
        is WebNoteEditMode.ConfirmingDelete -> element("section", "editor-panel") {
            appendChild(textElement("h3", "section-title", "Delete note?"))
            appendChild(textElement("p", "body", "This publishes an encrypted tombstone event for the selected note."))
            if (noteEditMessage.isNotBlank()) appendChild(textElement("p", "body error", noteEditMessage))
            appendChild(element("div", "inline-actions") {
                appendChild(buttonElement(text = "Delete note", enabled = signer != null, onClick = { deleteConfirmedNote(identity, mode.note, signer) }))
                appendChild(buttonElement(text = "Cancel", enabled = true, onClick = ::cancelNoteEditAndRender))
            })
        }
        is WebNoteEditMode.Busy -> element("section", "editor-panel") {
            appendChild(textElement("h3", "section-title", "Saving note"))
            appendChild(textElement("p", "body", mode.message))
        }
    }

private fun sectionTitleWithInfo(title: String, topic: WebSignInInfoTopic): WebElement =
    element("div", "section-heading-row") {
        appendChild(textElement("h2", "section-title", title))
        appendChild(signInInfoButton(topic))
    }

private fun signInInfoButton(topic: WebSignInInfoTopic): WebElement {
    val copy = webSignInInfoCopy(topic)
    return element("button", "info-button").also { button ->
        button.setAttribute("type", "button")
        button.setAttribute("aria-label", "About ${copy.title}")
        button.textContent = "?"
        button.addEventListener("click") {
            activeSignInInfoTopic = topic
            render()
        }
    }
}

private fun signInInfoModal(topic: WebSignInInfoTopic): WebElement {
    val copy = webSignInInfoCopy(topic)
    val titleId = "sign-in-info-${topic.name.lowercase()}-title"
    return element("div", "modal-backdrop") {
        appendChild(element("section", "panel modal-panel sign-in-info-panel") {
            setAttribute("role", "dialog")
            setAttribute("aria-modal", "true")
            setAttribute("aria-labelledby", titleId)
            appendChild(element("div", "modal-header") {
                appendChild(textElement("h2", "section-title", copy.title).also { it.setAttribute("id", titleId) })
                appendChild(buttonElement(text = "Close", enabled = true, onClick = ::closeSignInInfo))
            })
            appendChild(element("div", "panel-content") {
                copy.body.forEach { line ->
                    appendChild(textElement("p", "body small-gap", line))
                }
            })
        })
    }
}

private fun closeSignInInfo() {
    activeSignInInfoTopic = null
    render()
}

private fun nip07SignInPanel(state: WebAuthUiState): WebElement =
    element("section", "panel") {
        appendChild(sectionTitleWithInfo("Browser extension sign-in", WebSignInInfoTopic.Nip07))
        when (val signInState = state.signInState) {
            WebSignInState.SignedOut -> {
                appendChild(
                    textElement(
                        "p",
                        "body",
                        if (state.nip07Available) {
                            "NIP-07 signer detected."
                        } else {
                            "NIP-07 extension not detected."
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
                appendChild(textElement("p", "body", "Browser extension sign-in is available."))
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
                            "NIP-07 signer detected."
                        } else {
                            "NIP-07 extension not detected."
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
        appendChild(sectionTitleWithInfo("Remote signer / NIP-46", WebSignInInfoTopic.Nip46))
        appendChild(rememberedNip46SessionPanel(state))
        appendChild(textInputElement(
            label = Nip46TokenInputLabel,
            value = nip46TokenInput,
            enabled = state.signInState !is WebSignInState.SigningIn,
            onInput = { value -> nip46TokenInput = value },
        ))
        appendChild(checkboxElement(
            label = RememberNip46CheckboxLabel,
            checked = rememberNip46Session,
            enabled = state.signInState !is WebSignInState.SigningIn,
            onChange = ::setRememberNip46Session,
        ))
        appendChild(element("div", "inline-info-row") {
            appendChild(textElement("p", "body muted", rememberNip46OptInLabel(rememberNip46Session)))
            appendChild(signInInfoButton(WebSignInInfoTopic.RememberedNip46))
        })
        if (rememberNip46Session) {
            appendChild(textElement("p", "body muted small-gap", "Use only on a trusted browser/device."))
        }
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

private fun rememberedNip46SessionPanel(state: WebAuthUiState): WebElement =
    element("div", "remembered-signer-panel") {
        val record = rememberedNip46State.record
        if (record != null) {
            appendChild(element("div", "inline-info-row remembered-signer-summary") {
                appendChild(textElement("p", "body small-gap", "Remembered remote signer: ${WebAccountIdentity(record.userPubkey, WebAuthMethod.Nip46).displayPublicKey}"))
                appendChild(signInInfoButton(WebSignInInfoTopic.RememberedNip46))
            })
            appendChild(element("div", "inline-actions remembered-signer-actions") {
                appendChild(buttonElement(
                    text = "Continue with remembered remote signer",
                    enabled = state.signInState !is WebSignInState.SigningIn,
                    onClick = ::requestRememberedNip46Session,
                ))
                appendChild(buttonElement(
                    text = "Forget remembered remote signer",
                    enabled = state.signInState !is WebSignInState.SigningIn,
                    onClick = ::forgetRememberedNip46Session,
                ))
            })
        } else if (rememberedNip46State.invalidStoredSession) {
            appendChild(textElement("p", "body error small-gap", rememberedNip46State.message))
            appendChild(buttonElement(
                text = "Forget remembered remote signer",
                enabled = state.signInState !is WebSignInState.SigningIn,
                onClick = ::forgetRememberedNip46Session,
            ))
        } else if (rememberedNip46State.message.isNotBlank()) {
            appendChild(textElement("p", "body muted small-gap", rememberedNip46State.message))
        }
    }

private fun directNsecSignInPanel(state: WebAuthUiState): WebElement =
    element("section", "panel fallback-panel") {
        appendChild(textElement("p", "eyebrow", "Session-only fallback"))
        appendChild(sectionTitleWithInfo("Use session-only nsec", WebSignInInfoTopic.DirectNsec))
        appendChild(textElement("p", "body", "Session-only. Other Note will not save this key."))
        appendChild(textInputElement(
            label = DirectNsecInputLabel,
            value = directNsecDraft.input,
            enabled = state.signInState !is WebSignInState.SigningIn,
            inputType = DirectNsecInputType,
            autocomplete = DirectNsecInputAutocomplete,
            placeholder = DirectNsecInputPlaceholder,
            onInput = { value ->
                directNsecDraft = updateWebDirectNsecDraft(directNsecDraft, value)
            },
        ))
        val failed = state.signInState as? WebSignInState.Failed
        val message = directNsecDraft.message.takeIf { it.isNotBlank() }
            ?: failed?.takeIf { it.method == WebAuthMethod.DirectNsec }?.message
        if (message != null) {
            appendChild(textElement("p", "body error small-gap", message))
        }
        appendChild(
            buttonElement(
                text = if (state.signInState is WebSignInState.SigningIn && state.signInState.method == WebAuthMethod.DirectNsec) {
                    "Direct key sign-in pending"
                } else {
                    DirectNsecSubmitLabel
                },
                enabled = state.signInState !is WebSignInState.SigningIn,
                onClick = ::requestDirectNsecSession,
            ),
        )
    }

private fun generatedIdentityPanel(state: WebAuthUiState): WebElement =
    element("section", "panel fallback-panel") {
        appendChild(textElement("p", "eyebrow", "Session only"))
        appendChild(sectionTitleWithInfo("Create new identity", WebSignInInfoTopic.GeneratedIdentity))
        appendChild(textElement("p", "body", "Generate a fresh session-only Nostr identity."))
        val secret = generatedIdentityState.secret
        if (secret == null) {
            if (generatedIdentityState.message.isNotBlank()) {
                appendChild(textElement("p", "body small-gap", generatedIdentityState.message))
            }
            appendChild(buttonElement(
                text = "Generate fresh identity",
                enabled = state.signInState !is WebSignInState.SigningIn,
                onClick = ::generateFreshIdentity,
            ))
        } else {
            appendChild(textElement("p", "body small-gap", "Save this nsec before continuing. Other Note cannot recover it."))
            appendChild(textElement("p", "body small-gap", "Generated public identity: ${secret.displayPublicKey}"))
            appendChild(textElement("pre", "generated-secret-display", secret.revealNsec()))
            appendChild(element("div", "checkbox-list") {
                appendChild(checkboxElement(
                    label = GeneratedIdentityRecoverAckLabel,
                    checked = generatedIdentityState.recoverAcknowledged,
                    enabled = state.signInState !is WebSignInState.SigningIn,
                    onChange = ::acknowledgeGeneratedIdentityRecovery,
                ))
                appendChild(checkboxElement(
                    label = GeneratedIdentitySavedAckLabel,
                    checked = generatedIdentityState.savedAcknowledged,
                    enabled = state.signInState !is WebSignInState.SigningIn,
                    onChange = ::acknowledgeGeneratedIdentitySaved,
                ))
                appendChild(checkboxElement(
                    label = GeneratedIdentityLossAckLabel,
                    checked = generatedIdentityState.lossAcknowledged,
                    enabled = state.signInState !is WebSignInState.SigningIn,
                    onChange = ::acknowledgeGeneratedIdentityLoss,
                ))
            })
            if (generatedIdentityState.message.isNotBlank()) {
                appendChild(textElement("p", "body error small-gap", generatedIdentityState.message))
            }
            appendChild(element("div", "inline-actions") {
                appendChild(buttonElement(
                    text = GeneratedIdentitySubmitLabel,
                    enabled = generatedIdentityState.canUseForSession && state.signInState !is WebSignInState.SigningIn,
                    onClick = ::requestGeneratedIdentitySession,
                ))
                appendChild(buttonElement(
                    text = "Cancel",
                    enabled = state.signInState !is WebSignInState.SigningIn,
                    onClick = ::cancelGeneratedIdentityFlow,
                ))
            })
        }
    }

private fun requestNip07PublicKey() {
    val generation = nextAuthAttemptGeneration()
    val signer = window.nostr
    if (signer == null) {
        clearDirectNsecDraft()
        clearGeneratedIdentityState()
        authState = beginNip07SignIn(authState.copy(nip07Available = false))
        render()
        return
    }

    clearDirectNsecDraft()
    clearGeneratedIdentityState()
    authState = beginNip07SignIn(authState.copy(nip07Available = true))
    render()

    try {
        signer.getPublicKey().then(
            { publicKey ->
                if (!isCurrentAuthAttempt(generation)) return@then
                noteState = WebNoteLoadState.Idle
                clearDirectKeySession()
                resetLoadedNotes()
                resetNoteListControlsState()
                cancelNoteEdit()
                resetProfile()
                resetRelayListState()
                resetRelayMigrationState()
                resetRelayStatsState()
                authState = completeNip07SignIn(authState, publicKey)
                render()
                fetchProfileForSignedInSession()
                refreshRelayListForSignedInSession(reloadNotesOnImport = true)
                refreshRelayStatsForSignedInSession()
                autoLoadNotesForSignedInSession()
            },
            {
                if (!isCurrentAuthAttempt(generation)) return@then
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
    val generation = nextAuthAttemptGeneration()
    clearDirectNsecDraft()
    clearGeneratedIdentityState()
    authState = beginNip46SignIn(authState)
    render()
    clearDirectKeySession()
    activeNip46RemoteSigner.disconnect()
    activeNip46RemoteSigner = WebNip46RemoteSigner()
    activeNip46RemoteSigner.connectWithBunkerToken(
        rawToken = nip46TokenInput,
        onProgress = { status, message ->
            if (!isCurrentAuthAttempt(generation)) return@connectWithBunkerToken
            authState = updateNip46Progress(authState, status, message)
            render()
        },
        onResult = { result ->
            if (!isCurrentAuthAttempt(generation)) return@connectWithBunkerToken
            when (result) {
                is WebNip46ConnectResult.Connected -> {
                    val rememberMessage = rememberConnectedNip46Session(result)
                    activateNip46SignedInSession(result.userPubkey)
                    if (rememberMessage != null) {
                        authState = authState.copy(nip46Message = rememberMessage)
                    }
                    render()
                }
                is WebNip46ConnectResult.Failed -> {
                    authState = failNip46SignIn(authState, result.safeMessage)
                    render()
                }
            }
        },
    )
}

private fun requestRememberedNip46Session() {
    val record = rememberedNip46State.record ?: run {
        rememberedNip46State = rememberedNip46StateFromStorage(rememberedNip46StorageOrNull())
        render()
        return
    }
    val session = record.toSession()
    if (session == null) {
        rememberedNip46State = WebRememberedNip46UiState(
            invalidStoredSession = true,
            message = WebRememberedNip46Copy.InvalidStoredSession,
        )
        render()
        return
    }
    val generation = nextAuthAttemptGeneration()
    clearDirectNsecDraft()
    clearGeneratedIdentityState()
    clearDirectKeySession()
    nip46TokenInput = ""
    authState = updateNip46Progress(
        beginNip46SignIn(authState),
        WebNip46Status.RequestingPublicKey,
        "Checking remembered remote signer session.",
    )
    render()
    activeNip46RemoteSigner.disconnect()
    activeNip46RemoteSigner = WebNip46RemoteSigner()
    activeNip46RemoteSigner.resumeWithSession(
        session = session,
        onProgress = { status, message ->
            if (!isCurrentAuthAttempt(generation)) return@resumeWithSession
            authState = updateNip46Progress(authState, status, message)
            render()
        },
        onResult = { result ->
            if (!isCurrentAuthAttempt(generation)) return@resumeWithSession
            when (result) {
                is WebNip46ConnectResult.Connected -> {
                    if (!result.userPubkey.equals(record.userPubkey, ignoreCase = true)) {
                        activeNip46RemoteSigner.disconnect()
                        authState = failNip46SignIn(authState, WebRememberedNip46Copy.ReconnectFailed)
                        rememberedNip46State = rememberedNip46State.copy(message = WebRememberedNip46Copy.ReconnectFailed)
                        render()
                        return@resumeWithSession
                    }
                    updateRememberedNip46LastUsed(record)
                    activateNip46SignedInSession(result.userPubkey)
                }
                is WebNip46ConnectResult.Failed -> {
                    authState = failNip46SignIn(authState, WebRememberedNip46Copy.ReconnectFailed)
                    rememberedNip46State = rememberedNip46State.copy(message = WebRememberedNip46Copy.ReconnectFailed)
                    render()
                }
            }
        },
    )
}

private fun setRememberNip46Session(remember: Boolean) {
    rememberNip46Session = remember
    render()
}

private fun rememberConnectedNip46Session(result: WebNip46ConnectResult.Connected): String? {
    if (!rememberNip46Session) return null
    val session = result.session ?: activeNip46RemoteSigner.activeSessionSnapshot()
        ?: return WebRememberedNip46Copy.StoreFailed
    val record = rememberedNip46RecordFromSession(
        session = session,
        userPubkey = result.userPubkey,
        nowMs = nowMs(),
    ) ?: return WebRememberedNip46Copy.StoreFailed
    return if (saveRememberedNip46Record(rememberedNip46StorageOrNull(), record)) {
        rememberedNip46State = WebRememberedNip46UiState(record = record, message = WebRememberedNip46Copy.Stored)
        WebRememberedNip46Copy.Stored
    } else {
        WebRememberedNip46Copy.StoreFailed
    }
}

private fun updateRememberedNip46LastUsed(record: WebRememberedNip46Record) {
    val updated = record.copy(updatedAtMs = nowMs())
    if (saveRememberedNip46Record(rememberedNip46StorageOrNull(), updated)) {
        rememberedNip46State = WebRememberedNip46UiState(record = updated)
    }
}

private fun activateNip46SignedInSession(userPubkey: String) {
    nip46TokenInput = ""
    rememberNip46Session = false
    noteState = WebNoteLoadState.Idle
    clearDirectKeySession()
    resetLoadedNotes()
    resetNoteListControlsState()
    cancelNoteEdit()
    resetProfile()
    resetRelayListState()
    resetRelayMigrationState()
    resetRelayStatsState()
    authState = completeNip46SignIn(authState, userPubkey)
    render()
    fetchProfileForSignedInSession()
    refreshRelayListForSignedInSession(reloadNotesOnImport = true)
    refreshRelayStatsForSignedInSession()
    autoLoadNotesForSignedInSession()
}

private fun forgetRememberedNip46Session() {
    val previousRecord = rememberedNip46State.record
    val removed = forgetRememberedNip46Record(rememberedNip46StorageOrNull())
    if (!removed) {
        rememberedNip46State = rememberedNip46State.copy(message = WebRememberedNip46Copy.ForgetFailed)
        render()
        return
    }
    val active = authState.signInState as? WebSignInState.SignedIn
    val clearsActiveSession = active?.identity?.method == WebAuthMethod.Nip46 &&
        previousRecord != null &&
        active.identity.publicKeyHex.equals(previousRecord.userPubkey, ignoreCase = true)
    if (clearsActiveSession) {
        logout()
        rememberedNip46State = WebRememberedNip46UiState(message = WebRememberedNip46Copy.ForgottenActive)
    } else {
        rememberedNip46State = WebRememberedNip46UiState(message = WebRememberedNip46Copy.Forgotten)
    }
    render()
}

private fun requestDirectNsecSession() {
    val submitted = directNsecDraft.input
    clearDirectNsecDraft()
    clearGeneratedIdentityState()
    startDirectKeySession(submitted, ::failDirectNsecSignIn)
}

private fun generateFreshIdentity() {
    nextAuthAttemptGeneration()
    clearDirectNsecDraft()
    clearDirectKeySession()
    activeNip46RemoteSigner.disconnect()
    nip46TokenInput = ""
    generatedIdentityState = when (val generated = generateWebDirectKeyIdentity()) {
        is WebGeneratedIdentityResult.Success -> WebGeneratedIdentityState(
            secret = generated.secret,
            message = WebDirectKeyCopy.GeneratedIdentityReady,
        )
        is WebGeneratedIdentityResult.Failed -> clearWebGeneratedIdentityState(generated.safeMessage)
    }
    authState = authState.copy(
        signInState = WebSignInState.SignedOut,
        nip46Status = WebNip46Status.Idle,
        nip46Message = "",
    )
    render()
}

private fun acknowledgeGeneratedIdentityRecovery(acknowledged: Boolean) {
    generatedIdentityState = acknowledgeWebGeneratedIdentityRecovery(generatedIdentityState, acknowledged)
    render()
}

private fun acknowledgeGeneratedIdentitySaved(acknowledged: Boolean) {
    generatedIdentityState = acknowledgeWebGeneratedIdentitySaved(generatedIdentityState, acknowledged)
    render()
}

private fun acknowledgeGeneratedIdentityLoss(acknowledged: Boolean) {
    generatedIdentityState = acknowledgeWebGeneratedIdentityLoss(generatedIdentityState, acknowledged)
    render()
}

private fun cancelGeneratedIdentityFlow() {
    clearGeneratedIdentityState(WebDirectKeyCopy.GeneratedIdentityCancelled)
    render()
}

private fun requestGeneratedIdentitySession() {
    val secret = generatedIdentityState.secret
    if (!generatedIdentityState.canUseForSession || secret == null) {
        generatedIdentityState = generatedIdentityState.copy(message = WebDirectKeyCopy.GeneratedIdentityRequiresAcknowledgement)
        render()
        return
    }
    val submitted = secret.revealNsec()
    clearGeneratedIdentityState()
    clearDirectNsecDraft()
    startDirectKeySession(submitted, ::failGeneratedIdentitySignIn)
}

private fun startDirectKeySession(rawNsec: String, onFailure: (String) -> Unit) {
    nextAuthAttemptGeneration()
    clearDirectKeySession()
    activeNip46RemoteSigner.disconnect()
    nip46TokenInput = ""
    authState = authState.copy(
        signInState = WebSignInState.SigningIn(WebAuthMethod.DirectNsec),
        nip46Status = WebNip46Status.Idle,
        nip46Message = "",
    )
    render()
    when (val result = createWebDirectKeySession(rawNsec)) {
        is WebDirectKeyLoginResult.Success -> {
            activeDirectKeySession = result.session
            noteState = WebNoteLoadState.Idle
            resetLoadedNotes()
            resetNoteListControlsState()
            cancelNoteEdit()
            resetProfile()
            resetRelayListState()
            resetRelayMigrationState()
            resetRelayStatsState()
            authState = WebAuthUiState(
                nip07Available = isNip07Available(),
                signInState = WebSignInState.SignedIn(result.identity),
            )
            render()
            fetchProfileForSignedInSession()
            refreshRelayListForSignedInSession(reloadNotesOnImport = true)
            refreshRelayStatsForSignedInSession()
            autoLoadNotesForSignedInSession()
        }
        is WebDirectKeyLoginResult.Invalid -> onFailure(result.safeMessage)
        is WebDirectKeyLoginResult.Unavailable -> onFailure(result.safeMessage)
    }
}

private fun failDirectNsecSignIn(safeMessage: String) {
    clearDirectKeySession()
    clearDirectNsecDraft(message = safeMessage)
    authState = authState.copy(
        signInState = WebSignInState.Failed(safeMessage, WebAuthMethod.DirectNsec),
        nip46Status = WebNip46Status.Idle,
        nip46Message = "",
    )
    render()
}

private fun failGeneratedIdentitySignIn(safeMessage: String) {
    clearDirectKeySession()
    clearGeneratedIdentityState(message = safeMessage)
    authState = authState.copy(
        signInState = WebSignInState.SignedOut,
        nip46Status = WebNip46Status.Idle,
        nip46Message = "",
    )
    render()
}

private fun autoLoadNotesForSignedInSession() {
    val signedIn = authState.signInState as? WebSignInState.SignedIn ?: return
    loadReadOnlyNotes(signedIn.identity)
}

private fun fetchProfileForSignedInSession() {
    val signedIn = authState.signInState as? WebSignInState.SignedIn ?: return
    loadProfileMetadata(signedIn.identity)
}

private fun refreshRelayListForSignedInSession(reloadNotesOnImport: Boolean) {
    val signedIn = authState.signInState as? WebSignInState.SignedIn ?: return
    loadPublishedRelayList(signedIn.identity, reloadNotesOnImport)
}

private fun loadProfileMetadata(identity: WebAccountIdentity) {
    val started = profileLoadGuard.start(identity)
    profileLoadGuard = started.guard
    val request = started.request
    activeProfileFetcher.close()
    activeProfileFetcher = profileFetcherForCurrentRelays()
    profileState = WebProfileUiState(
        loading = true,
        pubkey = identity.publicKeyHex,
        metadata = profileState.metadata?.takeIf { it.pubkey == identity.publicKeyHex },
    )
    render()
    activeProfileFetcher.fetch(identity.publicKeyHex) { profile ->
        if (!profileLoadGuard.accepts(request, authState)) return@fetch
        profileState = if (profile == null) {
            WebProfileUiState(
                loading = false,
                pubkey = identity.publicKeyHex,
                metadata = profileState.metadata?.takeIf { it.pubkey == identity.publicKeyHex },
                safeMessage = WebProfileCopy.Unavailable,
            )
        } else {
            WebProfileUiState(loading = false, pubkey = identity.publicKeyHex, metadata = profile)
        }
        render()
    }
}

private fun loadPublishedRelayList(identity: WebAccountIdentity, reloadNotesOnImport: Boolean) {
    val started = relayListLoadGuard.start(identity)
    relayListLoadGuard = started.guard
    val request = started.request
    activeRelayListFetcher.close()
    activeRelayListFetcher = relayListFetcherForCurrentRelays()
    relayListState = relayListState.copy(
        loading = true,
        pubkey = identity.publicKeyHex,
        message = WebRelayListCopy.Loading,
    )
    render()
    activeRelayListFetcher.fetch(identity.publicKeyHex) { published ->
        if (!relayListLoadGuard.accepts(request, authState)) return@fetch
        if (published == null) {
            relayListState = relayListState.copy(
                loading = false,
                pubkey = identity.publicKeyHex,
                message = WebRelayListCopy.NoPublishedWriteRelays,
                pendingPublishedRelays = emptyList(),
                latestPublishedRelayList = null,
            )
            render()
            return@fetch
        }
        relayListState = relayListState.copy(latestPublishedRelayList = published)
        applyPublishedRelayList(
            publishedRelays = published.writeRelayUrls,
            reloadNotesOnImport = reloadNotesOnImport,
            identity = identity,
        )
    }
}

private fun loadReadOnlyNotes(identity: WebAccountIdentity) {
    val started = noteLoadGuard.start(identity)
    noteLoadGuard = started.guard
    val request = started.request
    activeNoteLoader.close()
    activeNoteLoader = noteLoaderForCurrentRelays()
    val decryptor = when (identity.method) {
        WebAuthMethod.Nip46 -> WebNip46NoteDecryptor(activeNip46RemoteSigner, identity.publicKeyHex)
        WebAuthMethod.Nip07 -> {
            val nip44 = window.nostr?.nip44
            if (nip44 == null) null else WebNip07NoteDecryptor(nip44, identity.publicKeyHex)
        }
        WebAuthMethod.DirectNsec -> directKeySessionFor(identity)?.let(::WebDirectKeyNoteDecryptor)
    }
    noteState = WebNoteLoadState.Loading("Preparing read-only note load.")
    render()
    activeNoteLoader.load(
        accountPubkey = identity.publicKeyHex,
        decryptor = decryptor,
        onProgress = { message ->
            if (!noteLoadGuard.accepts(request, authState)) return@load
            noteState = WebNoteLoadState.Loading(message)
            render()
        },
        onResult = { result ->
            if (!noteLoadGuard.accepts(request, authState)) return@load
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
        WebAuthMethod.DirectNsec -> directKeySessionFor(identity)?.let(::WebDirectKeyNoteCrudSigner)
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
                    refreshRelayStatsForSignedInSession()
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
                    refreshRelayStatsForSignedInSession()
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

private fun resetNoteListControlsState() {
    noteListControls = resetWebNoteListControls()
}

private fun resetProfile() {
    profileState = WebProfileUiState()
}

private fun resetRelayListState() {
    relayListState = WebRelayListUiState()
}

private fun resetRelayMigrationState() {
    relayMigrationState = WebRelayMigrationUiState()
}

private fun resetRelayStatsState() {
    relayStatsState = WebRelayStatsUiState()
}

private fun clearDirectKeySession() {
    activeDirectKeySession?.clear()
    activeDirectKeySession = null
}

private fun clearDirectNsecDraft(message: String = "") {
    directNsecDraft = clearWebDirectNsecDraft(message)
}

private fun clearGeneratedIdentityState(message: String = "") {
    generatedIdentityState = clearWebGeneratedIdentityState(message)
}

private fun updateNoteRelayInput(value: String) {
    noteRelaySettings = updateWebNoteRelayInput(noteRelaySettings, value)
}

private fun updateNoteSearchQuery(value: String) {
    noteListControls = noteListControls.copy(searchQuery = value)
    refreshNotesResultsOnly()
}

private fun updateNoteSortOrder(sortId: String) {
    noteListControls = noteListControls.copy(sortId = webNoteSortOptionForId(sortId).id)
    refreshNotesResultsOnly()
}

private fun selectWebTheme(themeId: String) {
    selectedThemeId = saveWebThemePreference(webThemeStorageOrNull(), themeId)
    applyWebTheme(selectedThemeId)
    render()
}

private fun applyWebTheme(themeId: String) {
    val theme = webThemeForId(themeId)
    document.documentElement.setAttribute("data-theme", theme.id)
    document.documentElement.setAttribute("data-theme-mode", if (theme.dark) "dark" else "light")
}

private fun loadInitialWebThemeId(): String =
    loadWebThemePreference(webThemeStorageOrNull())

private fun webThemeStorageOrNull(): WebThemePreferenceStorage? =
    runCatching {
        object : WebThemePreferenceStorage {
            override fun read(key: String): String? =
                localStorage.getItem(key)

            override fun write(key: String, value: String) {
                localStorage.setItem(key, value)
            }
        }
    }.getOrNull()

private fun loadInitialRememberedNip46State(): WebRememberedNip46UiState =
    rememberedNip46StateFromStorage(rememberedNip46StorageOrNull())

private fun rememberedNip46StorageOrNull(): WebRememberedNip46Storage? =
    runCatching {
        object : WebRememberedNip46Storage {
            override fun read(key: String): String? =
                localStorage.getItem(key)

            override fun write(key: String, value: String) {
                localStorage.setItem(key, value)
            }

            override fun remove(key: String) {
                localStorage.removeItem(key)
            }
        }
    }.getOrNull()

private fun nextAuthAttemptGeneration(): Int {
    authAttemptGeneration += 1
    return authAttemptGeneration
}

private fun isCurrentAuthAttempt(generation: Int): Boolean =
    generation == authAttemptGeneration

private fun nowMs(): Long =
    (js("Date.now()") as Double).toLong()

private fun refreshNotesResultsOnly() {
    val signedIn = authState.signInState as? WebSignInState.SignedIn ?: return
    val container = document.getElementById(WebNotesResultsContainerId)
    if (container == null) {
        render()
        return
    }
    container.innerHTML = ""
    container.appendChild(notesResultsContent(noteState, crudSignerFor(signedIn.identity) != null))
}

private fun addNoteRelay() {
    requestNoteRelaySettingsChange(addWebNoteRelay(noteRelaySettings))
}

private fun removeNoteRelay(relay: String) {
    requestNoteRelaySettingsChange(removeWebNoteRelay(noteRelaySettings, relay))
}

private fun restoreDefaultNoteRelays() {
    requestNoteRelaySettingsChange(restoreDefaultWebNoteRelays(noteRelaySettings))
}

private fun keepCurrentRelaySettings() {
    relayListState = keepLocalWebRelayEdits(relayListState)
    render()
}

private fun applyPendingPublishedRelays() {
    val identity = (authState.signInState as? WebSignInState.SignedIn)?.identity
    when (val decision = applyPendingPublishedWebNoteRelays(noteRelaySettings, relayListState)) {
        is WebRelayListImportDecision.Applied -> {
            relayListState = decision.relayState
            requestNoteRelaySettingsChange(decision.settings)
        }
        is WebRelayListImportDecision.Deferred -> handleRelayListImportDecision(decision, reloadNotesOnImport = true, identity = identity)
        is WebRelayListImportDecision.KeptCurrent -> handleRelayListImportDecision(decision, reloadNotesOnImport = true, identity = identity)
    }
}

private fun closeActivePanel() {
    webMenuState = closeWebMenuPanel(webMenuState)
    render()
}

private fun requestNoteRelaySettingsChange(next: WebNoteRelaySettingsState) {
    if (relayMigrationState.inProgress) return
    val previousRelays = selectedWebNoteRelays(noteRelaySettings)
    val requestedRelays = selectedWebNoteRelays(next)
    if (previousRelays == requestedRelays) {
        noteRelaySettings = next
        render()
        return
    }
    val identity = (authState.signInState as? WebSignInState.SignedIn)?.identity
    if (identity == null) {
        updateNoteRelaySettings(next)
        return
    }
    val started = relayMigrationGuard.start(identity)
    relayMigrationGuard = started.guard
    val request = started.request
    relayMigrationState = WebRelayMigrationUiState(
        inProgress = true,
        message = "Migrating encrypted notes before changing note relays.",
        pendingSettings = next,
    )
    render()
    activeRelayMigrationService.close()
    activeRelayMigrationService = WebRelayMigrationService()
    activeRelayMigrationService.migrate(
        accountPubkey = identity.publicKeyHex,
        oldRelays = previousRelays,
        newRelays = requestedRelays,
        existingRelayList = relayListStateLatest(),
        relayListSigner = relayListSignerFor(identity),
        onProgress = { message ->
            if (!relayMigrationGuard.accepts(request, authState)) return@migrate
            relayMigrationState = relayMigrationState.copy(inProgress = true, message = message)
            render()
        },
        onResult = { result ->
            if (!relayMigrationGuard.accepts(request, authState)) return@migrate
            if (result.fullSuccess) {
                relayMigrationState = WebRelayMigrationUiState(message = "Relay migration completed.")
                updateNoteRelaySettings(next)
                loadReadOnlyNotes(identity)
            } else {
                relayMigrationState = WebRelayMigrationUiState(
                    inProgress = false,
                    warning = webRelayMigrationWarning(result),
                    pendingSettings = next,
                )
                render()
            }
        },
    )
}

private fun syncCurrentNoteRelays() {
    if (!canRunManualRelaySync()) return
    val identity = (authState.signInState as? WebSignInState.SignedIn)?.identity ?: return
    val relays = selectedWebNoteRelays(noteRelaySettings)
    val started = relayMigrationGuard.start(identity)
    relayMigrationGuard = started.guard
    val request = started.request
    relayMigrationState = WebRelayMigrationUiState(
        inProgress = true,
        message = "Syncing encrypted note events across current note relays.",
    )
    render()
    activeRelayMigrationService.close()
    activeRelayMigrationService = WebRelayMigrationService()
    activeRelayMigrationService.syncCurrentRelays(
        accountPubkey = identity.publicKeyHex,
        relays = relays,
        onProgress = { message ->
            if (!relayMigrationGuard.accepts(request, authState)) return@syncCurrentRelays
            relayMigrationState = relayMigrationState.copy(inProgress = true, message = message)
            render()
        },
        onResult = { result ->
            if (!relayMigrationGuard.accepts(request, authState)) return@syncCurrentRelays
            relayMigrationState = when {
                result.fullSuccess -> WebRelayMigrationUiState(message = "Relay sync/migration completed.")
                result.onlyNoSourceEventsWarning -> WebRelayMigrationUiState(message = "No existing note events found on these relays.")
                else -> WebRelayMigrationUiState(warning = webRelayMigrationWarning(result))
            }
            if (isActiveIdentity(identity)) {
                refreshRelayStatsForSignedInSession()
                loadReadOnlyNotes(identity)
            } else {
                render()
            }
        },
    )
}

private fun cancelRelayMigration() {
    relayMigrationGuard = relayMigrationGuard.invalidate()
    activeRelayMigrationService.close()
    relayMigrationState = WebRelayMigrationUiState(message = "Relay settings were not changed.")
    render()
}

private fun continueRelayMigration() {
    val pending = relayMigrationState.pendingSettings ?: return
    val identity = (authState.signInState as? WebSignInState.SignedIn)?.identity
    relayMigrationGuard = relayMigrationGuard.invalidate()
    activeRelayMigrationService.close()
    relayMigrationState = WebRelayMigrationUiState(message = "Relay settings changed after migration warning.")
    updateNoteRelaySettings(pending)
    if (identity != null && isActiveIdentity(identity)) {
        loadReadOnlyNotes(identity)
    }
}

private fun updateNoteRelaySettings(next: WebNoteRelaySettingsState) {
    val previousRelays = selectedWebNoteRelays(noteRelaySettings)
    noteRelaySettings = next
    if (previousRelays != selectedWebNoteRelays(noteRelaySettings)) {
        rebuildNoteRelayClients()
        refreshRelayStatsForSignedInSession()
    }
    render()
}

private fun rebuildNoteRelayClients() {
    noteLoadGuard = noteLoadGuard.invalidate()
    profileLoadGuard = profileLoadGuard.invalidate()
    relayListLoadGuard = relayListLoadGuard.invalidate()
    relayMigrationGuard = relayMigrationGuard.invalidate()
    relayStatsGuard = relayStatsGuard.invalidate()
    activeNoteLoader.close()
    activeNoteCrudService.close()
    activeProfileFetcher.close()
    activeRelayListFetcher.close()
    activeRelayMigrationService.close()
    activeRelayStatsFetcher.close()
    activeNoteLoader = noteLoaderForCurrentRelays()
    activeNoteCrudService = noteCrudServiceForCurrentRelays()
    activeProfileFetcher = profileFetcherForCurrentRelays()
    activeRelayListFetcher = relayListFetcherForCurrentRelays()
    activeRelayMigrationService = WebRelayMigrationService()
    activeRelayStatsFetcher = relayStatsFetcherForCurrentRelays()
}

private fun noteLoaderForCurrentRelays(): WebNoteLoader =
    WebNoteLoader(WebNoteRelayFetcher(selectedWebNoteRelays(noteRelaySettings)))

private fun noteCrudServiceForCurrentRelays(): WebNoteCrudService =
    WebNoteCrudService(WebNoteRelayPublisher(selectedWebNoteRelays(noteRelaySettings)))

private fun profileFetcherForCurrentRelays(): WebProfileFetcher =
    WebProfileFetcher(selectedWebNoteRelays(noteRelaySettings))

private fun relayListFetcherForCurrentRelays(): WebRelayListFetcher =
    WebRelayListFetcher(selectedWebNoteRelays(noteRelaySettings))

private fun relayStatsFetcherForCurrentRelays(): WebRelayStatsFetcher =
    WebRelayStatsFetcher(selectedWebNoteRelays(noteRelaySettings))

private fun relayListStateLatest(): WebPublishedRelayList? =
    relayListState.latestPublishedRelayList

private fun relayListSignerFor(identity: WebAccountIdentity): WebNoteCrudSigner? =
    when (identity.method) {
        WebAuthMethod.Nip46 -> WebNip46NoteCrudSigner(activeNip46RemoteSigner, identity.publicKeyHex)
        WebAuthMethod.Nip07 -> {
            val signer = window.nostr
            if (signer.hasNip07SignCapabilityForRelayList()) WebNip07NoteCrudSigner(signer, identity.publicKeyHex) else null
        }
        WebAuthMethod.DirectNsec -> directKeySessionFor(identity)?.let(::WebDirectKeyNoteCrudSigner)
    }

private fun directKeySessionFor(identity: WebAccountIdentity): WebDirectKeySession? =
    activeDirectKeySession?.takeIf { session ->
        identity.method == WebAuthMethod.DirectNsec &&
            session.active &&
            session.publicKeyHex == identity.publicKeyHex
    }

private fun applyPublishedRelayList(
    publishedRelays: List<String>,
    reloadNotesOnImport: Boolean,
    identity: WebAccountIdentity?,
) {
    handleRelayListImportDecision(
        decision = importPublishedWebNoteRelays(noteRelaySettings, relayListState, publishedRelays),
        reloadNotesOnImport = reloadNotesOnImport,
        identity = identity,
    )
}

private fun handleRelayListImportDecision(
    decision: WebRelayListImportDecision,
    reloadNotesOnImport: Boolean,
    identity: WebAccountIdentity?,
) {
    when (decision) {
        is WebRelayListImportDecision.Applied -> {
            noteRelaySettings = decision.settings
            relayListState = decision.relayState
            if (decision.changed) {
                rebuildNoteRelayClients()
                refreshRelayStatsForSignedInSession()
                if (reloadNotesOnImport && identity != null && isActiveIdentity(identity)) {
                    loadReadOnlyNotes(identity)
                    return
                }
            }
        }
        is WebRelayListImportDecision.Deferred -> {
            noteRelaySettings = decision.settings
            relayListState = decision.relayState
            if (noteRelaySettings.input.isNotBlank() && webMenuState.activePanel == WebMenuPanel.NoteRelays) {
                return
            }
        }
        is WebRelayListImportDecision.KeptCurrent -> {
            noteRelaySettings = decision.settings
            relayListState = decision.relayState
        }
    }
    render()
}

private fun isActiveIdentity(identity: WebAccountIdentity): Boolean {
    val signedIn = authState.signInState as? WebSignInState.SignedIn ?: return false
    return signedIn.identity == identity
}

private fun relayListStatusCopy(): String? =
    relayListState.message.takeIf { it.isNotBlank() }

private fun relayListStatusClass(): String =
    if (relayListState.message == WebRelayListCopy.FetchFailed) "body error small-gap" else "body muted small-gap"

private fun relayMigrationStatusCopy(): String? =
    relayMigrationState.message.takeIf { it.isNotBlank() }

private fun relayMigrationStatusClass(): String =
    if (relayMigrationState.warning != null) "body error small-gap" else "body muted small-gap"

private fun refreshRelayStatsForSignedInSession() {
    val identity = (authState.signInState as? WebSignInState.SignedIn)?.identity ?: return
    val relays = selectedWebNoteRelays(noteRelaySettings)
    if (relays.isEmpty()) {
        resetRelayStatsState()
        render()
        return
    }
    val started = relayStatsGuard.start(identity, relays)
    relayStatsGuard = started.guard
    val request = started.request
    relayStatsState = WebRelayStatsUiState(
        pubkey = identity.publicKeyHex,
        stats = relays.associateWith { WebRelayEventStat.Checking },
    )
    render()
    activeRelayStatsFetcher.close()
    activeRelayStatsFetcher = relayStatsFetcherForCurrentRelays()
    activeRelayStatsFetcher.fetch(identity.publicKeyHex) { result ->
        if (!relayStatsGuard.accepts(request, authState, selectedWebNoteRelays(noteRelaySettings))) return@fetch
        relayStatsState = WebRelayStatsUiState(
            pubkey = identity.publicKeyHex,
            stats = result.stats,
        )
        render()
    }
}

private fun canRunManualRelaySync(): Boolean {
    val identity = (authState.signInState as? WebSignInState.SignedIn)?.identity ?: return false
    return isActiveIdentity(identity) &&
        !relayMigrationState.inProgress &&
        relayMigrationState.pendingSettings == null &&
        noteRelaySettings.input.isBlank() &&
        selectedWebNoteRelays(noteRelaySettings).isNotEmpty()
}

private fun manualRelaySyncDisabledCopy(): String? =
    when {
        relayMigrationState.inProgress -> "Relay sync or migration is already running."
        relayMigrationState.pendingSettings != null -> "Finish the pending relay migration decision before syncing."
        (authState.signInState as? WebSignInState.SignedIn) == null -> "Sign in before syncing note relays."
        selectedWebNoteRelays(noteRelaySettings).isEmpty() -> "Add at least one note relay before syncing."
        noteRelaySettings.input.isNotBlank() -> "Add or clear the relay input before syncing current relays."
        else -> null
    }

private fun logout() {
    nextAuthAttemptGeneration()
    noteLoadGuard = noteLoadGuard.invalidate()
    profileLoadGuard = profileLoadGuard.invalidate()
    relayListLoadGuard = relayListLoadGuard.invalidate()
    relayMigrationGuard = relayMigrationGuard.invalidate()
    relayStatsGuard = relayStatsGuard.invalidate()
    activeNip46RemoteSigner.disconnect()
    clearDirectKeySession()
    activeNoteLoader.close()
    activeNoteCrudService.close()
    activeProfileFetcher.close()
    activeRelayListFetcher.close()
    activeRelayMigrationService.close()
    activeRelayStatsFetcher.close()
    noteRelaySettings = defaultWebNoteRelaySettings()
    activeNoteLoader = noteLoaderForCurrentRelays()
    activeNoteCrudService = noteCrudServiceForCurrentRelays()
    activeProfileFetcher = profileFetcherForCurrentRelays()
    activeRelayListFetcher = relayListFetcherForCurrentRelays()
    activeRelayMigrationService = WebRelayMigrationService()
    activeRelayStatsFetcher = relayStatsFetcherForCurrentRelays()
    nip46TokenInput = ""
    rememberNip46Session = false
    clearDirectNsecDraft()
    clearGeneratedIdentityState()
    webMenuState = resetWebMenuState()
    resetNoteListControlsState()
    clearNoteDetail()
    resetProfile()
    resetRelayListState()
    resetRelayMigrationState()
    resetRelayStatsState()
    noteState = WebNoteLoadState.Idle
    resetLoadedNotes()
    cancelNoteEdit()
    authState = logoutWebAccount(authState)
    render()
}

private fun isNip07Available(): Boolean = window.nostr != null

private fun webNoteLaneCountForViewport(): Int =
    webNoteLaneCount((window.innerWidth - 32).coerceAtLeast(0))

private fun buttonElement(
    text: String,
    enabled: Boolean,
    onClick: (() -> Unit)? = null,
): WebElement =
    element("button", "action-button").also { button ->
        button.textContent = text
        button.setAttribute("type", "button")
        if (!enabled) {
            button.setAttribute("disabled", "disabled")
        } else if (onClick != null) {
            button.addEventListener("click") { onClick() }
        }
    }

private fun menuItemElement(
    text: String,
    danger: Boolean = false,
    onClick: () -> Unit,
): WebElement =
    element("button", if (danger) "menu-item danger" else "menu-item").also { button ->
        button.textContent = text
        button.addEventListener("click") { onClick() }
    }

private fun checkboxElement(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
): WebElement =
    element("label", "checkbox-label") {
        appendChild(
            element("input", "checkbox-input").also { input ->
                input.setAttribute("type", "checkbox")
                input.asDynamic().checked = checked
                if (!enabled) input.setAttribute("disabled", "disabled")
                input.addEventListener("change") {
                    onChange(input.asDynamic().checked as Boolean)
                }
            },
        )
        appendChild(textElement("span", "checkbox-text", label))
    }

private fun textInputElement(
    label: String,
    value: String,
    enabled: Boolean,
    inputType: String = Nip46TokenInputType,
    autocomplete: String = "off",
    placeholder: String = "bunker://...",
    onInput: (String) -> Unit,
): WebElement =
    element("label", "field-label") {
        appendChild(textElement("span", "field-label-text", label))
        appendChild(
            element("input", "text-input").also { input ->
                input.value = value
                input.setAttribute("type", inputType)
                input.setAttribute("autocomplete", autocomplete)
                input.setAttribute("autocapitalize", "off")
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

private fun Nip07Signer?.hasNip07SignCapabilityForRelayList(): Boolean =
    this != null && this.asDynamic().signEvent != undefined
