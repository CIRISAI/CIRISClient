package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.MembershipInvitesApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.GroupInvite
import ai.ciris.mobile.shared.models.federation.InboxInvite
import ai.ciris.mobile.shared.models.federation.InviteAnswer
import ai.ciris.mobile.shared.models.federation.InviteInbox
import ai.ciris.mobile.shared.models.federation.InviteState
import ai.ciris.mobile.shared.ui.screens.ActRoute
import ai.ciris.mobile.shared.ui.screens.Governance
import ai.ciris.mobile.shared.ui.screens.HouseholdAct
import ai.ciris.mobile.shared.ui.screens.InviteSupport
import ai.ciris.mobile.shared.ui.screens.canWithdraw
import ai.ciris.mobile.shared.ui.screens.inboxFor
import ai.ciris.mobile.shared.ui.screens.isInviteRouteMissing
import ai.ciris.mobile.shared.ui.screens.rosterInvites
import ai.ciris.mobile.shared.ui.screens.routeOf
import ai.ciris.mobile.shared.ui.screens.viewerOf
import ai.ciris.mobile.shared.ui.screens.withdrawShown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun inboxRow(id: String, kind: String = InboxInvite.KIND_FAMILY, pair: Boolean = false, at: String = "2026-10-01T00:00:00Z") =
    InboxInvite(proposalId = id, groupKind = kind, groupId = "g-$id", groupName = "G $id", isPairRoom = pair,
        role = "member", proposerKeyId = "ada", proposedAt = at, expiresAt = "2026-10-15T00:00:00Z")

/** The invitee's node, answering what it is told to and recording every answer it was asked to sign. */
private class FakeInbox(
    var rows: List<InboxInvite> = listOf(inboxRow("p1")),
    var readError: Exception? = null,
    var answerError: NodeRefusal? = null,
) : MembershipInvitesApi {
    val calls = mutableListOf<String>()
    override suspend fun myInvites(): InviteInbox {
        readError?.let { throw it }
        return InviteInbox("me", rows)
    }
    private fun answer(verb: String, id: String, state: String, awaiting: String?): InviteAnswer {
        calls += "$verb $id"
        answerError?.let { throw it }
        rows = rows.filterNot { it.proposalId == id }
        return InviteAnswer(state = state, proposalId = id, replyId = "r-$id", awaiting = awaiting)
    }
    override suspend fun accept(proposalId: String) = answer("accept", proposalId, "accepted", "the group's widening")
    override suspend fun decline(proposalId: String) = answer("decline", proposalId, "declined", null)
}

class InvitationsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun loaded(api: FakeInbox) = InvitationsViewModel(api).also { it.load() }

    @Test
    fun theInboxIsReadAndTheNodeCarriesInvitations() {
        val vm = loaded(FakeInbox())
        assertEquals(1, assertIs<InboxRead.Loaded>(vm.inbox.value).invites.size)
        assertEquals(InviteSupport.INVITES, vm.support.value)
    }

    @Test
    fun aBare404IsANodeWithoutInvitationsNotAPersonWithNone() {
        val vm = loaded(FakeInbox(readError = NodeRefusal(null, null, 404)))
        assertEquals(InboxRead.NotOnThisNode, vm.inbox.value)
        assertEquals(InviteSupport.LEGACY, vm.support.value)
    }

    @Test
    fun aRefusedReadKeepsTheNodesIdAndDecidesNothingAboutSupport() {
        val vm = loaded(FakeInbox(readError = NodeRefusal("membership.owner_session_required", "sign in", 401)))
        val f = assertIs<InboxRead.Failed>(vm.inbox.value)
        assertEquals("membership.owner_session_required", f.refusal?.reasonId)
        assertEquals(InviteSupport.UNKNOWN, vm.support.value, "a node that refused HAS the route; a refusal is not a version")
    }

    @Test
    fun acceptingWaitsForTheConfirmAndIsNotJoining() {
        val api = FakeInbox()
        val vm = loaded(api)
        vm.request(inboxRow("p1"), accept = true)
        assertNotNull(vm.confirming.value)
        assertTrue(api.calls.isEmpty(), "nothing is signed before the three-fact confirm")
        vm.confirm()
        assertEquals(listOf("accept p1"), api.calls)
        val a = assertNotNull(vm.answered.value)
        assertTrue(a.accepted)
        assertEquals("the group's widening", a.awaiting, "accepted, awaiting the group: never reported as joined")
        assertTrue(assertIs<InboxRead.Loaded>(vm.inbox.value).invites.isEmpty(), "the answered row is gone after the re-read")
    }

    @Test
    fun decliningIsItsOwnCall() {
        val api = FakeInbox()
        val vm = loaded(api)
        vm.request(inboxRow("p1"), accept = false)
        vm.confirm()
        assertEquals(listOf("decline p1"), api.calls)
        assertFalse(assertNotNull(vm.answered.value).accepted)
    }

    @Test
    fun dismissingTheConfirmSendsNothing() {
        val api = FakeInbox()
        val vm = loaded(api)
        vm.request(inboxRow("p1"), accept = true)
        vm.cancelConfirm()
        vm.confirm()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun everyRefusalOfAnAnswerRendersByItsIdAndIsNeverAnAnswer() {
        for (id in MEMBERSHIP_IDS) {
            val api = FakeInbox(answerError = NodeRefusal(id, "en", 409))
            val vm = loaded(api)
            vm.request(inboxRow("p1"), accept = true)
            vm.confirm()
            assertEquals(id, vm.refusal.value?.reasonId, id)
            assertNull(vm.answered.value, "$id is not an answer given")
        }
    }

    @Test
    fun logoutForgetsTheInbox() {
        val vm = loaded(FakeInbox())
        vm.clearSessionState()
        assertEquals(InboxRead.NotAsked, vm.inbox.value)
        assertEquals(InviteSupport.UNKNOWN, vm.support.value)
    }

    // ── The pure rules ────────────────────────────────────────────────────

    @Test
    fun eachHubShowsItsKindAndNoPairRoom() {
        val rows = listOf(
            inboxRow("f1"), inboxRow("c1", InboxInvite.KIND_COMMUNITY),
            inboxRow("pair", InboxInvite.KIND_COMMUNITY, pair = true),
            inboxRow("f2", at = "2026-10-02T00:00:00Z"),
        )
        assertEquals(listOf("f2", "f1"), inboxFor(rows, InboxInvite.KIND_FAMILY).map { it.proposalId }, "newest first")
        assertEquals(listOf("c1"), inboxFor(rows, InboxInvite.KIND_COMMUNITY).map { it.proposalId }, "a pair room is CSD-091's")
    }

    @Test
    fun aRosterNeverShowsJoinedOrWithdrawnAsInvitations() {
        val rows = InviteState.run { listOf(PENDING, ACCEPTED, JOINED, DECLINED, EXPIRED, WITHDRAWN) }
            .map { GroupInvite(proposalId = it, state = it) }
        assertEquals(listOf("accepted", "pending", "declined", "expired"), rosterInvites(rows).map { it.state })
    }

    @Test
    fun onlyTheProposerWithdrawsAndOnlyWhilePending() {
        val mine = GroupInvite(proposalId = "p", proposerKeyId = "me", state = InviteState.PENDING)
        assertTrue(canWithdraw(mine, "me"))
        assertFalse(canWithdraw(mine, "bo"))
        assertFalse(canWithdraw(mine, null), "with no known me, no row can be told apart as mine")
        assertFalse(canWithdraw(mine.copy(state = InviteState.ACCEPTED), "me"))
    }

    @Test
    fun withdrawShowsOnlyOnTheViewersOwnPendingInvitation() {
        val mine = GroupInvite(proposalId = "p", proposerKeyId = "me", state = InviteState.PENDING)
        val theirs = mine.copy(proposerKeyId = "bo")
        assertTrue(withdrawShown(mine, "me"), "proposer == me shows Withdraw")
        assertFalse(withdrawShown(theirs, "me"), "someone else's invitation hides it")
        assertFalse(withdrawShown(mine.copy(state = InviteState.ACCEPTED), "me"), "an answered one cannot be withdrawn")
        // No key at all: every pending row, and the node refuses non-proposers by id.
        assertTrue(withdrawShown(theirs, null))
        assertFalse(withdrawShown(theirs.copy(state = InviteState.DECLINED), null))
    }

    @Test
    fun theNodesViewerKeyIsPreferredOverTheCardsOwnerRead() {
        val inv = listOf(GroupInvite(proposalId = "p", proposerKeyId = "me"))
        assertEquals("me", viewerOf(GroupInvitesRead.Loaded(inv, viewerKeyId = "me"), ownerKeyId = "other"))
        assertEquals("owner", viewerOf(GroupInvitesRead.Loaded(inv, viewerKeyId = null), ownerKeyId = "owner"), "0.5.218 sends none")
        assertEquals("owner", viewerOf(GroupInvitesRead.Loaded(inv, viewerKeyId = ""), ownerKeyId = "owner"))
        assertNull(viewerOf(GroupInvitesRead.Loaded(inv), ownerKeyId = null))
        assertNull(viewerOf(GroupInvitesRead.NotAsked, ownerKeyId = null))
    }

    @Test
    fun aRowsTierPlacesItOnOneCommunityHubAndNoTierShowsItOnBoth() {
        val c = InboxInvite.KIND_COMMUNITY
        val rows = listOf(
            inboxRow("n", c).copy(tier = "community"),
            inboxRow("a", c).copy(tier = "affiliations"),
            inboxRow("old", c), // 0.5.218: no tier
        )
        assertEquals(setOf("n", "old"), inboxFor(rows, c, "community").map { it.proposalId }.toSet())
        assertEquals(setOf("a", "old"), inboxFor(rows, c, "affiliations").map { it.proposalId }.toSet())
        assertEquals(setOf("n", "a", "old"), inboxFor(rows, c).map { it.proposalId }.toSet(), "no hub tier: all")
        val untiered = listOf(inboxRow("x", c), inboxRow("y", c))
        assertEquals(2, inboxFor(untiered, c, "community").size, "until the node sends tier, every row on every hub")
        assertEquals(2, inboxFor(untiered, c, "affiliations").size)
    }

    @Test
    fun anIdLess404IsTheRouteMissingAndAnIdIsNot() {
        assertTrue(isInviteRouteMissing(NodeRefusal(null, null, 404)))
        assertFalse(isInviteRouteMissing(NodeRefusal("family.not_found", "x", 404)))
        assertFalse(isInviteRouteMissing(NodeRefusal("membership.invite_not_found", "x", 404)))
        assertFalse(isInviteRouteMissing(NodeRefusal(null, null, 500)))
    }

    @Test
    fun anInvitationIsOneInvitersActUnderEveryRule() {
        val invite = HouseholdAct.Invite("k", "K")
        assertEquals(ActRoute.DIRECT, routeOf(invite, Governance.FounderOnly(iAmFounder = true)))
        assertEquals(ActRoute.NOT_ALLOWED, routeOf(invite, Governance.FounderOnly(iAmFounder = false)))
        assertEquals(ActRoute.DIRECT, routeOf(invite, Governance.Quorum(2, 3)), "the quorum stays on the widening")
        assertEquals(ActRoute.NOT_ALLOWED, routeOf(invite, Governance.Ungovernable("reverse_quorum:1")))
        assertEquals(ActRoute.DIRECT, routeOf(HouseholdAct.Withdraw("p", "k", "K"), Governance.Quorum(2, 3)))
        assertNull(invite.envelopeAction, "an invitation is never proposed")
    }
}
