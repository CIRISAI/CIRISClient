package ai.ciris.mobile.shared.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CIRISClient#21 — the wizard's question is not the poller gate's question.
 *
 * The reproduction from the field log: a folded agent's first run reports
 * `folded=true, reachable=true` and `setup_required=true`, so the brain is
 * unconfigured, so `clientMode` is NODE — and the wizard used that to drop the
 * step that configures it.
 */
class WizardShapeTest {

    /** Exactly the first gate line in the issue's log. */
    private fun firstRunFoldedAgent() = clientModeFrom(
        cognitiveState = "SETUP",
        serviceCount = 0,
        agentFolded = true,
        agentReachable = true,
        brainUnconfigured = true,
        role = ROLE_FABRIC_NODE,
    )

    @Test
    fun the_poller_gate_still_says_node_and_that_is_correct() {
        // Unchanged on purpose: an unconfigured brain 503s every agent poller,
        // which is why the demotion exists (CIRISAgent#1075).
        assertEquals(ClientMode.NODE, firstRunFoldedAgent().mode)
    }

    @Test
    fun but_the_wizard_can_see_there_is_a_brain_to_configure() {
        // The same probe, the same instant, the answer the wizard needs.
        assertTrue(
            firstRunFoldedAgent().brainPresent,
            "folded && reachable — being unconfigured is why the AI step must be OFFERED",
        )
    }

    @Test
    fun brain_presence_does_not_depend_on_readiness() {
        // The circularity in one assertion: readiness must not gate the step
        // that produces readiness.
        val unconfigured = firstRunFoldedAgent()
        val configured = clientModeFrom(
            cognitiveState = "WORK", serviceCount = 22,
            agentFolded = true, agentReachable = true,
            brainUnconfigured = false, role = ROLE_FABRIC_NODE,
        )
        assertEquals(unconfigured.brainPresent, configured.brainPresent)
        assertTrue(configured.mode == ClientMode.AGENT && unconfigured.mode == ClientMode.NODE,
            "the poller gate differs across these two; brainPresent does not")
    }

    @Test
    fun a_bare_node_has_no_brain_to_configure() {
        // The other direction: no false AI step on a node that carries none.
        val bare = clientModeFrom(
            cognitiveState = null, serviceCount = 0,
            agentFolded = false, agentReachable = false,
            brainUnconfigured = false, role = ROLE_FABRIC_NODE,
        )
        assertFalse(bare.brainPresent)
        assertEquals(ClientMode.NODE, bare.mode)
    }

    @Test
    fun a_folded_brain_that_is_not_answering_is_not_yet_present() {
        // Undetermined: a brain exists but has not spoken. Not a licence to
        // compose the agent wizard, and not a claim there is no brain either —
        // the caller waits, which is what Screen.Setup now does.
        val booting = clientModeFrom(
            cognitiveState = null, serviceCount = 0,
            agentFolded = true, agentReachable = false,
            brainUnconfigured = false, role = ROLE_FABRIC_NODE,
        )
        assertTrue(booting.undetermined)
        assertFalse(booting.brainPresent, "not answering yet is not present")
    }
}
