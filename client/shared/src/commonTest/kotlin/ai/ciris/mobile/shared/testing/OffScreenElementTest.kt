package ai.ciris.mobile.shared.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Composed is not shown (CIRISClient#33).
 *
 * On a phone the nav rail lives in a `ModalNavigationDrawer`, whose content is
 * composed always and translated off screen when closed. Its items registered
 * with live handlers, `/tree` listed them, and a harness waited 20s for
 * `menu_logout` while the only thing a person could tap was
 * `btn_nav_drawer_open`. These pin the distinction between present and usable,
 * and the one thing a failed wait must say: what IS usable.
 */
class OffScreenElementTest {

    private val handler = TestAutomationHandler

    @BeforeTest fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TestAutomationState.clearElements()
    }

    @AfterTest fun tearDown() {
        listOf("menu_logout", "btn_nav_drawer_open", "input_hidden").forEach {
            TestAutomationState.unregisterElement(it)
        }
        TestAutomationState.clearElements()
        Dispatchers.resetMain()
    }

    private fun drawerClosed() {
        // The closed drawer: item fully outside the window, so its clipped
        // bounds have no area — and a handler, because its modifier composed.
        TestAutomationState.registerElement("menu_logout", 0, 0, 0, 0, "Log out")
        TestAutomationState.registerClickHandler("menu_logout") {}
        // The one control a person can actually reach.
        TestAutomationState.registerElement("btn_nav_drawer_open", 8, 8, 48, 48, null)
        TestAutomationState.registerClickHandler("btn_nav_drawer_open") {}
    }

    @Test
    fun a_window_clipped_rect_with_no_area_is_off_screen() {
        assertFalse(isOnScreen(0, 0))
        assertFalse(isOnScreen(0, 48))
        assertFalse(isOnScreen(48, 0))
        assertTrue(isOnScreen(1, 1))
    }

    @Test
    fun the_tree_says_which_is_which() {
        drawerClosed()
        val byTag = handler.handleTree().elements.associateBy { it.testTag }
        assertFalse(byTag.getValue("menu_logout").visible)
        assertTrue(byTag.getValue("btn_nav_drawer_open").visible)
        // still LISTED: a harness reading the tree sees it and its state
        assertTrue("menu_logout" in byTag)
    }

    @Test
    fun a_wait_for_an_off_screen_item_fails_and_names_what_is_usable() = runTest {
        drawerClosed()
        val r = handler.handleWait(WaitRequest(testTag = "menu_logout", timeoutMs = 150))
        assertFalse(r.success, "a closed drawer's item must not satisfy a wait")
        val err = r.error ?: ""
        assertTrue("off screen" in err, err)
        assertTrue("btn_nav_drawer_open" in err, "the failure must say what IS drivable: $err")
        assertFalse("menu_logout]" in err || "menu_logout," in err, "an off-screen item is not offered as drivable: $err")
    }

    @Test
    fun a_click_on_an_off_screen_item_is_refused_not_fired() = runTest {
        var fired = false
        TestAutomationState.registerElement("menu_logout", 0, 0, 0, 0, null)
        TestAutomationState.registerClickHandler("menu_logout") { fired = true }
        val r = handler.handleClick(ClickRequest(testTag = "menu_logout"))
        assertFalse(r.success)
        assertFalse(fired, "the handler is live, and firing it would drive a control no one can see")
        assertTrue("off screen" in (r.error ?: ""))
    }

    @Test
    fun opening_the_drawer_makes_the_same_item_usable() = runTest {
        drawerClosed()
        // Drawer opens: the item is re-positioned inside the window.
        TestAutomationState.registerElement("menu_logout", 16, 200, 240, 48, "Log out")
        assertTrue(handler.handleWait(WaitRequest(testTag = "menu_logout", timeoutMs = 150)).success)
        assertTrue(handler.handleClick(ClickRequest(testTag = "menu_logout")).success)
        assertEquals(listOf("btn_nav_drawer_open", "menu_logout"), TestAutomationState.onScreenDrivable())
    }

    @Test
    fun a_handler_only_entry_still_satisfies_a_wait() = runTest {
        // Popup content registers a handler and never a position; that
        // allowance predates this and must survive it.
        TestAutomationState.registerClickHandler("btn_nav_drawer_open") {}
        assertTrue(handler.handleWait(WaitRequest(testTag = "btn_nav_drawer_open", timeoutMs = 150)).success)
    }

    @Test
    fun a_missing_element_also_names_what_is_usable() = runTest {
        drawerClosed()
        val r = handler.handleWait(WaitRequest(testTag = "btn_does_not_exist", timeoutMs = 150))
        assertFalse(r.success)
        assertTrue("btn_nav_drawer_open" in (r.error ?: ""))
    }
}
