package ai.ciris.mobile.shared.calls.media

/**
 * The browser: getUserMedia for the microphone and camera (asking is part of
 * getting them, so they suspend), Web Audio for the speaker. A refusal is the
 * browser's own error, and the call goes on without that device.
 */
actual fun platformCallMediaDevices(): CallMediaDevices = object : CallMediaDevices {
    override suspend fun microphone(): AudioSource? = runCatching { webMicrophoneSource() }.getOrNull()
    override fun speaker(): AudioSink = webSpeakerSink()
    override suspend fun camera(): VideoSource? = runCatching { webCameraSource() }.getOrNull()
}
