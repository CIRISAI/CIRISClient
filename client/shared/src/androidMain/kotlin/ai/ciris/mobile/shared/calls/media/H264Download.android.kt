package ai.ciris.mobile.shared.calls.media

/** MediaCodec supplies H.264 on Android, so there is nothing to download or to turn off. */
actual fun h264Download(): H264Download? = null
