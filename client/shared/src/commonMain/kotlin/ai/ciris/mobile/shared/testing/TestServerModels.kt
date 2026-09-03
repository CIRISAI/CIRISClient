package ai.ciris.mobile.shared.testing

import kotlinx.serialization.Serializable

/**
 * Shared data models for the test automation HTTP server.
 * Used by all platforms (Desktop, iOS, Android).
 */

@Serializable
data class ElementInfo(
    val testTag: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val text: String? = null,
    val centerX: Int,
    val centerY: Int,
    /**
     * Whether automation can DRIVE this element, not merely see it.
     *
     * A tag proves visibility and nothing more. Desktop has carried these since
     * 0.5.197; mobile did not, so a harness could read /tree on Android or iOS
     * and have no way to tell a driveable control from a decorative one
     * (CIRISClient#28).
     */
    val canClick: Boolean = false,
    /** Whether a field is listening for /input, rather than merely tagged. */
    val canInput: Boolean = false,
    /**
     * What a text field currently holds, or null for anything that is not one.
     *
     * `/element` carried `text: null` for input fields, so no consumer could
     * confirm that an `/input` had actually landed — the only witness was the
     * app's own validation UI in a screenshot (CIRISClient#31).
     */
    val inputValue: String? = null
)

@Serializable
data class HealthResponse(val status: String, val testMode: Boolean)

@Serializable
data class TreeResponse(val screen: String, val elements: List<ElementInfo>, val count: Int)

@Serializable
data class ScreenResponse(val screen: String)

/**
 * The app's own account of the gates a walk-test needs to assert.
 *
 * `/screen` and `/tree` can only tell a harness what is DRAWN, and the two
 * things a client of a federation node must get right are not drawings: which
 * node it is talking to, and whether that node is carrying a brain. Inferring
 * the mode from which widgets happen to be on screen makes the assertion a
 * restatement of the layout -- it goes green for a client that renders agent
 * affordances against a bare node, which is the bug.
 *
 * [clientMode] is `ClientMode.name`, or `"unset"` while the probe is still
 * undetermined -- which is a REAL state, not a missing value: a folded brain
 * that is not answering must leave the gate unset and be retried, never latched
 * (CIRISServer#390). A harness has to be able to see the difference.
 */
@Serializable
data class StateResponse(
    val screen: String,
    val testMode: Boolean,
    val clientMode: String,
    val nodeUrl: String
)

/**
 * Tagged elements on the current screen that automation can SEE but not DRIVE.
 *
 * The pre-flight a harness runs before driving a screen, so it fails with a
 * list rather than discovering gaps one flaky control at a time. Desktop has
 * served this since 0.5.197; adding it here puts it on all five platforms,
 * which is what makes it safe to depend on (CIRISClient#28, #30).
 */
@Serializable
data class UndrivableResponse(
    val screen: String,
    val undrivable: List<String>,
    val count: Int
)

@Serializable
data class ClickRequest(val testTag: String)

@Serializable
data class InputRequest(val testTag: String, val text: String, val clearFirst: Boolean = true)

@Serializable
data class NavigateRequest(val screen: String)

@Serializable
data class WaitRequest(val testTag: String, val timeoutMs: Int? = 5000)

@Serializable
data class ScreenshotRequest(val path: String, val format: String? = "png")

@Serializable
data class ScrollRequest(val testTag: String, val direction: String = "down", val amount: Int = 300)

@Serializable
data class ActionResponse(
    val success: Boolean,
    val element: String? = null,
    val action: String? = null,
    val coordinates: String? = null,
    val text: String? = null,
    val screen: String? = null,
    val error: String? = null
)

/**
 * Combined action + view request.
 * Performs an action, waits, then returns the updated UI state.
 * Reduces 3 API calls to 1.
 */
@Serializable
data class ActAndViewRequest(
    val action: String,                    // "click", "input", "wait"
    val testTag: String,                   // Target element
    val text: String? = null,              // For input action
    val clearFirst: Boolean = true,        // For input action
    val waitMs: Int = 500,                 // Wait after action before reading tree
    val filterTags: List<String>? = null   // Only return elements matching these patterns (substring match)
)

@Serializable
data class ActAndViewResponse(
    val actionResult: ActionResponse,
    val screen: String,
    val elements: List<ElementInfo>,
    val elementCount: Int
)
