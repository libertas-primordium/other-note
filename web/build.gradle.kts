import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import java.nio.file.Files

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

plugins.withType<NodeJsPlugin> {
    extensions.configure<NodeJsEnvSpec>(NodeJsEnvSpec.EXTENSION_NAME) {
        download.set(false)
        command.set("node")
    }
}

val webSecuritySourceCheck by tasks.registering {
    group = "verification"
    description = "Checks the web runtime source and static shell for forbidden deployment/security patterns."

    val runtimeSourceDir = layout.projectDirectory.dir("src/jsMain/kotlin")
    val resourceDir = layout.projectDirectory.dir("src/jsMain/resources")
    val deploymentSecurityDoc = layout.projectDirectory.file("../docs/web-deployment-security.md")
    inputs.dir(runtimeSourceDir)
    inputs.dir(resourceDir)
    inputs.file(deploymentSecurityDoc)

    doLast {
        val runtimeFiles = fileTree(runtimeSourceDir).matching {
            include("**/*.kt")
        }.files
        val resourceFiles = fileTree(resourceDir).matching {
            include("**/*.html")
            include("**/*.css")
            include("**/*.js")
        }.files

        val forbiddenRuntimePatterns = listOf(
            "indexedDB",
            "document.cookie",
            "CacheStorage",
            "serviceWorker",
            "sessionStorage",
            "console.log",
            "println",
            "analytics",
            "telemetry",
        )
        val runtimeText = runtimeFiles.joinToString("\n") { file -> file.readText() }
        val runtimeHits = forbiddenRuntimePatterns.filter { pattern -> runtimeText.contains(pattern) }
        check(runtimeHits.isEmpty()) {
            "Forbidden web runtime pattern(s) found: ${runtimeHits.joinToString()}"
        }
        val localStorageFiles = runtimeFiles.filter { file -> file.readText().contains("localStorage") }
            .map { file -> file.relativeTo(runtimeSourceDir.asFile).invariantSeparatorsPath }
        check(localStorageFiles == listOf("com/libertasprimordium/othernote/web/WebMain.kt")) {
            "localStorage may appear only in WebMain.kt for the allowed generic theme preference and explicit remembered NIP-46 session: ${localStorageFiles.joinToString()}"
        }
        val themeSource = runtimeSourceDir.file("com/libertasprimordium/othernote/web/WebThemes.kt").asFile.readText()
        check(themeSource.contains("""WebThemePreferenceKey = "on.web.theme"""")) {
            "Web theme preference storage must use the documented generic key."
        }
        val rememberedNip46Source = runtimeSourceDir.file("com/libertasprimordium/othernote/web/WebRememberedNip46.kt").asFile.readText()
        check(rememberedNip46Source.contains("""WebRememberedNip46StorageKey = "on.web.nip46"""")) {
            "Remembered web NIP-46 storage must use the documented generic key."
        }
        listOf("pubkey", "npub", "nsec", "bunker", "signer", "relay", "note", "event", "profile", "secret").forEach { forbiddenKeyPart ->
            check(!"on.web.theme".contains(forbiddenKeyPart)) {
                "Web theme preference key must not include account, relay, note, signer, or profile identifiers."
            }
        }
        listOf("pubkey", "npub", "nsec", "bunker", "signer", "relay", "note", "event", "profile", "secret").forEach { forbiddenKeyPart ->
            check(!"on.web.nip46".contains(forbiddenKeyPart)) {
                "Remembered web NIP-46 storage key must not include account, relay, note, signer, or profile identifiers."
            }
        }
        check(rememberedNip46Source.contains("ignoreUnknownKeys = false")) {
            "Remembered web NIP-46 storage schema must reject unexpected fields."
        }
        check(rememberedNip46Source.contains("clientPrivateKey=redacted")) {
            "Remembered web NIP-46 session string rendering must redact the communication private key."
        }
        check(!rememberedNip46Source.contains("bunkerToken") && !rememberedNip46Source.contains("tokenSecret")) {
            "Remembered web NIP-46 storage must not persist bunker tokens or token secrets."
        }
        val directKeySource = runtimeSourceDir.file("com/libertasprimordium/othernote/web/WebDirectKey.kt").asFile.readText()
        val forbiddenDirectKeyPatterns = listOf(
            "localStorage",
            "sessionStorage",
            "indexedDB",
            "document.cookie",
            "CacheStorage",
            "serviceWorker",
            "setAttribute",
            "textContent",
            "console.log",
            "println",
            "WebThemePreferenceKey",
        )
        val directKeyHits = forbiddenDirectKeyPatterns.filter { pattern -> directKeySource.contains(pattern) }
        check(directKeyHits.isEmpty()) {
            "Direct nsec web foundation must stay memory-only and isolated from browser storage/DOM rendering; forbidden pattern(s): ${directKeyHits.joinToString()}"
        }
        val webMainText = runtimeSourceDir.file("com/libertasprimordium/othernote/web/WebMain.kt").asFile.readText()
        val webAuthStateText = runtimeSourceDir.file("com/libertasprimordium/othernote/web/WebAuthState.kt").asFile.readText()
        listOf("Nip07", "Nip46", "RememberedNip46", "DirectNsec", "GeneratedIdentity").forEach { topic ->
            check(webMainText.contains("signInInfoButton(WebSignInInfoTopic.$topic)") || webMainText.contains("sectionTitleWithInfo(\"") && webMainText.contains("WebSignInInfoTopic.$topic")) {
                "Signed-out login must expose an accessible info button for WebSignInInfoTopic.$topic."
            }
        }
        listOf(
            "does not receive your private key",
            "separate from note relays",
            "plaintext note payloads",
            "communication session record",
            "nsec is your private key",
            "Other Note cannot recover it",
        ).forEach { requiredCopy ->
            check(webAuthStateText.contains(requiredCopy)) {
                "Web sign-in info popups must preserve required safety copy: $requiredCopy"
            }
        }
        check(webMainText.contains("""setAttribute("role", "dialog")""") && webMainText.contains("""setAttribute("aria-modal", "true")""")) {
            "Sign-in info popups must use dialog semantics."
        }
        check(webMainText.contains("activeSignInInfoTopic") && webMainText.contains("""key == "Escape"""")) {
            "Sign-in info popups must be dismissible with Escape."
        }
        val directNsecPanel = Regex(
            """private fun directNsecSignInPanel\(state: WebAuthUiState\): WebElement\s*=(?<body>.*?)private fun requestNip07PublicKey""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define directNsecSignInPanel for session-only direct nsec login.")
        check(directNsecPanel.contains("DirectNsecInputType") && directNsecPanel.contains("DirectNsecInputAutocomplete")) {
            "Direct nsec input must use the password-style input type and browser-assistance controls."
        }
        check(directNsecPanel.contains("Session-only fallback") && directNsecPanel.contains("Other Note will not save this key")) {
            "Direct nsec sign-in copy must clearly state the session-only, non-persistent boundary."
        }
        check(!directNsecPanel.contains("render()")) {
            "Direct nsec typing must not call render(); replacing the active input on each character drops focus and risks stale DOM values."
        }
        val directNsecRequest = Regex(
            """private fun requestDirectNsecSession\(\)\s*\{(?<body>.*?)private fun failDirectNsecSignIn""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define requestDirectNsecSession.")
        check(directNsecRequest.indexOf("clearDirectNsecDraft()") in 0 until directNsecRequest.indexOf("createWebDirectKeySession")) {
            "Direct nsec submit must clear the draft before creating or reporting the session."
        }
        val generatedIdentityPanel = Regex(
            """private fun generatedIdentityPanel\(state: WebAuthUiState\): WebElement\s*=(?<body>.*?)private fun requestNip07PublicKey""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define generatedIdentityPanel for session-only generated identities.")
        check(generatedIdentityPanel.contains("Create new identity") && generatedIdentityPanel.contains("GeneratedIdentitySubmitLabel")) {
            "Generated identity UI must expose a deliberate create/use flow."
        }
        check(
            generatedIdentityPanel.contains("GeneratedIdentityRecoverAckLabel") &&
                generatedIdentityPanel.contains("GeneratedIdentitySavedAckLabel") &&
                generatedIdentityPanel.contains("GeneratedIdentityLossAckLabel"),
        ) {
            "Generated identity UI must require recovery, saved-key, and loss-risk acknowledgements."
        }
        check(generatedIdentityPanel.contains("generated-secret-display") && !generatedIdentityPanel.contains("setAttribute")) {
            "Generated nsec may be displayed only as explicit confirmation text, not as DOM attributes."
        }
        val generatedIdentityRequest = Regex(
            """private fun requestGeneratedIdentitySession\(\)\s*\{(?<body>.*?)private fun startDirectKeySession""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define requestGeneratedIdentitySession.")
        check(generatedIdentityRequest.indexOf("clearGeneratedIdentityState()") in 0 until generatedIdentityRequest.indexOf("startDirectKeySession")) {
            "Generated identity submit must clear generated-key state before session activation."
        }
        val rememberedNip46Panel = Regex(
            """private fun rememberedNip46SessionPanel\(state: WebAuthUiState\): WebElement\s*=(?<body>.*?)private fun directNsecSignInPanel""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define rememberedNip46SessionPanel for explicit remembered remote signer sessions.")
        check(rememberedNip46Panel.contains("Continue with remembered remote signer") && rememberedNip46Panel.contains("Forget remembered remote signer")) {
            "Remembered NIP-46 UI must expose explicit continue and forget actions."
        }
        val nip46Panel = Regex(
            """private fun nip46SignInPanel\(state: WebAuthUiState\): WebElement\s*=(?<body>.*?)private fun rememberedNip46SessionPanel""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define nip46SignInPanel.")
        check(nip46Panel.contains("RememberNip46CheckboxLabel") && nip46Panel.contains("rememberNip46OptInLabel")) {
            "NIP-46 sign-in must keep remembered remote signer storage behind explicit opt-in copy."
        }
        check(webMainText.contains("rememberNip46Session = false")) {
            "Remembered NIP-46 opt-in must default to off and clear after session transitions."
        }
        val relayInputUpdater = Regex(
            """private fun updateNoteRelayInput\(value: String\)\s*\{(?<body>.*?)}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define updateNoteRelayInput for Relay Settings input handling.")
        check(!relayInputUpdater.contains("render()")) {
            "Relay Settings input typing must not call render(); replacing the modal input on each character drops focus."
        }
        val searchInputUpdater = Regex(
            """private fun updateNoteSearchQuery\(value: String\)\s*\{(?<body>.*?)}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define updateNoteSearchQuery for note search input handling.")
        check(!searchInputUpdater.contains("render()")) {
            "Note search typing must not call render(); replacing the active search input on each character drops focus."
        }
        check(!runtimeText.contains("element(\"img\"") && !runtimeText.contains("element(\"image\"")) {
            "Web runtime must not render profile picture/banner URLs as image elements in this preview."
        }
        check(!runtimeText.contains("createElement(\"img\"") && !runtimeText.contains("createElement(\"image\"")) {
            "Web runtime must not create profile image elements in this preview."
        }

        val serviceWorkerFiles = resourceFiles.filter { file ->
            file.name.equals("sw.js", ignoreCase = true) ||
                file.name.contains("service-worker", ignoreCase = true) ||
                file.name.contains("serviceworker", ignoreCase = true)
        }
        check(serviceWorkerFiles.isEmpty()) {
            "Unexpected web service worker resource(s): ${serviceWorkerFiles.joinToString { it.name }}"
        }

        val index = resourceDir.file("index.html").asFile
        val indexText = index.readText()
        val thirdPartyScript = Regex("""<script\b[^>]*\bsrc=["']https?://""", RegexOption.IGNORE_CASE)
        check(!thirdPartyScript.containsMatchIn(indexText)) {
            "Web index.html must not load third-party scripts."
        }
        check(indexText.contains("""<script src="other-note-web.js"></script>""")) {
            "Web index.html should load only the self-hosted generated bundle."
        }
        check(!indexText.contains("<img", ignoreCase = true)) {
            "Web index.html must not render remote profile images or other image tags in this preview."
        }
        check(!indexText.contains("background-image", ignoreCase = true)) {
            "Web index.html must not render remote profile images through CSS backgrounds in this preview."
        }
        check(!indexText.contains("""http-equiv="Content-Security-Policy"""")) {
            "Web index.html must not enforce CSP through a meta tag; production CSP belongs in host HTTP headers."
        }
        listOf("fonts.googleapis.com", "fonts.gstatic.com", "fontawesome", "cdnjs", "jsdelivr", "unpkg").forEach { forbiddenFontHost ->
            check(!indexText.contains(forbiddenFontHost, ignoreCase = true)) {
                "Web index.html must not reference external font/CDN host: $forbiddenFontHost"
            }
        }
        check(!Regex("""url\(["']?https?://""", RegexOption.IGNORE_CASE).containsMatchIn(indexText)) {
            "Web font and CSS asset URLs must be same-origin."
        }
        check(!indexText.contains("local(", ignoreCase = true)) {
            "Web font loading must not rely on local(...) as the primary font source."
        }
        listOf(
            "Roboto-Regular.woff2",
            "Roboto-Italic.woff2",
            "Roboto-Medium.woff2",
            "Roboto-Bold.woff2",
        ).forEach { fontFile ->
            check(indexText.contains("""url("fonts/roboto/$fontFile") format("woff2")""")) {
                "Web index.html must declare same-origin Roboto font file $fontFile."
            }
            check(resourceDir.file("fonts/roboto/$fontFile").asFile.isFile) {
                "Bundled web Roboto font file is missing: $fontFile"
            }
        }
        check(indexText.contains("--font-family-body: \"Roboto\"") && indexText.contains("font-family: var(--font-family-body)")) {
            "Web root typography must use the bundled Roboto font token."
        }
        check(indexText.contains("font-display: swap")) {
            "Bundled web fonts must use font-display for readable loading behavior."
        }
        check(resourceDir.file("fonts/roboto/Roboto-COPYRIGHT.txt").asFile.isFile) {
            "Bundled Roboto web fonts must include copyright/source attribution."
        }
        check(resourceDir.file("fonts/roboto/Apache-2.0.txt").asFile.isFile) {
            "Bundled Roboto web fonts must include the Apache-2.0 license text."
        }

        fun cssBlocks(selector: String): List<String> =
            Regex("""${Regex.escape(selector)}\s*\{([^}]*)}""")
                .findAll(indexText)
                .map { match -> match.groups.get(1)?.value.orEmpty() }
                .toList()
                .also { blocks ->
                    check(blocks.isNotEmpty()) {
                        "Web index.html is missing CSS selector $selector"
                    }
                }

        fun requireCssDeclaration(selector: String, declaration: String) {
            check(cssBlocks(selector).any { block -> block.contains(declaration) }) {
                "Web index.html selector $selector must include `$declaration` to keep long note-card text inside cards."
            }
        }

        fun rejectCssDeclaration(selector: String, declaration: String) {
            check(cssBlocks(selector).none { block -> block.contains(declaration) }) {
                "Web index.html selector $selector must not include `$declaration`; note cards must use explicit horizontal lanes, not vertical-first CSS columns."
            }
        }

        listOf(".note-list", ".note-lane", ".notes-panel", ".note-list-controls", ".notes-results", ".notes-results-content", ".note-card", ".note-card-open", ".modal-panel", ".modal-header", ".note-detail-panel", ".note-detail-body", ".markdown-view", ".markdown-code-block").forEach { selector ->
            requireCssDeclaration(selector, "max-width: 100%")
        }
        listOf(".note-list", ".note-lane", ".notes-panel", ".note-list-controls", ".notes-results", ".notes-results-content", ".note-card", ".note-card-open", ".modal-panel", ".modal-header", ".modal-header .section-title", ".note-detail-panel", ".note-detail-body", ".markdown-view", ".markdown-code-block").forEach { selector ->
            requireCssDeclaration(selector, "min-width: 0")
        }
        listOf(".note-card", ".note-title", ".note-snippet,\n        .note-meta", ".markdown-view", ".markdown-code-block", ".inline-code").forEach { selector ->
            requireCssDeclaration(selector, "overflow-wrap: anywhere")
        }
        requireCssDeclaration(".note-list", "display: flex")
        requireCssDeclaration(".note-list", "gap: 6px")
        requireCssDeclaration(".note-lanes", "align-items: flex-start")
        requireCssDeclaration(".note-lane", "display: grid")
        requireCssDeclaration(".note-lane", "flex: 1 1 0")
        requireCssDeclaration(".note-lane", "gap: 6px")
        requireCssDeclaration(".note-card", "break-inside: avoid")
        requireCssDeclaration(".note-card", "border: 1px solid var(--subtle-border)")
        requireCssDeclaration(".note-card", "background: var(--card-surface)")
        requireCssDeclaration(".note-card", "display: grid")
        requireCssDeclaration(".note-card-open", "cursor: pointer")
        requireCssDeclaration(".note-title", "-webkit-line-clamp: 2")
        requireCssDeclaration(".note-title", "overflow: hidden")
        requireCssDeclaration(".note-snippet", "-webkit-line-clamp: 4")
        requireCssDeclaration(".note-snippet", "overflow: hidden")
        requireCssDeclaration(".modal-panel", "overflow-x: hidden")
        requireCssDeclaration(".modal-header .section-title", "overflow-wrap: anywhere")
        requireCssDeclaration(".note-detail-panel", "overflow-x: hidden")
        requireCssDeclaration(".note-detail-body", "overflow-x: hidden")
        requireCssDeclaration(".note-detail-body .markdown-view", "display: block")
        requireCssDeclaration(".note-detail-body .markdown-view", "overflow-x: hidden")
        requireCssDeclaration(".note-detail-body .markdown-heading,\n        .note-detail-body .markdown-paragraph,\n        .note-detail-body .markdown-quote,\n        .note-detail-body .markdown-code-block,\n        .note-detail-body .inline-code", "overflow-wrap: anywhere")
        requireCssDeclaration(".note-detail-body .markdown-heading,\n        .note-detail-body .markdown-paragraph,\n        .note-detail-body .markdown-quote,\n        .note-detail-body .markdown-code-block,\n        .note-detail-body .inline-code", "word-break: break-word")
        requireCssDeclaration(".note-detail-body .markdown-heading,\n        .note-detail-body .markdown-paragraph,\n        .note-detail-body .markdown-quote,\n        .note-detail-body .markdown-code-block,\n        .note-detail-body .inline-code", "white-space: pre-wrap")
        requireCssDeclaration(".markdown-view", "overflow-x: hidden")
        requireCssDeclaration(".notes-panel", "background: transparent")
        requireCssDeclaration(".notes-panel", "border: 0")
        requireCssDeclaration(".note-list-controls", "display: grid")
        requireCssDeclaration(".note-list-controls", "grid-template-columns: minmax(0, 1fr) minmax(190px, 260px)")
        requireCssDeclaration(".sort-select", "min-height: 44px")
        requireCssDeclaration(":root", "--button-background: #8e44ff")
        requireCssDeclaration(":root[data-theme=\"urban\"]", "--background: #e7e7e3")
        requireCssDeclaration(":root[data-theme=\"hacker\"]", "--background: #020403")
        requireCssDeclaration(":root[data-theme=\"papyrus\"]", "--background: #fbf5e7")
        requireCssDeclaration(".note-card-actions", "flex-direction: row")
        requireCssDeclaration(".note-card-actions", "gap: 6px")
        requireCssDeclaration(".note-card-actions .action-button", "min-height: 34px")
        requireCssDeclaration(".note-card-actions .action-button", "border-color: color-mix(in srgb, var(--accent) 68%, transparent)")
        requireCssDeclaration(".note-card-actions .action-button", "white-space: nowrap")
        requireCssDeclaration(".note-card-actions .action-button:last-child", "border-color: var(--danger-border)")
        rejectCssDeclaration(".note-card-actions", "flex-direction: column")
        rejectCssDeclaration(".note-list", "columns:")
        rejectCssDeclaration(".note-list", "column-count")
        rejectCssDeclaration(".note-list", "column-width")
        rejectCssDeclaration(".note-list", "column-gap")
        rejectCssDeclaration(".note-detail-body", "overflow-x: auto")
        rejectCssDeclaration(".note-detail-body .markdown-view", "overflow-x: auto")
        requireCssDeclaration(".markdown-code-block", "overflow-x: hidden")
        rejectCssDeclaration(".markdown-code-block", "overflow-x: auto")
        requireCssDeclaration(".markdown-code-block", "white-space: pre-wrap")
        requireCssDeclaration(".markdown-code-block", "word-break: break-word")
        requireCssDeclaration(".markdown-paragraph,\n        .markdown-quote", "white-space: pre-wrap")
        requireCssDeclaration(".inline-code", "word-break: break-word")

        val deploymentDoc = deploymentSecurityDoc.asFile.readText()
        val expectedCspDirectives = listOf(
            "default-src 'self'",
            "script-src 'self'",
            "connect-src 'self' wss:",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            "worker-src 'none'",
        )
        val missingDirectives = expectedCspDirectives.filterNot { directive -> deploymentDoc.contains(directive) }
        check(missingDirectives.isEmpty()) {
            "Web deployment security doc is missing production CSP directive(s): ${missingDirectives.joinToString()}"
        }
        check(!deploymentDoc.contains("ws://" + "[::1]")) {
            "Web deployment security doc must not include invalid bracketed IPv6 wildcard CSP sources."
        }

        val externalRuntimeResource = resourceFiles.filter { file -> file.extension != "html" }
            .map { file -> file to file.readText() }
            .filter { (_, text) -> Regex("""https?://""").containsMatchIn(text) }
            .map { (file, _) -> file.name }
        check(externalRuntimeResource.isEmpty()) {
            "Web runtime resources must not reference external http(s) URLs: ${externalRuntimeResource.joinToString()}"
        }

        check(Files.exists(index.toPath())) {
            "Web index.html must exist."
        }
    }
}

tasks.configureEach {
    if (name == "jsBrowserTest") {
        dependsOn(webSecuritySourceCheck)
    }
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "other-note-web.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation(npm("nostr-tools", "2.10.4"))
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
