package ai.ciris.mobile.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WHERE THE LOCAL BACKEND IS AFTER A RUN-WITHOUT-AI HAND-OFF.
 *
 * CIRISClient#66 and #67 were one bug seen from two platforms: the launcher
 * pinned the agent's :8080, the hand-off retired the agent on purpose, and
 * every wait kept polling the pin — macOS never left "Restarting your node…",
 * Windows revived a healthy node in a loop. The rule that fixes it must not
 * undo #52, which is why it is pinned here in both directions.
 */
class LocalNodeHandOffTest {

    private val agent = "http://127.0.0.1:8080"
    private val node = "http://127.0.0.1:4243"
    private val customNode = "http://127.0.0.1:9999"

    private fun decide(pinned: String, handOff: String?, pinnedAnswers: Boolean, handOffAnswers: Boolean) =
        CIRISApiClient.localNodeAfterHandOff(pinned, handOff, pinnedAnswers, handOffAnswers)

    @Test
    fun withoutAHandOffThePinIsTheAnswer() {
        assertEquals(agent, decide(agent, null, pinnedAnswers = false, handOffAnswers = true))
    }

    @Test
    fun aRetiredAgentPinYieldsToTheServingNode() {
        // #66 / #67: the pin names the agent the hand-off shut down.
        assertEquals(node, decide(agent, node, pinnedAnswers = false, handOffAnswers = true))
    }

    @Test
    fun aPinThatStillAnswersIsKept() {
        // #52: an operator's node on a custom port is where the operator put it.
        assertEquals(customNode, decide(customNode, node, pinnedAnswers = true, handOffAnswers = true))
        // And the agent is kept while it is still up (the fold has not gone down yet).
        assertEquals(agent, decide(agent, node, pinnedAnswers = true, handOffAnswers = true))
    }

    @Test
    fun bothSilentLearnsNothing() {
        // Mid-restart: neither is up. Moving now would be a guess.
        assertEquals(agent, decide(agent, node, pinnedAnswers = false, handOffAnswers = false))
    }

    @Test
    fun aHandOffToThePinnedAddressIsNotAMove() {
        assertEquals(node, decide(node, node, pinnedAnswers = false, handOffAnswers = false))
    }
}
