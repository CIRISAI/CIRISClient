package ai.ciris.mobile.shared.testing

import ai.ciris.mobile.shared.platform.TestAutomation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The automation surface Android and iOS serve (CIRISClient#28).
 *
 * Both platforms route through the shared [TestAutomationHandler], so what is
 * asserted here is what a harness gets on mobile. Until now that surface
 * differed from desktop in three ways, all of them silent:
 *
 *   - `/input` answered success:true whether or not a field was listening, so a
 *     green setup step could have typed nothing
 *   - `/tree` carried no drivability, so a harness could not tell a driveable
 *     control from a decorative one
 *   - `/undrivable` and `/state` did not exist, so the pre-flight this repo
 *     tells harnesses to run was desktop-only
 *
 * A capability present on three platforms and absent on the two where
 * automation is hardest is not one anybody can depend on.
 */
class MobileAutomationSurfaceTest {

    private val btn = "btn_surface_probe"
    private val field = "input_surface_probe"

    private fun register(tag: String) =
        TestAutomationState.registerElement(tag, 10, 20, 100, 40, null)

    @BeforeTest
    fun setup() {
        TestAutomationState.clearElements()
        TestAutomationState.currentScreen = "Setup"
    }

    @AfterTest
    fun cleanup() {
        TestAutomationState.clearElements()
        TestAutomation.unregisterInputSink(field)
        TestAutomation.unregisterClickHandler(btn)
    }

    // ── /input must not claim to have typed into nothing ──────────────────

    @Test
    fun input_into_a_field_with_no_sink_is_refused() {
        register(field)
        val r = TestAutomationHandler.handleInput(InputRequest(field, "admin", clearFirst = true))
        assertFalse(r.success, "this answered success:true on mobile while typing nothing")
        assertTrue(
            (r.error ?: "").contains("no text sink is listening"),
            "the refusal must name the cause, not merely fail: ${r.error}",
        )
    }

    @Test
    fun input_succeeds_once_a_sink_is_listening() {
        register(field)
        TestAutomation.registerInputSink(field)
        val r = TestAutomationHandler.handleInput(InputRequest(field, "admin", clearFirst = true))
        assertTrue(r.success, "a listening field must accept text: ${r.error}")
        assertEquals("admin", r.text)
    }

    @Test
    fun a_missing_element_is_still_reported_as_missing() {
        // Not-found and not-drivable are different failures and must stay so.
        val r = TestAutomationHandler.handleInput(InputRequest("input_absent", "x"))
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("Element not found"), r.error ?: "")
    }

    // ── /tree carries drivability ─────────────────────────────────────────

    @Test
    fun tree_reports_what_automation_can_actually_do() {
        register(field)
        register(btn)
        TestAutomation.registerInputSink(field)

        val byTag = TestAutomationHandler.handleTree().elements.associateBy { it.testTag }
        assertTrue(byTag.getValue(field).canInput, "a listening field must say so")
        assertFalse(byTag.getValue(btn).canClick, "an unhandled button must not claim otherwise")
    }

    @Test
    fun one_element_never_disagrees_with_the_list_it_is_in() {
        register(field)
        TestAutomation.registerInputSink(field)
        val fromTree = TestAutomationHandler.handleTree().elements.single { it.testTag == field }
        val alone = TestAutomationHandler.handleGetElement(field)
        assertEquals(fromTree.canInput, alone?.canInput)
        assertEquals(fromTree.canClick, alone?.canClick)
    }

    // ── /undrivable, the pre-flight ───────────────────────────────────────

    @Test
    fun undrivable_names_the_interactive_controls_that_cannot_be_driven() {
        register(btn)
        register(field)
        val r = TestAutomationHandler.handleUndrivable()
        assertEquals(listOf(btn, field).sorted(), r.undrivable)
        assertEquals(2, r.count)
        assertEquals("Setup", r.screen)
    }

    @Test
    fun undrivable_is_empty_once_everything_is_wired() {
        register(field)
        TestAutomation.registerInputSink(field)
        assertEquals(emptyList(), TestAutomationHandler.handleUndrivable().undrivable)
    }

    @Test
    fun display_only_tags_are_never_undrivable() {
        // Being readable IS their job. Listing them would make the pre-flight
        // noise, and a noisy gate gets ignored.
        for (t in listOf("txt_backend_status", "text_hint", "screen_setup", "card_summary")) {
            register(t)
        }
        assertEquals(emptyList(), TestAutomationHandler.handleUndrivable().undrivable)
    }

    @Test
    fun the_quick_setup_input_prefix_is_covered() {
        // SetupScreen's quick path dispatches quick_input_* — missing this
        // prefix would exempt a whole wizard branch from the check.
        register("quick_input_api_key")
        assertEquals(listOf("quick_input_api_key"), TestAutomationHandler.handleUndrivable().undrivable)
    }

    // ── /state ────────────────────────────────────────────────────────────

    @Test
    fun state_reports_the_gate_not_the_layout() {
        TestAutomationState.clientMode = "NODE"
        TestAutomationState.nodeUrl = "http://127.0.0.1:4343"
        val s = TestAutomationHandler.handleState()
        assertEquals("NODE", s.clientMode)
        assertEquals("http://127.0.0.1:4343", s.nodeUrl)
        assertEquals("Setup", s.screen)
        assertTrue(s.testMode)
    }
}
