package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable

/**
 * The microphone and camera a call needs, and the person's permission for each
 * (CSD-134). The OS-facing half; [callPermissionStep] is the behaviour, pure so
 * it is tested without a device.
 *
 *  - Android: RECORD_AUDIO and CAMERA, asked at the moment of the call.
 *  - iOS: AVCaptureDevice authorisation for audio and video; the Info.plist
 *    carries NSMicrophoneUsageDescription and NSCameraUsageDescription.
 *  - Desktop and web: there is no permission to ask for apart from opening the
 *    device, so the microphone is opened once before the call
 *    ([OpenToAskMediaPlatform]).
 *
 * Asked only when the person starts a call, never at launch: a permission asked
 * with no reason on screen is one people refuse.
 */
enum class CallKind { VOICE, VIDEO }

enum class MediaSource { MICROPHONE, CAMERA }

/** Why a source cannot be used at all. Each has its own sentence. */
enum class MediaUnavailable {
    /** A build with no capture at all. No platform is one today. */
    PLATFORM,

    /** This device has no such hardware. */
    NO_DEVICE,
}

/** Where access to one source stands, as the OS reports it right now. */
sealed interface MediaAccess {
    data object Granted : MediaAccess

    /** Not yet granted, and the OS will still ask the person. */
    data object Askable : MediaAccess

    /** Refused, and the OS will not ask again until the person changes it in Settings. */
    data object Denied : MediaAccess

    data class Unavailable(val reason: MediaUnavailable) : MediaAccess
}

/** The OS-facing half. Obtain with [rememberCallMediaPlatform]. */
interface CallMediaPlatform {
    /** Read fresh each time: the person can change it in Settings between taps. */
    fun access(source: MediaSource): MediaAccess

    /** Ask the OS; [onResult] runs on the main thread. */
    fun requestAccess(source: MediaSource, onResult: (granted: Boolean) -> Unit)
}

@Composable
expect fun rememberCallMediaPlatform(): CallMediaPlatform

/** How opening a device to ask for it went. */
enum class MediaOpened { OPENED, REFUSED, NO_DEVICE }

/**
 * Desktop and the browser: there is no permission to ask for apart from opening
 * the device. The JVM has no permission API (macOS asks when the device is first
 * opened), and in the browser opening the device is the prompt.
 *
 * The microphone is opened once before the call and let go, because a call
 * cannot happen without it and the person should learn that before they join.
 * The camera is not opened here: it is opened only while its picture is sent,
 * and a refusal then is said on the call.
 *
 * [openMicrophone] opens the microphone, lets it go, and answers how it went.
 * A refusal is not remembered, so trying again asks again. In [testMode] the
 * call captures a tone and a test pattern, and nothing is opened.
 */
class OpenToAskMediaPlatform(
    private val testMode: () -> Boolean,
    private val openMicrophone: (onResult: (MediaOpened) -> Unit) -> Unit,
) : CallMediaPlatform {
    private var microphone: MediaAccess = MediaAccess.Askable

    override fun access(source: MediaSource): MediaAccess = when {
        testMode() || source == MediaSource.CAMERA -> MediaAccess.Granted
        else -> microphone
    }

    override fun requestAccess(source: MediaSource, onResult: (granted: Boolean) -> Unit) {
        if (testMode() || source == MediaSource.CAMERA) return onResult(true)
        openMicrophone { opened ->
            microphone = when (opened) {
                MediaOpened.OPENED -> MediaAccess.Granted
                MediaOpened.REFUSED -> MediaAccess.Askable
                MediaOpened.NO_DEVICE -> MediaAccess.Unavailable(MediaUnavailable.NO_DEVICE)
            }
            onResult(opened == MediaOpened.OPENED)
        }
    }
}

/** Where getting ready for a call stands. */
sealed interface CallPermissionState {
    /** The OS prompt for [source] is due, or up. */
    data class Asking(val kind: CallKind, val source: MediaSource) : CallPermissionState

    /**
     * Everything this call can have is granted. [audioOnly] is true for a video
     * call whose camera was refused or is missing: it goes on as a voice call,
     * and the screen says so (CSD-134: the call is not refused).
     */
    data class Ready(val kind: CallKind, val audioOnly: Boolean) : CallPermissionState

    /** A call cannot happen without the microphone. Its own sentence, and a retry for after Settings. */
    data object MicrophoneDenied : CallPermissionState

    /** No call from this device at all. */
    data class Unavailable(val reason: MediaUnavailable) : CallPermissionState
}

/**
 * The next step for a [kind] of call, given access as it stands and the sources
 * already [asked] about this time.
 *
 * [asked] is what stops a loop: Android reports a source refused once as still
 * askable, so a source asked and not granted counts as refused for this attempt.
 * A retry starts with an empty set.
 */
fun callPermissionStep(
    kind: CallKind,
    microphone: MediaAccess,
    camera: MediaAccess,
    asked: Set<MediaSource> = emptySet(),
): CallPermissionState {
    when (microphone) {
        is MediaAccess.Unavailable -> return CallPermissionState.Unavailable(microphone.reason)
        MediaAccess.Denied -> return CallPermissionState.MicrophoneDenied
        MediaAccess.Askable ->
            return if (MediaSource.MICROPHONE in asked) CallPermissionState.MicrophoneDenied
            else CallPermissionState.Asking(kind, MediaSource.MICROPHONE)
        MediaAccess.Granted -> Unit
    }
    if (kind == CallKind.VOICE) return CallPermissionState.Ready(CallKind.VOICE, audioOnly = false)
    return when (camera) {
        MediaAccess.Granted -> CallPermissionState.Ready(CallKind.VIDEO, audioOnly = false)
        MediaAccess.Askable ->
            if (MediaSource.CAMERA in asked) CallPermissionState.Ready(CallKind.VIDEO, audioOnly = true)
            else CallPermissionState.Asking(kind, MediaSource.CAMERA)
        MediaAccess.Denied, is MediaAccess.Unavailable -> CallPermissionState.Ready(CallKind.VIDEO, audioOnly = true)
    }
}
