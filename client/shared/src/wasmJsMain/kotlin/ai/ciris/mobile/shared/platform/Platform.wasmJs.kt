package ai.ciris.mobile.shared.platform

import kotlinx.browser.window

actual fun getPlatform(): Platform = Platform.WEB

actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}

actual fun getDeviceDebugInfo(): String {
    return buildString {
        appendLine("Platform: Web (WASM)")
        appendLine("User Agent: ${window.navigator.userAgent}")
        appendLine("Language: ${window.navigator.language}")
    }
}

actual fun openUrlInBrowser(url: String) {
    window.open(url, "_blank")
}

// The other three platforms read the packaged version from the artifact that
// carries them — JAR manifest, CFBundleShortVersionString, Android versionName.
// wasm has no bundle to ask, and answered with a literal that was staler than
// the sidebar's (CIRISClient#58). CLIENT_VERSION is generated from the same
// release: the packaged major is pinned to 1 and tracks it digit for digit
// (0.5.219 -> 1.5.219), so this names the same build the others do.
actual fun getAppVersion(): String = ai.ciris.mobile.shared.models.CLIENT_VERSION

actual fun getAppBuildNumber(): String = "0"

actual fun startTestAutomationServer() {
    // No-op on web - test automation via browser DevTools
}
