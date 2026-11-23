package ai.ciris.mobile.shared.calls

/** `navigator.connection` (Chromium-based browsers): cellular, or the person's data saver. Elsewhere unknown, so false. */
private fun jsMetered(): Boolean =
    js("(function(){var c=navigator.connection;return !!(c && (c.type==='cellular' || c.saveData===true));})()")

actual fun networkIsMetered(): Boolean = runCatching { jsMetered() }.getOrDefault(false)
