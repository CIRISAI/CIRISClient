package ai.ciris.mobile.shared.calls.media

import android.util.Log

/**
 * Android call video on MediaCodec (AndroidVideoCodec.kt). The encoder is AV1
 * (`0x01`) only on a hardware AV1 encoder that says it runs this size and rate;
 * otherwise H.264 (`0x04`). Null only when the device has no usable encoder,
 * and the call then runs audio only.
 */
actual fun createVideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int): VideoEncoder? = runCatching {
    val av1 = realtimeAv1Encoder(width, height, fps)
    if (av1 != null) AndroidVideoEncoder(av1, "video/av01", fps, bitrate)
    else avcEncoder()?.let { AndroidVideoEncoder(it, "video/avc", fps, bitrate) }
}.onFailure { Log.e("CallVideo", "no video encoder", it) }.getOrNull()

/**
 * H.264 always; AV1 only where the device has a hardware AV1 decoder (no
 * software AV1 decoder is bundled), else null and the tile says audio only.
 */
actual fun createVideoDecoder(codecId: Int): VideoDecoder? = AndroidVideoDecoder.create(codecId)
