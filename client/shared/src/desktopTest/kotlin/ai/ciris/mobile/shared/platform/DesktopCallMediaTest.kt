package ai.ciris.mobile.shared.platform

import ai.ciris.mobile.shared.calls.media.desktopMicrophoneAvailable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CSD-134: a desktop can start a call. Until 0.5.241 the desktop's permission
 * step answered "no capture on this platform" while the capture behind it was
 * built, so the call screen stopped there and never joined.
 */
class DesktopCallMediaTest {
    @Test fun aDesktopWithAMicrophoneIsReadyAndOneWithoutSaysNoDevice() {
        val media = desktopCallMediaPlatform()
        var asked = emptySet<MediaSource>()
        var state = callPermissionStep(CallKind.VIDEO, media.access(MediaSource.MICROPHONE), media.access(MediaSource.CAMERA), asked)
        while (state is CallPermissionState.Asking) {
            val source = state.source
            media.requestAccess(source) {}
            asked = asked + source
            state = callPermissionStep(CallKind.VIDEO, media.access(MediaSource.MICROPHONE), media.access(MediaSource.CAMERA), asked)
        }
        val expected = if (TestAutomation.isEnabled() || desktopMicrophoneAvailable()) {
            CallPermissionState.Ready(CallKind.VIDEO, audioOnly = false)
        } else {
            CallPermissionState.Unavailable(MediaUnavailable.NO_DEVICE)
        }
        assertEquals(expected, state)
    }
}
