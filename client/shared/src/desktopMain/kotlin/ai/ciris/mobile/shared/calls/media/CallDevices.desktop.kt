package ai.ciris.mobile.shared.calls.media

/** Desktop: javax.sound for audio. Camera capture is not built yet (it needs a native library). */
actual fun platformCallMediaDevices(): CallMediaDevices = object : CallMediaDevices {
    override suspend fun microphone(): AudioSource? = if (desktopMicrophoneAvailable()) DesktopMicrophoneSource() else null
    override fun speaker(): AudioSink? = if (desktopSpeakerAvailable()) DesktopSpeakerSink() else null
    override suspend fun camera(): VideoSource? = null
}
