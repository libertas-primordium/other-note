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
private var nip46TokenInput = ""
private var activeNip46RemoteSigner = WebNip46RemoteSigner()

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
        is WebSignInState.SignedIn -> appendChild(accountPanel(signInState.identity))
        else -> {
            appendChild(nip07SignInPanel(state))
            appendChild(nip46SignInPanel(state))
        }
    }
    appendChild(element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Not implemented yet"))
        appendChild(textElement("p", "body", "This web preview does not fetch, decrypt, edit, or publish notes. It does not persist web sessions or remote signer sessions."))
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
        appendChild(textElement("p", "body small-gap", "Note sync is not implemented for web yet. Refreshing this page may clear this in-memory session."))
        appendChild(buttonElement(text = "Logout", enabled = true, onClick = ::logout))
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

private fun logout() {
    activeNip46RemoteSigner.disconnect()
    nip46TokenInput = ""
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
