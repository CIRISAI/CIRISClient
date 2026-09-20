package ai.ciris.mobile.shared.models

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **A null gate is not a reason to wait** (CIRISClient#48).
 *
 * [shouldWaitForAgent] and `CIRISApp`'s `isAgentMode` are two derivations of the
 * SAME nullable gate that must default in OPPOSITE directions. They were one
 * identifier, and the conflation was not theoretical: on a run-without-AI
 * install the gate is still unprobed when the credential login returns, so
 * `!isAgentMode` never fired and three separate call sites each polled
 * `getSystemStatus()` for a cognitive state on a bare node for the loop's full
 * 150 × 200 ms budget. The QA gate measured **34.1 s (linux) / 36.1 (macOS) /
 * 40.1 (Windows)** between a login that had already succeeded in ~200 ms and
 * Interact composing.
 *
 * The case that matters is [unprobedMustNotWait]. The other two would pass
 * against the defect too — which is the point of writing this one down: a test
 * suite where every case passes before the fix is a suite that would not have
 * caught it.
 */
class ClientModeShouldWaitTest {

    /**
     * THE REGRESSION. This is the only case here that FAILS against the
     * pre-fix `clientMode?.isAgent ?: true`, and it is the whole defect: the
     * gate is routinely null at login because the probe has not landed yet, and
     * "I have not asked" was being read as "yes, wait for a brain".
     */
    @Test
    fun unprobedMustNotWait() {
        assertFalse(
            shouldWaitForAgent(null),
            "an unprobed gate must not spend the 30-second WORK-state budget — " +
                "null means NOT ASKED YET, never 'assume agent' (CIRISClient#48)",
        )
    }

    /** A bare node has no cognitive brain, so there is nothing to reach WORK. */
    @Test
    fun nodeMustNotWait() {
        assertFalse(shouldWaitForAgent(ClientMode.NODE), "a bare node has no WORK state to wait for")
    }

    /**
     * AND THE WAIT MUST STILL HAPPEN when it is real. Defaulting the unprobed
     * case to false is only safe because a resolved AGENT still waits — a fix
     * that turned the wait off everywhere would trade 30 seconds of spinner for
     * an Interact screen composed against a brain that is not answering yet.
     */
    @Test
    fun agentStillWaits() {
        assertTrue(shouldWaitForAgent(ClientMode.AGENT), "a probed AGENT must still wait for WORK")
    }

    /**
     * The two derivations disagree on exactly one input, and that is by design.
     * Pinned so that "just make them the same again" cannot land quietly: on a
     * resolved gate they agree, and on `null` they must not.
     */
    @Test
    fun wordingAndWaitDifferOnlyWhenUnprobed() {
        // `isAgentMode` is a local val in a @Composable and is not reachable
        // from a unit test, so its rule is restated here as the one line it is.
        fun wording(mode: ClientMode?): Boolean = mode?.isAgent ?: true

        for (mode in listOf(ClientMode.NODE, ClientMode.AGENT)) {
            assertTrue(
                shouldWaitForAgent(mode) == wording(mode),
                "on a RESOLVED gate the two must agree (mode=$mode)",
            )
        }
        assertTrue(
            wording(null) && !shouldWaitForAgent(null),
            "unprobed is the ONE input where they must differ: wording may say " +
                "'agent', the wait may not. That split is the fix.",
        )
    }
}
