package ai.ciris.mobile.shared.calls.media

/** Android: AudioRecord and AudioTrack. Camera capture is not built yet. */
actual fun platformCallMediaDevices(): CallMediaDevices = object : CallMediaDevices {
    override fun microphone(): AudioSource = AndroidMicrophoneSource()
    override fun speaker(): AudioSink = AndroidSpeakerSink()
    override fun camera(): VideoSource? = null
}
