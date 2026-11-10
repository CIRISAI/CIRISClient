package ai.ciris.mobile.shared.calls.media

/**
 * iOS: one AVAudioEngine for the microphone (voice processing on) and the
 * speaker, so the echo canceller removes what the call itself plays; the front
 * camera through AVCaptureSession. A device that cannot open the microphone or
 * camera yields null, and the screen says so.
 */
actual fun platformCallMediaDevices(): CallMediaDevices = object : CallMediaDevices {
    override suspend fun microphone(): AudioSource? = runCatching { iosMicrophoneSource() }.getOrNull()
    override fun speaker(): AudioSink? = runCatching { iosSpeakerSink() }.getOrNull()
    override suspend fun camera(): VideoSource? = runCatching { iosFrontCameraSource() }.getOrNull()
}
