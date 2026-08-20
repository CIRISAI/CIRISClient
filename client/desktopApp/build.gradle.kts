import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")

    // Test automation server (Ktor embedded) - must match shared module version
    val ktorVersion = "3.0.3"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
}

compose.desktop {
    application {
        mainClass = "ai.ciris.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CIRIS"
            packageVersion = "2.9.28"
            description = "CIRIS Agent Desktop Application"
            vendor = "CIRIS L3C"

            macOS {
                bundleID = "ai.ciris.desktop"
                iconFile.set(project.file("icons/icon.icns"))
            }

            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "CIRIS"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }

            linux {
                iconFile.set(project.file("icons/icon.png"))
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// LOCALIZATION: the committed bundle IS the source of truth here.
//
// This used to be a Gradle `Sync` from "../../localization" into
// src/main/resources/localization, wired to processResources. A `Sync` makes the
// destination MATCH the source — so when the source holds no *.json (upstream it
// holds three .txt and a CLAUDE.md; extracted, it resolves outside this repo
// altogether) the task does not copy nothing, it DELETES the 30 committed locale
// files in its destination. Localization is the product; it is never cut, and it
// is certainly never cut by a task whose name says "sync".
//
// The four in-tree bundles are kept identical by
// client/tools/check_localization_sync.py, which runs in CI. See
// client/VENDORING.md §5.
