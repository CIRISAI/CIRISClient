package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.SelfRosterApi
import ai.ciris.mobile.shared.models.federation.RosterAnswerResponse
import ai.ciris.mobile.shared.models.federation.RosterDecision
import ai.ciris.mobile.shared.models.federation.RosterProposal
import ai.ciris.mobile.shared.models.federation.RosterProposalsResponse
import ai.ciris.mobile.shared.models.federation.RosterStanding
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The self-key roster card's model** (CSD-095, CIRISServer#794): polling
 * stands down while an answer is in flight and a read that started before an
 * answer never paints over it; a change this device proposed offers no answer;
 * a change that leaves the open set is kept as history with how it ended.
 */
class SelfRosterViewModelTest {

    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain() }

    private fun standing(id: String, kind: String = "add", target: String = "key-c", needs: Int? = 1, outcome: String = "pending", reason: String? = null) =
        RosterStanding(
            proposal = RosterProposal(id = id, kind = kind, proposer = "key-a", target = target),
            decision = RosterDecision(outcome = outcome, needs = needs, reason = reason, earliestLoneAt = "2026-10-21T15:00:00Z"),
            outcome = reason ?: outcome,
        )

    private class Node : SelfRosterApi {
        val calls = mutableListOf<String>()
        var listing = RosterProposalsResponse(identityKeyId = "owner", roster = listOf("owner", "key-a"))
        /** When set, the answer waits on it: an action held open. */
        var gate: CompletableDeferred<Unit>? = null
        var answer: (String, Boolean) -> RosterAnswerResponse = { id, assent ->
            RosterAnswerResponse(id, if (assent) "assent" else "object", "att-$id", settled = false, proposal = null)
        }
        var refuse: NodeRefusal? = null

        override suspend fun proposals(): RosterProposalsResponse {
            calls += "list"
            return listing
        }

        private suspend fun answered(id: String, assent: Boolean): RosterAnswerResponse {
            calls += (if (assent) "assent:" else "object:") + id
            gate?.await()
            refuse?.let { throw it }
            return answer(id, assent)
        }

        override suspend fun assent(proposalId: String) = answered(proposalId, true)
        override suspend fun objectTo(proposalId: String) = answered(proposalId, false)
    }

    @Test
    fun pollingStandsDownWhileAnAnswerIsInFlight() = runBlocking {
        val node = Node()
        node.listing = node.listing.copy(proposals = listOf(standing("p1")))
        val vm = SelfRosterViewModel(node).also { made += it }
        vm.refreshNow()
        node.gate = CompletableDeferred()
        vm.askAssent(vm.state.value.proposals.single())
        val answering = vm.confirm()
        assertEquals("p1", vm.state.value.working)
        val before = node.calls.size
        vm.refresh().join()
        vm.refreshNow()
        assertEquals(before, node.calls.size, "no read while answering: ${node.calls}")
        assertFalse(vm.state.value.canAnswer(vm.state.value.proposals.single()), "a second answer is not offered while one is out")
        node.gate!!.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(10_000) { answering.join() } }
        assertNull(vm.state.value.working)
        assertEquals(listOf("list", "assent:p1", "list"), node.calls, "the answer re-reads once, after itself")
        assertEquals(true, vm.state.value.answered["p1"])
        assertEquals("att-p1", vm.state.value.lastAnswer?.attestationId)
    }

    @Test
    fun aSecondTapSignsOnce() = runBlocking {
        val node = Node()
        node.listing = node.listing.copy(proposals = listOf(standing("p1")))
        val vm = SelfRosterViewModel(node).also { made += it }
        vm.refreshNow()
        node.gate = CompletableDeferred()
        vm.askObject(vm.state.value.proposals.single())
        val first = vm.confirm()
        vm.askObject(vm.state.value.proposals.single())
        vm.confirm()
        node.gate!!.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(10_000) { first.join() } }
        assertEquals(1, node.calls.count { it.startsWith("object:") })
    }

    @Test
    fun aChangeThisDeviceProposedOffersNoAnswer() = runBlocking {
        val node = Node()
        node.listing = node.listing.copy(proposals = listOf(standing("mine"), standing("theirs", target = "key-d")))
        val vm = SelfRosterViewModel(node).also { made += it }
        vm.markProposedHere("mine", "Tablet", "key-c")
        vm.refreshNow()
        val s = vm.state.value
        assertFalse(s.canAnswer(s.proposals.first { it.proposal.id == "mine" }))
        assertTrue(s.canAnswer(s.proposals.first { it.proposal.id == "theirs" }))
        assertEquals(1, s.awaitingThisDevice, "only the change another device proposed awaits this one")
        assertEquals("Tablet", s.proposedLabels["key-c"])
    }

    @Test
    fun aHeldRevokeMakesItsEvictProposalThisDevices() = runBlocking {
        val node = Node()
        node.listing = node.listing.copy(proposals = listOf(standing("ev", kind = "evict", target = "key-x")))
        val vm = SelfRosterViewModel(node).also { made += it }
        vm.markEvictionProposedHere("key-x")
        vm.refreshNow()
        assertTrue("ev" in vm.state.value.ownProposals)
        assertEquals(0, vm.state.value.awaitingThisDevice)
    }

    @Test
    fun theOwnProposalRefusalIsLearned() = runBlocking {
        val node = Node()
        node.listing = node.listing.copy(proposals = listOf(standing("p1")))
        node.refuse = NodeRefusal(ROSTER_OWN_PROPOSAL, "This device proposed that change; another of your devices answers it.", 409)
        val vm = SelfRosterViewModel(node).also { made += it }
        vm.refreshNow()
        vm.askAssent(vm.state.value.proposals.single())
        withContext(Dispatchers.Default) { withTimeout(10_000) { vm.confirm().join() } }
        val s = vm.state.value
        assertEquals(ROSTER_OWN_PROPOSAL, s.answerRefusal?.reasonId)
        assertTrue("p1" in s.ownProposals)
        assertEquals(0, s.awaitingThisDevice)
    }

    @Test
    fun aChangeThatLeavesTheOpenSetIsKeptWithHowItEnded() = runBlocking {
        val node = Node()
        node.listing = node.listing.copy(
            proposals = listOf(
                standing("landed", target = "key-c"),
                standing("dropped", target = "key-z"),
                standing("refused", target = "key-y", outcome = "refused", reason = "target_has_open_proposal", needs = null),
            ),
        )
        val vm = SelfRosterViewModel(node).also { made += it }
        vm.refreshNow()
        node.listing = node.listing.copy(roster = listOf("owner", "key-a", "key-c"), proposals = emptyList())
        vm.refreshNow()
        val h = vm.state.value.history.associate { it.proposal.id to it.how }
        assertEquals(RosterSettledHow.LANDED, h["landed"])
        assertEquals(RosterSettledHow.CLOSED, h["dropped"])
        assertEquals(RosterSettledHow.REFUSED, h["refused"])
        assertEquals("target_has_open_proposal", vm.state.value.history.first { it.proposal.id == "refused" }.reason)
        vm.refreshNow()
        assertEquals(3, vm.state.value.history.size, "history is not repeated")
    }

    @Test
    fun aBareFourOhFourIsTheVersionFact() = runBlocking {
        val node = object : SelfRosterApi {
            override suspend fun proposals(): RosterProposalsResponse = throw NodeRefusal(null, null, 404)
            override suspend fun assent(proposalId: String): RosterAnswerResponse = error("unreachable")
            override suspend fun objectTo(proposalId: String): RosterAnswerResponse = error("unreachable")
        }
        val vm = SelfRosterViewModel(node).also { made += it }
        vm.refreshNow()
        assertTrue(vm.state.value.nodeTooOld)
        assertNull(vm.state.value.refusal)
    }

    @Test
    fun signaturesCountTheProposerAndWhatIsStillNeeded() {
        val s = standing("p", needs = 1).copy(approvers = listOf("key-b"))
        assertEquals(2, s.signaturesSoFar())
        assertEquals(3, s.signaturesNeeded(rosterSize = 4))
        val done = standing("q", outcome = "lands", needs = null)
        assertEquals(3, done.signaturesNeeded(rosterSize = 5))
    }
}
