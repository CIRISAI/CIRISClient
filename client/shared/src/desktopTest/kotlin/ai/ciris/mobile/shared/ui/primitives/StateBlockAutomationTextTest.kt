package ai.ciris.mobile.shared.ui.primitives

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * WHAT `/tree` CARRIES FOR A STATE BLOCK IS WHAT THE BLOCK DRAWS.
 *
 * `StateBlock` registered its tag with the title alone. CSD-092's error state
 * puts the fact that matters in the BODY — "It needs ciris-server 0.5.218 or
 * newer." — and its flow asserts that sentence, so on every desktop leg of the
 * 2026-09-29 run the client rendered the right words and the tree reported
 * "Could not get your contact code." only. A flow's `text:` is a claim about
 * what a person reads; the tree has to carry all of it.
 */
class StateBlockAutomationTextTest {

    @Test
    fun anErrorsBodyAndDetailAreInItsAutomationText() {
        val state = ListState.Error(
            title = "Could not get your contact code.",
            body = "This node can't make a contact code yet. It needs ciris-server 0.5.218 or newer.",
            detail = "404",
        )
        assertEquals(
            "Could not get your contact code.\n" +
                "This node can't make a contact code yet. It needs ciris-server 0.5.218 or newer.\n404",
            state.automationText(label = "ERROR"),
        )
    }

    @Test
    fun anErrorWithOnlyATitleReadsAsBefore() {
        assertEquals("Nope.", ListState.Error(title = "Nope.").automationText(label = "ERROR"))
    }

    @Test
    fun theOtherStatesReadTheirMessageThenTheirLabel() {
        assertEquals("Nobody here yet", ListState.Empty("Nobody here yet").automationText(label = null))
        assertEquals("Gone", ListState.FileGone("Gone").automationText(label = "GONE"))
        assertEquals("LOADING", ListState.Loading.automationText(label = "LOADING"))
        assertNull(ListState.Loading.automationText(label = null))
        assertNull(ListState.Populated.automationText(label = null))
    }
}
