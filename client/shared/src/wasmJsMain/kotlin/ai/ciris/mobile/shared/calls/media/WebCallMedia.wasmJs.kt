package ai.ciris.mobile.shared.calls.media

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * The browser's microphone, speakers, camera and screen, as [AudioSource],
 * [AudioSink] and [VideoSource].
 *
 * The sources are `suspend` because the permission prompt is part of getting
 * them: `getUserMedia` / `getDisplayMedia` resolve when the person answers and
 * throw when they refuse (the exception carries the DOM error name, e.g.
 * `NotAllowedError`). `getDisplayMedia` must also be called from a user gesture,
 * so call [webDisplaySource] straight from the click.
 *
 * Each source is one-shot: [AudioSource.stop] / [VideoSource.stop] end the
 * capture AND release the device (the camera light goes out); get a new source
 * for the next call.
 */
class WebMediaException(message: String) : Exception(message)

/** The microphone, with echo cancellation and noise suppression, as 960-sample 48 kHz mono frames. */
suspend fun webMicrophoneSource(): AudioSource = WebMicrophoneSource(acquire(jsGetMicrophone(CallAudioFormat.SAMPLE_RATE)))

/**
 * Open the microphone to ask for it, and let it go. The browser's refusal
 * (`NotAllowedError`, or `SecurityError` where the page may not ask) is the
 * person's or the site's; anything else means there is no microphone to open.
 */
suspend fun webAskMicrophone(): ai.ciris.mobile.shared.platform.MediaOpened =
    try {
        jsStopStream(acquire(jsGetMicrophone(CallAudioFormat.SAMPLE_RATE)))
        ai.ciris.mobile.shared.platform.MediaOpened.OPENED
    } catch (e: WebMediaException) {
        val name = e.message.orEmpty()
        if (name.startsWith("NotAllowedError") || name.startsWith("SecurityError")) {
            ai.ciris.mobile.shared.platform.MediaOpened.REFUSED
        } else {
            ai.ciris.mobile.shared.platform.MediaOpened.NO_DEVICE
        }
    }

/** The speakers: one scheduled queue per publisher, mixed at the destination. */
fun webSpeakerSink(): AudioSink = WebSpeakerSink()

/** The camera, at up to [maxWidth] x [maxHeight] and [fps]. */
suspend fun webCameraSource(maxWidth: Int = 640, maxHeight: Int = 480, fps: Int = 15): VideoSource {
    val stream = acquire(jsGetCamera(maxWidth, maxHeight, fps))
    return WebVideoSource(VideoKind.CAMERA, stream, fps, maxWidth, maxHeight)
}

/**
 * What the person picks to share: [VideoKind.SCREEN] for a whole monitor,
 * [VideoKind.WINDOW] for a window or a browser tab (per the track's
 * `displaySurface`; a browser that does not say counts as SCREEN).
 */
suspend fun webDisplaySource(maxWidth: Int = 1280, maxHeight: Int = 720, fps: Int = 10): VideoSource {
    val stream = acquire(jsGetDisplay(fps))
    val kind = when (jsDisplaySurface(stream)) {
        "window", "browser" -> VideoKind.WINDOW
        else -> VideoKind.SCREEN
    }
    return WebVideoSource(kind, stream, fps, maxWidth, maxHeight)
}

private suspend fun acquire(p: Promise<JsAny?>): JsAny {
    val r = p.await<JsAny?>() ?: throw WebMediaException("no media stream")
    jsErrorName(r)?.let { throw WebMediaException(it) }
    return r
}

private class WebMicrophoneSource(private val stream: JsAny) : AudioSource {
    private var handle: JsAny? = null
    private var released = false

    override fun start(onFrame: (ShortArray) -> Unit) {
        if (handle != null || released) return
        handle = jsMicrophoneStart(
            stream, CallAudioFormat.SAMPLE_RATE, CallAudioFormat.FRAME_SAMPLES,
            { pcm -> onFrame(pcm.jsToShortArray()) },
            { msg -> jsLog("microphone: $msg") },
        )
    }

    override fun stop() {
        handle?.let { jsCaptureStop(it) }
        handle = null
        released = true
        jsStopStream(stream)
    }
}

private class WebVideoSource(
    override val kind: VideoKind,
    private val stream: JsAny,
    private val fps: Int,
    private val maxWidth: Int,
    private val maxHeight: Int,
) : VideoSource {
    private var handle: JsAny? = null
    private var released = false

    override fun start(onFrame: (VideoFrame) -> Unit) {
        if (handle != null || released) return
        handle = jsVideoCaptureStart(jsMediaKit(), stream, fps, maxWidth, maxHeight) { w, h, ts, data ->
            onFrame(i420FromJs(w, h, ts.toLong(), data))
        }
    }

    override fun stop() {
        handle?.let { jsCaptureStop(it) }
        handle = null
        released = true
        jsStopStream(stream)
    }
}

private class WebSpeakerSink : AudioSink {
    private val state: JsAny = jsSinkCreate()

    override fun play(publisher: String, pcm: ShortArray) = jsSinkPlay(state, publisher, pcm.toJsInt16(), CallAudioFormat.SAMPLE_RATE)

    override fun remove(publisher: String) = jsSinkRemove(state, publisher)

    override fun stop() = jsSinkStop(state)
}

/** A rejected getUserMedia resolves to `{ error: name }` here, so Kotlin sees the reason, not a bare JsException. */
private fun jsErrorName(r: JsAny): String? = js("(r && typeof r.error === 'string') ? r.error : null")

private fun jsGetMicrophone(sampleRate: Int): Promise<JsAny?> = js(
    """(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
    ? navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, sampleRate: sampleRate, channelCount: 1 }, video: false })
        .catch((e) => ({ error: (e && e.name) ? e.name + ': ' + e.message : String(e) }))
    : Promise.resolve({ error: 'NotSupportedError: no mediaDevices (needs a secure context)' })"""
)

private fun jsGetCamera(width: Int, height: Int, fps: Int): Promise<JsAny?> = js(
    """(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
    ? navigator.mediaDevices.getUserMedia({ audio: false, video: { width: { ideal: width }, height: { ideal: height }, frameRate: { ideal: fps } } })
        .catch((e) => ({ error: (e && e.name) ? e.name + ': ' + e.message : String(e) }))
    : Promise.resolve({ error: 'NotSupportedError: no mediaDevices (needs a secure context)' })"""
)

private fun jsGetDisplay(fps: Int): Promise<JsAny?> = js(
    """(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia)
    ? navigator.mediaDevices.getDisplayMedia({ audio: false, video: { frameRate: { ideal: fps } } })
        .catch((e) => ({ error: (e && e.name) ? e.name + ': ' + e.message : String(e) }))
    : Promise.resolve({ error: 'NotSupportedError: no getDisplayMedia in this browser' })"""
)

private fun jsDisplaySurface(stream: JsAny): String = js(
    "{ const t = stream.getVideoTracks()[0]; const s = t && t.getSettings ? t.getSettings() : {}; return s.displaySurface || ''; }"
)

private fun jsStopStream(stream: JsAny): Unit = js("{ try { stream.getTracks().forEach((t) => t.stop()); } catch (e) {} }")

private fun jsCaptureStop(handle: JsAny): Unit = js("{ try { handle.stop(); } catch (e) {} }")

/**
 * Microphone → AudioContext at 48 kHz → an AudioWorklet (ScriptProcessor where
 * worklets are unavailable) that cuts exactly [frame]-sample Int16 frames. The
 * capture node feeds a muted gain so the graph is pulled without echoing.
 */
private fun jsMicrophoneStart(
    stream: JsAny,
    sampleRate: Int,
    frame: Int,
    onFrame: (JsAny) -> Unit,
    onError: (String) -> Unit,
): JsAny = js(
    """{
    const st = { stopped: false, ctx: null, node: null, src: null };
    const ctx = new AudioContext({ sampleRate: sampleRate, latencyHint: 'interactive' });
    st.ctx = ctx;
    const src = ctx.createMediaStreamSource(stream);
    st.src = src;
    const mute = ctx.createGain();
    mute.gain.value = 0;
    mute.connect(ctx.destination);
    const viaScript = () => {
        const sp = ctx.createScriptProcessor(1024, 1, 1);
        let b = new Int16Array(frame), n = 0;
        sp.onaudioprocess = (ev) => {
            const ch = ev.inputBuffer.getChannelData(0);
            for (let i = 0; i < ch.length; i++) {
                let v = ch[i]; v = v < -1 ? -1 : (v > 1 ? 1 : v);
                b[n++] = v < 0 ? v * 32768 : v * 32767;
                if (n === frame) { if (!st.stopped) onFrame(b); b = new Int16Array(frame); n = 0; }
            }
        };
        src.connect(sp);
        sp.connect(mute);
        st.node = sp;
    };
    if (ctx.audioWorklet && typeof AudioWorkletNode !== 'undefined') {
        const code = 'class P extends AudioWorkletProcessor {' +
            ' constructor() { super(); this.N = ' + frame + '; this.b = new Int16Array(this.N); this.n = 0; }' +
            ' process(inputs) {' +
            '  const ch = inputs[0] && inputs[0][0];' +
            '  const len = ch ? ch.length : 128;' +
            '  for (let i = 0; i < len; i++) {' +
            '   let v = ch ? ch[i] : 0; v = v < -1 ? -1 : (v > 1 ? 1 : v);' +
            '   this.b[this.n++] = v < 0 ? v * 32768 : v * 32767;' +
            '   if (this.n === this.N) { this.port.postMessage(this.b, [this.b.buffer]); this.b = new Int16Array(this.N); this.n = 0; }' +
            '  }' +
            '  return true;' +
            ' }' +
            '}' +
            "registerProcessor('ciris-mic-' + " + frame + ", P);";
        const url = URL.createObjectURL(new Blob([code], { type: 'application/javascript' }));
        ctx.audioWorklet.addModule(url).then(() => {
            URL.revokeObjectURL(url);
            if (st.stopped) return;
            const node = new AudioWorkletNode(ctx, 'ciris-mic-' + frame, {
                numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1], channelCount: 1, channelCountMode: 'explicit',
            });
            node.port.onmessage = (ev) => { if (!st.stopped) onFrame(ev.data); };
            src.connect(node);
            node.connect(mute);
            st.node = node;
        }).catch((e) => {
            onError('audio worklet failed, using ScriptProcessor: ' + e);
            if (!st.stopped) viaScript();
        });
    } else {
        viaScript();
    }
    if (ctx.state === 'suspended') ctx.resume().catch(() => {});
    st.stop = () => {
        st.stopped = true;
        try { if (st.node) st.node.disconnect(); src.disconnect(); } catch (e) {}
        ctx.close().catch(() => {});
    };
    return st;
}"""
)

/**
 * Track → frames at [fps] → canvas-scaled to fit → packed I420. Uses
 * MediaStreamTrackProcessor where it exists (Chromium), else a hidden <video>
 * element sampled on a timer (Firefox, Safari).
 */
private fun jsVideoCaptureStart(
    kit: JsAny,
    stream: JsAny,
    fps: Int,
    maxW: Int,
    maxH: Int,
    onFrame: (Int, Int, Double, JsAny) -> Unit,
): JsAny = js(
    """{
    const track = stream.getVideoTracks()[0];
    const st = { stopped: false, timer: null, reader: null, video: null };
    const interval = 1000 / fps;
    const t0 = performance.now();
    let last = -1e9;
    if (typeof MediaStreamTrackProcessor !== 'undefined') {
        const reader = new MediaStreamTrackProcessor({ track: track }).readable.getReader();
        st.reader = reader;
        (async () => {
            while (!st.stopped) {
                const r = await reader.read();
                if (r.done) break;
                const f = r.value;
                const now = performance.now();
                if (now - last < interval * 0.9) { f.close(); continue; }
                last = now;
                try {
                    const o = await kit.frameToI420(f, maxW, maxH);
                    if (!st.stopped) onFrame(o.w, o.h, Math.round((now - t0) * 1000), o.data);
                } catch (e) {
                    console.warn('[calls/media] capture frame: ' + e);
                } finally {
                    f.close();
                }
            }
        })().catch((e) => console.warn('[calls/media] capture: ' + e));
    } else {
        const v = document.createElement('video');
        v.muted = true;
        v.playsInline = true;
        v.srcObject = stream;
        v.play().catch(() => {});
        st.video = v;
        st.timer = setInterval(() => {
            if (st.stopped || v.readyState < 2 || !v.videoWidth) return;
            const o = kit.drawToI420(v, v.videoWidth, v.videoHeight, maxW, maxH);
            onFrame(o.w, o.h, Math.round((performance.now() - t0) * 1000), o.data);
        }, interval);
    }
    st.stop = () => {
        st.stopped = true;
        if (st.timer) clearInterval(st.timer);
        if (st.reader) st.reader.cancel().catch(() => {});
        if (st.video) { st.video.pause(); st.video.srcObject = null; }
    };
    return st;
}"""
)

private fun jsSinkCreate(): JsAny = js("({ ctx: null, pubs: new Map() })")

/**
 * One AudioBufferSourceNode per 20 ms frame, scheduled back to back on that
 * publisher's own clock, through that publisher's own gain into the
 * destination (where the mix happens). An underrun re-buffers 60 ms ahead; a
 * queue more than 300 ms ahead drops the frame, so latency stays bounded when
 * a sender's clock runs fast.
 */
private fun jsSinkPlay(s: JsAny, pub: String, pcm: JsAny, sampleRate: Int): Unit = js(
    """{
    if (!s.ctx) s.ctx = new AudioContext({ sampleRate: sampleRate, latencyHint: 'interactive' });
    const ctx = s.ctx;
    if (ctx.state === 'suspended') ctx.resume().catch(() => {});
    let p = s.pubs.get(pub);
    if (!p) {
        const g = ctx.createGain();
        g.connect(ctx.destination);
        p = { gain: g, next: 0, live: new Set() };
        s.pubs.set(pub, p);
    }
    const now = ctx.currentTime;
    if (p.next < now + 0.005) p.next = now + 0.06;
    if (p.next > now + 0.3) return;
    const buf = ctx.createBuffer(1, pcm.length, sampleRate);
    const ch = buf.getChannelData(0);
    for (let i = 0; i < pcm.length; i++) ch[i] = pcm[i] / 32768;
    const src = ctx.createBufferSource();
    src.buffer = buf;
    src.connect(p.gain);
    src.onended = () => p.live.delete(src);
    p.live.add(src);
    src.start(p.next);
    p.next += pcm.length / sampleRate;
}"""
)

private fun jsSinkRemove(s: JsAny, pub: String): Unit = js(
    """{
    const p = s.pubs.get(pub);
    if (!p) return;
    p.live.forEach((x) => { try { x.stop(); } catch (e) {} });
    try { p.gain.disconnect(); } catch (e) {}
    s.pubs.delete(pub);
}"""
)

private fun jsSinkStop(s: JsAny): Unit = js(
    """{
    for (const p of s.pubs.values()) {
        p.live.forEach((x) => { try { x.stop(); } catch (e) {} });
        try { p.gain.disconnect(); } catch (e) {}
    }
    s.pubs.clear();
    if (s.ctx) { s.ctx.close().catch(() => {}); s.ctx = null; }
}"""
)
