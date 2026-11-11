package ai.ciris.mobile.shared.platform

import ai.ciris.mobile.shared.calls.media.desktopMicrophoneAvailable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop: Java Sound has no permission to ask for, so "asking" is finding out
 * whether there is a microphone line this call can open. Where the system has
 * its own prompt (macOS), it shows it when the device is first opened.
 */
internal fun desktopCallMediaPlatform(): CallMediaPlatform =
    OpenToAskMediaPlatform(testMode = TestAutomation::isEnabled) { onResult ->
        onResult(if (desktopMicrophoneAvailable()) MediaOpened.OPENED else MediaOpened.NO_DEVICE)
    }

@Composable
actual fun rememberCallMediaPlatform(): CallMediaPlatform = remember { desktopCallMediaPlatform() }
