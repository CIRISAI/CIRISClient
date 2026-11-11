package ai.ciris.mobile.shared.calls.media

/** VideoToolbox supplies H.264 on iOS, so there is nothing to download or to turn off. */
actual fun h264Download(): H264Download? = null
