package ai.ciris.mobile.shared.platform

import ai.ciris.mobile.shared.testing.TestAutomationState
import androidx.compose.foundation.clickable
import androidx.compose.ui.composed
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.StateFlow
import platform.posix.getenv

/**
 * iOS implementation of test automation.
 * Delegates to shared TestAutomationState.
 * When CIRIS_TEST_MODE=true, tracks element positions for the POSIX HTTP server.
 */
actual object TestAutomation {
    actual val textInputRequests: StateFlow<TextInputRequest?> = TestAutomationState.textInputRequests
    actual val fileInjectionRequests: StateFlow<PickedFile?> = TestAutomationState.fileInjectionRequests

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    actual fun isEnabled(): Boolean {
        if (TestAutomationState.isEnabled) return true
        val testMode = getenv("CIRIS_TEST_MODE")?.toKString()?.lowercase()
        val enabled = testMode in listOf("true", "1", "yes")
        if (enabled) TestAutomationState.isEnabled = true
        return enabled
    }

    actual fun registerElement(testTag: String, x: Int, y: Int, width: Int, height: Int, text: String?) {
        if (!isEnabled()) return
        TestAutomationState.registerElement(testTag, x, y, width, height, text)
    }

    actual fun unregisterElement(testTag: String) {
        TestAutomationState.unregisterElement(testTag)
    }

    actual fun setCurrentScreen(screen: String) {
        TestAutomationState.currentScreen = screen
    }

    actual fun clearElements() {
        TestAutomationState.clearElements()
    }

    actual fun registerClickHandler(testTag: String, handler: () -> Unit) {
        if (!isEnabled()) return
        TestAutomationState.registerClickHandler(testTag, handler)
    }

    actual fun unregisterClickHandler(testTag: String) {
        TestAutomationState.unregisterClickHandler(testTag)
    }

    actual fun triggerClick(testTag: String): Boolean {
        return TestAutomationState.triggerClick(testTag)
    }

    /** Whether automation can DRIVE this tag, as opposed to merely see it. */
    actual fun hasClickHandler(testTag: String): Boolean = false

    private val inputSinks = mutableSetOf<String>()

    /** A field declares itself text-drivable when it starts listening. */
    actual fun registerInputSink(testTag: String) { inputSinks.add(testTag) }

    actual fun unregisterInputSink(testTag: String) {
        inputSinks.remove(testTag)
        inputValues.remove(testTag)
    }

    actual fun hasInputSink(testTag: String): Boolean = inputSinks.contains(testTag)

    /** tag -> what the field currently holds. See the expect for why. */
    private val inputValues = mutableMapOf<String, String>()

    actual fun setInputValue(testTag: String, value: String) { inputValues[testTag] = value }

    actual fun inputValue(testTag: String): String? = inputValues[testTag]

    actual fun requestTextInput(testTag: String, text: String, clearFirst: Boolean) {
        TestAutomationState.requestTextInput(testTag, text, clearFirst)
    }

    actual fun clearTextInputRequest() {
        TestAutomationState.clearTextInputRequest()
    }

    actual fun injectFile(name: String, mediaType: String, dataBase64: String, sizeBytes: Long) {
        TestAutomationState.injectFile(name, mediaType, dataBase64, sizeBytes)
    }

    actual fun clearFileInjectionRequest() {
        TestAutomationState.clearFileInjectionRequest()
    }
}
