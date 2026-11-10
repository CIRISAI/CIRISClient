package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.calls.CallSession
import ai.ciris.mobile.shared.calls.CallState
import ai.ciris.mobile.shared.calls.CallTransport
import ai.ciris.mobile.shared.calls.media.CallAudio
import ai.ciris.mobile.shared.calls.media.CallMediaDevices
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
) {
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val transportRef: CallTransport = transport(scope)
    val session = CallSession(room, transportRef, scope)
    val state: StateFlow<CallState> = session.state

    private var audio: CallAudio? = null
    private var starting = false
    private var video: ai.ciris.mobile.shared.calls.media.CallVideo? = null
    private var wantsVideo = false
    private var share: ai.ciris.mobile.shared.calls.CallShare? = null
    private var shareVideo: ai.ciris.mobile.shared.calls.media.CallVideo? = null

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
                startVideo()
            }
            if (s is CallState.InCall) s.departed.keys.forEach { audio?.forget(it) }
            if (s is CallState.Left || s is CallState.Refused) stopAudio()
        }.launchIn(scope)
    }

    fun join(video: Boolean) {
        wantsVideo = video
        session.join(video)
    }

    fun setCameraOff(value: Boolean) {
        _cameraOff.value = value
        video?.cameraOff = value
    }

    private fun hex(id: ByteArray) = id.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private suspend fun startVideo() {
        // Always decode others' video, even on a voice call: they may have their camera on.
        val camera = if (wantsVideo) devices.camera() else null
        val encoder = camera?.let { ai.ciris.mobile.shared.calls.media.createVideoEncoder(640, 480, 15, 400_000) }
        if (wantsVideo && (camera == null || encoder == null)) _videoUnavailable.value = true
        video = ai.ciris.mobile.shared.calls.media.CallVideo(
            session = session,
            source = if (encoder != null) camera else null,
            encoder = encoder,
            scope = scope,
            render = { publisher, streamId, frame ->
                val image = frame.toImageBitmapSafe() ?: return@CallVideo
                _pictures.value = _pictures.value + (hex(streamId) to Picture(publisher, image))
            },
        ).also { it.cameraOff = _cameraOff.value; it.start() }
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
                shareVideo = ai.ciris.mobile.shared.calls.media.CallVideo(
                    session = session, source = source, encoder = enc, scope = scope,
                    render = { _, _, _ -> }, publish = sh::publish, receive = false,
                ).also { it.start() }
            }
            if (st is ai.ciris.mobile.shared.calls.CallShare.State.Stopped ||
                st is ai.ciris.mobile.shared.calls.CallShare.State.Refused
            ) {
                shareVideo?.stop(); shareVideo = null
                if (st is ai.ciris.mobile.shared.calls.CallShare.State.Stopped) share = null
            }
        }.launchIn(scope)
        sh.start()
    }

    fun stopShare() {
        shareVideo?.stop(); shareVideo = null
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
        scope.cancel()
    }
}

/** A frame that cannot be converted costs one picture, never the call. */
private fun ai.ciris.mobile.shared.calls.media.VideoFrame.toImageBitmapSafe(): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching { toImageBitmap() }.getOrNull()
