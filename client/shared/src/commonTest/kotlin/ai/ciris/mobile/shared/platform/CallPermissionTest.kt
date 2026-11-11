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
    }

    /** The call screen's loop: ask what is due, then step again with what was asked. */
    private fun attempt(media: CallMediaPlatform, kind: CallKind): CallPermissionState {
        var state: CallPermissionState? = null
        fun advance(asked: Set<MediaSource>) {
            val next = callPermissionStep(kind, media.access(MediaSource.MICROPHONE), media.access(MediaSource.CAMERA), asked)
            state = next
            if (next is CallPermissionState.Asking) media.requestAccess(next.source) { advance(asked + next.source) }
        }
        advance(emptySet())
        return state!!
    }

    /** Desktop and the browser: a platform whose only way to ask is to open the device. */
    private class Opening(var answer: MediaOpened, var testMode: Boolean = false) {
        var opened = 0
        val platform = OpenToAskMediaPlatform({ testMode }) { onResult -> opened++; onResult(answer) }
    }

    @Test fun whereAskingIsOpeningAVoiceCallOpensTheMicrophoneOnce() {
        val o = Opening(MediaOpened.OPENED)
        assertEquals(CallPermissionState.Ready(CallKind.VOICE, false), attempt(o.platform, CallKind.VOICE))
        assertEquals(1, o.opened)
        // The answer holds for the next call from this screen: it is not asked twice.
        assertEquals(CallPermissionState.Ready(CallKind.VOICE, false), attempt(o.platform, CallKind.VOICE))
        assertEquals(1, o.opened)
    }

    @Test fun whereAskingIsOpeningTheCameraIsNotOpenedBeforeTheCall() {
        val o = Opening(MediaOpened.OPENED)
        assertEquals(CallPermissionState.Ready(CallKind.VIDEO, false), attempt(o.platform, CallKind.VIDEO))
        assertEquals(1, o.opened, "the microphone, and nothing else")
    }

    @Test fun whereAskingIsOpeningARefusedMicrophoneIsNoCallAndTryingAgainAsksAgain() {
        val o = Opening(MediaOpened.REFUSED)
        assertEquals(CallPermissionState.MicrophoneDenied, attempt(o.platform, CallKind.VIDEO))
        assertEquals(1, o.opened)
        o.answer = MediaOpened.OPENED
        assertEquals(CallPermissionState.Ready(CallKind.VIDEO, false), attempt(o.platform, CallKind.VIDEO))
        assertEquals(2, o.opened)
    }

    @Test fun whereAskingIsOpeningNoMicrophoneIsSaidAsNoDevice() {
        val o = Opening(MediaOpened.NO_DEVICE)
        assertEquals(CallPermissionState.Unavailable(MediaUnavailable.NO_DEVICE), attempt(o.platform, CallKind.VOICE))
    }

    @Test fun inTestModeNothingIsOpened() {
        val o = Opening(MediaOpened.NO_DEVICE, testMode = true)
        assertEquals(CallPermissionState.Ready(CallKind.VIDEO, false), attempt(o.platform, CallKind.VIDEO))
        assertEquals(0, o.opened)
    }
}
