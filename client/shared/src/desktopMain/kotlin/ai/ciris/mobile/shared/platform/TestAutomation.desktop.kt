package ai.ciris.mobile.shared.platform

import androidx.compose.foundation.clickable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Desktop implementation of test automation.
 * Delegates to TestAutomationServer when test mode is enabled.
 */
actual object TestAutomation {
    // Callback to the TestAutomationServer (set by desktop Main.kt)
    private var registerCallback: ((String, Int, Int, Int, Int, String?) -> Unit)? = null
    private var unregisterCallback: ((String) -> Unit)? = null
    private var setScreenCallback: ((String) -> Unit)? = null
    private var clearCallback: (() -> Unit)? = null
    private var enabledCheck: (() -> Boolean)? = null

    // Click handlers registered by testableClickable
    private val clickHandlers = ConcurrentHashMap<String, () -> Unit>()

    // Text input requests flow
    private val _textInputRequests = MutableStateFlow<TextInputRequest?>(null)
    actual val textInputRequests: StateFlow<TextInputRequest?> = _textInputRequests.asStateFlow()

    // File injection requests flow
    private val _fileInjectionRequests = MutableStateFlow<PickedFile?>(null)
    actual val fileInjectionRequests: StateFlow<PickedFile?> = _fileInjectionRequests.asStateFlow()

    /**
     * Configure callbacks from TestAutomationServer.
     * Called by desktop Main.kt when test mode is enabled.
     */
    fun configure(
        onRegister: (String, Int, Int, Int, Int, String?) -> Unit,
        onUnregister: (String) -> Unit,
        onSetScreen: (String) -> Unit,
        onClear: () -> Unit,
        isEnabled: () -> Boolean
    ) {
        registerCallback = onRegister
        unregisterCallback = onUnregister
        setScreenCallback = onSetScreen
        clearCallback = onClear
        enabledCheck = isEnabled
    }

    actual fun isEnabled(): Boolean {
        return enabledCheck?.invoke() ?: false
    }

    actual fun registerElement(testTag: String, x: Int, y: Int, width: Int, height: Int, text: String?) {
        registerCallback?.invoke(testTag, x, y, width, height, text)
    }

    actual fun unregisterElement(testTag: String) {
        unregisterCallback?.invoke(testTag)
    }

    actual fun setCurrentScreen(screen: String) {
        setScreenCallback?.invoke(screen)
    }

    actual fun clearElements() {
        clearCallback?.invoke()
    }

    actual fun registerClickHandler(testTag: String, handler: () -> Unit) {
        clickHandlers[testTag] = handler
    }

    actual fun unregisterClickHandler(testTag: String) {
        clickHandlers.remove(testTag)
    }

    actual fun triggerClick(testTag: String): Boolean {
        val handler = clickHandlers[testTag]
        return if (handler != null) {
            handler()
            true
        } else {
            false
        }
    }

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

    /**
     * Whether a click handler is currently registered for [testTag].
     * Mirrors `TestAutomationState.hasClickHandler` for desktop's local
     * handler map. Used by the desktop test server's `/click` and `/wait`
     * routes to surface popup / dialog buttons whose handlers are live but
     * whose layout positions never reached the main-window
     * `onGloballyPositioned` callback.
     */
    actual fun hasClickHandler(testTag: String): Boolean = clickHandlers.containsKey(testTag)

    actual fun requestTextInput(testTag: String, text: String, clearFirst: Boolean) {
        _textInputRequests.value = TextInputRequest(testTag, text, clearFirst)
    }

    actual fun clearTextInputRequest() {
        _textInputRequests.value = null
    }

    actual fun injectFile(name: String, mediaType: String, dataBase64: String, sizeBytes: Long) {
        _fileInjectionRequests.value = PickedFile(
            name = name,
            mediaType = mediaType,
            dataBase64 = dataBase64,
            sizeBytes = sizeBytes
        )
    }

    actual fun clearFileInjectionRequest() {
        _fileInjectionRequests.value = null
    }
}
