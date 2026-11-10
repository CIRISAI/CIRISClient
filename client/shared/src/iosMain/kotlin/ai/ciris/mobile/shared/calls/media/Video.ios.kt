package ai.ciris.mobile.shared.calls.media

/**
 * Not built yet on this platform (CSD-134 build order). Null means "no video
 * codec here": the call runs audio only and the tile says so.
 */
actual fun createVideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int): VideoEncoder? = null

actual fun createVideoDecoder(codecId: Int): VideoDecoder? = null
