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
            "localStorage",
            "indexedDB",
            "document.cookie",
            "CacheStorage",
            "serviceWorker",
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
        val webMainText = runtimeSourceDir.file("com/libertasprimordium/othernote/web/WebMain.kt").asFile.readText()
        val relayInputUpdater = Regex(
            """private fun updateNoteRelayInput\(value: String\)\s*\{(?<body>.*?)}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(webMainText)?.groups?.get("body")?.value
            ?: error("WebMain.kt must define updateNoteRelayInput for Relay Settings input handling.")
        check(!relayInputUpdater.contains("render()")) {
            "Relay Settings input typing must not call render(); replacing the modal input on each character drops focus."
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

        fun cssBlock(selector: String): String =
            Regex("""${Regex.escape(selector)}\s*\{([^}]*)}""")
                .findAll(indexText)
                .lastOrNull()
                ?.groups
                ?.get(1)
                ?.value
                ?: error("Web index.html is missing CSS selector $selector")

        fun requireCssDeclaration(selector: String, declaration: String) {
            check(cssBlock(selector).contains(declaration)) {
                "Web index.html selector $selector must include `$declaration` to keep long note-card text inside cards."
            }
        }

        fun rejectCssDeclaration(selector: String, declaration: String) {
            check(!cssBlock(selector).contains(declaration)) {
                "Web index.html selector $selector must not include `$declaration`; note cards must use explicit horizontal lanes, not vertical-first CSS columns."
            }
        }

        listOf(".note-list", ".note-lane", ".notes-panel", ".note-card", ".note-card-open", ".modal-panel", ".modal-header", ".note-detail-panel", ".note-detail-body", ".markdown-view", ".markdown-code-block").forEach { selector ->
            requireCssDeclaration(selector, "max-width: 100%")
        }
        listOf(".note-list", ".note-lane", ".notes-panel", ".note-card", ".note-card-open", ".modal-panel", ".modal-header", ".modal-header .section-title", ".note-detail-panel", ".note-detail-body", ".markdown-view", ".markdown-code-block").forEach { selector ->
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
        requireCssDeclaration(".note-card-actions", "flex-direction: row")
        requireCssDeclaration(".note-card-actions", "gap: 6px")
        requireCssDeclaration(".note-card-actions .action-button", "min-height: 34px")
        requireCssDeclaration(".note-card-actions .action-button", "border-color: rgba(142, 68, 255, 0.68)")
        requireCssDeclaration(".note-card-actions .action-button", "white-space: nowrap")
        requireCssDeclaration(".note-card-actions .action-button:last-child", "border-color: rgba(255, 139, 139, 0.58)")
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
