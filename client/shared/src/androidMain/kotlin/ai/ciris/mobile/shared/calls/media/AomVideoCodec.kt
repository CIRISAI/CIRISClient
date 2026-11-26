package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.Layer
import android.util.Log

/**
 * AV1 on any Android phone (CSD-134, "AV1 both ways"): libaom, real-time and
 * 8-bit, behind src/androidMain/cpp/ciris_aom_jni.c. Used where the device has
 * no hardware AV1: a desktop sends AV1 and shows only AV1 unless its person
 * turns on Cisco's H.264, so a phone that sent H.264 showed no picture there.
 */
internal object AomNative {
    /** Null where the library did not load: this device then falls back to H.264. */
    val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("ciris_aom") }.onFailure { Log.e(TAG, "libciris_aom did not load", it) }.isSuccess
    }

    @JvmStatic external fun encOpen(w: Int, h: Int, fps: Int, kbps: Int, threads: Int): Long
    @JvmStatic external fun encEncode(h: Long, y: ByteArray, u: ByteArray, v: ByteArray, ptsUs: Long, key: Boolean): ByteArray?
    @JvmStatic external fun encClose(h: Long)
    @JvmStatic external fun decOpen(threads: Int): Long
    @JvmStatic external fun decDecode(h: Long, data: ByteArray): ByteArray?
    @JvmStatic external fun decClose(h: Long)

    private const val TAG = "CallVideo"
}

/** Two threads: a call frame is small, and a phone has other work during a call. */
private const val THREADS = 2

internal class AomVideoEncoder private constructor(private val handle: Long) : VideoEncoder {
    override val codecId = CallVideoCodec.AV1_SVC
    override val codecName = "av1"
    private var closed = false

    @Synchronized
    override fun encode(frame: VideoFrame, forceKeyframe: Boolean, onChunk: (EncodedChunk) -> Unit) {
        if (closed) return
        val tu = AomNative.encEncode(handle, frame.y, frame.u, frame.v, frame.timestampUs, forceKeyframe) ?: return
        // The witness is the bytes, as a receiver will read them.
        onChunk(EncodedChunk(Layer.BASE, tu, Keyframes.isKeyframe(codecId, tu)))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        AomNative.encClose(handle)
    }

    companion object {
        fun open(width: Int, height: Int, fps: Int, bitrate: Int): AomVideoEncoder? {
            if (!AomNative.loaded) return null
            val h = AomNative.encOpen(width, height, fps, (bitrate / 1000).coerceAtLeast(50), THREADS)
            return if (h == 0L) null else AomVideoEncoder(h)
        }
    }
}

internal class AomVideoDecoder private constructor(private val handle: Long) : VideoDecoder {
    private var closed = false

    @Synchronized
    override fun decode(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit) {
        if (closed) return
        val pic = AomNative.decDecode(handle, bytes) ?: return
        val w = int(pic, 0)
        val h = int(pic, 4)
        val ys = w * h
        val cs = ys / 4
        onFrame(
            VideoFrame(
                w, h,
                pic.copyOfRange(8, 8 + ys),
                pic.copyOfRange(8 + ys, 8 + ys + cs),
                pic.copyOfRange(8 + ys + cs, 8 + ys + 2 * cs),
                0,
            ),
        )
    }

    private fun int(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        AomNative.decClose(handle)
    }

    companion object {
        fun open(): AomVideoDecoder? {
            if (!AomNative.loaded) return null
            val h = AomNative.decOpen(THREADS)
            return if (h == 0L) null else AomVideoDecoder(h)
        }
    }
}
