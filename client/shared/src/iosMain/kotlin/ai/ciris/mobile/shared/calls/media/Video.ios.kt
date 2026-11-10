@file:OptIn(ExperimentalForeignApi::class)

package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.Layer
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreMedia.*
import platform.CoreVideo.*
import platform.Foundation.NSData
import platform.Foundation.NSLock
import platform.Foundation.create
import platform.VideoToolbox.*

/**
 * iOS video through VideoToolbox.
 *
 * Encode: H.264 only (`0x04`, "h264"). No iPhone or iPad encodes AV1 in hardware,
 * so [createVideoEncoder] never returns AV1 here.
 *
 * Decode: H.264 always; AV1 (`0x01`) only where VideoToolbox reports a hardware
 * AV1 decoder (A17 Pro / M3 and later), else null and the tile says so. There is
 * no software AV1 decoder on iOS (dav1d is not bundled).
 */
actual fun createVideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int): VideoEncoder? =
    VtH264Encoder.open(width, height, fps, bitrate)

actual fun createVideoDecoder(codecId: Int): VideoDecoder? = when (codecId) {
    CallVideoCodec.TEMPORAL_LAYERED -> VtH264Decoder()
    CallVideoCodec.AV1_SVC -> if (av1HardwareDecode) VtAv1Decoder() else null
    else -> null
}

private const val CODEC_H264 = 0x61766331u // 'avc1' kCMVideoCodecType_H264
private const val CODEC_AV1 = 0x61763031u // 'av01' kCMVideoCodecType_AV1
private const val NO_ERR: Int = 0

private val av1HardwareDecode: Boolean by lazy { macBool(VTIsHardwareDecodeSupported(CODEC_AV1)) }

/** Copies a CMSampleBuffer's data out as one ByteArray. */
private fun sampleBytes(sb: CMSampleBufferRef): ByteArray? {
    val block = CMSampleBufferGetDataBuffer(sb) ?: return null
    val len = CMBlockBufferGetDataLength(block).toInt()
    if (len <= 0) return null
    val out = ByteArray(len)
    val status = out.usePinned { CMBlockBufferCopyDataBytes(block, 0uL, len.toULong(), it.addressOf(0)) }
    return if (status == NO_ERR) out else null
}

/** The SPS and PPS of an H.264 format description, and its NAL length size. */
private fun h264ParameterSets(desc: CMFormatDescriptionRef): Pair<List<ByteArray>, Int> = memScoped {
    val count = alloc<ULongVar>()
    val lengthSize = alloc<IntVar>()
    if (CMVideoFormatDescriptionGetH264ParameterSetAtIndex(desc, 0uL, null, null, count.ptr, lengthSize.ptr) != NO_ERR) {
        return@memScoped emptyList<ByteArray>() to 4
    }
    val sets = ArrayList<ByteArray>()
    for (i in 0 until count.value.toInt()) {
        val p = alloc<CPointerVar<UByteVar>>()
        val size = alloc<ULongVar>()
        if (CMVideoFormatDescriptionGetH264ParameterSetAtIndex(desc, i.toULong(), p.ptr, size.ptr, null, null) == NO_ERR) {
            val ptr = p.value ?: continue
            sets += ptr.reinterpret<ByteVar>().readBytes(size.value.toInt())
        }
    }
    sets to lengthSize.value.takeIf { it in 1..4 }.let { it ?: 4 }
}

/**
 * VTCompressionSession H.264: Baseline, real time, no frame reordering, a
 * keyframe at least every two seconds and whenever asked. Output is one Annex B
 * access unit per frame, SPS and PPS before every keyframe (CallVideoCodec).
 * A frame of a new size reopens the session (and so starts at a keyframe).
 */
private class VtH264Encoder private constructor(
    private val fps: Int,
    private val bitrate: Int,
) : VideoEncoder {
    override val codecId = CallVideoCodec.TEMPORAL_LAYERED
    override val codecName = "h264"

    private val lock = NSLock()
    private var session: VTCompressionSessionRef? = null
    private var width = 0
    private var height = 0
    private var closed = false

    private fun openSession(w: Int, h: Int): Boolean {
        closeSession()
        val s = memScoped {
            val out = alloc<VTCompressionSessionRefVar>()
            val status = VTCompressionSessionCreate(kCFAllocatorDefault, w, h, CODEC_H264, null, null, null, null, null, out.ptr)
            if (status != NO_ERR) null else out.value
        } ?: return false
        VTSessionSetProperty(s, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
        VTSessionSetProperty(s, kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_Baseline_AutoLevel)
        VTSessionSetProperty(s, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
        withCfInt(bitrate) { VTSessionSetProperty(s, kVTCompressionPropertyKey_AverageBitRate, it) }
        withCfInt(fps.coerceAtLeast(1) * 2) { VTSessionSetProperty(s, kVTCompressionPropertyKey_MaxKeyFrameInterval, it) }
        withCfInt(fps) { VTSessionSetProperty(s, kVTCompressionPropertyKey_ExpectedFrameRate, it) }
        VTCompressionSessionPrepareToEncodeFrames(s)
        session = s
        width = w
        height = h
        return true
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        VTCompressionSessionInvalidate(s)
        CFRelease(s)
    }

    override fun encode(frame: VideoFrame, forceKeyframe: Boolean, onChunk: (EncodedChunk) -> Unit) {
        lock.lock()
        try {
            if (closed) return
            var force = forceKeyframe
            if (session == null || frame.width != width || frame.height != height) {
                if (!openSession(frame.width, frame.height)) return
                force = true
            }
            val s = session ?: return
            val pb = i420ToPixelBuffer(frame) ?: return
            val props = if (force) cfDictionary(listOf(kVTEncodeFrameOptionKey_ForceKeyFrame to kCFBooleanTrue)) else null
            VTCompressionSessionEncodeFrameWithOutputHandler(
                s,
                pb,
                CMTimeMake(frame.timestampUs, 1_000_000),
                CMTimeMake(1, fps.coerceAtLeast(1)),
                props,
                null,
            ) { status, _, sampleBuffer ->
                if (status == NO_ERR && sampleBuffer != null) emit(sampleBuffer, onChunk)
            }
            if (props != null) CFRelease(props)
            CVPixelBufferRelease(pb)
        } finally {
            lock.unlock()
        }
    }

    private fun emit(sb: CMSampleBufferRef, onChunk: (EncodedChunk) -> Unit) {
        val desc = CMSampleBufferGetFormatDescription(sb) ?: return
        val (params, lengthSize) = h264ParameterSets(desc)
        val data = sampleBytes(sb) ?: return
        val nals = H264Bitstream.splitAvcc(data, lengthSize)
        if (nals.isEmpty()) return
        val keyframe = nals.any { H264Bitstream.nalType(it) == H264Bitstream.NAL_IDR }
        val unit = if (keyframe) params + nals else nals
        onChunk(EncodedChunk(Layer.BASE, H264Bitstream.toAnnexB(unit), keyframe))
    }

    override fun close() {
        lock.lock()
        try {
            closed = true
            closeSession()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        fun open(width: Int, height: Int, fps: Int, bitrate: Int): VtH264Encoder? {
            val enc = VtH264Encoder(fps, bitrate)
            // Prove the hardware will open a session at all before promising video.
            val ok = enc.lock.let { it.lock(); try { enc.openSession(width, height) } finally { it.unlock() } }
            if (!ok) return null
            return enc
        }
    }
}

/** Where a decoded picture goes: set before each synchronous decode call. */
private class DecodeTarget {
    var onFrame: ((VideoFrame) -> Unit)? = null
}

private fun decoded(
    outputRefCon: COpaquePointer?,
    @Suppress("UNUSED_PARAMETER") sourceFrameRefCon: COpaquePointer?,
    status: Int,
    @Suppress("UNUSED_PARAMETER") infoFlags: VTDecodeInfoFlags,
    image: CVImageBufferRef?,
    @Suppress("UNUSED_PARAMETER") pts: CValue<CMTime>,
    @Suppress("UNUSED_PARAMETER") duration: CValue<CMTime>,
) {
    if (status != NO_ERR || image == null) return
    val target = outputRefCon?.asStableRef<DecodeTarget>()?.get() ?: return
    val frame = pixelBufferToI420(image) ?: return
    target.onFrame?.invoke(frame)
}

private val decodedCallback = staticCFunction(::decoded)

/**
 * The VTDecompressionSession half both decoders share: a format description in,
 * NV12 pictures out, converted to I420. Decoding is synchronous (no
 * asynchronous-decompression flag), so pictures come out in order, before
 * [decodeSample] returns.
 */
private abstract class VtDecoder : VideoDecoder {
    protected val lock = NSLock()
    private val target = DecodeTarget()
    private val targetRef = StableRef.create(target)
    private var session: VTDecompressionSessionRef? = null
    private var format: CMFormatDescriptionRef? = null
    private var closed = false

    protected val ready: Boolean get() = session != null

    /** Replaces the session for a new format description (owned by this decoder from now on). */
    protected fun reopen(desc: CMFormatDescriptionRef): Boolean {
        closeSession()
        val attrs = pixelBufferAttributes(PIXEL_NV12_VIDEO)
        val s = memScoped {
            val record = alloc<VTDecompressionOutputCallbackRecord>()
            record.decompressionOutputCallback = decodedCallback
            record.decompressionOutputRefCon = targetRef.asCPointer()
            val out = alloc<VTDecompressionSessionRefVar>()
            val status = VTDecompressionSessionCreate(kCFAllocatorDefault, desc, null, attrs, record.ptr, out.ptr)
            if (status != NO_ERR) null else out.value
        }
        if (attrs != null) CFRelease(attrs)
        if (s == null) {
            CFRelease(desc)
            return false
        }
        session = s
        format = desc
        return true
    }

    protected fun decodeSample(sample: ByteArray, onFrame: (VideoFrame) -> Unit) {
        val s = session ?: return
        val fmt = format ?: return
        if (sample.isEmpty()) return
        memScoped {
            val blockOut = alloc<CMBlockBufferRefVar>()
            // 1u: kCMBlockBufferAssureMemoryNowFlag — allocate the block now.
            if (CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, null, sample.size.toULong(), kCFAllocatorDefault, null, 0uL, sample.size.toULong(), 1u, blockOut.ptr) != NO_ERR) return@memScoped
            val block = blockOut.value ?: return@memScoped
            try {
                val copied = sample.usePinned { CMBlockBufferReplaceDataBytes(it.addressOf(0), block, 0uL, sample.size.toULong()) }
                if (copied != NO_ERR) return@memScoped
                val sizes = alloc<ULongVar>().apply { value = sample.size.toULong() }
                val sampleOut = alloc<CMSampleBufferRefVar>()
                if (CMSampleBufferCreateReady(kCFAllocatorDefault, block, fmt, 1, 0, null, 1, sizes.ptr, sampleOut.ptr) != NO_ERR) return@memScoped
                val sb = sampleOut.value ?: return@memScoped
                target.onFrame = onFrame
                VTDecompressionSessionDecodeFrame(s, sb, 0u, null, null)
                target.onFrame = null
                CFRelease(sb)
            } finally {
                CFRelease(block)
            }
        }
    }

    private fun closeSession() {
        session?.let {
            VTDecompressionSessionInvalidate(it)
            CFRelease(it)
        }
        session = null
        format?.let { CFRelease(it) }
        format = null
    }

    protected abstract fun decodeLocked(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit)

    override fun decode(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit) {
        lock.lock()
        try {
            if (!closed) decodeLocked(layer, bytes, onFrame)
        } finally {
            lock.unlock()
        }
    }

    override fun close() {
        lock.lock()
        try {
            if (closed) return
            closed = true
            closeSession()
            targetRef.dispose()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * H.264 Annex B access units in. A keyframe's SPS/PPS (re)builds the session
 * when they change; until the first one arrives, frames are dropped.
 */
private class VtH264Decoder : VtDecoder() {
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    override fun decodeLocked(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit) {
        val nals = H264Bitstream.splitAnnexB(bytes)
        var newSps: ByteArray? = null
        var newPps: ByteArray? = null
        val picture = ArrayList<ByteArray>()
        for (n in nals) when (H264Bitstream.nalType(n)) {
            H264Bitstream.NAL_SPS -> newSps = n
            H264Bitstream.NAL_PPS -> newPps = n
            H264Bitstream.NAL_AUD -> {}
            else -> picture += n
        }
        if (newSps != null && newPps != null &&
            (!ready || !newSps.contentEquals(sps) || !newPps.contentEquals(pps))
        ) {
            val desc = formatFrom(newSps, newPps)
            if (desc != null && reopen(desc)) {
                sps = newSps
                pps = newPps
            }
        }
        if (!ready || picture.isEmpty()) return
        decodeSample(H264Bitstream.toAvcc(picture), onFrame)
    }

    private fun formatFrom(sps: ByteArray, pps: ByteArray): CMFormatDescriptionRef? = memScoped {
        val pointers = allocArray<CPointerVar<UByteVar>>(2)
        val sizes = allocArray<ULongVar>(2)
        val out = alloc<CMFormatDescriptionRefVar>()
        val status = sps.usePinned { s ->
            pps.usePinned { p ->
                pointers[0] = s.addressOf(0).reinterpret<UByteVar>()
                pointers[1] = p.addressOf(0).reinterpret<UByteVar>()
                sizes[0] = sps.size.toULong()
                sizes[1] = pps.size.toULong()
                CMVideoFormatDescriptionCreateFromH264ParameterSets(kCFAllocatorDefault, 2uL, pointers, sizes, 4, out.ptr)
            }
        }
        if (status != NO_ERR) null else out.value
    }
}

/**
 * AV1 low-overhead OBUs in, on devices with a hardware AV1 decoder. The session
 * is built from the sequence header (as an `av1C` sample-description atom) and
 * rebuilt when it changes. Only the base spatial and quality layer is decoded:
 * a spatial enhancement chunk alone is not a decodable sample, and this decoder
 * does not reassemble a temporal unit from several chunks.
 */
private class VtAv1Decoder : VtDecoder() {
    private var sequenceHeader: ByteArray? = null

    override fun decodeLocked(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit) {
        if (layer.spatial != 0 || layer.quality != 0) return
        val obus = Av1Bitstream.split(bytes)
        val seq = obus.firstOrNull { it.type == Av1Bitstream.OBU_SEQUENCE_HEADER }
        if (seq != null && (!ready || !seq.bytes.contentEquals(sequenceHeader))) {
            val parsed = Av1Bitstream.parseSequenceHeader(seq)
            val desc = parsed?.let { formatFrom(it, seq.bytes) }
            if (desc != null && reopen(desc)) sequenceHeader = seq.bytes
        }
        if (!ready) return
        val sample = obus.filter { it.type != Av1Bitstream.OBU_TEMPORAL_DELIMITER }
        if (sample.isEmpty()) return
        val joined = ByteArray(sample.sumOf { it.bytes.size })
        var o = 0
        for (obu in sample) { obu.bytes.copyInto(joined, o); o += obu.bytes.size }
        decodeSample(joined, onFrame)
    }

    private fun formatFrom(seq: Av1Bitstream.SequenceHeader, seqObu: ByteArray): CMFormatDescriptionRef? {
        val record = Av1Bitstream.av1C(seq, seqObu)
        val data = record.usePinned { NSData.create(bytes = it.addressOf(0), length = record.size.toULong()) }
        val cfData = CFBridgingRetain(data)
        val key = CFBridgingRetain("av1C")
        val atoms = CFDictionaryCreateMutable(kCFAllocatorDefault, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionarySetValue(atoms, key, cfData)
        val extensions = cfDictionary(listOf(kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms to atoms))
        val desc = memScoped {
            val out = alloc<CMFormatDescriptionRefVar>()
            val status = CMVideoFormatDescriptionCreate(kCFAllocatorDefault, CODEC_AV1, seq.maxWidth, seq.maxHeight, extensions, out.ptr)
            if (status != NO_ERR) null else out.value
        }
        extensions?.let { CFRelease(it) }
        atoms?.let { CFRelease(it) }
        key?.let { CFRelease(it) }
        cfData?.let { CFRelease(it) }
        return desc
    }
}
