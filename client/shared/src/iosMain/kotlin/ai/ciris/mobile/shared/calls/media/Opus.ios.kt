package ai.ciris.mobile.shared.calls.media

/**
 * Not built yet on this platform (CSD-134 build order): iOS uses AudioToolbox's
 * Opus, the web WebCodecs. Until then a call here has no audio, and
 * [opusAvailable] says so, so the screen never pretends otherwise.
 */
actual fun createOpusEncoder(): OpusEncoder = throw UnsupportedOperationException("call audio is not built on this platform yet")

actual fun createOpusDecoder(): OpusDecoder = throw UnsupportedOperationException("call audio is not built on this platform yet")

actual val opusAvailable: Boolean = false
