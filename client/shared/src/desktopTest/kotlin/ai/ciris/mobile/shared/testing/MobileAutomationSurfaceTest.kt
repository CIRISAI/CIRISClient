package ai.ciris.mobile.shared.testing

import ai.ciris.mobile.shared.platform.TestAutomation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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

    /**
     * Stand in for a screen's collector: apply the request and clear it, which
     * is what all six real dispatches do. /input now waits for that clear as
     * its apply signal, so a test without one is testing the timeout.
     */
    private fun CoroutineScope.collectLikeAScreen() = launch {
        TestAutomation.textInputRequests.collect { req ->
            if (req != null) TestAutomation.clearTextInputRequest()
        }
    }

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
    fun input_into_a_field_with_no_sink_is_refused() = runTest {
        register(field)
        val r = TestAutomationHandler.handleInput(InputRequest(field, "admin", clearFirst = true))
        assertFalse(r.success, "this answered success:true on mobile while typing nothing")
        assertTrue(
            (r.error ?: "").contains("no text sink is listening"),
            "the refusal must name the cause, not merely fail: ${r.error}",
        )
    }

    @Test
    fun input_succeeds_once_a_sink_is_listening_and_a_screen_applies_it() = runTest {
        register(field)
        TestAutomation.registerInputSink(field)
        val collector = CoroutineScope(UnconfinedTestDispatcher(testScheduler)).collectLikeAScreen()

        val r = TestAutomationHandler.handleInput(InputRequest(field, "admin", clearFirst = true))
        assertTrue(r.success, "a listening field whose screen applied must succeed: ${r.error}")
        assertEquals("admin", r.text)
        collector.cancel()
    }

    @Test
    fun input_that_is_never_applied_reports_failure_not_success() = runTest {
        // THE #31 DEFECT, INVERTED INTO A TEST.
        //
        // A registered sink whose dispatch never runs — the exact shape when a
        // conflating StateFlow drops a request. This used to answer
        // success:true; the gate typed a password, got success, and the product
        // said "Password is required".
        register(field)
        TestAutomation.registerInputSink(field)   // registered, but nobody collects

        val r = TestAutomationHandler.handleInput(InputRequest(field, "hunter2", clearFirst = true))
        assertFalse(r.success, "unapplied input must not report success")
        assertTrue((r.error ?: "").contains("did not apply"), r.error ?: "")
        TestAutomation.clearTextInputRequest()
    }

    @Test
    fun an_applied_input_is_readable_back_from_element_and_tree() = runTest {
        // Without this a consumer had no witness but a screenshot.
        register(field)
        TestAutomation.registerInputSink(field)
        val collector = CoroutineScope(UnconfinedTestDispatcher(testScheduler)).collectLikeAScreen()

        TestAutomationHandler.handleInput(InputRequest(field, "qaadmin", clearFirst = true))
        assertEquals("qaadmin", TestAutomationHandler.handleGetElement(field)?.inputValue)
        assertEquals(
            "qaadmin",
            TestAutomationHandler.handleTree().elements.single { it.testTag == field }.inputValue,
        )
        collector.cancel()
    }

    @Test
    fun the_applied_value_is_readable_under_text_too_where_drivers_look() {
        // EXPOSING IT UNDER A NEW NAME ONLY WOULD HAVE CLOSED THIS ON PAPER.
        //
        // The driver that reported #31 polls `text`, and on reading null it
        // concludes on the FIRST read by design. A consumer would have seen
        // "unverifiable: element exposes no text" against this release exactly
        // as against the last one.
        register(field)
        TestAutomation.registerInputSink(field)
        TestAutomation.setInputValue(field, "qaadmin")
        assertEquals("qaadmin", TestAutomationHandler.handleGetElement(field)?.text)
    }

    @Test
    fun a_display_label_outranks_a_mirrored_value() {
        // `text` is a label for display elements and that meaning comes first;
        // an overwrite would have made the fix a regression elsewhere.
        TestAutomationState.registerElement("txt_banner", 0, 0, 10, 10, "Password is required")
        assertEquals(
            "Password is required",
            TestAutomationHandler.handleGetElement("txt_banner")?.text,
        )
        TestAutomationState.clearElements()
    }

    @Test
    fun a_missing_element_is_still_reported_as_missing() = runTest {
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
