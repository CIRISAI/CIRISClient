package ai.ciris.mobile.shared.platform

import ai.ciris.mobile.shared.calls.media.webAskMicrophone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * The browser: `getUserMedia` is the prompt, so the microphone is opened once
 * before the call and its tracks stopped. The browser remembers the answer for
 * this page, so opening it again for the call does not ask twice.
 */
@Composable
actual fun rememberCallMediaPlatform(): CallMediaPlatform {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        OpenToAskMediaPlatform(testMode = TestAutomation::isEnabled) { onResult ->
            scope.launch { onResult(webAskMicrophone()) }
        }
    }
}
