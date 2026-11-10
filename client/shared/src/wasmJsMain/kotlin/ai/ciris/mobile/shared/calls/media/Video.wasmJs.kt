package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.Layer
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Video on the web: WebCodecs `VideoEncoder` / `VideoDecoder`.
 *
 * The encoder is AV1 (`av01.0.04M.08`, or level 4.0 above 1088x612, `L1T2`,
 * realtime) where `VideoEncoder.isConfigSupported` says so, codec `0x01`; else
 * H.264 (`avc1.42E01F`, or level 4.0 above 720p, `avc.format = annexb`,
 * `L1T2` where supported), codec `0x04`. Output goes through [Av1Packetizer] /
 * [H264Packetizer] so it matches [CallVideoCodec] whatever the browser emits.
 *
 * `isConfigSupported` is a Promise and [createVideoEncoder] is synchronous, so
 * support is probed once, up front ([prepareWebVideo], started on first use).
 * Before the probe has answered, [createVideoEncoder] returns H.264 (the codec
 * every WebCodecs browser encodes) and [createVideoDecoder] returns a decoder
 * on trust; [createWebVideoEncoder] awaits the probe and is the one to call.
 */
actual fun createVideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int): VideoEncoder? {
    if (!jsHasWebCodecsVideo()) return null
    WebVideoSupport.start()
    val choice = WebVideoSupport.result
    if (choice != null && choice.encoder == null) return null
    return WebVideoEncoder(choice?.encoder ?: EncoderChoice.H264, width, height, fps, bitrate)
}

actual fun createVideoDecoder(codecId: Int): VideoDecoder? {
    if (!jsHasWebCodecsVideo()) return null
    WebVideoSupport.start()
    val r = WebVideoSupport.result
    return when (codecId) {
        CallVideoCodec.AV1_SVC -> if (r != null && !r.av1Decode) null else WebVideoDecoder(codecId, AV1_DECODE)
        CallVideoCodec.TEMPORAL_LAYERED -> {
            val codec = if (r == null) H264_DECODE_HIGH else r.h264Decode ?: return null
            WebVideoDecoder(codecId, codec)
        }
        else -> null
    }
}

/** Probe what this browser's WebCodecs can do. Safe to call repeatedly; the first answer is kept. */
suspend fun prepareWebVideo() {
    if (!jsHasWebCodecsVideo()) return
    WebVideoSupport.start()
    WebVideoSupport.promise?.await<JsAny?>()
}

/** [createVideoEncoder], after the support probe has answered: AV1 where it really is available. */
suspend fun createWebVideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int): VideoEncoder? {
    prepareWebVideo()
    return createVideoEncoder(width, height, fps, bitrate)
}

internal enum class EncoderChoice(val codecId: Int, val codecName: String, val js: String) {
    AV1_L1T2(CallVideoCodec.AV1_SVC, "av1", "av1"),
    H264_L1T2(CallVideoCodec.TEMPORAL_LAYERED, "h264", "h264-l1t2"),
    H264(CallVideoCodec.TEMPORAL_LAYERED, "h264", "h264"),
}

private const val AV1_DECODE = "av01.0.08M.08"
private const val H264_DECODE_HIGH = "avc1.640028"

private object WebVideoSupport {
    class Result(val encoder: EncoderChoice?, val av1Decode: Boolean, val h264Decode: String?)

    var result: Result? = null
    var promise: Promise<JsAny?>? = null

    fun start() {
        if (promise != null) return
        promise = jsProbeVideo { enc, av1, h264 ->
            result = Result(EncoderChoice.entries.firstOrNull { it.js == enc }, av1, h264.ifEmpty { null })
            jsLog("video support: encoder=${enc.ifEmpty { "none" }} av1Decode=$av1 h264Decode=${h264.ifEmpty { "none" }}")
        }
    }
}

internal class WebVideoEncoder(
    internal val choice: EncoderChoice,
    private var width: Int,
    private var height: Int,
    private val fps: Int,
    private val bitrate: Int,
) : VideoEncoder {
    override val codecId: Int = choice.codecId
    override val codecName: String = choice.codecName

    private class Pending(val timestampUs: Double, val onChunk: (EncodedChunk) -> Unit)

    private val h264 = H264Packetizer()
    private val av1 = Av1Packetizer()
    private val pending = ArrayDeque<Pending>()
    private var lastCallback: ((EncodedChunk) -> Unit)? = null
    private var lastTimestamp = -1.0
    private var keyDue = true
    private var failed = false
    private var closed = false
    private var encoder: JsAny = create()

    private fun create(): JsAny = jsVideoEncoderCreate(
        choice.js, width, height, fps, bitrate,
        { ts, key, temporal, data, description -> onOutput(ts, key, temporal, data, description) },
        { msg -> jsLog("video encoder: $msg"); failed = true },
    )

    override fun encode(frame: VideoFrame, forceKeyframe: Boolean, onChunk: (EncodedChunk) -> Unit) {
        if (closed) return
        if (failed) {
            // A WebCodecs codec that errors is closed for good: start a fresh one at a keyframe.
            jsCodecClose(encoder)
            failed = false
            encoder = create()
            keyDue = true
        }
        if (frame.width != width || frame.height != height) {
            // A screen share's size changes when the shared window does.
            width = frame.width
            height = frame.height
            jsVideoEncoderConfigure(encoder, choice.js, width, height, fps, bitrate)
            keyDue = true
        }
        val key = forceKeyframe || keyDue
        if (jsEncodeQueueSize(encoder) > MAX_QUEUE) { keyDue = keyDue || key; return } // behind: drop this frame
        val ts = maxOf(frame.timestampUs.toDouble(), lastTimestamp + 1)
        if (!jsVideoEncoderEncode(encoder, frame.i420ToJs(), width, height, ts, key)) { keyDue = keyDue || key; return }
        keyDue = false
        lastTimestamp = ts
        pending.addLast(Pending(ts, onChunk))
        lastCallback = onChunk
    }

    private fun onOutput(ts: Double, key: Boolean, temporal: Int, data: JsAny, description: JsAny?) {
        if (closed) return
        val raw = data.jsToByteArray()
        val bytes = if (codecId == CallVideoCodec.AV1_SVC) {
            description?.let { av1.onDescription(it.jsToByteArray()) }
            av1.temporalUnit(raw, key)
        } else {
            description?.let { h264.onDescription(it.jsToByteArray()) }
            h264.accessUnit(raw, key)
        }
        while (pending.isNotEmpty() && pending.first().timestampUs < ts) pending.removeFirst() // dropped by the encoder
        val cb = if (pending.firstOrNull()?.timestampUs == ts) pending.removeFirst().onChunk else lastCallback ?: return
        cb(EncodedChunk(Layer(0, temporal, 0), bytes, key))
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        jsCodecClose(encoder)
    }

    private companion object {
        const val MAX_QUEUE = 2
    }
}

private class WebVideoDecoder(private val codecId: Int, private val codecString: String) : VideoDecoder {
    private val pending = ArrayDeque<Pair<Double, (VideoFrame) -> Unit>>()
    private var lastCallback: ((VideoFrame) -> Unit)? = null
    private var decoder: JsAny? = null
    private var needKey = true
    private var nextTimestamp = 0.0
    private var closed = false

    override fun decode(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit) {
        if (closed) return
        val key = if (codecId == CallVideoCodec.AV1_SVC) Obu.isKeyframe(bytes) else AnnexB.isKeyframe(bytes)
        if (needKey && !key) return // a decoder starts at a keyframe; a late joiner waits for the next one
        val dec = decoder ?: jsVideoDecoderCreate(
            jsMediaKit(), codecString,
            { ts, w, h, data -> onOutput(ts, w, h, data) },
            { msg -> onError(msg) },
        ).also { decoder = it }
        nextTimestamp += 1.0
        if (!jsVideoDecoderDecode(dec, bytes.toJsBytes(), nextTimestamp, key)) { needKey = true; return }
        needKey = false
        pending.addLast(nextTimestamp to onFrame)
        lastCallback = onFrame
    }

    private fun onOutput(ts: Double, width: Int, height: Int, data: JsAny) {
        if (closed) return
        while (pending.isNotEmpty() && pending.first().first < ts) pending.removeFirst()
        val cb = if (pending.firstOrNull()?.first == ts) pending.removeFirst().second else lastCallback ?: return
        cb(i420FromJs(width, height, ts.toLong(), data))
    }

    private fun onError(msg: String) {
        jsLog("video decoder ($codecString): $msg")
        decoder?.let { jsCodecClose(it) }
        decoder = null
        needKey = true
        pending.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        decoder?.let { jsCodecClose(it) }
        decoder = null
    }
}

private fun jsHasWebCodecsVideo(): Boolean =
    js("typeof VideoEncoder !== 'undefined' && typeof VideoDecoder !== 'undefined' && typeof VideoFrame !== 'undefined'")

/**
 * Resolves once [done] has the answer: the encoder choice (`av1`, `h264-l1t2`,
 * `h264` or empty), whether AV1 decodes, and the H.264 decoder codec string
 * that works (or empty).
 */
private fun jsProbeVideo(done: (String, Boolean, String) -> Unit): Promise<JsAny?> = js(
    """{
    const k = globalThis.__cirisVideoConfig || (globalThis.__cirisVideoConfig = (choice, w, h, fps, bitrate) => {
        const big = w * h > 665856;
        const c = { width: w, height: h, bitrate: bitrate, framerate: fps, latencyMode: 'realtime' };
        if (choice === 'av1') { c.codec = big ? 'av01.0.08M.08' : 'av01.0.04M.08'; c.scalabilityMode = 'L1T2'; }
        else { c.codec = w * h > 921600 ? 'avc1.42E028' : 'avc1.42E01F'; c.avc = { format: 'annexb' }; if (choice === 'h264-l1t2') c.scalabilityMode = 'L1T2'; }
        return c;
    });
    const ok = (p) => p.then((r) => !!(r && r.supported)).catch(() => false);
    const enc = async () => {
        for (const choice of ['av1', 'h264-l1t2', 'h264']) {
            if (await ok(VideoEncoder.isConfigSupported(k(choice, 640, 360, 30, 800000)))) return choice;
        }
        return '';
    };
    const h264 = async () => {
        for (const codec of ['avc1.640028', 'avc1.42E01F']) {
            if (await ok(VideoDecoder.isConfigSupported({ codec: codec }))) return codec;
        }
        return '';
    };
    return Promise.all([enc(), ok(VideoDecoder.isConfigSupported({ codec: 'av01.0.08M.08' })), h264()])
        .then((r) => { done(r[0], r[1], r[2]); return null; })
        .catch(() => { done('h264', true, 'avc1.640028'); return null; });
}"""
)

private fun jsVideoEncoderCreate(
    choice: String,
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    onChunk: (Double, Boolean, Int, JsAny, JsAny?) -> Unit,
    onError: (String) -> Unit,
): JsAny = js(
    """{
    const e = new VideoEncoder({
        output: (c, meta) => {
            const u = new Uint8Array(c.byteLength);
            c.copyTo(u);
            const tl = (meta && meta.svc && typeof meta.svc.temporalLayerId === 'number') ? meta.svc.temporalLayerId : 0;
            const d = (meta && meta.decoderConfig && meta.decoderConfig.description) ? meta.decoderConfig.description : null;
            onChunk(c.timestamp, c.type === 'key', tl, u, d);
        },
        error: (x) => onError(String(x)),
    });
    e.configure(globalThis.__cirisVideoConfig(choice, width, height, fps, bitrate));
    return e;
}"""
)

private fun jsVideoEncoderConfigure(e: JsAny, choice: String, width: Int, height: Int, fps: Int, bitrate: Int): Unit = js(
    "{ if (e.state !== 'closed') e.configure(globalThis.__cirisVideoConfig(choice, width, height, fps, bitrate)); }"
)

private fun jsEncodeQueueSize(e: JsAny): Int = js("e.encodeQueueSize")

private fun jsVideoEncoderEncode(e: JsAny, i420: JsAny, width: Int, height: Int, ts: Double, key: Boolean): Boolean = js(
    """{
    if (e.state !== 'configured') return false;
    const f = new VideoFrame(i420, { format: 'I420', codedWidth: width, codedHeight: height, timestamp: ts });
    try { e.encode(f, { keyFrame: key }); return true; } catch (x) { return false; } finally { f.close(); }
}"""
)

private fun jsVideoDecoderCreate(
    kit: JsAny,
    codec: String,
    onFrame: (Double, Int, Int, JsAny) -> Unit,
    onError: (String) -> Unit,
): JsAny = js(
    """{
    let chain = Promise.resolve();
    const d = new VideoDecoder({
        // copyTo is asynchronous: chain the conversions so frames come out in the order they went in.
        output: (f) => {
            chain = chain.then(() => kit.frameToI420(f, 4096, 4096))
                .then((o) => onFrame(f.timestamp, o.w, o.h, o.data))
                .catch((x) => onError('frame: ' + x))
                .finally(() => f.close());
        },
        error: (x) => onError(String(x)),
    });
    d.configure({ codec: codec, optimizeForLatency: true });
    return d;
}"""
)

private fun jsVideoDecoderDecode(d: JsAny, data: JsAny, ts: Double, key: Boolean): Boolean = js(
    """{
    if (d.state !== 'configured') return false;
    try { d.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: ts, data: data })); return true; } catch (x) { return false; }
}"""
)

/**
 * Frame conversion shared by the decoder and the capture sources, installed
 * once on `globalThis`: any WebCodecs/canvas image to packed I420 (BT.601
 * limited range, the inverse of [VideoFrame.toArgb]), scaled to fit.
 */
internal fun jsMediaKit(): JsAny = js(
    """{
    if (globalThis.__cirisCallMedia) return globalThis.__cirisCallMedia;
    const k = {};
    k.fit = (w, h, maxW, maxH) => {
        const s = Math.min(1, maxW / w, maxH / h);
        return [Math.max(2, Math.floor(w * s) & ~1), Math.max(2, Math.floor(h * s) & ~1)];
    };
    k.rgbaToI420 = (p, w, h) => {
        const out = new Uint8Array(w * h * 3 / 2);
        const uo = w * h, vo = uo + (w * h >> 2), cw = w >> 1;
        for (let y = 0; y < h; y++) {
            for (let x = 0; x < w; x++) {
                const i = (y * w + x) * 4;
                out[y * w + x] = ((66 * p[i] + 129 * p[i + 1] + 25 * p[i + 2] + 128) >> 8) + 16;
            }
        }
        for (let y = 0; y < h; y += 2) {
            for (let x = 0; x < w; x += 2) {
                const a = (y * w + x) * 4, b = a + 4, c = a + w * 4, d = c + 4;
                const r = (p[a] + p[b] + p[c] + p[d]) >> 2;
                const g = (p[a + 1] + p[b + 1] + p[c + 1] + p[d + 1]) >> 2;
                const bl = (p[a + 2] + p[b + 2] + p[c + 2] + p[d + 2]) >> 2;
                const ci = (y >> 1) * cw + (x >> 1);
                out[uo + ci] = ((-38 * r - 74 * g + 112 * bl + 128) >> 8) + 128;
                out[vo + ci] = ((112 * r - 94 * g - 18 * bl + 128) >> 8) + 128;
            }
        }
        return out;
    };
    k.drawToI420 = (src, sw, sh, maxW, maxH) => {
        const [w, h] = k.fit(sw, sh, maxW, maxH);
        if (!k.canvas) {
            k.canvas = (typeof OffscreenCanvas !== 'undefined') ? new OffscreenCanvas(w, h) : document.createElement('canvas');
        }
        if (k.canvas.width !== w) k.canvas.width = w;
        if (k.canvas.height !== h) k.canvas.height = h;
        const ctx = k.canvas.getContext('2d', { willReadFrequently: true });
        ctx.drawImage(src, 0, 0, w, h);
        return { w: w, h: h, data: k.rgbaToI420(ctx.getImageData(0, 0, w, h).data, w, h) };
    };
    k.frameToI420 = async (f, maxW, maxH) => {
        const vr = f.visibleRect;
        const [w, h] = k.fit(f.displayWidth, f.displayHeight, maxW, maxH);
        const native = vr && w === (vr.width & ~1) && h === (vr.height & ~1) && vr.x % 2 === 0 && vr.y % 2 === 0;
        const rect = native ? { x: vr.x, y: vr.y, width: w, height: h } : null;
        if (native && f.format === 'I420') {
            const out = new Uint8Array(w * h * 3 / 2);
            await f.copyTo(out, { rect: rect, layout: [
                { offset: 0, stride: w }, { offset: w * h, stride: w >> 1 }, { offset: w * h * 5 / 4, stride: w >> 1 }] });
            return { w: w, h: h, data: out };
        }
        if (native && f.format === 'NV12') {
            const tmp = new Uint8Array(w * h * 3 / 2);
            await f.copyTo(tmp, { rect: rect, layout: [{ offset: 0, stride: w }, { offset: w * h, stride: w }] });
            const out = new Uint8Array(w * h * 3 / 2);
            out.set(tmp.subarray(0, w * h), 0);
            const n = w * h >> 2, uo = w * h, vo = uo + n;
            for (let i = 0; i < n; i++) { out[uo + i] = tmp[uo + 2 * i]; out[vo + i] = tmp[uo + 2 * i + 1]; }
            return { w: w, h: h, data: out };
        }
        return k.drawToI420(f, f.displayWidth, f.displayHeight, maxW, maxH);
    };
    globalThis.__cirisCallMedia = k;
    return k;
}"""
)
