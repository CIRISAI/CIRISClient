package ai.ciris.mobile.shared.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
            // Stands in for rememberTestableScrollState's collector: scroll,
            // then clear — clearing IS the acknowledgement handleScroll waits on.
            backgroundScope.launch {
                TestAutomationState.scrollRequests.collect { request ->
                    if (request != null) TestAutomationState.clearScrollRequest()
                }
            }
            runCurrent()
            val r = handler.handleScroll(ScrollRequest("input_password", "down", 300))
            assertTrue(r.success, r.error ?: "")
            assertEquals("down:300", r.text)
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

    @Test
    fun the_most_recent_scrollable_owns_the_dispatch() {
        // A scrollable dialog over a scrollable screen: the dialog scrolls, and
        // the two collectors do not race on one request.
        val screen = TestAutomationState.registerScrollable()
        val dialog = TestAutomationState.registerScrollable()
        try {
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
