package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.calls.CallSession
import ai.ciris.mobile.shared.calls.CallState
import ai.ciris.mobile.shared.calls.CallTransport
import ai.ciris.mobile.shared.calls.media.CallAudio
import ai.ciris.mobile.shared.calls.media.CallMediaDevices
import ai.ciris.mobile.shared.calls.media.CallVideoCodec
import ai.ciris.mobile.shared.calls.media.H264Download
import ai.ciris.mobile.shared.calls.media.MeasuringSink
import ai.ciris.mobile.shared.calls.media.opusAvailable
import ai.ciris.mobile.shared.calls.media.toImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One call in one room, on the screen (CSD-134): joins through [transport],
 * runs audio through this device's [devices], and leaves when [close] is called,
 * which the screen does when it goes away. A call never runs on unseen.
 *
 * Audio starts once the node says `ready`. Where this platform has no microphone
 * or no Opus yet, the call still joins and hears nothing it cannot play, and
 * [audioUnavailable] says so on screen.
 */
class CallViewModel(
    private val room: String,
    transport: (CoroutineScope) -> CallTransport,
    private val devices: CallMediaDevices,
    /** The person's switch for H.264 where it is a separate download (desktop); null elsewhere. */
    private val h264: H264Download? = ai.ciris.mobile.shared.calls.media.h264Download(),
    /** The platform's video encoder for the camera; a test passes its own. */
    private val newEncoder: (width: Int, height: Int, fps: Int, bitrate: Int) -> ai.ciris.mobile.shared.calls.media.VideoEncoder? =
        { w, h, fps, bitrate -> ai.ciris.mobile.shared.calls.media.createVideoEncoder(w, h, fps, bitrate) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
) {
    private val transportRef: CallTransport = transport(scope)
    val session = CallSession(room, transportRef, scope)
    val state: StateFlow<CallState> = session.state

    private var audio: CallAudio? = null
    private var starting = false
    /** Other people's video: their decoders and tiles. It never holds this device's camera. */
    private var video: ai.ciris.mobile.shared.calls.media.CallVideo? = null
    /**
     * This device's camera, being sent. It exists only while its picture is going
     * out, so the camera is held, and its light is on, only then. Set and cleared
     * only with [videoLock] held.
     */
    @kotlin.concurrent.Volatile
    private var camera: ai.ciris.mobile.shared.calls.media.CallVideo? = null
    /** The codec this device's camera is being sent in, or null when it is not sending. */
    private var videoCodec: Int? = null
    /** The last try found no camera or no encoder. It is not tried again until something changes. */
    private var cameraFailed = false
    private val videoLock = Mutex()
    private var wantsVideo = false
    private var share: ai.ciris.mobile.shared.calls.CallShare? = null
    private var shareVideo: ai.ciris.mobile.shared.calls.media.CallVideo? = null
    private var shareCodec: Int? = null

    /** The latest picture per stream (hex id), with whose it is: one tile each, camera or share. */
    data class Picture(val publisher: String, val image: androidx.compose.ui.graphics.ImageBitmap)

    private val _pictures = MutableStateFlow<Map<String, Picture>>(emptyMap())
    val pictures: StateFlow<Map<String, Picture>> = _pictures.asStateFlow()

    private val _shareState = MutableStateFlow<ai.ciris.mobile.shared.calls.CallShare.State>(ai.ciris.mobile.shared.calls.CallShare.State.Idle)
    val shareState: StateFlow<ai.ciris.mobile.shared.calls.CallShare.State> = _shareState.asStateFlow()

    private val _cameraOff = MutableStateFlow(false)
    val cameraOff: StateFlow<Boolean> = _cameraOff.asStateFlow()

    /** True when a video call found no camera or no encoder here: the call goes on as voice, and says so. */
    private val _videoUnavailable = MutableStateFlow(false)
    val videoUnavailable: StateFlow<Boolean> = _videoUnavailable.asStateFlow()
    private var sink: MeasuringSink? = null

    /**
     * True when someone's H.264 video arrived and this device has H.264 turned
     * off, so their tile has no picture. The screen says so beside the switch.
     */
    private val _h264Needed = MutableStateFlow(false)
    val h264Needed: StateFlow<Boolean> = _h264Needed.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _audioUnavailable = MutableStateFlow(!opusAvailable)

    /**
     * True when this device cannot capture or play call audio: no Opus on this
     * platform yet, or no microphone or speaker when the call started. Said on
     * screen, never silent. Found out when audio starts, not by opening devices early.
     */
    val audioUnavailable: StateFlow<Boolean> = _audioUnavailable.asStateFlow()

    init {
        // Audio follows the node's ready; a member's departure releases their slot.
        state.onEach { s ->
            if (s is CallState.InCall && s.ready && audio == null && !starting) {
                starting = true
                startAudio()
                videoLock.withLock { startReceiving() }
            }
            if (s is CallState.InCall) {
                s.departed.keys.forEach { audio?.forget(it) }
                // More people than the node carries video for: the camera is released and a
                // share ends. Back under it, the camera sends again if the person left it on.
                if (!s.videoAllowed && share != null) stopShare()
                videoLock.withLock { syncCamera() }
            }
            if (s is CallState.Left || s is CallState.Refused) {
                stopAudio()
                videoLock.withLock { stopCamera(); cameraFailed = false }
            }
        }.launchIn(scope)
        // H.264 turned on or off during a call. Receiving needs nothing here: the next
        // H.264 frame finds a decoder or does not. Sending does, when H.264 is what this
        // device sends (or would, having had no encoder until now).
        h264?.state?.onEach { st ->
            val on = st == H264Download.State.ON
            if (on) _h264Needed.value = false
            videoLock.withLock {
                if (video == null) return@withLock
                // On, after a try that found no encoder: try again. Off, while H.264 is
                // what the camera is sent in: stop, and send AV1 if this machine can.
                val again = if (on) cameraFailed else videoCodec == CallVideoCodec.TEMPORAL_LAYERED
                if (again) {
                    stopCamera()
                    cameraFailed = false
                    _videoUnavailable.value = false
                    syncCamera()
                }
            }
            if (!on && shareCodec == CallVideoCodec.TEMPORAL_LAYERED) stopShare()
        }?.launchIn(scope)
    }

    fun join(video: Boolean) {
        wantsVideo = video
        session.join(video)
    }

    fun setCameraOff(value: Boolean) {
        _cameraOff.value = value
        camera?.cameraOff = value // no frame after this one; releasing the camera follows
        if (!value) cameraFailed = false // turning it on is asking again
        scope.launch { videoLock.withLock { syncCamera() } }
    }

    private fun hex(id: ByteArray) = id.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** Always decode others' video, even on a voice call: they may have their camera on. */
    private fun startReceiving() {
        video = ai.ciris.mobile.shared.calls.media.CallVideo(
            session = session,
            source = null,
            encoder = null,
            scope = scope,
            render = { publisher, streamId, frame ->
                val image = frame.toImageBitmapSafe() ?: return@CallVideo
                _pictures.value = _pictures.value + (hex(streamId) to Picture(publisher, image))
            },
            undecodable = { _, codecId ->
                if (codecId == CallVideoCodec.TEMPORAL_LAYERED && h264 != null && h264.state.value != H264Download.State.ON) {
                    _h264Needed.value = true
                }
            },
        ).also { it.start() }
    }

    /**
     * The camera is held exactly while its picture is being sent: a video call,
     * the person's camera on, and a call the node carries video for. At any other
     * time it is released, so the device's camera light is on only when someone
     * can see the picture. Called with [videoLock] held, whenever one of those changes.
     */
    private suspend fun syncCamera() {
        if (!cameraWanted()) {
            stopCamera()
            return
        }
        if (camera != null || cameraFailed) return
        val source = devices.camera()
        // Getting the camera can wait on the person (the browser asks them). If the call
        // ended, grew, or they turned the camera off meanwhile, it is let go unused.
        if (!cameraWanted()) {
            source?.stop()
            return
        }
        val encoder = source?.let { newEncoder(640, 480, 15, 400_000) }
        if (source == null || encoder == null) {
            // Getting the source may already have opened the camera (desktop, the browser): let it go.
            source?.stop()
            cameraFailed = true
            _videoUnavailable.value = true
            return
        }
        _videoUnavailable.value = false
        videoCodec = encoder.codecId
        camera = ai.ciris.mobile.shared.calls.media.CallVideo(
            session = session, source = source, encoder = encoder, scope = scope,
            render = { _, _, _ -> }, receive = false,
        ).also { it.start() }
    }

    private fun cameraWanted(): Boolean {
        val call = state.value as? CallState.InCall ?: return false
        return video != null && call.ready && call.videoAllowed && wantsVideo && !_cameraOff.value
    }

    /** With [videoLock] held. */
    private fun stopCamera() {
        camera?.stop()
        camera = null
        videoCodec = null
    }

    /**
     * Share a screen or a window as its own stream (CSD-134): a second socket, a
     * low frame rate, and a tile for everyone else. Waits on the node for the
     * route (Edge 45.1); a node without it refuses by name.
     */
    fun startShare(source: ai.ciris.mobile.shared.calls.media.VideoSource, width: Int = 1280, height: Int = 720) {
        if (share != null) return
        val sh = ai.ciris.mobile.shared.calls.CallShare(room, transportRef, scope)
        share = sh
        sh.state.onEach { st ->
            _shareState.value = st
            if (st is ai.ciris.mobile.shared.calls.CallShare.State.Sharing && shareVideo == null) {
                val enc = ai.ciris.mobile.shared.calls.media.createVideoEncoder(width, height, 5, 800_000)
                if (enc == null) { sh.stop(); return@onEach }
                shareCodec = enc.codecId
                shareVideo = ai.ciris.mobile.shared.calls.media.CallVideo(
                    session = session, source = source, encoder = enc, scope = scope,
                    render = { _, _, _ -> }, publish = sh::publish, receive = false,
                ).also { it.start() }
            }
            if (st is ai.ciris.mobile.shared.calls.CallShare.State.Stopped ||
                st is ai.ciris.mobile.shared.calls.CallShare.State.Refused
            ) {
                shareVideo?.stop(); shareVideo = null; shareCodec = null
                if (st is ai.ciris.mobile.shared.calls.CallShare.State.Stopped) share = null
            }
        }.launchIn(scope)
        sh.start()
    }

    fun stopShare() {
        shareVideo?.stop(); shareVideo = null; shareCodec = null
        share?.stop(); share = null
    }

    fun setMuted(value: Boolean) {
        _muted.value = value
        audio?.muted = value
    }

    /** What has been heard from [member]: frames and pitch, for the screen's test tags. */
    fun heard(member: String): MeasuringSink.Heard? = sink?.heard(member)

    private suspend fun startAudio() {
        if (!opusAvailable) return
        val mic = devices.microphone()
        val speaker = devices.speaker()
        if (mic == null || speaker == null) _audioUnavailable.value = true
        if (mic == null) return
        val s = MeasuringSink(speaker)
        sink = s
        audio = CallAudio(session, mic, s, scope).also { it.muted = _muted.value; it.start() }
    }

    private fun stopAudio() {
        audio?.stop()
        audio = null
        video?.stop()
        video = null
        // The camera sends nothing from here; it is released under the lock, by the
        // state's change to Left (and by close()), since a sync may be using it now.
        camera?.cameraOff = true
        stopShare()
    }

    fun leave(reason: ai.ciris.mobile.shared.calls.LeaveReason = ai.ciris.mobile.shared.calls.LeaveReason.LEFT) {
        stopAudio()
        session.leave(reason)
    }

    /** A new stream after leaving: rejoining is never the old stream resumed. */
    fun rejoin() {
        starting = false
        session.join(wantsVideo)
    }

    fun close() {
        leave()
        // The scope ends only once the camera is let go: a sync may be holding it right now.
        scope.launch { videoLock.withLock { stopCamera() } }.invokeOnCompletion { scope.cancel() }
    }
}

/** A frame that cannot be converted costs one picture, never the call. */
private fun ai.ciris.mobile.shared.calls.media.VideoFrame.toImageBitmapSafe(): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching { toImageBitmap() }.getOrNull()
