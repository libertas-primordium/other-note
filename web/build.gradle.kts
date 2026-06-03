import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

plugins.withType<NodeJsPlugin> {
    extensions.configure<NodeJsEnvSpec>(NodeJsEnvSpec.EXTENSION_NAME) {
        download.set(false)
        command.set("node")
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
}
