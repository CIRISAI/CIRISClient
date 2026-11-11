package ai.ciris.mobile.shared.calls.media

/**
 * Opus on the web: WebCodecs `AudioEncoder` / `AudioDecoder` (codec `opus`,
 * 48 kHz mono, 20 ms frames, ~28 kbit/s, no DTX, no in-band FEC).
 *
 * WebCodecs has no packet-loss concealment entry point, so a lost packet
 * (`decode(null)`) is concealed with 20 ms of SILENCE, queued in order behind
 * any packet still being decoded. That is the honest floor, not Opus PLC.
 */
actual fun createOpusEncoder(): OpusEncoder = WebOpusEncoder()

actual fun createOpusDecoder(): OpusDecoder = WebOpusDecoder()

/** True when this browser has WebCodecs audio (checked at runtime, once). */
actual val opusAvailable: Boolean = jsHasWebCodecsAudio()

private class WebOpusEncoder : OpusEncoder {
    private class Pending(val timestampUs: Double, val onPacket: (ByteArray) -> Unit)

    private val pending = ArrayDeque<Pending>()
    private var lastCallback: ((ByteArray) -> Unit)? = null
    private var nextTimestamp = 0.0
    private var failed = false
    private var closed = false
    private val encoder: JsAny = jsOpusEncoderCreate(
        CallAudioFormat.SAMPLE_RATE, CallAudioFormat.BITRATE, CallAudioFormat.FRAME_MS * 1000,
        { ts, data -> onOutput(ts, data.jsToByteArray()) },
        { msg -> jsLog("opus encoder: $msg"); failed = true },
    )

    override fun encode(pcm: ShortArray, onPacket: (ByteArray) -> Unit) {
        if (closed || failed) return
        val ts = nextTimestamp
        nextTimestamp += CallAudioFormat.FRAME_MS * 1000.0
        pending.addLast(Pending(ts, onPacket))
        lastCallback = onPacket
        jsOpusEncoderEncode(encoder, pcm.toJsInt16(), ts)
    }

    /** Matches each packet to its input by timestamp, so outputs keep input order and callbacks. */
    private fun onOutput(timestampUs: Double, packet: ByteArray) {
        if (closed) return
        // Inputs the encoder folded or dropped are older than this packet: forget them.
        while (pending.size > 1 && pending.first().timestampUs < timestampUs - FRAME_US / 2) pending.removeFirst()
        val cb = pending.removeFirstOrNull()?.onPacket ?: lastCallback ?: return
        cb(packet)
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        jsCodecClose(encoder)
    }
}

private class WebOpusDecoder : OpusDecoder {
    /** One output owed, in input order. [timestampUs] is NaN for a concealment slot (already filled). */
    private class Slot(val timestampUs: Double, val onPcm: (ShortArray) -> Unit, var pcm: ShortArray?)

    private val slots = ArrayDeque<Slot>()
    private var decoder: JsAny? = null
    private var nextTimestamp = 0.0
    private var closed = false

    override fun decode(packet: ByteArray?, onPcm: (ShortArray) -> Unit) {
        if (closed) return
        if (packet == null) {
            slots.addLast(Slot(Double.NaN, onPcm, silence()))
            flush()
            return
        }
        val dec = decoder ?: jsOpusDecoderCreate(
            CallAudioFormat.SAMPLE_RATE,
            { ts, data -> onOutput(ts, data.jsToShortArray()) },
            { msg -> onError(msg) },
        ).also { decoder = it }
        val ts = nextTimestamp
        nextTimestamp += FRAME_US
        val accepted = jsOpusDecoderDecode(dec, packet.toJsBytes(), ts)
        slots.addLast(Slot(ts, onPcm, if (accepted) null else silence()))
        if (!accepted) flush()
        // Only a decoder that has stopped answering altogether gets here (a burst is a few dozen).
        while (slots.size > MAX_IN_FLIGHT) { slots.first().pcm = slots.first().pcm ?: silence(); flush() }
    }

    /**
     * Matched by timestamp, not by position: an output for a later packet proves
     * the decoder produced nothing for the earlier ones still owed, and those
     * become silence rather than holding the queue.
     */
    private fun onOutput(timestampUs: Double, raw: ShortArray) {
        if (closed) return
        val pcm = if (raw.size == CallAudioFormat.FRAME_SAMPLES) raw else raw.copyOf(CallAudioFormat.FRAME_SAMPLES)
        for (slot in slots) {
            if (slot.pcm != null) continue
            if (slot.timestampUs < timestampUs) slot.pcm = silence()
            else { if (slot.timestampUs == timestampUs) slot.pcm = pcm; break }
        }
        flush()
    }

    private fun silence() = ShortArray(CallAudioFormat.FRAME_SAMPLES)

    /** A WebCodecs decoder that errors is closed for good: what it owed becomes silence, and the next packet makes a new one. */
    private fun onError(msg: String) {
        jsLog("opus decoder: $msg")
        decoder?.let { jsCodecClose(it) }
        decoder = null
        slots.forEach { if (it.pcm == null) it.pcm = silence() }
        flush()
    }

    private fun flush() {
        while (true) {
            val head = slots.firstOrNull() ?: return
            val pcm = head.pcm ?: return
            slots.removeFirst()
            head.onPcm(pcm)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        slots.clear()
        decoder?.let { jsCodecClose(it) }
        decoder = null
    }

    private companion object {
        const val MAX_IN_FLIGHT = 250 // 5 s of audio
    }
}

private const val FRAME_US = CallAudioFormat.FRAME_MS * 1000.0

private fun jsHasWebCodecsAudio(): Boolean =
    js("typeof AudioEncoder !== 'undefined' && typeof AudioDecoder !== 'undefined' && typeof AudioData !== 'undefined'")

internal fun jsCodecClose(codec: JsAny): Unit = js("{ try { if (codec.state !== 'closed') codec.close(); } catch (e) {} }")

private fun jsOpusEncoderCreate(
    sampleRate: Int,
    bitrate: Int,
    frameUs: Int,
    onChunk: (Double, JsAny) -> Unit,
    onError: (String) -> Unit,
): JsAny = js(
    """{
    const e = new AudioEncoder({
        output: (c) => { const u = new Uint8Array(c.byteLength); c.copyTo(u); onChunk(c.timestamp, u); },
        error: (x) => onError(String(x)),
    });
    e.configure({
        codec: 'opus', sampleRate: sampleRate, numberOfChannels: 1, bitrate: bitrate,
        opus: { frameDuration: frameUs, format: 'opus', usedtx: false, useinbandfec: false, packetlossperc: 0 },
    });
    return e;
}"""
)

private fun jsOpusEncoderEncode(e: JsAny, pcm: JsAny, ts: Double): Unit = js(
    """{
    if (e.state !== 'configured') return;
    const a = new AudioData({ format: 's16', sampleRate: 48000, numberOfFrames: pcm.length, numberOfChannels: 1, timestamp: ts, data: pcm });
    try { e.encode(a); } finally { a.close(); }
}"""
)

private fun jsOpusDecoderCreate(sampleRate: Int, onPcm: (Double, JsAny) -> Unit, onError: (String) -> Unit): JsAny = js(
    """{
    const d = new AudioDecoder({
        output: (a) => {
            try {
                const n = a.numberOfFrames;
                const f = new Float32Array(n);
                a.copyTo(f, { planeIndex: 0, format: 'f32-planar' });
                const s = new Int16Array(n);
                for (let i = 0; i < n; i++) { let v = f[i]; v = v < -1 ? -1 : (v > 1 ? 1 : v); s[i] = v < 0 ? v * 32768 : v * 32767; }
                onPcm(a.timestamp, s);
            } catch (x) { onError(String(x)); } finally { a.close(); }
        },
        error: (x) => onError(String(x)),
    });
    d.configure({ codec: 'opus', sampleRate: sampleRate, numberOfChannels: 1 });
    return d;
}"""
)

private fun jsOpusDecoderDecode(d: JsAny, packet: JsAny, ts: Double): Boolean = js(
    """{
    if (d.state !== 'configured') return false;
    try { d.decode(new EncodedAudioChunk({ type: 'key', timestamp: ts, data: packet })); return true; } catch (x) { return false; }
}"""
)
