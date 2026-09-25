package ai.ciris.mobile.shared.platform

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A disabled control has no `/click` handler (CIRISClient#69).
 *
 * `testableClickable` registered its handler whatever the Button's `enabled`
 * said, so `/click btn_next` ran the setup wizard's final step while Next was
 * greyed out for a step already in flight — a robot pressed what no person
 * could. This module has no Compose UI test infrastructure, so what is pinned
 * here is [bindClickHandler], the one call the modifier's effect makes, against
 * the same registry the desktop `/click` route triggers.
 */
class DisabledClickHandlerTest {

    private val tag = "btn_disabled_probe"

    @AfterTest
    fun cleanup() = TestAutomation.unregisterClickHandler(tag)

    @Test
    fun aDisabledControlCannotBeClicked() {
        var presses = 0
        bindClickHandler(tag, enabled = false) { presses++ }
        assertFalse(TestAutomation.hasClickHandler(tag), "/tree must report canClick=false")
        assertFalse(TestAutomation.triggerClick(tag), "/click must refuse a disabled control")
        assertEquals(0, presses, "the handler ran behind a disabled button")
    }

    @Test
    fun enablingRegistersAndDisablingRemoves() {
        var presses = 0
        bindClickHandler(tag, enabled = true) { presses++ }
        assertTrue(TestAutomation.triggerClick(tag))
        assertEquals(1, presses)
        // A step starts, the button greys out: the handler must go with it.
        bindClickHandler(tag, enabled = false) { presses++ }
        assertFalse(TestAutomation.triggerClick(tag))
        assertEquals(1, presses, "a second press during the step reached the handler")
    }
}
