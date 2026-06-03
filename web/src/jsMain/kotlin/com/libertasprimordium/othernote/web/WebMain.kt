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
private var nip46TokenInput = ""
private var activeNip46RemoteSigner = WebNip46RemoteSigner()
private var activeNoteLoader = WebNoteLoader()

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
        appendChild(textElement("p", "lede", "This web client preview supports in-memory NIP-07 and NIP-46 public-key sign-in. Note sync is not enabled yet."))
    })
    appendChild(element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Security boundary"))
        appendChild(textElement("p", "body", "Your signer keeps your private key. Other Note only asks for your public key in this preview, and signing, encryption, and decryption will remain client-side or signer-delegated."))
    })
    when (val signInState = state.signInState) {
        is WebSignInState.SignedIn -> {
            appendChild(accountPanel(signInState.identity))
            appendChild(notesPanel(signInState.identity, noteState))
        }
        else -> {
            appendChild(nip07SignInPanel(state))
            appendChild(nip46SignInPanel(state))
        }
    }
    appendChild(element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Not implemented yet"))
        appendChild(textElement("p", "body", "This web preview does not create, edit, delete, or publish notes. It does not persist web sessions, remote signer sessions, or note caches."))
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
        appendChild(textElement("p", "body small-gap", "Read-only note loading is in memory only. Refreshing this page may clear this session."))
        appendChild(buttonElement(text = "Logout", enabled = true, onClick = ::logout))
    }

private fun notesPanel(identity: WebAccountIdentity, notes: WebNoteLoadState): WebElement =
    element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Read-only notes"))
        appendChild(textElement("p", "body", "Loads encrypted Other Note events from note relays and decrypts them through the active signer. No note data is saved in the browser."))
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
                    notes.notes.forEach { note -> appendChild(noteCard(note)) }
                })
            }
        }
    }

private fun noteCard(note: WebReadOnlyNote): WebElement =
    element("article", "note-card") {
        val preview = webNotePreview(note.bodyMarkdown)
        appendChild(textElement("h3", "note-title", preview.title))
        if (preview.snippet.isNotBlank()) {
            appendChild(textElement("p", "note-snippet", preview.snippet))
        }
        appendChild(textElement("p", "note-meta", "Last edited ${formatWebNoteTimestamp(note.updatedAtMs)}"))
        appendChild(renderMarkdown(note.bodyMarkdown))
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
    activeNoteLoader = WebNoteLoader()
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
                        WebNoteLoadState.Empty(result.relayStatus)
                    } else {
                        WebNoteLoadState.Loaded(result.state.notes, result.relayStatus)
                    }
                is WebNoteLoadResult.Failed -> WebNoteLoadState.Failed(result.safeMessage)
                is WebNoteLoadResult.SignerUnsupported -> WebNoteLoadState.SignerUnsupported(result.safeMessage)
            }
            render()
        },
    )
}

private fun logout() {
    activeNip46RemoteSigner.disconnect()
    activeNoteLoader.close()
    nip46TokenInput = ""
    noteState = WebNoteLoadState.Idle
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
    onInput: (String) -> Unit,
): WebElement =
    element("label", "field-label") {
        appendChild(textElement("span", "field-label-text", label))
        appendChild(
            element("input", "text-input").also { input ->
                input.value = value
                input.setAttribute("type", Nip46TokenInputType)
                input.setAttribute("autocomplete", "off")
                input.setAttribute("autocapitalize", "none")
                input.setAttribute("autocorrect", "off")
                input.setAttribute("spellcheck", "false")
                input.setAttribute("placeholder", "bunker://...")
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
