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
    fun appendChild(child: WebElement): WebElement
    fun setAttribute(name: String, value: String)
    fun addEventListener(type: String, listener: (dynamic) -> Unit)
}

private lateinit var rootElement: WebElement
private var authState = WebAuthUiState(nip07Available = isNip07Available())

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
        appendChild(textElement("p", "lede", "This web client preview supports in-memory NIP-07 public-key sign-in. Note sync is not enabled yet."))
    })
    appendChild(element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Security boundary"))
        appendChild(textElement("p", "body", "Your extension keeps your private key. Other Note only asks for your public key in this preview, and signing, encryption, and decryption will remain client-side."))
    })
    appendChild(signInPanel(state))
    appendChild(element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Not implemented yet"))
        appendChild(textElement("p", "body", "This web preview does not fetch, decrypt, edit, or publish notes. It does not persist web sessions."))
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

private fun signInPanel(state: WebAuthUiState): WebElement =
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
            WebSignInState.SigningIn -> {
                appendChild(textElement("p", "body", "Waiting for the browser extension..."))
                appendChild(buttonElement(text = "Sign-in request pending", enabled = false))
            }
            is WebSignInState.SignedIn -> {
                appendChild(textElement("p", "body", "Signed in as ${signInState.identity.displayPublicKey}."))
                appendChild(textElement("p", "body small-gap", "Note sync is not implemented for web yet. Refreshing this page may clear this in-memory session."))
                appendChild(buttonElement(text = "Logout", enabled = true, onClick = ::logout))
            }
            is WebSignInState.Failed -> {
                appendChild(textElement("p", "body error", signInState.message))
                appendChild(
                    buttonElement(
                        text = "Sign in with browser extension",
                        enabled = state.nip07Available,
                        onClick = ::requestNip07PublicKey,
                    ),
                )
            }
        }
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

private fun logout() {
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

private fun element(tagName: String, className: String, build: WebElement.() -> Unit = {}): WebElement =
    document.createElement(tagName).also { element ->
        element.className = className
        element.build()
    }

private fun textElement(tagName: String, className: String, text: String): WebElement =
    element(tagName, className).also { it.textContent = text }
