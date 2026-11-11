package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.Layer
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * Cisco's OpenH264 library, loaded from the file [OpenH264Store] checked, and
 * called through JNA. Nothing here is compiled against the library: the calls go
 * through its C++ interfaces' virtual tables by position, and the structs are
 * written as bytes at fixed offsets.
 *
 * So the positions and offsets below are OpenH264 2.7.0's, from its
 * `codec_api.h` and `codec_app_def.h`, and [load] refuses a library that reports
 * any other version. They were checked against a C program built from those
 * headers on Linux x86-64. Every struct used here holds only `int`, `float`,
 * `long long`, `unsigned char` and pointers, so the same offsets hold on the
 * other 64-bit systems Cisco publishes for; that was reasoned from the headers
 * and has not been run on macOS or Windows.
 */
internal class OpenH264Api private constructor(private val lib: NativeLibrary) {
    /**
     * False once the person turns H.264 off. The library stays mapped until the
     * app exits, because a codec on another thread may be inside it; every
     * encoder and decoder asks this before each call and does nothing when false.
     */
    @Volatile
    var enabled = true

    private val createEncoder = lib.getFunction("WelsCreateSVCEncoder")
    private val destroyEncoder = lib.getFunction("WelsDestroySVCEncoder")
    private val createDecoder = lib.getFunction("WelsCreateDecoder")
    private val destroyDecoder = lib.getFunction("WelsDestroyDecoder")

    fun encoder(width: Int, height: Int, fps: Int, bitrate: Int): VideoEncoder? {
        if (!enabled) return null
        return try {
            OpenH264Encoder(this, width, height, fps, bitrate)
        } catch (e: IllegalStateException) {
            System.err.println("[call] OpenH264 encoder ${width}x$height: ${e.message}")
            null
        }
    }

    fun decoder(): VideoDecoder? {
        if (!enabled) return null
        return try {
            OpenH264Decoder(this)
        } catch (e: IllegalStateException) {
            System.err.println("[call] OpenH264 decoder: ${e.message}")
            null
        }
    }

    internal fun newEncoder(): Pointer {
        val ref = PointerByReference()
        check(createEncoder.invokeInt(arrayOf(ref)) == 0) { "WelsCreateSVCEncoder failed" }
        return checkNotNull(ref.value) { "WelsCreateSVCEncoder gave no encoder" }
    }

    internal fun freeEncoder(handle: Pointer) = destroyEncoder.invokeVoid(arrayOf(handle))

    internal fun newDecoder(): Pointer {
        val ref = PointerByReference()
        check(createDecoder.invokeInt(arrayOf(ref)) == 0) { "WelsCreateDecoder failed" }
        return checkNotNull(ref.value) { "WelsCreateDecoder gave no decoder" }
    }

    internal fun freeDecoder(handle: Pointer) = destroyDecoder.invokeVoid(arrayOf(handle))

    companion object {
        /** Load [file] and require it to be the pinned version. Throws when it is not, or will not load. */
        fun load(file: File): OpenH264Api {
            val lib = NativeLibrary.getInstance(file.absolutePath)
            val v = Memory(16) // OpenH264Version: major, minor, revision, reserved
            lib.getFunction("WelsGetCodecVersionEx").invokeVoid(arrayOf(v))
            val got = "${v.getInt(0)}.${v.getInt(4)}.${v.getInt(8)}"
            check(got == OpenH264Builds.VERSION) { "library reports version $got, not ${OpenH264Builds.VERSION}" }
            return OpenH264Api(lib)
        }

        /** The method at [index] in the C++ object's virtual table, called with the object first. */
        internal fun call(handle: Pointer, index: Int, vararg args: Any?): Int {
            val vtable = handle.getPointer(0)
            val fn = Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE))
            return fn.invokeInt(arrayOf(handle, *args))
        }
    }
}

/** `ISVCEncoder`, one spatial and one temporal layer: [Layer.BASE]. */
internal class OpenH264Encoder(
    private val api: OpenH264Api,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    bitrate: Int,
) : VideoEncoder {
    override val codecId = CallVideoCodec.TEMPORAL_LAYERED
    override val codecName = "h264"

    private val handle: Pointer
    private val packetizer = H264Packetizer()
    private val ySize = width * height
    private val cSize = ySize / 4
    private val planes = Memory(ySize + 2L * cSize)
    private val picture = Memory(SOURCE_PICTURE_SIZE)
    private val info = Memory(FRAME_BS_INFO_SIZE)
    private var frames = 0L
    private var closed = false

    init {
        check(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "I420 needs even dimensions" }
        handle = api.newEncoder()
        quiet(TRACE_LEVEL)
        // SEncParamBase: camera video in real time, at a bitrate. Keyframes are the
        // caller's (CallVideo forces one every two seconds), so no intra period.
        val p = Memory(ENC_PARAM_BASE_SIZE).apply { clear() }
        p.setInt(0, USAGE_CAMERA_REAL_TIME)
        p.setInt(4, width)
        p.setInt(8, height)
        p.setInt(12, bitrate)
        p.setInt(16, RC_BITRATE_MODE)
        p.setFloat(20, fps.toFloat())
        val r = OpenH264Api.call(handle, INITIALIZE, p)
        if (r != 0) {
            api.freeEncoder(handle)
            error("Initialize returned $r")
        }
        option(OPTION_DATAFORMAT, VIDEO_FORMAT_I420)
        // Every frame is sent: a skipped frame is a hole in the layer's frame count.
        option(OPTION_RC_FRAME_SKIP, 0)

        picture.clear()
        picture.setInt(0, VIDEO_FORMAT_I420)
        picture.setInt(4, width)
        picture.setInt(8, width / 2)
        picture.setInt(12, width / 2)
        picture.setPointer(24, planes)
        picture.setPointer(32, planes.share(ySize.toLong()))
        picture.setPointer(40, planes.share(ySize.toLong() + cSize))
        picture.setInt(56, width)
        picture.setInt(60, height)
    }

    private fun quiet(optionId: Int) = option(optionId, WELS_LOG_QUIET)

    private fun option(id: Int, value: Int) {
        val v = Memory(4)
        v.setInt(0, value)
        OpenH264Api.call(handle, SET_OPTION, id, v)
    }

    @Synchronized
    override fun encode(frame: VideoFrame, forceKeyframe: Boolean, onChunk: (EncodedChunk) -> Unit) {
        if (closed || !api.enabled) return
        require(frame.width == width && frame.height == height) { "encoder is ${width}x$height, frame ${frame.width}x${frame.height}" }
        planes.write(0, frame.y, 0, ySize)
        planes.write(ySize.toLong(), frame.u, 0, cSize)
        planes.write(ySize.toLong() + cSize, frame.v, 0, cSize)
        picture.setLong(64, frames++ * 1000 / fps) // uiTimeStamp, milliseconds
        if (forceKeyframe) OpenH264Api.call(handle, FORCE_INTRA_FRAME, 1, -1)
        info.clear()
        if (OpenH264Api.call(handle, ENCODE_FRAME, picture, info) != 0) return
        val type = info.getInt(FRAME_TYPE_AT)
        if (type == FRAME_TYPE_INVALID || type == FRAME_TYPE_SKIP) return
        // SFrameBSInfo: each layer is a run of NAL units, already in Annex B form.
        // On a keyframe the first layer is the SPS and PPS.
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until info.getInt(0).coerceIn(0, MAX_LAYERS)) {
            val layer = LAYERS_AT + i.toLong() * LAYER_SIZE
            val count = info.getInt(layer + 16)
            val lengths = info.getPointer(layer + 24) ?: continue
            val bytes = info.getPointer(layer + 32) ?: continue
            var size = 0
            for (n in 0 until count) size += lengths.getInt(n * 4L)
            if (size > 0) out.write(bytes.getByteArray(0, size))
        }
        val data = out.toByteArray()
        if (data.isEmpty()) return
        val payload = packetizer.accessUnit(data, type == FRAME_TYPE_IDR || AnnexB.isKeyframe(data))
        // The witness is the bytes, as a receiver will read them.
        onChunk(EncodedChunk(Layer.BASE, payload, Keyframes.isKeyframe(codecId, payload)))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        OpenH264Api.call(handle, UNINITIALIZE)
        api.freeEncoder(handle)
    }

    private companion object {
        // ISVCEncoder's virtual table.
        const val INITIALIZE = 0
        const val UNINITIALIZE = 3
        const val ENCODE_FRAME = 4
        const val FORCE_INTRA_FRAME = 6
        const val SET_OPTION = 7

        const val ENC_PARAM_BASE_SIZE = 24L
        const val SOURCE_PICTURE_SIZE = 80L
        const val FRAME_BS_INFO_SIZE = 7192L
        const val LAYERS_AT = 8L
        const val LAYER_SIZE = 56
        const val MAX_LAYERS = 128
        const val FRAME_TYPE_AT = 7176L

        const val USAGE_CAMERA_REAL_TIME = 0
        const val RC_BITRATE_MODE = 1
        const val VIDEO_FORMAT_I420 = 23
        const val FRAME_TYPE_INVALID = 0
        const val FRAME_TYPE_IDR = 1
        const val FRAME_TYPE_SKIP = 4
        const val OPTION_DATAFORMAT = 0
        const val OPTION_RC_FRAME_SKIP = 9
        const val TRACE_LEVEL = 25
        const val WELS_LOG_QUIET = 0
    }
}

/** `ISVCDecoder`: one access unit in, at most one picture out, no reordering delay. */
internal class OpenH264Decoder(private val api: OpenH264Api) : VideoDecoder {
    private val handle: Pointer = api.newDecoder()
    private var input = Memory(1 shl 16)
    private val planes = Memory(3L * Native.POINTER_SIZE)
    private val info = Memory(BUFFER_INFO_SIZE)
    private var closed = false

    init {
        val level = Memory(4)
        level.setInt(0, WELS_LOG_QUIET)
        OpenH264Api.call(handle, SET_OPTION, TRACE_LEVEL, level)
        // SDecodingParam: plain H.264, and no concealment: a picture built on one
        // that never arrived is not drawn. CallVideo waits for the next keyframe.
        val p = Memory(DECODING_PARAM_SIZE).apply { clear() }
        p.setInt(16, ERROR_CON_DISABLE)
        p.setInt(28, VIDEO_BITSTREAM_DEFAULT)
        val r = OpenH264Api.call(handle, INITIALIZE, p)
        if (r != 0) {
            api.freeDecoder(handle)
            error("Initialize returned $r")
        }
    }

    @Synchronized
    override fun decode(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit) {
        if (closed || !api.enabled || bytes.isEmpty()) return
        if (input.size() < bytes.size) input = Memory(bytes.size.toLong() * 2)
        input.write(0, bytes, 0, bytes.size)
        planes.clear()
        info.clear()
        val state = OpenH264Api.call(handle, DECODE_FRAME_NO_DELAY, input, bytes.size, planes, info)
        if (state != 0 || info.getInt(0) != 1) return // a damaged chunk: skip it, the next keyframe recovers
        // SBufferInfo.UsrData.sSystemBuffer, then the three planes.
        val w = info.getInt(24) and 1.inv()
        val h = info.getInt(28) and 1.inv()
        val yStride = info.getInt(36)
        val cStride = info.getInt(40)
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || yStride < w || cStride < w / 2) return
        fun plane(i: Int, pw: Int, ph: Int, stride: Int): ByteArray? {
            val p = info.getPointer(48L + i * Native.POINTER_SIZE) ?: return null
            val out = ByteArray(pw * ph)
            for (row in 0 until ph) p.read(row.toLong() * stride, out, row * pw, pw)
            return out
        }
        val y = plane(0, w, h, yStride) ?: return
        val u = plane(1, w / 2, h / 2, cStride) ?: return
        val v = plane(2, w / 2, h / 2, cStride) ?: return
        onFrame(VideoFrame(w, h, y, u, v, System.nanoTime() / 1000))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        OpenH264Api.call(handle, UNINITIALIZE)
        api.freeDecoder(handle)
    }

    private companion object {
        // ISVCDecoder's virtual table.
        const val INITIALIZE = 0
        const val UNINITIALIZE = 1
        const val DECODE_FRAME_NO_DELAY = 3
        const val SET_OPTION = 8

        const val DECODING_PARAM_SIZE = 32L
        const val BUFFER_INFO_SIZE = 72L
        const val ERROR_CON_DISABLE = 0
        const val VIDEO_BITSTREAM_DEFAULT = 1
        const val TRACE_LEVEL = 9
        const val WELS_LOG_QUIET = 0
        /** H.264 level 6.2's largest side, well past any call's picture. */
        const val MAX_SIDE = 16_384
    }
}
