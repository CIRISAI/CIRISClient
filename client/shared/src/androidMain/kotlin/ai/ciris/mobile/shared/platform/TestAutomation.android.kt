package ai.ciris.mobile.shared.platform

import ai.ciris.mobile.shared.testing.AndroidTestAutomationServer
import ai.ciris.mobile.shared.testing.TestAutomationState
import androidx.compose.foundation.clickable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.StateFlow

/**
 * Android implementation of test automation.
 * Delegates to shared TestAutomationState when test mode is enabled.
 * When CIRIS_TEST_MODE=true, tracks element positions for the Ktor HTTP server.
 */
actual object TestAutomation {
    actual val textInputRequests: StateFlow<TextInputRequest?> = TestAutomationState.textInputRequests
    actual val fileInjectionRequests: StateFlow<PickedFile?> = TestAutomationState.fileInjectionRequests

    actual fun isEnabled(): Boolean {
        if (TestAutomationState.isEnabled) return true
        val enabled = AndroidTestAutomationServer.isTestModeEnabled()
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

    actual fun unregisterInputSink(testTag: String) { inputSinks.remove(testTag) }

    actual fun hasInputSink(testTag: String): Boolean = inputSinks.contains(testTag)

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

/**
 * Android implementation — tracks position when test mode enabled, otherwise just testTag.
 *
 * Wrapped in `composed { DisposableEffect }` so the registry entry is removed
 * when the modifier leaves the composition. Without that, dialog / sheet
 * elements remain "visible" forever after dismiss, and walk-tests that wait
 * for an element to disappear loop until timeout.
 */
actual fun Modifier.testable(tag: String, text: String?): Modifier = composed {
    if (TestAutomation.isEnabled()) {
        DisposableEffect(tag) {
            onDispose { TestAutomation.unregisterElement(tag) }
        }
        this.testTag(tag).onGloballyPositioned { coords ->
            val bounds = coords.boundsInWindow()
            TestAutomation.registerElement(
                tag,
                bounds.left.toInt(), bounds.top.toInt(),
                bounds.width.toInt(), bounds.height.toInt(),
                text
            )
        }
    } else {
        this.testTag(tag)
    }
}

/**
 * Android implementation — tracks position + registers click handler when test mode enabled.
 *
 * Registers the click handler from a `DisposableEffect` so it unregisters on
 * dispose. Dialog / sheet buttons live inside a Popup composition tree that
 * dismisses when the dialog closes; without dispose-time unregistration the
 * handler outlives the visible button.
 */
actual fun Modifier.testableClickable(tag: String, text: String?, onClick: () -> Unit): Modifier = composed {
    if (TestAutomation.isEnabled()) {
        // THE REGISTERED HANDLER MUST TRACK RECOMPOSITION (CIRISClient#32).
        //
        // DisposableEffect is keyed on `tag`, so it runs once and the registry
        // kept the FIRST composition's `onClick` — a closure holding the screen
        // state of that moment. Every automation /click replayed that snapshot:
        // on the Android wizard, Test Connection ran with an EMPTY api key
        // while the field visibly held one, and answered success:true for it.
        //
        // Real mouse clicks were immune, because `.clickable { onClick() }`
        // reads the current lambda. That split is exactly why this survived
        // manual testing and only a programmatic click could expose it.
        //
        // Desktop fixed this and neither mobile platform inherited it — which
        // is #33's argument for one implementation instead of three.
        // rememberUpdatedState keeps single registration while the registered
        // thunk always calls the LATEST lambda.
        val currentOnClick by rememberUpdatedState(onClick)
        DisposableEffect(tag) {
            TestAutomation.registerClickHandler(tag) { currentOnClick() }
            onDispose {
                TestAutomation.unregisterClickHandler(tag)
                TestAutomation.unregisterElement(tag)
            }
        }
        this.testTag(tag)
            .clickable { onClick() }
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                TestAutomation.registerElement(
                    tag,
                    bounds.left.toInt(), bounds.top.toInt(),
                    bounds.width.toInt(), bounds.height.toInt(),
                    text
                )
            }
    } else {
        this.testTag(tag).clickable { onClick() }
    }
}

/**
 * Android implementation — registers click handler without adding clickable.
 *
 * Same DisposableEffect pattern as `testableClickable` so handlers attached
 * to internally-clickable widgets (e.g. switches, sliders) unregister on
 * dispose.
 */
actual fun Modifier.testableWithHandler(tag: String, onClick: () -> Unit): Modifier = composed {
    if (TestAutomation.isEnabled()) {
        // THE REGISTERED HANDLER MUST TRACK RECOMPOSITION (CIRISClient#32).
        //
        // DisposableEffect is keyed on `tag`, so it runs once and the registry
        // kept the FIRST composition's `onClick` — a closure holding the screen
        // state of that moment. Every automation /click replayed that snapshot:
        // on the Android wizard, Test Connection ran with an EMPTY api key
        // while the field visibly held one, and answered success:true for it.
        //
        // Real mouse clicks were immune, because `.clickable { onClick() }`
        // reads the current lambda. That split is exactly why this survived
        // manual testing and only a programmatic click could expose it.
        //
        // Desktop fixed this and neither mobile platform inherited it — which
        // is #33's argument for one implementation instead of three.
        // rememberUpdatedState keeps single registration while the registered
        // thunk always calls the LATEST lambda.
        val currentOnClick by rememberUpdatedState(onClick)
        DisposableEffect(tag) {
            TestAutomation.registerClickHandler(tag) { currentOnClick() }
            onDispose {
                TestAutomation.unregisterClickHandler(tag)
                TestAutomation.unregisterElement(tag)
            }
        }
        // ALSO register the element, matching the desktop actual.
        //
        // This registered only a click handler, so a tagged element was
        // clickable by the test server but absent from /tree — the same
        // modifier made a button assertable on desktop and invisible on
        // Android. A test that asserts a control is present before driving it
        // therefore passed on desktop and failed on Android against a screen
        // rendering perfectly. Found while driving the new debug block: the
        // click worked and the assertion did not.
        //
        // The docstring already promised this ("tracks an element AND registers
        // a click handler") — the implementation was what disagreed.
        this.testTag(tag).onGloballyPositioned { coords ->
            val bounds = coords.boundsInWindow()
            TestAutomation.registerElement(
                tag,
                bounds.left.toInt(), bounds.top.toInt(),
                bounds.width.toInt(), bounds.height.toInt(),
                null
            )
        }
    } else {
        this.testTag(tag)
    }
}
