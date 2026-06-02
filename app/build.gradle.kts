import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("com.vitorpamplona.quartz:quartz:1.11.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.1")
                implementation("androidx.compose.ui:ui-tooling-preview:1.11.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    namespace = "com.libertasprimordium.othernote"
    compileSdkPreview = "CinnamonBun"

    defaultConfig {
        applicationId = "com.libertasprimordium.othernote"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

compose.desktop {
    application {
        mainClass = "com.libertasprimordium.othernote.DesktopMainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            modules("java.net.http")
            packageName = "Other Note"
            packageVersion = "0.1.0"
            description = "GPLv3 Nostr-backed encrypted notes app"
            vendor = "Libertas Primordium"
            licenseFile.set(project.rootProject.file("LICENSE"))
            linux {
                packageName = "other-note"
                iconFile.set(project.file("src/desktopMain/resources/icons/other-note.png"))
                shortcut = true
                appCategory = "Utility"
            }
        }
    }
}
