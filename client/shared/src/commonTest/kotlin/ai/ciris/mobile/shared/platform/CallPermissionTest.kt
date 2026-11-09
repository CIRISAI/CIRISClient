package ai.ciris.mobile.shared.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/** CSD-134: what a call asks for, and what it does when refused. */
class CallPermissionTest {
    private val granted = MediaAccess.Granted
    private val askable = MediaAccess.Askable
    private val denied = MediaAccess.Denied

    @Test fun aVoiceCallAsksForTheMicrophoneOnly() {
        assertEquals(
            CallPermissionState.Asking(CallKind.VOICE, MediaSource.MICROPHONE),
            callPermissionStep(CallKind.VOICE, askable, askable),
        )
        assertEquals(CallPermissionState.Ready(CallKind.VOICE, false), callPermissionStep(CallKind.VOICE, granted, askable))
    }

    @Test fun aVideoCallAsksForTheMicrophoneThenTheCamera() {
        assertEquals(
            CallPermissionState.Asking(CallKind.VIDEO, MediaSource.MICROPHONE),
            callPermissionStep(CallKind.VIDEO, askable, askable),
        )
        assertEquals(
            CallPermissionState.Asking(CallKind.VIDEO, MediaSource.CAMERA),
            callPermissionStep(CallKind.VIDEO, granted, askable, setOf(MediaSource.MICROPHONE)),
        )
        assertEquals(CallPermissionState.Ready(CallKind.VIDEO, false), callPermissionStep(CallKind.VIDEO, granted, granted))
    }

    @Test fun aRefusedCameraMakesItAVoiceCallNotNoCall() {
        assertEquals(CallPermissionState.Ready(CallKind.VIDEO, true), callPermissionStep(CallKind.VIDEO, granted, denied))
        assertEquals(
            CallPermissionState.Ready(CallKind.VIDEO, true),
            callPermissionStep(CallKind.VIDEO, granted, MediaAccess.Unavailable(MediaUnavailable.NO_DEVICE)),
        )
    }

    @Test fun aRefusedMicrophoneMeansNoCall() {
        assertEquals(CallPermissionState.MicrophoneDenied, callPermissionStep(CallKind.VOICE, denied, granted))
        assertEquals(CallPermissionState.MicrophoneDenied, callPermissionStep(CallKind.VIDEO, denied, granted))
    }

    /** Android reports a once-refused permission as still askable: asking again would loop. */
    @Test fun askingTwiceInOneAttemptIsARefusal() {
        assertEquals(
            CallPermissionState.MicrophoneDenied,
            callPermissionStep(CallKind.VOICE, askable, askable, setOf(MediaSource.MICROPHONE)),
        )
        assertEquals(
            CallPermissionState.Ready(CallKind.VIDEO, true),
            callPermissionStep(CallKind.VIDEO, granted, askable, setOf(MediaSource.MICROPHONE, MediaSource.CAMERA)),
        )
    }

    @Test fun noCaptureOnThisPlatformIsSaidNotAsked() {
        val none = MediaAccess.Unavailable(MediaUnavailable.PLATFORM)
        assertEquals(CallPermissionState.Unavailable(MediaUnavailable.PLATFORM), callPermissionStep(CallKind.VIDEO, none, none))
        assertEquals(MediaAccess.Unavailable(MediaUnavailable.PLATFORM), NoCallMediaPlatform.access(MediaSource.MICROPHONE))
    }
}
