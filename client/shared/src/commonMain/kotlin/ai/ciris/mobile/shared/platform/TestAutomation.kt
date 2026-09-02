package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * Modifier that makes an element trackable for test automation.
 * Combines testTag with position tracking.
 *
 * On desktop with test mode enabled, this reports element position.
 * On other platforms, this just applies testTag.
 */
expect fun Modifier.testable(tag: String, text: String? = null): Modifier

/**
 * Modifier that makes an element both trackable AND clickable for test automation.
 * The onClick handler can be triggered programmatically by the test server.
 *
 * On desktop with test mode enabled, this registers the click handler.
 * On other platforms, this just applies testTag and clickable.
 */
expect fun Modifier.testableClickable(tag: String, text: String? = null, onClick: () -> Unit): Modifier

/**
 * Modifier that tracks an element AND registers a click handler WITHOUT adding clickable.
 * Use this for components that already handle clicks internally (like DropdownMenuItem).
 *
 * The onClick handler can be triggered programmatically by the test server,
 * but normal user clicks are handled by the component's built-in onClick.
 */
expect fun Modifier.testableWithHandler(tag: String, onClick: () -> Unit): Modifier
