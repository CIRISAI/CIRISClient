package ai.ciris.mobile.shared.calls.media

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusSignal

/**
 * Opus on the JVM through Concentus, a pure-Java port of libopus: no native
 * library and no Android API floor (MediaCodec's Opus encoder needs API 29; this
 * app supports 24). Configured to the agreed call audio (CSD-134): 48 kHz mono,
 * 20 ms, VOIP, VBR at about 28 kbit/s, and in frames v1 no DTX and no in-band FEC.
 */
actual fun createOpusEncoder(): OpusEncoder = ConcentusEncoder()

actual fun createOpusDecoder(): OpusDecoder = ConcentusDecoder()

actual val opusAvailable: Boolean = true

private class ConcentusEncoder : OpusEncoder {
    private val enc = io.github.jaredmdobson.concentus.OpusEncoder(
        CallAudioFormat.SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP,
    ).apply {
        setBitrate(CallAudioFormat.BITRATE)
        setUseVBR(true)
        setUseDTX(false)
        setUseInbandFEC(false)
        setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        setComplexity(5)
    }
    private val out = ByteArray(CallAudioFormat.MAX_PACKET_BYTES)

    override fun encode(pcm: ShortArray): ByteArray {
        val n = enc.encode(pcm, 0, CallAudioFormat.FRAME_SAMPLES, out, 0, out.size)
        return out.copyOf(n)
    }

    override fun close() = Unit
}

private class ConcentusDecoder : OpusDecoder {
    private val dec = io.github.jaredmdobson.concentus.OpusDecoder(CallAudioFormat.SAMPLE_RATE, 1)

    override fun decode(packet: ByteArray?): ShortArray {
        val pcm = ShortArray(CallAudioFormat.FRAME_SAMPLES)
        // A null packet asks the decoder for packet-loss concealment.
        val n = dec.decode(packet, 0, packet?.size ?: 0, pcm, 0, CallAudioFormat.FRAME_SAMPLES, false)
        return if (n == pcm.size) pcm else pcm.copyOf(CallAudioFormat.FRAME_SAMPLES)
    }

    override fun close() = Unit
}
