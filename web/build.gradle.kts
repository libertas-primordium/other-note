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
        check(!webMainText.contains("event.asDynamic()")) {
            "Web DOM event handlers must use direct dynamic event properties; event.asDynamic() causes runtime failures in the Kotlin/JS bundle."
        }
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
        check(webMainText.contains("""element("img", "markdown-image")""")) {
            "Web runtime must render note Markdown images only through the full-note markdown-image path."
        }
        check(webMainText.contains("""element("img", "profile-thumbnail")""")) {
            "Web runtime must render profile images only through the signed-in profile-thumbnail path."
        }
        check(webMainText.contains("""WebProfilePlaceholderImagePath = "images/profile-placeholder.svg"""")) {
            "Web profile thumbnail fallback must use the bundled same-origin placeholder asset."
        }
        check(resourceDir.file("images/profile-placeholder.svg").asFile.isFile) {
            "Bundled web profile placeholder image is missing."
        }
        listOf(
            """setAttribute("loading", "lazy")""",
            """setAttribute("decoding", "async")""",
            """setAttribute("referrerpolicy", "no-referrer")""",
            """setAttribute("rel", "noopener noreferrer")""",
            """setAttribute("target", "_blank")""",
        ).forEach { requiredSafeAttribute ->
            check(webMainText.contains(requiredSafeAttribute)) {
                "Web full-note links/images must include safe attribute: $requiredSafeAttribute"
            }
        }
        check(!runtimeText.contains("element(\"image\"")) {
            "Web runtime must not create generic image elements outside the full-note Markdown and profile-thumbnail paths."
        }
        check(!runtimeText.contains("createElement(\"img\"") && !runtimeText.contains("createElement(\"image\"")) {
            "Web runtime must not create profile image elements with raw createElement calls."
        }
        check(webMainText.contains("is WebMarkdownBlock.ListBlock -> listElement(block)") && webMainText.contains("WebMarkdownBlock.HorizontalRule -> element(\"hr\", \"markdown-rule\")")) {
            "Web full-note renderer must render the supported Markdown list and horizontal-rule block model."
        }
        val noteCardRenderer = Regex(
            """private fun noteCard\(note: WebReadOnlyNote, canCrud: Boolean\): WebElement\s*=(?<body>.*?)private fun noteDetailPanel""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define noteCard before noteDetailPanel.")
        check(!noteCardRenderer.contains("renderMarkdown(") && noteCardRenderer.contains("webNotePreview(note.bodyMarkdown)")) {
            "Web note cards must use raw preview text and must not render active Markdown."
        }
        val appShellRenderer = Regex(
            """private fun appShell\(state: WebAuthUiState\): WebElement\s*=(?<body>.*?)private fun appShellClass""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define appShell before appShellClass.")
        check(appShellRenderer.contains("activeNoteOverlayPanel(signedIn.identity)?.let(::appendChild)")) {
            "Web signed-in shell must use one note overlay slot for view/edit/delete flows."
        }
        val notesPanelRenderer = Regex(
            """private fun notesPanel\(identity: WebAccountIdentity, notes: WebNoteLoadState\): WebElement\s*=(?<body>.*?)private fun noteListControlsPanel""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define notesPanel before noteListControlsPanel.")
        check(!notesPanelRenderer.contains("noteEditorPanel(")) {
            "Web note editor/delete confirmation must not render on the main page behind full-note overlays."
        }
        val noteDetailRenderer = Regex(
            """private fun noteDetailPanel\(note: WebReadOnlyNote\): WebElement\s*=(?<body>.*?)private fun viewedNoteForCurrentState""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define noteDetailPanel before viewedNoteForCurrentState.")
        check(noteDetailRenderer.contains("note-detail-header") && noteDetailRenderer.contains("note-detail-actions")) {
            "Web full-note view must render a dedicated sticky action/header area."
        }
        listOf(
            "buttonElement(text = \"Close\"",
            "buttonElement(text = \"Edit\"",
            "buttonElement(text = \"Delete\"",
        ).forEach { action ->
            check(noteDetailRenderer.contains(action)) {
                "Web full-note action row must include $action."
            }
        }
        check(noteDetailRenderer.contains("startEditNote(note)") && noteDetailRenderer.contains("confirmDeleteNote(note)") && noteDetailRenderer.contains("closeNoteDetail")) {
            "Web full-note actions must target the viewed note and preserve Close/Edit/Delete behavior."
        }
        val activeNoteOverlayRenderer = Regex(
            """private fun activeNoteOverlayPanel\(identity: WebAccountIdentity\): WebElement\?\s*\{(?<body>.*?)private fun openNoteDetail""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define activeNoteOverlayPanel before openNoteDetail.")
        check(activeNoteOverlayRenderer.contains("noteEditorPanel(identity, crudSigner)") && activeNoteOverlayRenderer.contains("viewedNoteForCurrentState()?.let(::noteDetailPanel)")) {
            "Web active note overlay must choose exactly one edit/delete or full-note view panel."
        }
        listOf("startCreateNote", "startEditNote", "confirmDeleteNote").forEach { transition ->
            val transitionBody = Regex(
                """private fun $transition\([^)]*\)\s*\{(?<body>.*?)private fun""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).find(webMainText)?.groups?.get("body")?.value
                ?: error("WebMain.kt must define $transition.")
            check(transitionBody.contains("clearNoteDetail()")) {
                "$transition must dismiss the full-note viewer before opening edit/delete overlay state."
            }
        }
        val noteEditorRenderer = Regex(
            """private fun noteEditorPanel\(identity: WebAccountIdentity, signer: WebNoteCrudSigner\?\): WebElement\s*=(?<body>.*?)private fun sectionTitleWithInfo""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define noteEditorPanel before sectionTitleWithInfo.")
        check(!noteEditorRenderer.contains("renderMarkdown(") && noteEditorRenderer.contains("textAreaElement(")) {
            "Web note editors must keep raw Markdown text in a textarea."
        }
        listOf("note-edit-backdrop", "note-edit-panel", "note-edit-header", "note-edit-actions", "note-delete-backdrop", "note-delete-panel", "note-delete-title", "note-delete-actions").forEach { overlayClass ->
            check(noteEditorRenderer.contains(overlayClass)) {
                "Web note edit/delete flow must render overlay class $overlayClass."
            }
        }
        check(!Regex("""innerHTML\s*=\s*(markdown|note|body|raw|span)""").containsMatchIn(webMainText)) {
            "Web Markdown rendering must not route note content through innerHTML."
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
            "Web index.html must not render static image tags; runtime image rendering is limited to reviewed full-note and profile-thumbnail paths."
        }
        check(!indexText.contains("background-image", ignoreCase = true)) {
            "Web index.html must not render remote images through CSS backgrounds."
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

        listOf(".note-list", ".note-lane", ".notes-panel", ".note-list-controls", ".notes-results", ".notes-results-content", ".note-card", ".note-card-open", ".modal-panel", ".modal-header", ".note-detail-header", ".note-detail-heading", ".note-detail-body", ".note-edit-header", ".note-edit-panel .field-label", ".markdown-view", ".markdown-code-block", ".markdown-list", ".markdown-list-item", ".markdown-rule").forEach { selector ->
            requireCssDeclaration(selector, "max-width: 100%")
        }
        listOf(".note-list", ".note-lane", ".notes-panel", ".note-list-controls", ".notes-results", ".notes-results-content", ".note-card", ".note-card-open", ".modal-panel", ".modal-header", ".modal-header .section-title", ".note-detail-panel", ".note-detail-header", ".note-detail-heading", ".note-detail-body", ".note-edit-panel", ".note-edit-header", ".note-edit-panel .field-label", ".note-delete-panel", ".markdown-view", ".markdown-code-block", ".markdown-list", ".markdown-list-item", ".markdown-rule").forEach { selector ->
            requireCssDeclaration(selector, "min-width: 0")
        }
        listOf(".note-card", ".note-title", ".note-snippet,\n        .note-meta", ".markdown-view", ".markdown-code-block", ".inline-code", ".markdown-link").forEach { selector ->
            requireCssDeclaration(selector, "overflow-wrap: anywhere")
        }
        requireCssDeclaration(".note-list", "display: flex")
        requireCssDeclaration(".profile-thumbnail", "width: 42px")
        requireCssDeclaration(".profile-thumbnail", "height: 42px")
        requireCssDeclaration(".profile-thumbnail", "object-fit: cover")
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
        requireCssDeclaration(".note-detail-backdrop", "padding: 10px")
        requireCssDeclaration(".note-detail-panel", "width: min(1120px, calc(100vw - 20px))")
        requireCssDeclaration(".note-detail-panel", "max-height: min(94vh, 980px)")
        requireCssDeclaration(".note-detail-panel", "overflow-y: auto")
        requireCssDeclaration(".note-detail-panel", "overflow-x: hidden")
        requireCssDeclaration(".note-detail-header", "position: sticky")
        requireCssDeclaration(".note-detail-header", "top: 0")
        requireCssDeclaration(".note-detail-header", "z-index: 2")
        requireCssDeclaration(".note-detail-actions", "display: flex")
        requireCssDeclaration(".note-detail-actions", "flex-wrap: wrap")
        requireCssDeclaration(".note-detail-actions .danger-action", "color: var(--danger)")
        requireCssDeclaration(".note-detail-body", "overflow-x: hidden")
        requireCssDeclaration(".note-detail-body", "width: 100%")
        requireCssDeclaration(".note-detail-body .markdown-view", "display: block")
        requireCssDeclaration(".note-detail-body .markdown-view", "overflow-x: hidden")
        requireCssDeclaration(".note-detail-body .markdown-heading,\n        .note-detail-body .markdown-paragraph,\n        .note-detail-body .markdown-quote,\n        .note-detail-body .markdown-code-block,\n        .note-detail-body .inline-code,\n        .note-detail-body .markdown-link", "overflow-wrap: anywhere")
        requireCssDeclaration(".note-detail-body .markdown-heading,\n        .note-detail-body .markdown-paragraph,\n        .note-detail-body .markdown-quote,\n        .note-detail-body .markdown-code-block,\n        .note-detail-body .inline-code,\n        .note-detail-body .markdown-link", "word-break: break-word")
        requireCssDeclaration(".note-detail-body .markdown-heading,\n        .note-detail-body .markdown-paragraph,\n        .note-detail-body .markdown-quote,\n        .note-detail-body .markdown-code-block,\n        .note-detail-body .inline-code,\n        .note-detail-body .markdown-link", "white-space: pre-wrap")
        requireCssDeclaration(".markdown-image", "max-width: 100%")
        requireCssDeclaration(".markdown-image", "height: auto")
        requireCssDeclaration(".markdown-image", "display: block")
        requireCssDeclaration(".markdown-list", "box-sizing: border-box")
        requireCssDeclaration(".markdown-list-item", "overflow-wrap: anywhere")
        requireCssDeclaration(".markdown-list-item", "white-space: pre-wrap")
        requireCssDeclaration(".markdown-rule", "border-top: 1px solid var(--border)")
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
        requireCssDeclaration(".note-edit-backdrop", "padding: 10px")
        requireCssDeclaration(".note-edit-panel", "width: min(1120px, calc(100vw - 20px))")
        requireCssDeclaration(".note-edit-panel", "max-height: min(94vh, 980px)")
        requireCssDeclaration(".note-edit-panel", "overflow-y: auto")
        requireCssDeclaration(".note-edit-panel", "overflow-x: hidden")
        requireCssDeclaration(".note-edit-header", "position: sticky")
        requireCssDeclaration(".note-edit-header", "flex-wrap: wrap")
        requireCssDeclaration(".note-edit-actions", "display: flex")
        requireCssDeclaration(".note-edit-actions", "flex-wrap: wrap")
        requireCssDeclaration(".note-edit-panel .field-label", "flex-direction: column")
        requireCssDeclaration(".note-edit-panel .note-editor-input", "width: 100%")
        requireCssDeclaration(".note-edit-panel .note-editor-input", "min-height: min(62vh, 620px)")
        requireCssDeclaration(".note-delete-panel", "width: min(460px, calc(100vw - 28px))")
        requireCssDeclaration(".note-delete-panel", "max-height: min(86vh, 520px)")
        requireCssDeclaration(".note-delete-title", "overflow-wrap: anywhere")
        requireCssDeclaration(".note-delete-actions .danger-action", "color: var(--danger)")

        val deploymentDoc = deploymentSecurityDoc.asFile.readText()
        val expectedCspDirectives = listOf(
            "default-src 'self'",
            "script-src 'self'",
            "img-src 'self' data: https:",
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
