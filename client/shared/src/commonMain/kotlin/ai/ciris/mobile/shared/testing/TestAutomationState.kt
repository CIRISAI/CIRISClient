package ai.ciris.mobile.shared.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cross-platform shared state for test automation.
 * All platforms write to this, the test server reads from it.
 * Uses coroutine Mutex for thread safety (works on all K/MP targets).
 */
object TestAutomationState {

    // Element registry (guarded by elementsMutex for K/N safety)
    private val elements = mutableMapOf<String, ElementInfo>()
    private val clickHandlers = mutableMapOf<String, () -> Unit>()

    var currentScreen: String = "unknown"
    var isEnabled: Boolean = false

    /**
     * The node-vs-agent gate, as the app has actually derived it.
     *
     * `"unset"` is meaningful: it is the state a folded-but-unreachable brain
     * must leave the client in, pending retry. Written by `CIRISApp` wherever
     * `clientMode` is assigned; read by the test server's `/state`.
     */
    var clientMode: String = "unset"

    /** The node URL the app settled on -- local default, or a remote override. */
    var nodeUrl: String = ""

    // Window position offset (desktop only, for converting to screen coords)
    var windowX: Int = 0
    var windowY: Int = 0

    fun registerElement(testTag: String, x: Int, y: Int, width: Int, height: Int, text: String?) {
        val screenX = x + windowX
        val screenY = y + windowY
        elements[testTag] = ElementInfo(
            testTag = testTag,
            x = screenX, y = screenY,
            width = width, height = height,
            text = text,
            centerX = screenX + width / 2,
            centerY = screenY + height / 2,
            visible = isOnScreen(width, height),
        )
    }

    /**
     * What a harness could act on RIGHT NOW: on screen, with a click handler.
     * This is the list a failed `/wait` or `/click` prints, so a wrong tag is a
     * line of output rather than a log dig (CIRISClient#33, #39).
     */
    fun onScreenDrivable(): List<String> =
        elements.values.filter { it.visible && clickHandlers.containsKey(it.testTag) }.map { it.testTag }.sorted()

    /** Composed and positioned, but entirely outside the window. */
    fun isOffScreen(testTag: String): Boolean = elements[testTag]?.visible == false

    fun unregisterElement(testTag: String) {
        elements.remove(testTag)
        clickHandlers.remove(testTag)
    }

    fun clearElements() {
        elements.clear()
    }

    fun getElement(testTag: String): ElementInfo? {
        return elements[testTag]
    }

    fun getAllElements(): Map<String, ElementInfo> {
        return elements.toMap()
    }

    fun registerClickHandler(testTag: String, handler: () -> Unit) {
        clickHandlers[testTag] = handler
    }

    fun unregisterClickHandler(testTag: String) {
        clickHandlers.remove(testTag)
    }

    fun triggerClick(testTag: String): Boolean {
        val handler = clickHandlers[testTag] ?: return false
        handler()
        return true
    }

    /**
     * Whether a click handler is currently registered for [testTag].
     *
     * Used to surface popup / dialog buttons whose `testableClickable` modifier
     * has composed (registering the handler) but whose `onGloballyPositioned`
     * callback hasn't fired yet — Compose Multiplatform's AlertDialog and
     * ModalBottomSheet render content in a separate Popup window, and the main
     * window's layout pass doesn't reliably deliver position events into that
     * tree. Element-by-position lookups miss those buttons; click-handler
     * lookups don't.
     */
    fun hasClickHandler(testTag: String): Boolean = clickHandlers.containsKey(testTag)

    // Text input
    private val _textInputRequests = MutableStateFlow<TextInputRequest?>(null)
    val textInputRequests: StateFlow<TextInputRequest?> = _textInputRequests

    fun requestTextInput(testTag: String, text: String, clearFirst: Boolean) {
        _textInputRequests.value = TextInputRequest(testTag, text, clearFirst)
    }

    fun clearTextInputRequest() {
        _textInputRequests.value = null
    }

    // File injection
    private val _fileInjectionRequests = MutableStateFlow<ai.ciris.mobile.shared.platform.PickedFile?>(null)
    val fileInjectionRequests: StateFlow<ai.ciris.mobile.shared.platform.PickedFile?> = _fileInjectionRequests

    fun injectFile(name: String, mediaType: String, dataBase64: String, sizeBytes: Long) {
        _fileInjectionRequests.value = ai.ciris.mobile.shared.platform.PickedFile(
            name = name,
            mediaType = mediaType,
            dataBase64 = dataBase64,
            sizeBytes = sizeBytes
        )
    }

    fun clearFileInjectionRequest() {
        _fileInjectionRequests.value = null
    }

    // Scroll requests
    //
    // A REQUEST NEEDS SOMETHING THAT CAN SCROLL. This flow existed, and
    // `handleScroll` set it and answered success:true, and NOTHING IN THE TREE
    // COLLECTED IT -- on any platform. So `/scroll` moved nothing while
    // reporting that it had, which only became load-bearing when 0.5.206 made
    // /click and /input refuse off-screen elements and a harness started
    // scrolling to recover (CIRISClient#33). Same class as #28 and #31: an
    // endpoint answering for something it did not do.
    //
    // `rememberTestableScrollState` registers the screen's scroll state here
    // and dispatches; the ACTIVE registrant is the most recent one, so a
    // scrollable dialog over a scrollable screen does not double-apply.
    private val _scrollRequests = MutableStateFlow<ScrollRequest?>(null)
    val scrollRequests: StateFlow<ScrollRequest?> = _scrollRequests

    private val scrollables = mutableListOf<Long>()
    private var nextScrollToken = 1L

    /** Claim a token for a scrollable that is now composed. */
    fun registerScrollable(): Long {
        val token = nextScrollToken++
        scrollables.add(token)
        return token
    }

    fun unregisterScrollable(token: Long) {
        scrollables.remove(token)
    }

    /** The most recently composed scrollable owns the dispatch. */
    fun isActiveScrollable(token: Long): Boolean = scrollables.lastOrNull() == token

    /** Whether anything on screen can act on a scroll request at all. */
    fun hasScrollable(): Boolean = scrollables.isNotEmpty()

    fun requestScroll(testTag: String, direction: String, amount: Int) {
        _scrollRequests.value = ScrollRequest(testTag, direction, amount)
    }

    fun clearScrollRequest() {
        _scrollRequests.value = null
    }
}

// Re-use the TextInputRequest from platform package
typealias TextInputRequest = ai.ciris.mobile.shared.platform.TextInputRequest
