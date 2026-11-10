package ai.ciris.mobile.shared.calls.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Desktop: javax.sound for audio; the camera through FFmpeg's avdevice ([desktopCamera]). */
actual fun platformCallMediaDevices(): CallMediaDevices = object : CallMediaDevices {
    override suspend fun microphone(): AudioSource? = if (desktopMicrophoneAvailable()) DesktopMicrophoneSource() else null
    override fun speaker(): AudioSink? = if (desktopSpeakerAvailable()) DesktopSpeakerSink() else null
    // Opening a device blocks (and loads the natives the first time): off the caller's thread.
    override suspend fun camera(): VideoSource? = withContext(Dispatchers.IO) { desktopCamera() }
}
