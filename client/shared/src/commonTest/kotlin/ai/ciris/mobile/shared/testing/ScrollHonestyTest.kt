package ai.ciris.mobile.shared.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/scroll` answers for whether the screen MOVED (CIRISClient#33).
 *
 * It used to post to a StateFlow that nothing in the tree collected — on every
 * platform — and return `success: true` regardless. That was invisible until
 * 0.5.206 made `/click` and `/input` refuse off-screen elements: a harness
 * recovering by scrolling was told the scroll worked, met the identical
 * refusal, and had every reason to think the app was broken.
 */
class ScrollHonestyTest {

    private val handler = TestAutomationHandler

    @BeforeTest fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TestAutomationState.clearElements()
        TestAutomationState.clearScrollRequest()
    }

    @AfterTest fun tearDown() {
        TestAutomationState.clearScrollRequest()
        TestAutomationState.clearElements()
        Dispatchers.resetMain()
    }

    @Test
    fun a_screen_that_cannot_scroll_refuses_instead_of_claiming_success() = runTest {
        // THE DEFECT. No registered scrollable: the request would move nothing.
        val r = handler.handleScroll(ScrollRequest("input_password", "down", 300))
        assertFalse(r.success, "reporting success for a scroll nothing can perform is the #28/#31 defect")
        assertTrue("can scroll" in (r.error ?: ""), r.error ?: "")
        assertEquals(null, TestAutomationState.scrollRequests.value, "a refused scroll leaves no request behind")
    }

    @Test
    fun a_registered_scrollable_that_applies_the_request_succeeds() = runTest {
        val token = TestAutomationState.registerScrollable()
        try {
            // Stands in for rememberTestableScrollState's collector: move,
            // record what moved, then clear — clearing IS the acknowledgement
            // handleScroll waits on.
            TestAutomationState.setScrollableCapacity(token, 2000)
            backgroundScope.launch {
                TestAutomationState.scrollRequests.collect { request ->
                    if (request != null) {
                        TestAutomationState.recordScrollOutcome(0, request.amount, 2000)
                        TestAutomationState.clearScrollRequest()
                    }
                }
            }
            runCurrent()
            val r = handler.handleScroll(ScrollRequest("input_password", "down", 300))
            assertTrue(r.success, r.error ?: "")
            assertEquals("down:300 moved 0\u2192300 of 2000", r.text)
        } finally {
            TestAutomationState.unregisterScrollable(token)
        }
    }

    @Test
    fun a_scrollable_that_never_applies_is_a_failure_not_a_success() = runTest {
        // Registered but inert — which is what every platform was, silently.
        val token = TestAutomationState.registerScrollable()
        try {
            val r = handler.handleScroll(ScrollRequest("input_password", "down", 300))
            assertFalse(r.success, "unapplied must not read as applied")
            assertTrue("did not apply" in (r.error ?: ""), r.error ?: "")
        } finally {
            TestAutomationState.unregisterScrollable(token)
            TestAutomationState.clearScrollRequest()
        }
    }

    /** A consumer that moves by [moved] and reports [max] as its capacity. */
    private fun TestScope.consumer(token: Long, moved: Int, max: Int) {
        TestAutomationState.setScrollableCapacity(token, max)
        backgroundScope.launch {
            TestAutomationState.scrollRequests.collect { request ->
                if (request != null) {
                    TestAutomationState.recordScrollOutcome(0, moved, max)
                    TestAutomationState.clearScrollRequest()
                }
            }
        }
    }

    @Test
    fun a_scrollable_with_no_overflow_reports_that_it_did_not_move() = runTest {
        // THE #44 DEFECT. The request is consumed, the screen stays where it
        // was, and the old contract answered 200 — byte-identical to a scroll
        // that worked. A harness could only infer the difference from a later
        // failure.
        val token = TestAutomationState.registerScrollable()
        try {
            consumer(token, moved = 0, max = 0)
            runCurrent()
            val r = handler.handleScroll(ScrollRequest("input_username", "down", 300))
            assertFalse(r.success, "consumed is not moved")
            assertTrue("NO overflow" in (r.error ?: ""), r.error ?: "")
            assertEquals("moved 0\u21920 of 0", r.text)
        } finally {
            TestAutomationState.unregisterScrollable(token)
            TestAutomationState.clearScrollRequest()
        }
    }

    @Test
    fun a_scrollable_already_at_the_bottom_says_so() = runTest {
        val token = TestAutomationState.registerScrollable()
        try {
            TestAutomationState.setScrollableCapacity(token, 900)
            backgroundScope.launch {
                TestAutomationState.scrollRequests.collect { request ->
                    if (request != null) {
                        TestAutomationState.recordScrollOutcome(900, 900, 900)
                        TestAutomationState.clearScrollRequest()
                    }
                }
            }
            runCurrent()
            val r = handler.handleScroll(ScrollRequest("input_username", "down", 300))
            assertFalse(r.success)
            assertTrue("already at the bottom" in (r.error ?: ""), r.error ?: "")
        } finally {
            TestAutomationState.unregisterScrollable(token)
            TestAutomationState.clearScrollRequest()
        }
    }

    @Test
    fun the_dispatch_goes_to_a_scrollable_that_can_actually_move() {
        // A scaffold or drawer that composes LAST and cannot scroll must not
        // swallow the request: it would consume it, not move, and report
        // success while the element stayed off screen (CIRISClient#44).
        val form = TestAutomationState.registerScrollable()
        val inert = TestAutomationState.registerScrollable()
        try {
            TestAutomationState.setScrollableCapacity(form, 1400)
            TestAutomationState.setScrollableCapacity(inert, 0)
            assertTrue(TestAutomationState.isActiveScrollable(form), "the container with somewhere to go owns it")
            assertFalse(TestAutomationState.isActiveScrollable(inert))
        } finally {
            TestAutomationState.unregisterScrollable(form)
            TestAutomationState.unregisterScrollable(inert)
        }
    }

    @Test
    fun with_nothing_able_to_move_the_most_recent_still_answers() {
        // So the caller gets "no overflow" rather than "nothing consumed it".
        val a = TestAutomationState.registerScrollable()
        val b = TestAutomationState.registerScrollable()
        try {
            assertTrue(TestAutomationState.isActiveScrollable(b))
            assertFalse(TestAutomationState.isActiveScrollable(a))
        } finally {
            TestAutomationState.unregisterScrollable(a)
            TestAutomationState.unregisterScrollable(b)
        }
    }

    @Test
    fun the_most_recent_scrollable_owns_the_dispatch() {
        // A scrollable dialog over a scrollable screen: the dialog scrolls, and
        // the two collectors do not race on one request.
        val screen = TestAutomationState.registerScrollable()
        val dialog = TestAutomationState.registerScrollable()
        try {
            TestAutomationState.setScrollableCapacity(screen, 800)
            TestAutomationState.setScrollableCapacity(dialog, 400)
            assertTrue(TestAutomationState.isActiveScrollable(dialog))
            assertFalse(TestAutomationState.isActiveScrollable(screen))
            TestAutomationState.unregisterScrollable(dialog)
            assertTrue(TestAutomationState.isActiveScrollable(screen), "closing the dialog hands dispatch back")
        } finally {
            TestAutomationState.unregisterScrollable(screen)
        }
    }

    @Test
    fun presence_tracks_composition() {
        assertFalse(TestAutomationState.hasScrollable())
        val t = TestAutomationState.registerScrollable()
        assertTrue(TestAutomationState.hasScrollable())
        TestAutomationState.unregisterScrollable(t)
        assertFalse(TestAutomationState.hasScrollable(), "a disposed screen must not leave a phantom scrollable")
    }
}
