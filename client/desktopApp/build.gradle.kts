import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// ─── NODE VENDOR DRIFT #35 (restored after the 2.9.28 re-vendor hardcoded the
// AGENT's version here): the native-distribution version, DERIVED
// (CIRISServer#403) ──────────────────────────────────────────────────────────
//
// This is packaging metadata only: it names the Dmg/Msi/Deb bundle and, as a
// side effect, the uber-JAR's filename. It is NOT the client's identity — that
// is `CLIENT_VERSION` in shared/…/models/ClientMode.kt.
//
// It was the hardcoded string "2.6.0" from vendoring day until 0.5.171 — a
// real CIRISAgent release number, so every JAR filename and bug report read as
// "agent 2.6.0". The re-vendor reintroduced EXACTLY that defect as "2.9.28",
// with a sharper edge: shipped once, native upgraders (MSI especially) treat
// the next real 1.5.x as a DOWNGRADE and refuse it.
//
// It cannot simply BE the release version: compose-desktop refuses a 0.x major
// for Dmg (macOS CFBundleVersion requires major >= 1), so the major is forced
// to 1 and the rest tracks the release digit for digit — 0.5.185 -> 1.5.185.
// Being derived is the point: there is nothing left to forget to bump.
// The release-version source differs by habitat: in CIRISClient (the tree of
// record) it is the repo-root VERSION file — the same one source the flavor
// generation and the wheel version already read; in CIRISServer it is
// Cargo.toml. Try VERSION first so the derivation keeps working in both.
val releaseVersion: String = run {
    val versionFile = rootProject.file("../VERSION")
    val cargoToml = rootProject.file("../Cargo.toml")
    when {
        versionFile.exists() -> versionFile.readText().trim()
        cargoToml.exists() ->
            Regex("""^version = "([^"]+)"""", RegexOption.MULTILINE)
                .find(cargoToml.readText())
                ?.groupValues
                ?.get(1)
                ?: error("could not read [package] version from Cargo.toml")
        else -> error(
            "no release-version source: neither ../VERSION (CIRISClient) " +
            "nor ../Cargo.toml (CIRISServer) exists"
        )
    }
}

val nativePackageVersion: String =
    releaseVersion.split('.').let { parts ->
        require(parts.size >= 3) { "release version '$releaseVersion' is not major.minor.patch" }
        // Only the major is coerced, and only because the format forbids 0.
        val major = parts[0].toIntOrNull() ?: error("non-numeric major in '$releaseVersion'")
        "${maxOf(major, 1)}.${parts[1]}.${parts[2]}"
    }

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
            packageVersion = nativePackageVersion
            // Neither "Node" nor "Agent": since CIRISServer#479 ONE artifact
            // ships and narrows itself against the probed node, so naming it
            // for either half describes a build that no longer exists.
            description = "CIRIS Desktop Application"
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
// (Upstream removed the same task as NODE VENDOR DRIFT #9, CIRISServer#465 —
// same destination-owning Sync hazard as androidApp's syncLocalizationAssets.)
//
// This used to be a Gradle `Sync` from "../../localization" into
// src/main/resources/localization, wired to processResources. A `Sync` makes the
// destination MATCH the source — so when the source holds no *.json (upstream it
// holds three .txt and a CLAUDE.md; extracted, it resolves outside this repo
// altogether) the task does not copy nothing, it DELETES all 30 committed files
// in its destination — the 29 locales and manifest.json. Localization is the
// product; it is never cut, and it is certainly never cut by a task whose name
// says "sync".
//
// The four in-tree bundles are kept identical by
// client/tools/check_localization_sync.py, which runs in CI. See
// client/VENDORING.md §5.

// ─── CALL VIDEO NATIVES COME FROM ciris-holon ───────────────────────────────
//
// FFmpeg's natives are 18-24 MiB per OS, and with them inside the uber-jar the
// 0.5.242 macOS x86-64 wheel was 0.18 MiB under PyPI's 100 MiB file limit. They
// are published once, separately, as `ciris-holon` (CIRISHolon, media/jars/,
// the bytedeco jars byte-identical), and ciris-client depends on it.
//
// So the uber-jar leaves this OS's natives out and names them in its manifest
// `Class-Path`, relative to where the jar sits once installed:
//
//   site-packages/ciris_client/_artifacts/<this jar>
//   site-packages/ciris_holon/media/jars/<the bytedeco jars>
//
// `java -jar <jar>` is unchanged (CIRISServer and CIRISAgent launch exactly
// that). A Class-Path entry that does not exist is ignored by the JVM; FFmpeg
// then fails to load and the call is audio-only from this side, which
// Video.desktop.kt already does and says why. The Java bindings (small) stay in.
//
// The same bytedeco jars Gradle resolved are copied to build/ciris_holon/media/
// jars/, the same relative place from build/compose/jars/, so a jar run from the
// build tree (the five-platform run, a developer) loads them through the very
// Class-Path the wheel uses rather than a second mechanism.
evaluationDependsOn(":shared")
val bytedecoClassifier = project(":shared").extra["bytedecoHostClassifier"] as String
val bytedecoNativeJars = listOf(
    "javacpp-${project(":shared").extra["bytedecoJavacppVersion"]}-$bytedecoClassifier.jar",
    "ffmpeg-${project(":shared").extra["bytedecoFfmpegVersion"]}-$bytedecoClassifier.jar",
)
val holonJarsRelative = "../../ciris_holon/media/jars"

val stageHolonJars by tasks.registering(Sync::class) {
    description = "Places the bytedeco native jars where the uber-jar's Class-Path looks, for build-tree runs."
    from(configurations.named("runtimeClasspath").map { cp -> cp.filter { it.name in bytedecoNativeJars } })
    into(layout.buildDirectory.dir("ciris_holon/media/jars"))
    doLast {
        val got = destinationDir.list()?.toSet().orEmpty()
        val missing = bytedecoNativeJars.filterNot { it in got }
        if (missing.isNotEmpty()) throw GradleException("bytedeco native jars not on the runtime classpath: $missing")
    }
}

tasks.matching { it.name == "packageUberJarForCurrentOS" }.configureEach {
    this as org.gradle.jvm.tasks.Jar
    exclude("org/bytedeco/ffmpeg/$bytedecoClassifier/**", "org/bytedeco/javacpp/$bytedecoClassifier/**")
    manifest {
        attributes("Class-Path" to bytedecoNativeJars.joinToString(" ") { "$holonJarsRelative/$it" })
    }
    finalizedBy(stageHolonJars)
}
