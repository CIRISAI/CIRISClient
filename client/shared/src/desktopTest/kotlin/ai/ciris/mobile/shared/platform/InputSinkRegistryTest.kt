package ai.ciris.mobile.shared.platform

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The registry `/input` consults before it will type anything (CIRISClient#30).
 *
 * `rememberInputSinks` is a Compose helper and this module has no Compose UI
 * test infrastructure, so the composable itself is covered by the static check
 * (`check_ui_drivable.py` fails a file that dispatches a tag it does not
 * declare). What CAN be tested here is the registry beneath it — the exact
 * calls the desktop `/input` route makes to decide between typing and a 422.
 *
 * 0.5.197 shipped with this registry permanently empty: the gate asked it a
 * question no screen had ever answered, and every text input on every desktop
 * was refused. These pin that the answer changes when a sink registers, and
 * changes back when it leaves.
 */
class InputSinkRegistryTest {

    private val tag = "input_registry_probe"

    @AfterTest
    fun cleanup() = TestAutomation.unregisterInputSink(tag)

    @Test
    fun an_unregistered_tag_is_not_a_sink() {
        // The 0.5.197 state for every field: /input must refuse, not pretend.
        assertFalse(TestAutomation.hasInputSink(tag))
    }

    @Test
    fun registering_makes_it_drivable() {
        TestAutomation.registerInputSink(tag)
        assertTrue(TestAutomation.hasInputSink(tag), "/input consults exactly this")
    }

    @Test
    fun unregistering_on_dispose_makes_it_undrivable_again() {
        // A screen that left must not leave a phantom sink behind, or /input
        // would type into nothing and report success — the original defect.
        TestAutomation.registerInputSink(tag)
        TestAutomation.unregisterInputSink(tag)
        assertFalse(TestAutomation.hasInputSink(tag))
    }

    @Test
    fun registering_twice_then_unregistering_once_is_not_a_sink() {
        // The helper keys its DisposableEffect on the tag set, so a plain set
        // is the right model: registration is idempotent, not counted.
        TestAutomation.registerInputSink(tag)
        TestAutomation.registerInputSink(tag)
        TestAutomation.unregisterInputSink(tag)
        assertFalse(TestAutomation.hasInputSink(tag))
    }

    @Test
    fun sinks_are_per_tag() {
        TestAutomation.registerInputSink(tag)
        assertFalse(TestAutomation.hasInputSink("input_some_other_field"))
    }
}
