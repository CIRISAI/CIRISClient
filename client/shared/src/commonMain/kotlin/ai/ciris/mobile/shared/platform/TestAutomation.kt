package ai.ciris.mobile.shared.platform

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.StateFlow

/**
 * Data class for pending text input requests.
 */
data class TextInputRequest(
    val testTag: String,
    val text: String,
    val clearFirst: Boolean = true
)

/**
 * Test automation support for UI element tracking.
 *
 * On desktop with test mode enabled, this tracks element positions
 * for the TestAutomationServer. On other platforms, this is a no-op.
 */
expect object TestAutomation {
    /**
     * Check if test mode is enabled.
     */
    fun isEnabled(): Boolean

    /**
     * Register a UI element for automation.
     * Called when elements are positioned on screen.
     */
    fun registerElement(testTag: String, x: Int, y: Int, width: Int, height: Int, text: String?)

    /**
     * Unregister a UI element.
     * Called when elements leave composition.
     */
    fun unregisterElement(testTag: String)

    /**
     * Update the current screen name.
     */
    fun setCurrentScreen(screen: String)

    /**
     * Clear all registered elements (on screen transition).
     */
    fun clearElements()

    /**
     * Register a click handler for an element.
     * Called by testableClickable modifier.
     */
    fun registerClickHandler(testTag: String, handler: () -> Unit)

    /**
     * Unregister a click handler.
     */
    fun unregisterClickHandler(testTag: String)

    /**
     * Trigger a click on an element (called by test server).
     * Returns true if handler was found and invoked.
     */
    fun triggerClick(testTag: String): Boolean

    /**
     * Does [testTag] have a registered click handler — i.e. can automation
     * actually DRIVE it, rather than guess at its pixels?
     *
     * THE INVARIANT THIS EXISTS TO MAKE CHECKABLE.
     *
     * A tag alone proves an element is VISIBLE, not that it is drivable.
     * `testable()` sets a tag and registers nothing, so `/click` fell back to a
     * blind mouse click at fixed coordinates — which is luck about DPI and
     * window size. It worked on Linux and Windows and missed on macOS five
     * times running, and a miss produced no verdict, no log line, and a
     * screenshot identical to a working screen (CIRISClient#28).
     *
     * That was one of 61 `btn_*` tags with no handler. Point-fixing them does
     * not hold, because nothing made the next one detectable. This makes
     * "tagged but not drivable" a fact the harness can assert on.
     */
    fun hasClickHandler(testTag: String): Boolean

    /**
     * Tags that have declared themselves text-drivable by collecting
     * [textInputRequests].
     *
     * Text entry is consumed per screen by hand, so a field can carry an
     * `input_*` tag with nothing listening — and `/input` answered
     * `success: true` after a fixed delay regardless, reporting text that was
     * never typed. A field registers here when it is listening.
     */
    fun registerInputSink(testTag: String)

    fun unregisterInputSink(testTag: String)

    fun hasInputSink(testTag: String): Boolean

    /**
     * Record what the field currently HOLDS, so `/input` can be verified from
     * outside (CIRISClient#31).
     *
     * `/input` answers on POST, not on apply: it drops a request into a
     * conflating StateFlow and returns success:true before any field has
     * collected it. Three inputs in quick succession can leave the first two
     * applied to nothing while every call reports success — which is what the
     * CIRISAgent gate hit, typing username/password/confirm and then reading
     * "Password is required" from the product's own validation.
     *
     * No consumer could detect that, because `/element` carried `text: null`
     * for input fields: the only witness was a screenshot. A field that reports
     * its value makes "acknowledged but not applied" a single poll instead.
     */
    fun setInputValue(testTag: String, value: String)

    /** What the field holds now, or null if it has never reported. */
    fun inputValue(testTag: String): String?

    /**
     * Flow of pending text input requests.
     * Text fields should observe this and handle requests for their tag.
     */
    val textInputRequests: StateFlow<TextInputRequest?>

    /**
     * Request text input to an element (called by test server).
     */
    fun requestTextInput(testTag: String, text: String, clearFirst: Boolean)

    /**
     * Clear a text input request (called after handling).
     */
    fun clearTextInputRequest()

    /**
     * Flow of pending file injection requests (for test automation).
     * InteractViewModel observes this to add injected files as attachments.
     */
    val fileInjectionRequests: StateFlow<PickedFile?>

    /**
     * Inject a file as an attachment (called by test server).
     */
    fun injectFile(name: String, mediaType: String, dataBase64: String, sizeBytes: Long)

    /**
     * Clear a file injection request (called after handling).
     */
    fun clearFileInjectionRequest()
}

/**
 * ONE IMPLEMENTATION, NOT THREE (CIRISClient#33).
 *
 * These were `expect` with four `actual`s, and every one of them was pure
 * Compose over `TestAutomationState` — which is already common. Nothing in them
 * was ever platform-specific, and the cost of pretending otherwise was three
 * defects in one month, each a copy missing a fix another copy already had:
 *
 *   #32  `testableClickable` kept the FIRST composition's onClick on Android
 *        and iOS, because their DisposableEffect was keyed on the tag alone.
 *        Desktop had fixed it with `rememberUpdatedState`. A programmatic click
 *        replayed a closure captured before the user typed, so Test Connection
 *        ran with an empty api key while the field visibly held one — and real
 *        mouse clicks were immune, which is why it survived manual testing.
 *
 *   #30  iOS's plain `testable()` registered on layout and never unregistered,
 *        so `/tree` described a screen that was not on screen. The fix for #32
 *        went into the two clickable variants here and not this one: one rule,
 *        four copies, a fix landing in two of the three that needed it.
 *
 * A gate finds these one platform and one release at a time. A single
 * implementation means the next fix is the last one.
 *
 * WHY THIS IS SAFE ON EVERY TARGET
 *
 * The only platform-specific thing is `TestAutomation.isEnabled()`, which stays
 * an `expect`. wasmJs returns `false` unconditionally — it serves no automation
 * server — so the common body no-ops there exactly as its hand-written actual
 * did, rather than registering elements nothing can read.
 *
 * ONE DELIBERATE BEHAVIOUR CHANGE: desktop measured with
 * `positionInWindow() + size`, Android and iOS with `boundsInWindow()`. They
 * agree for a fully visible element and differ for a clipped one, where
 * `boundsInWindow` reports the rect actually ON SCREEN. That is the more
 * correct answer for a click target — an unclipped rect can put a coordinate
 * outside the window for a partially scrolled row — and it is what two of the
 * three already did. Desktop's `/mouse-click` is the only consumer of these
 * coordinates, and `e2e-desktop` exercises it on every push.
 */
private fun Modifier.trackPosition(tag: String, text: String?): Modifier =
    this.onGloballyPositioned { coords ->
        val bounds = coords.boundsInWindow()
        TestAutomation.registerElement(
            tag,
            bounds.left.toInt(), bounds.top.toInt(),
            bounds.width.toInt(), bounds.height.toInt(),
            text,
        )
    }

/**
 * Track an element's position for automation. Tag only when test mode is off.
 *
 * Registered on composition and UNREGISTERED ON DISPOSE. Without the disposal an
 * entry outlives the composable that owned it and `/tree` reports a control that
 * is not on screen — worse than a missing entry, because nothing can tell a
 * ghost from a live control (#30).
 */
fun Modifier.testable(tag: String, text: String? = null): Modifier = composed {
    if (!TestAutomation.isEnabled()) return@composed this.testTag(tag)
    DisposableEffect(tag) {
        onDispose { TestAutomation.unregisterElement(tag) }
    }
    this.testTag(tag).trackPosition(tag, text)
}

/**
 * Track an element AND register a programmatic click handler, plus `clickable`.
 *
 * `rememberUpdatedState` is the whole of #32: the DisposableEffect is keyed on
 * the tag so it runs once, and without the indirection the handler it registered
 * would keep calling the FIRST composition's lambda forever.
 */
fun Modifier.testableClickable(
    tag: String,
    text: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    if (!TestAutomation.isEnabled()) return@composed this.testTag(tag).clickable { onClick() }
    val currentOnClick by rememberUpdatedState(onClick)
    DisposableEffect(tag) {
        TestAutomation.registerClickHandler(tag) { currentOnClick() }
        onDispose {
            TestAutomation.unregisterClickHandler(tag)
            TestAutomation.unregisterElement(tag)
        }
    }
    this.testTag(tag).clickable { onClick() }.trackPosition(tag, text)
}

/**
 * Track an element and register a handler WITHOUT adding `clickable`.
 *
 * For components that already handle their own clicks (Button, DropdownMenuItem)
 * — adding another `clickable` would give them two.
 */
fun Modifier.testableWithHandler(tag: String, onClick: () -> Unit): Modifier = composed {
    if (!TestAutomation.isEnabled()) return@composed this.testTag(tag)
    val currentOnClick by rememberUpdatedState(onClick)
    DisposableEffect(tag) {
        TestAutomation.registerClickHandler(tag) { currentOnClick() }
        onDispose {
            TestAutomation.unregisterClickHandler(tag)
            TestAutomation.unregisterElement(tag)
        }
    }
    this.testTag(tag).trackPosition(tag, null)
}
