@file:OptIn(ExperimentalForeignApi::class)

package ai.ciris.mobile.shared.calls.media

import kotlinx.cinterop.*
import platform.AudioToolbox.*
import platform.CoreAudioTypes.*
import platform.Foundation.NSLock

/**
 * iOS Opus through AudioToolbox's `AudioConverter` (kAudioFormatOpus, iOS 11+):
 * 48 kHz mono Int16 in, one Opus packet per 960-sample frame out, and back.
 *
 * Both directions are synchronous: [OpusEncoder.encode] and [OpusDecoder.decode]
 * call their callback before they return, which keeps outputs in input order.
 *
 * Not controllable through the converter: the Opus application (VOIP) and
 * complexity. The bitrate is set (`kAudioConverterEncodeBitRate`); VBR is the
 * converter's default for Opus.
 */
actual fun createOpusEncoder(): OpusEncoder =
    AudioToolboxOpusEncoder.open() ?: throw UnsupportedOperationException("AudioToolbox has no Opus encoder here")

actual fun createOpusDecoder(): OpusDecoder =
    AudioToolboxOpusDecoder.open() ?: throw UnsupportedOperationException("AudioToolbox has no Opus decoder here")

/** True only when this device's AudioToolbox actually builds both converters. */
actual val opusAvailable: Boolean get() = OpusProbe.available

private object OpusProbe {
    val available: Boolean by lazy {
        val enc = AudioToolboxOpusEncoder.open()
        val dec = AudioToolboxOpusDecoder.open()
        val ok = enc != null && dec != null
        enc?.close()
        dec?.close()
        ok
    }
}

// Four-character codes, as literals so their Kotlin type is exactly UInt.
private const val FORMAT_OPUS = 0x6F707573u // 'opus' kAudioFormatOpus
private const val FORMAT_LPCM = 0x6C70636Du // 'lpcm' kAudioFormatLinearPCM
private const val FLAGS_S16_PACKED = 0x0Cu // kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked
private const val PROP_ENCODE_BITRATE = 0x62726174u // 'brat' kAudioConverterEncodeBitRate

/** Returned by an input proc that has handed over everything it has for this call. */
private const val NO_MORE_INPUT: Int = 0x6E6F6D6F // 'nomo', any non-zero status

private fun AudioStreamBasicDescription.pcm16Mono() {
    mSampleRate = CallAudioFormat.SAMPLE_RATE.toDouble()
    mFormatID = FORMAT_LPCM
    mFormatFlags = FLAGS_S16_PACKED
    mBytesPerPacket = 2u
    mFramesPerPacket = 1u
    mBytesPerFrame = 2u
    mChannelsPerFrame = 1u
    mBitsPerChannel = 16u
    mReserved = 0u
}

private fun AudioStreamBasicDescription.opusMono(frameSamples: Int) {
    mSampleRate = CallAudioFormat.SAMPLE_RATE.toDouble()
    mFormatID = FORMAT_OPUS
    mFormatFlags = 0u
    mBytesPerPacket = 0u
    mFramesPerPacket = frameSamples.toUInt()
    mBytesPerFrame = 0u
    mChannelsPerFrame = 1u
    mBitsPerChannel = 0u
    mReserved = 0u
}

private fun newConverter(from: AudioStreamBasicDescription.() -> Unit, to: AudioStreamBasicDescription.() -> Unit): AudioConverterRef? =
    memScoped {
        val src = alloc<AudioStreamBasicDescription>().apply(from)
        val dst = alloc<AudioStreamBasicDescription>().apply(to)
        val out = alloc<AudioConverterRefVar>()
        val status = AudioConverterNew(src.ptr, dst.ptr, out.ptr)
        if (status != 0) null else out.value
    }

/**
 * What the input proc hands the converter on this call: one PCM frame (encoder)
 * or one packet (decoder). Lives in native memory owned by the codec, so the
 * pointer stays valid for as long as the converter may read it.
 */
private class ConverterInput(capacityBytes: Int) {
    val data = nativeHeap.allocArray<ByteVar>(capacityBytes)
    val packetDesc = nativeHeap.alloc<AudioStreamPacketDescription>()
    var bytes = 0
    var packets = 0
    /** True for compressed input, which must come with a packet description. */
    var described = false
    var pending = false

    fun free() {
        nativeHeap.free(data)
        nativeHeap.free(packetDesc)
    }
}

private fun supplyInput(
    @Suppress("UNUSED_PARAMETER") converter: AudioConverterRef?,
    ioNumberDataPackets: CPointer<UIntVar>?,
    ioData: CPointer<AudioBufferList>?,
    outDataPacketDescription: CPointer<CPointerVar<AudioStreamPacketDescription>>?,
    userData: COpaquePointer?,
): Int {
    val input = userData?.asStableRef<ConverterInput>()?.get()
    if (input == null || !input.pending || ioNumberDataPackets == null || ioData == null) {
        ioNumberDataPackets?.pointed?.value = 0u
        return NO_MORE_INPUT
    }
    input.pending = false
    val buffers = ioData.pointed
    buffers.mNumberBuffers = 1u
    val buf = buffers.mBuffers[0]
    buf.mNumberChannels = 1u
    buf.mDataByteSize = input.bytes.toUInt()
    buf.mData = input.data
    ioNumberDataPackets.pointed.value = input.packets.toUInt()
    if (input.described) {
        input.packetDesc.mStartOffset = 0
        input.packetDesc.mVariableFramesInPacket = 0u
        input.packetDesc.mDataByteSize = input.bytes.toUInt()
        outDataPacketDescription?.pointed?.value = input.packetDesc.ptr
    }
    return 0
}

private val supplyInputProc = staticCFunction(::supplyInput)

/**
 * [frameSamples] is one packet's worth of sound, fixed for the converter's life:
 * 960 (20 ms) in a call today. The 40 and 60 ms sizes exist for
 * `OpusIosTest`, which asks whether AudioToolbox makes them at all.
 */
internal class AudioToolboxOpusEncoder private constructor(
    private var converter: AudioConverterRef?,
    private val frameSamples: Int,
) : OpusEncoder {
    private val lock = NSLock()
    private val input = ConverterInput(frameSamples * 2)
    private val inputRef = StableRef.create(input)
    /** Room for one packet: the 20 ms allowance, scaled by the packet time. */
    private val outCapacity = CallAudioFormat.MAX_PACKET_BYTES * (frameSamples / CallAudioFormat.FRAME_SAMPLES)
    private val out = nativeHeap.allocArray<ByteVar>(outCapacity)
    private val outDesc = nativeHeap.alloc<AudioStreamPacketDescription>()
    private val outList = nativeHeap.alloc<AudioBufferList>()
    private val outPackets = nativeHeap.alloc<UIntVar>()

    override fun encode(pcm: ShortArray, onPacket: (ByteArray) -> Unit) {
        require(pcm.size == frameSamples) { "one packet here is $frameSamples samples, not ${pcm.size}" }
        var packet: ByteArray? = null
        lock.lock()
        try {
            val conv = converter ?: return
            pcm.usePinned { platform.posix.memcpy(input.data, it.addressOf(0), (pcm.size * 2).toULong()) }
            input.bytes = pcm.size * 2
            input.packets = pcm.size
            input.described = false
            input.pending = true
            outList.mNumberBuffers = 1u
            val buf = outList.mBuffers[0]
            buf.mNumberChannels = 1u
            buf.mDataByteSize = outCapacity.toUInt()
            buf.mData = out
            outPackets.value = 1u
            val status = AudioConverterFillComplexBuffer(conv, supplyInputProc, inputRef.asCPointer(), outPackets.ptr, outList.ptr, outDesc.ptr)
            // NO_MORE_INPUT is the normal end of a call: the converter used the frame.
            if ((status == 0 || status == NO_MORE_INPUT) && outPackets.value > 0u) {
                val size = outDesc.mDataByteSize.toInt().takeIf { it > 0 } ?: buf.mDataByteSize.toInt()
                val offset = outDesc.mStartOffset.toInt()
                if (size > 0 && offset + size <= outCapacity) {
                    packet = (out + offset)!!.readBytes(size)
                }
            }
        } finally {
            lock.unlock()
        }
        // The converter may hold the first frame back (encoder lookahead); then nothing comes out this time.
        packet?.let(onPacket)
    }

    override fun close() {
        lock.lock()
        try {
            val conv = converter ?: return
            converter = null
            AudioConverterDispose(conv)
            inputRef.dispose()
            input.free()
            nativeHeap.free(out)
            nativeHeap.free(outDesc)
            nativeHeap.free(outList)
            nativeHeap.free(outPackets)
        } finally {
            lock.unlock()
        }
    }

    companion object {
        fun open(frameSamples: Int = CallAudioFormat.FRAME_SAMPLES): AudioToolboxOpusEncoder? {
            require(frameSamples > 0 && frameSamples % CallAudioFormat.FRAME_SAMPLES == 0) { "whole 20 ms frames, not $frameSamples samples" }
            val conv = newConverter({ pcm16Mono() }, { opusMono(frameSamples) }) ?: return null
            memScoped {
                val bitrate = alloc<UIntVar>().apply { value = CallAudioFormat.BITRATE.toUInt() }
                // Best effort: a converter that refuses keeps its own default rate.
                AudioConverterSetProperty(conv, PROP_ENCODE_BITRATE, sizeOf<UIntVar>().toUInt(), bitrate.ptr)
            }
            return AudioToolboxOpusEncoder(conv, frameSamples)
        }
    }
}

/** [frameSamples] is the length of every packet this converter is given, fixed for its life (see the encoder). */
internal class AudioToolboxOpusDecoder private constructor(
    private var converter: AudioConverterRef?,
    private val frameSamples: Int,
) : OpusDecoder {
    private val lock = NSLock()
    private val input = ConverterInput(MAX_INBOUND_PACKET)
    private val inputRef = StableRef.create(input)
    private val out = nativeHeap.allocArray<ShortVar>(frameSamples)
    private val outList = nativeHeap.alloc<AudioBufferList>()
    private val outFrames = nativeHeap.alloc<UIntVar>()

    override fun decode(packet: ByteArray?, onPcm: (ShortArray) -> Unit) {
        val pcm = ShortArray(frameSamples)
        lock.lock()
        try {
            val conv = converter
            // A lost packet, or one that cannot be a frame: AudioConverter exposes no
            // Opus PLC, so the gap is silence. Always one packet long, so timing holds.
            if (conv != null && packet != null && packet.isNotEmpty() && packet.size <= MAX_INBOUND_PACKET) {
                packet.usePinned { platform.posix.memcpy(input.data, it.addressOf(0), packet.size.toULong()) }
                input.bytes = packet.size
                input.packets = 1
                input.described = true
                input.pending = true
                outList.mNumberBuffers = 1u
                val buf = outList.mBuffers[0]
                buf.mNumberChannels = 1u
                buf.mDataByteSize = (frameSamples * 2).toUInt()
                buf.mData = out
                outFrames.value = frameSamples.toUInt()
                val status = AudioConverterFillComplexBuffer(conv, supplyInputProc, inputRef.asCPointer(), outFrames.ptr, outList.ptr, null)
                if (status == 0 || status == NO_MORE_INPUT) {
                    val n = outFrames.value.toInt().coerceIn(0, frameSamples)
                    for (i in 0 until n) pcm[i] = out[i]
                }
            }
        } finally {
            lock.unlock()
        }
        onPcm(pcm)
    }

    override fun close() {
        lock.lock()
        try {
            val conv = converter ?: return
            converter = null
            AudioConverterDispose(conv)
            inputRef.dispose()
            input.free()
            nativeHeap.free(out)
            nativeHeap.free(outList)
            nativeHeap.free(outFrames)
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** Far above any Opus packet a call carries; anything bigger is not one. */
        const val MAX_INBOUND_PACKET = 1500

        fun open(frameSamples: Int = CallAudioFormat.FRAME_SAMPLES): AudioToolboxOpusDecoder? {
            require(frameSamples > 0 && frameSamples % CallAudioFormat.FRAME_SAMPLES == 0) { "whole 20 ms frames, not $frameSamples samples" }
            val conv = newConverter({ opusMono(frameSamples) }, { pcm16Mono() }) ?: return null
            return AudioToolboxOpusDecoder(conv, frameSamples)
        }
    }
}
