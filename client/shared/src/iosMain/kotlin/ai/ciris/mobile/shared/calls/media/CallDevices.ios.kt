package ai.ciris.mobile.shared.calls.media

/** Not built yet on this platform: a call here has no capture or playback, and the screen says so. */
actual fun platformCallMediaDevices(): CallMediaDevices = object : CallMediaDevices {
    override fun microphone(): AudioSource? = null
    override fun speaker(): AudioSink? = null
    override fun camera(): VideoSource? = null
}
