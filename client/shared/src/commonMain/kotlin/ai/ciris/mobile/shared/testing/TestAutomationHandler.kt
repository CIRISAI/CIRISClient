package ai.ciris.mobile.shared.testing

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * Pure handler logic for test automation endpoints.
 * Platform-independent — operates on TestAutomation shared state.
 * Used by Ktor server (JVM) and POSIX server (iOS).
 */
object TestAutomationHandler {

    /** How long a single /input may wait for a field to apply it. */
    private const val APPLY_TIMEOUT_MS = 3_000L
    private const val APPLY_POLL_MS = 20L

    /**
     * Wait until no text-input request is outstanding, i.e. every screen that
     * was going to collect one has, and cleared it. False on timeout.
     */
    private suspend fun awaitFlowIdle(
        ta: ai.ciris.mobile.shared.platform.TestAutomation,
    ): Boolean {
        var waited = 0L
        while (ta.textInputRequests.value != null && waited < APPLY_TIMEOUT_MS) {
            kotlinx.coroutines.delay(APPLY_POLL_MS)
            waited += APPLY_POLL_MS
        }
        return ta.textInputRequests.value == null
    }

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    fun getJson(): Json = json

    fun handleHealth(): HealthResponse {
        return HealthResponse(status = "ok", testMode = true)
    }

    fun handleTree(): TreeResponse {
        val screen = TestAutomationState.currentScreen
        val elements = TestAutomationState.getAllElements().values.toList().map { withDrivability(it) }
        return TreeResponse(screen = screen, elements = elements, count = elements.size)
    }

    /** Stamp an element with what automation can actually do to it. */
    private fun withDrivability(e: ElementInfo): ElementInfo {
        val ta = ai.ciris.mobile.shared.platform.TestAutomation
        return e.copy(
            canClick = ta.hasClickHandler(e.testTag),
            canInput = ta.hasInputSink(e.testTag),
            // The field's CURRENT value, so /input can be verified from outside
            // (#31). `text` already carries a label for display elements, so a
            // separate field keeps the two meanings apart.
            inputValue = ta.inputValue(e.testTag),
        )
    }

    /**
     * THE INVARIANT, AS A PRE-FLIGHT: everything interactive on screen is drivable.
     *
     * `btn_*`/`chip_*`/`menu_*` need a click handler; `input_*`/`quick_input_*`/
     * `field_*` need a listening text sink. Display tags — `txt_`, `text_`,
     * `screen_`, `card_`, `dialog_` — are exempt, because being readable IS
     * their job.
     *
     * Shared, so the answer is identical on all five platforms. A harness that
     * can only ask this on desktop cannot depend on it (CIRISClient#30).
     */
    fun handleUndrivable(): UndrivableResponse {
        val ta = ai.ciris.mobile.shared.platform.TestAutomation
        val offenders = TestAutomationState.getAllElements().values
            .map { it.testTag }
            .filter { t ->
                when {
                    t.startsWith("btn_") || t.startsWith("chip_") || t.startsWith("menu_") ->
                        !ta.hasClickHandler(t)
                    t.startsWith("input_") || t.startsWith("quick_input_") || t.startsWith("field_") ->
                        !ta.hasInputSink(t)
                    else -> false
                }
            }
            .sorted()
        return UndrivableResponse(
            screen = TestAutomationState.currentScreen,
            undrivable = offenders,
            count = offenders.size,
        )
    }

    /** The app's own account of its gates — see [StateResponse]. */
    fun handleState(): StateResponse = StateResponse(
        screen = TestAutomationState.currentScreen,
        testMode = true,
        clientMode = TestAutomationState.clientMode,
        nodeUrl = TestAutomationState.nodeUrl,
    )

    fun handleScreen(): ScreenResponse {
        return ScreenResponse(screen = TestAutomationState.currentScreen)
    }

    fun handleClick(request: ClickRequest): ActionResponse {
        val element = TestAutomationState.getElement(request.testTag)

        // Try the programmatic click handler FIRST, before requiring an
        // element-position entry. `testableClickable` registers the handler the
        // moment its modifier composes — popup / dialog content composes its
        // modifiers and registers handlers even though their layout positions
        // never reach the main-window `onGloballyPositioned` callback. Gating
        // on a position entry would 404 the click for handlers that are live
        // and dispatchable.
        val clicked = TestAutomationState.triggerClick(request.testTag)
        if (clicked) {
            return ActionResponse(
                success = true,
                element = request.testTag,
                action = "click",
                coordinates = element?.let { "${it.centerX},${it.centerY}" }
            )
        }

        if (element == null) {
            return ActionResponse(success = false, error = "Element not found: ${request.testTag}")
        }
        // Element is positioned but has no programmatic handler (e.g. a plain
        // `testable()` text). Caller can fall back to a coordinate-based click.
        return ActionResponse(
            success = false,
            error = "No click handler for: ${request.testTag}",
            element = request.testTag,
            coordinates = "${element.centerX},${element.centerY}"
        )
    }

    suspend fun handleInput(request: InputRequest): ActionResponse {
        val element = TestAutomationState.getElement(request.testTag)
            ?: return ActionResponse(success = false, error = "Element not found: ${request.testTag}")

        // A TAGGED FIELD IS NOT NECESSARILY A LISTENING ONE.
        //
        // Text entry is collected per screen, so a field can carry an input_*
        // tag with nothing subscribed. This used to fire the request and answer
        // success:true regardless — telling a harness it had typed something it
        // had not. Desktop stopped doing that in 0.5.197; mobile kept doing it,
        // which is why Android and iOS could report a green setup step that had
        // entered nothing (CIRISClient#28).
        if (!ai.ciris.mobile.shared.platform.TestAutomation.hasInputSink(request.testTag)) {
            return ActionResponse(
                success = false,
                element = request.testTag,
                action = "input",
                error = "no text sink is listening for ${request.testTag}; the field is " +
                    "tagged but not drivable (see GET /undrivable)"
            )
        }

        val ta = ai.ciris.mobile.shared.platform.TestAutomation

        // ACKNOWLEDGE ON APPLY, NOT ON POST (CIRISClient#31).
        //
        // requestTextInput drops the request into a CONFLATING StateFlow and
        // this used to return success:true immediately. Two consequences, both
        // observed by the CIRISAgent gate:
        //
        //   1. A second request replaces a first the field has not collected
        //      yet. Typing username, password and confirm back-to-back left the
        //      password applied to nothing while all three calls reported
        //      success — the product then said "Password is required".
        //   2. Even alone, success meant "posted", never "changed".
        //
        // Every screen's dispatch calls clearTextInputRequest() after applying,
        // so the flow returning to null IS the apply signal. Waiting for it
        // serialises the requests (fixing 1) and makes the acknowledgement mean
        // what a caller assumes it means (fixing 2) — without touching any of
        // the six screens that collect.
        //
        // A timeout is reported as a failure, because "I could not confirm this
        // landed" is not success. That is the whole lesson of #30 and of the
        // handleInput sink check.
        if (!awaitFlowIdle(ta)) {
            return ActionResponse(
                success = false, element = request.testTag, action = "input",
                error = "a previous /input was still unapplied after ${APPLY_TIMEOUT_MS}ms; " +
                    "posting now would silently replace it",
            )
        }

        ta.requestTextInput(request.testTag, request.text, request.clearFirst)

        if (!awaitFlowIdle(ta)) {
            return ActionResponse(
                success = false, element = request.testTag, action = "input",
                error = "the field did not apply the text within ${APPLY_TIMEOUT_MS}ms; " +
                    "it is registered as a sink but its dispatch did not run",
            )
        }

        // Record what the field now holds, so /element and /tree can be read
        // back. Without this a consumer had no witness but a screenshot.
        ta.setInputValue(request.testTag, request.text)

        return ActionResponse(
            success = true,
            element = request.testTag,
            action = "input",
            text = request.text
        )
    }

    suspend fun handleWait(request: WaitRequest): ActionResponse {
        val timeoutMs = request.timeoutMs ?: 5000
        val startTime = currentTimeMs()

        while (currentTimeMs() - startTime < timeoutMs) {
            // Element-position OR click-handler is a positive signal. Dialog /
            // sheet buttons register click handlers when their modifiers
            // compose (before the popup window's layout pass reaches them), so
            // `wait_for_element("btn_mode_confirm")` resolves the moment the
            // confirm-button's modifier composes inside the dialog content —
            // it does NOT have to wait for a position entry that may never
            // arrive through the popup's separate layout tree.
            if (TestAutomationState.getElement(request.testTag) != null
                || TestAutomationState.hasClickHandler(request.testTag)) {
                return ActionResponse(success = true, element = request.testTag, action = "wait")
            }
            delay(100)
        }

        return ActionResponse(
            success = false,
            error = "Element not found within ${timeoutMs}ms: ${request.testTag}"
        )
    }

    fun handleGetElement(testTag: String): ElementInfo? {
        // Stamped exactly as /tree does: one element must never disagree with
        // the list it appears in.
        return TestAutomationState.getElement(testTag)?.let { withDrivability(it) }
    }

    fun handleScroll(request: ScrollRequest): ActionResponse {
        TestAutomationState.requestScroll(request.testTag, request.direction, request.amount)
        return ActionResponse(
            success = true,
            element = request.testTag,
            action = "scroll",
            text = "${request.direction}:${request.amount}"
        )
    }

    /**
     * Combined action + view handler.
     * Performs action, waits, returns updated UI state.
     * Reduces 3 API calls (action + sleep + tree) to 1.
     */
    suspend fun handleActAndView(request: ActAndViewRequest): ActAndViewResponse {
        // 1. Perform the action
        val actionResult = when (request.action.lowercase()) {
            "click" -> handleClick(ClickRequest(request.testTag))
            "input" -> handleInput(InputRequest(request.testTag, request.text ?: "", request.clearFirst))
            "wait" -> handleWait(WaitRequest(request.testTag, request.waitMs))
            else -> ActionResponse(success = false, error = "Unknown action: ${request.action}")
        }

        // 2. Wait for UI to settle
        if (request.waitMs > 0 && request.action.lowercase() != "wait") {
            delay(request.waitMs.toLong())
        }

        // 3. Get current screen
        val screen = TestAutomationState.currentScreen

        // 4. Get elements (optionally filtered)
        val allElements = TestAutomationState.getAllElements().values.toList()
        val filteredElements = if (request.filterTags.isNullOrEmpty()) {
            allElements
        } else {
            allElements.filter { element ->
                request.filterTags.any { pattern ->
                    element.testTag.contains(pattern, ignoreCase = true)
                }
            }
        }

        return ActAndViewResponse(
            actionResult = actionResult,
            screen = screen,
            elements = filteredElements,
            elementCount = filteredElements.size
        )
    }

    // Platform-independent time
    private fun currentTimeMs(): Long {
        return kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    }
}
