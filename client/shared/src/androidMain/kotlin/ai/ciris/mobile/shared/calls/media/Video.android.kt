package ai.ciris.mobile.shared.calls.media

import android.util.Log

/**
 * Android call video (CSD-134, AV1 both ways). The encoder is AV1 (`0x01`): a
 * hardware AV1 encoder that says it runs this size and rate, else libaom in
 * software (AomVideoCodec.kt). H.264 (`0x04`) only where libaom did not load.
 * Null only when the device has no usable encoder, and the call runs audio only.
 */
actual fun createVideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int): VideoEncoder? = runCatching {
    val av1 = realtimeAv1Encoder(width, height, fps)
    if (av1 != null) AndroidVideoEncoder(av1, "video/av01", fps, bitrate)
    else AomVideoEncoder.open(width, height, fps, bitrate)
        ?: avcEncoder()?.let { AndroidVideoEncoder(it, "video/avc", fps, bitrate) }
}.onFailure { Log.e("CallVideo", "no video encoder", it) }.getOrNull()

/**
 * H.264: hardware, else the platform's software decoder. AV1, which every client
 * must decode (CC 5.3.3.2.1): hardware, else libaom, else the platform's
 * software one (Android 10+). Null only when none starts, and the tile says audio only.
 */
actual fun createVideoDecoder(codecId: Int): VideoDecoder? = when (codecId) {
    CallVideoCodec.AV1_SVC ->
        AndroidVideoDecoder.create(codecId, hardwareOnly = true) ?: AomVideoDecoder.open() ?: AndroidVideoDecoder.create(codecId)
    else -> AndroidVideoDecoder.create(codecId)
}
