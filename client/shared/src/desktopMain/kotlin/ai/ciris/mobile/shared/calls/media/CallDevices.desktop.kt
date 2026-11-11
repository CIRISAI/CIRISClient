package ai.ciris.mobile.shared.calls.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Desktop: javax.sound for audio, with the call's own echo canceller between the
 * speaker and the microphone; the camera through FFmpeg's avdevice ([desktopCamera]).
 */
actual fun platformCallMediaDevices(): CallMediaDevices = object : CallMediaDevices {
    // One canceller per start of audio: the call asks for the microphone, then the
    // speaker, and both halves of it must be the same canceller.
    private var echo: EchoCanceller? = null

    override suspend fun microphone(): AudioSource? =
        if (desktopMicrophoneAvailable()) DesktopMicrophoneSource(EchoCanceller().also { echo = it }) else null.also { echo = null }

    override fun speaker(): AudioSink? = if (desktopSpeakerAvailable()) DesktopSpeakerSink(echo) else null
    // Opening a device blocks (and loads the natives the first time): off the caller's thread.
    override suspend fun camera(): VideoSource? = withContext(Dispatchers.IO) { desktopCamera() }
}
