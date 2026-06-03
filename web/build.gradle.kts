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
        check(!indexText.contains("""http-equiv="Content-Security-Policy"""")) {
            "Web index.html must not enforce CSP through a meta tag; production CSP belongs in host HTTP headers."
        }

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
