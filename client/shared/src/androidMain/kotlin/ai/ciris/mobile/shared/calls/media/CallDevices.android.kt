package ai.ciris.mobile.shared.calls.media

/**
 * Android: AudioRecord and AudioTrack; the camera on Camera2 ([androidCameraSource],
 * null without CAMERA or a camera). Screen share is not a device here: it needs
 * an Activity result, so the host asks with [androidScreenShareIntent] and builds
 * the source with [androidScreenSource].
 */
actual fun platformCallMediaDevices(): CallMediaDevices = object : CallMediaDevices {
    override suspend fun microphone(): AudioSource = AndroidMicrophoneSource()
    override fun speaker(): AudioSink = AndroidSpeakerSink()
    override suspend fun camera(): VideoSource? = androidCameraSource()
}
