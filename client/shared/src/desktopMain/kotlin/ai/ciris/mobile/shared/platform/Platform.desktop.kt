package ai.ciris.mobile.shared.platform

import java.awt.Desktop
import java.net.URI

actual fun getPlatform(): Platform = Platform.DESKTOP

actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}

actual fun getDeviceDebugInfo(): String {
    return buildString {
        appendLine("Platform: Desktop JVM")
        appendLine("Java Version: ${System.getProperty("java.version")}")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        appendLine("Arch: ${System.getProperty("os.arch")}")
        appendLine("User: ${System.getProperty("user.name")}")
        appendLine("Home: ${System.getProperty("user.home")}")
    }
}

actual fun openUrlInBrowser(url: String) {
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (e: Exception) {
        println("Failed to open URL: $url - ${e.message}")
    }
}

/**
 * Desktop implementation: read version from JAR manifest or fallback to constant.
 * The version is set in desktopApp/build.gradle.kts compose.desktop.application.version
 */
actual fun getAppVersion(): String {
    // Try to read from JAR manifest (set by Compose Desktop build)
    return try {
        val pkg = Platform::class.java.`package`
        pkg?.implementationVersion ?: DESKTOP_VERSION_FALLBACK
    } catch (e: Exception) {
        DESKTOP_VERSION_FALLBACK
    }
}

/**
 * Desktop implementation: build number (not applicable, return "0").
 */
actual fun getAppBuildNumber(): String = "0"

actual fun startTestAutomationServer() {
    // Desktop: no-op here — server is started from desktopApp/Main.kt
}

/**
 * Fallback version if the JAR manifest is unavailable.
 *
 * THE FALLBACK IS THE ONLY VALUE DESKTOP EVER REPORTS, which is why the old
 * hand-maintained constant drifted to `2.3.2` while builds shipped 2.9.x and
 * then 0.5.x. The Compose uber-jar writes only `Main-Class` into its manifest,
 * so `implementationVersion` is always null and the "fallback" above is the
 * whole answer. A diagnostics bundle from a 0.5.191 build named 2.3.2 as its
 * version — in the one artifact whose entire job is to say what was running.
 *
 * "Keep in sync with androidApp/build.gradle versionName" was the instruction,
 * and nothing enforced it in either repo (CIRISClient#11 asked us to put a
 * check on it if we kept the constant). We do not keep it: CLIENT_VERSION is
 * GENERATED from the repo-root VERSION file by :shared:generateBuildFlavor —
 * the same value the wheel publishes — so there is no second number that can
 * drift, and no check needed for one that cannot exist.
 */
private val DESKTOP_VERSION_FALLBACK: String = ai.ciris.mobile.shared.models.CLIENT_VERSION
