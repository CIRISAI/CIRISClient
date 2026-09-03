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
 * iOS implementation — tracks position when test mode enabled, otherwise just testTag.
 */
actual fun Modifier.testable(tag: String, text: String?): Modifier {
    return if (TestAutomation.isEnabled()) {
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
 * iOS implementation — tracks position + registers click handler when test mode enabled.
 */
actual fun Modifier.testableClickable(tag: String, text: String?, onClick: () -> Unit): Modifier {
    return if (TestAutomation.isEnabled()) {
        composed {
        // REGISTER ONCE, TRACK RECOMPOSITION, UNREGISTER ON DISPOSE (CIRISClient#32).
        //
        // This called registerClickHandler directly in the composable body, so
        // it re-registered on EVERY recomposition and never unregistered at
        // all: a handler outlived the screen that owned it, and a /click after
        // navigation could run a closure belonging to a composable no longer on
        // screen. Android had the sibling defect — DisposableEffect keyed on
        // `tag` only, freezing the first composition's lambda, which ran Test
        // Connection with an empty api key.
        //
        // Neither is what desktop does. Three implementations of one idea is
        // how they diverged; #33 is the fix for that. This makes iOS behave
        // like desktop in the meantime: single registration, latest lambda,
        // cleaned up when the composable leaves.
            val currentOnClick by rememberUpdatedState(onClick)
            DisposableEffect(tag) {
                TestAutomation.registerClickHandler(tag) { currentOnClick() }
                onDispose {
                    TestAutomation.unregisterClickHandler(tag)
                    TestAutomation.unregisterElement(tag)
                }
            }
            this
        }
            .testTag(tag)
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
 * iOS implementation — registers click handler without adding clickable.
 */
actual fun Modifier.testableWithHandler(tag: String, onClick: () -> Unit): Modifier {
    if (TestAutomation.isEnabled()) {
        // ALSO register the element, matching the Android and desktop actuals.
        //
        // /tree is populated from the element registry, not from testTag, so
        // registering only a handler made a control invokable but INVISIBLE to
        // automation. Switching call sites from `testable` to
        // `testableWithHandler` — done to make the reset flow and the new
        // diagnostics buttons drivable — therefore removed those same controls
        // from the iOS tree, trading one half of automation for the other.
        //
        // The identical defect existed on Android and was fixed there; this is
        // its twin, and it went unnoticed because iOS has no build on the host
        // that found the Android one.
        return composed {
        // REGISTER ONCE, TRACK RECOMPOSITION, UNREGISTER ON DISPOSE (CIRISClient#32).
        //
        // This called registerClickHandler directly in the composable body, so
        // it re-registered on EVERY recomposition and never unregistered at
        // all: a handler outlived the screen that owned it, and a /click after
        // navigation could run a closure belonging to a composable no longer on
        // screen. Android had the sibling defect — DisposableEffect keyed on
        // `tag` only, freezing the first composition's lambda, which ran Test
        // Connection with an empty api key.
        //
        // Neither is what desktop does. Three implementations of one idea is
        // how they diverged; #33 is the fix for that. This makes iOS behave
        // like desktop in the meantime: single registration, latest lambda,
        // cleaned up when the composable leaves.
            val currentOnClick by rememberUpdatedState(onClick)
            DisposableEffect(tag) {
                TestAutomation.registerClickHandler(tag) { currentOnClick() }
                onDispose {
                    TestAutomation.unregisterClickHandler(tag)
                    TestAutomation.unregisterElement(tag)
                }
            }
            this
        }.testTag(tag).onGloballyPositioned { coords ->
            val bounds = coords.boundsInWindow()
            TestAutomation.registerElement(
                tag,
                bounds.left.toInt(), bounds.top.toInt(),
                bounds.width.toInt(), bounds.height.toInt(),
                null
            )
        }
    }
    return this.testTag(tag)
}
