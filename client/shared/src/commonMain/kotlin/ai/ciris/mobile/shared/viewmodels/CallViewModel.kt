package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.calls.CallSession
import ai.ciris.mobile.shared.calls.CallState
import ai.ciris.mobile.shared.calls.CallTransport
import ai.ciris.mobile.shared.calls.media.CallAudio
import ai.ciris.mobile.shared.calls.media.CallMediaDevices
import ai.ciris.mobile.shared.calls.media.MeasuringSink
import ai.ciris.mobile.shared.calls.media.opusAvailable
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
    val session = CallSession(room, transport(scope), scope)
    val state: StateFlow<CallState> = session.state

    private var audio: CallAudio? = null
    private var starting = false
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
            if (s is CallState.InCall && s.ready && audio == null && !starting) { starting = true; startAudio() }
            if (s is CallState.InCall) s.departed.keys.forEach { audio?.forget(it) }
            if (s is CallState.Left || s is CallState.Refused) stopAudio()
        }.launchIn(scope)
    }

    fun join(video: Boolean) = session.join(video)

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
    }

    fun leave() {
        stopAudio()
        session.leave()
    }

    fun close() {
        leave()
        scope.cancel()
    }
}
