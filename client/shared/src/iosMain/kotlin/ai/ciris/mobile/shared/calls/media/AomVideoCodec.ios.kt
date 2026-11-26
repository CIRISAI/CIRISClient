@file:OptIn(ExperimentalForeignApi::class)

package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.Layer
import ai.ciris.mobile.shared.calls.media.aom.ciris_aom_dec_close
import ai.ciris.mobile.shared.calls.media.aom.ciris_aom_dec_decode
import ai.ciris.mobile.shared.calls.media.aom.ciris_aom_dec_open
import ai.ciris.mobile.shared.calls.media.aom.ciris_aom_enc_close
import ai.ciris.mobile.shared.calls.media.aom.ciris_aom_enc_encode
import ai.ciris.mobile.shared.calls.media.aom.ciris_aom_enc_open
import ai.ciris.mobile.shared.calls.media.aom.ciris_aom_free
import kotlinx.cinterop.*
import platform.Foundation.NSLock
import platform.posix.size_tVar

/**
 * AV1 on any iPhone or iPad (CSD-134, AV1 both ways): libaom, real-time and
 * 8-bit, through src/nativeInterop/aom/ciris_aom.h, the same C the Android
 * build uses. No iPhone encodes AV1 in hardware, and only A17 Pro / M3 and
 * later decode it, so this is what lets an iPhone and a desktop see each other.
 */
private const val THREADS = 2

internal class AomVideoEncoder private constructor(private val e: COpaquePointer) : VideoEncoder {
    override val codecId = CallVideoCodec.AV1_SVC
    override val codecName = "av1"
    private val lock = NSLock()
    private var closed = false

    override fun encode(frame: VideoFrame, forceKeyframe: Boolean, onChunk: (EncodedChunk) -> Unit) {
        lock.lock()
        val tu: ByteArray? = try {
            if (closed) null else memScoped {
                val out = alloc<CPointerVar<UByteVar>>()
                val len = alloc<size_tVar>()
                val r = frame.y.usePinned { y ->
                    frame.u.usePinned { u ->
                        frame.v.usePinned { v ->
                            ciris_aom_enc_encode(
                                e,
                                y.addressOf(0).reinterpret(), u.addressOf(0).reinterpret(), v.addressOf(0).reinterpret(),
                                frame.timestampUs, if (forceKeyframe) 1 else 0, out.ptr, len.ptr,
                            )
                        }
                    }
                }
                val p = out.value
                try {
                    if (r != 0 || p == null || len.value == 0uL) null else p.readBytes(len.value.toInt())
                } finally {
                    ciris_aom_free(p)
                }
            }
        } finally {
            lock.unlock()
        }
        // The witness is the bytes, as a receiver will read them.
        tu?.let { onChunk(EncodedChunk(Layer.BASE, it, Keyframes.isKeyframe(codecId, it))) }
    }

    override fun close() {
        lock.lock()
        try {
            if (closed) return
            closed = true
            ciris_aom_enc_close(e)
        } finally {
            lock.unlock()
        }
    }

    companion object {
        fun open(width: Int, height: Int, fps: Int, bitrate: Int): AomVideoEncoder? =
            ciris_aom_enc_open(width, height, fps, (bitrate / 1000).coerceAtLeast(50), THREADS)?.let { AomVideoEncoder(it) }
    }
}

internal class AomVideoDecoder private constructor(private val d: COpaquePointer) : VideoDecoder {
    private val lock = NSLock()
    private var closed = false

    override fun decode(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit) {
        if (bytes.isEmpty()) return
        lock.lock()
        val frame: VideoFrame? = try {
            if (closed) null else memScoped {
                val out = alloc<CPointerVar<UByteVar>>()
                val w = alloc<IntVar>()
                val h = alloc<IntVar>()
                val r = bytes.usePinned { b ->
                    ciris_aom_dec_decode(d, b.addressOf(0).reinterpret(), bytes.size.convert(), out.ptr, w.ptr, h.ptr)
                }
                val p = out.value
                try {
                    if (r != 0 || p == null) null else {
                        val ys = w.value * h.value
                        val cs = ys / 4
                        val all = p.readBytes(ys + 2 * cs)
                        VideoFrame(
                            w.value, h.value,
                            all.copyOfRange(0, ys), all.copyOfRange(ys, ys + cs), all.copyOfRange(ys + cs, ys + 2 * cs), 0,
                        )
                    }
                } finally {
                    ciris_aom_free(p)
                }
            }
        } finally {
            lock.unlock()
        }
        frame?.let(onFrame)
    }

    override fun close() {
        lock.lock()
        try {
            if (closed) return
            closed = true
            ciris_aom_dec_close(d)
        } finally {
            lock.unlock()
        }
    }

    companion object {
        fun open(): AomVideoDecoder? = ciris_aom_dec_open(THREADS)?.let { AomVideoDecoder(it) }
    }
}
