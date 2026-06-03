package com.libertasprimordium.othernote.web

external val document: WebDocument

external interface WebDocument {
    fun getElementById(id: String): WebElement?
    fun createElement(tagName: String): WebElement
}

external interface WebElement {
    var className: String
    var textContent: String?
    fun appendChild(child: WebElement): WebElement
}

fun main() {
    val root = document.getElementById("root") ?: return
    root.appendChild(appShell())
}

private fun appShell(): WebElement = element("main", "shell") {
    appendChild(element("section", "hero") {
        appendChild(textElement("p", "eyebrow", "Web client preview"))
        appendChild(textElement("h1", "title", "Other Note"))
        appendChild(textElement("p", "lede", "This web client shell is an early implementation target. Sign-in and note sync are not enabled yet."))
    })
    appendChild(element("section", "panel") {
        appendChild(textElement("h2", "section-title", "Security boundary"))
        appendChild(textElement("p", "body", "Other Note web will keep signing, encryption, and decryption client-side. No secret input fields are available in this skeleton."))
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

private fun element(tagName: String, className: String, build: WebElement.() -> Unit = {}): WebElement =
    document.createElement(tagName).also { element ->
        element.className = className
        element.build()
    }

private fun textElement(tagName: String, className: String, text: String): WebElement =
    element(tagName, className).also { it.textContent = text }
