package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.CommunityRoom
import ai.ciris.mobile.shared.models.federation.DecisionAnswered
import ai.ciris.mobile.shared.models.federation.GroupInvite
import ai.ciris.mobile.shared.models.federation.InviteState
import ai.ciris.mobile.shared.models.federation.NodeDecision
import ai.ciris.mobile.shared.models.federation.NodeDecisionList
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The node's decision queue (`/v1/self/decisions`, CIRISServer 0.5.247
 * `src/communities.rs` `seat_decisions` / `deferral_decisions` /
 * `answer_decision`), read by an accepted invitation's row.
 */
class NodeDecisionsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The fields `seat_decisions` writes, in its order (communities.rs at v0.5.247). */
    private val seatItem = """
        {"decision_id":"quorum_cosign:prop-1","kind":"quorum_cosign","circle":"room-1",
         "subject":"carol","role":null,"proposed_by":"alice","proposed_at":"2026-11-14T10:00:00Z",
         "expires_at":"2026-11-21T10:00:00Z","acceptance_id":"reply-1",
         "consensus_protocol":"simple_majority","required":2,"eligible":["alice","bob"],
         "my_answer":null,"signed":1,
         "reason_id":"decisions.quorum_cosign.approve_joining","says":"Approve this person joining the room."}
    """.trimIndent()

    /** The fields `deferral_decisions` writes (communities.rs at v0.5.247). */
    private val deferralItem = """
        {"decision_id":"deferral:att-9","kind":"deferral","circle":null,"subject":"agent-key",
         "deferral_id":"d-1","task_id":"t-1","domain_hint":null,"reason_digest":"sha256:ab",
         "proposed_at":"2026-11-14T10:00:00Z","request_attestation_id":"att-9",
         "reason_id":"decisions.deferral.answer","says":"Your agent is waiting for your decision."}
    """.trimIndent()

    private fun accepted(proposal: String = "prop-1") =
        GroupInvite(proposalId = proposal, inviteeKeyId = "carol", state = InviteState.ACCEPTED)

    private fun loaded(vararg d: NodeDecision) = DecisionsRead.Loaded(d.toList())

    private fun seat(myAnswer: String? = null, signed: Int? = 1, required: Int? = 2) =
        NodeDecision(
            decisionId = NodeDecision.seatDecisionId("prop-1"), kind = NodeDecision.KIND_QUORUM_COSIGN,
            subject = "carol", myAnswer = myAnswer, signed = signed, required = required,
        )

    // ── decoding what the node actually sends ────────────────────────────

    @Test
    fun the_list_decodes_both_kinds_the_node_sends() {
        val list = json.decodeFromString(
            NodeDecisionList.serializer(), """{"decisions":[$seatItem,$deferralItem],"count":2}""",
        )
        assertEquals(2, list.decisions.size)
        val s = list.decisions[0]
        assertEquals(NodeDecision.KIND_QUORUM_COSIGN, s.kind)
        assertEquals("quorum_cosign:prop-1", s.decisionId)
        assertEquals(2, s.required)
        assertEquals(1, s.signed)
        assertNull(s.myAnswer)
        assertEquals(listOf("alice", "bob"), s.eligible)
        val d = list.decisions[1]
        assertEquals(NodeDecision.KIND_DEFERRAL, d.kind)
        assertEquals("t-1", d.taskId)
        assertNull(d.circle)
    }

    @Test
    fun the_seat_id_is_the_one_the_node_builds_from_the_proposal() {
        assertEquals("quorum_cosign:prop-1", NodeDecision.seatDecisionId("prop-1"))
    }

    @Test
    fun an_answer_that_seats_and_one_that_waits_are_both_answers() {
        val seated = json.decodeFromString(
            DecisionAnswered.serializer(),
            """{"decision_id":"quorum_cosign:prop-1","answer":"approve","state":"applied","seated":["carol"]}""",
        )
        assertTrue(seated.applied)
        assertEquals(listOf("carol"), seated.seated)
        // The 202 body is `refuse_with`'s: a reason_id beside the fields. Still an answer.
        val held = json.decodeFromString(
            DecisionAnswered.serializer(),
            """{"error":"decisions.awaiting_members","reason_id":"decisions.awaiting_members",
                "detail":"Your answer is recorded.","decision_id":"quorum_cosign:prop-1",
                "answer":"approve","state":"awaiting_members","required":3,"signed":2}""",
        )
        assertFalse(held.applied)
        assertEquals(2, held.signed)
        assertEquals(3, held.required)
    }

    // ── what the row says ────────────────────────────────────────────────

    @Test
    fun a_seat_this_person_has_not_answered_offers_approve() {
        val st = seatStatus(accepted(), loaded(seat()))
        assertIs<SeatStatus.CanApprove>(st)
        assertEquals("quorum_cosign:prop-1", st.decision.decisionId)
    }

    @Test
    fun an_approved_seat_carries_the_nodes_count_never_a_local_one() {
        val st = seatStatus(accepted(), loaded(seat(myAnswer = "approve", signed = 2, required = 3)))
        assertEquals(SeatStatus.Approved(signed = 2, required = 3), st)
    }

    @Test
    fun a_declined_seat_says_so() {
        assertEquals(SeatStatus.Declined, seatStatus(accepted(), loaded(seat(myAnswer = "decline"))))
    }

    @Test
    fun a_read_queue_with_no_decision_for_this_seat_is_waiting_not_approvable() {
        // Not this person's to make, or the inviter's proposal has not reached this node.
        assertEquals(SeatStatus.Waiting, seatStatus(accepted(), loaded()))
        // Another seat's decision is not this one's.
        val other = seat().copy(decisionId = NodeDecision.seatDecisionId("prop-2"))
        assertEquals(SeatStatus.Waiting, seatStatus(accepted(), loaded(other)))
        // A deferral is never a seat.
        val deferral = seat().copy(kind = NodeDecision.KIND_DEFERRAL)
        assertEquals(SeatStatus.Waiting, seatStatus(accepted(), loaded(deferral)))
    }

    @Test
    fun a_queue_not_read_failed_or_absent_says_nothing_new() {
        for (read in listOf(
            DecisionsRead.NotAsked, DecisionsRead.Loading, DecisionsRead.NotOnThisNode,
            DecisionsRead.Failed(null, "timeout"),
        )) {
            assertEquals(SeatStatus.Unknown, seatStatus(accepted(), read), read.toString())
        }
    }

    @Test
    fun only_an_accepted_invitation_has_a_seat_question() {
        for (state in listOf(InviteState.PENDING, InviteState.JOINED, InviteState.DECLINED, InviteState.EXPIRED)) {
            val inv = accepted().copy(state = state)
            assertEquals(SeatStatus.NotApplicable, seatStatus(inv, loaded(seat())), state)
        }
    }

    @Test
    fun a_bare_404_is_a_node_without_the_queue_and_a_named_one_is_a_refusal() {
        assertEquals(DecisionsRead.NotOnThisNode, DecisionsRead.of(NodeRefusal(null, null, 404)))
        val named = DecisionsRead.of(NodeRefusal("decisions.not_found", "closed", 404))
        assertIs<DecisionsRead.Failed>(named)
    }

    // ── the full room's line ─────────────────────────────────────────────

    private fun room(max: Int?) = CommunityRoom(communityId = "room-1", maxPeople = max)

    @Test
    fun two_people_for_now_only_where_the_limit_is_two() {
        assertEquals(CommunitiesViewModel.TWO_PEOPLE_ONLY, CommunitiesViewModel.fullRoomLine(room(2), null))
        assertEquals(CommunitiesViewModel.TWO_PEOPLE_ONLY, CommunitiesViewModel.fullRoomLine(room(null), 2))
        // No limit known at all is a 0.5.246 node's answer: its rooms hold two.
        assertEquals(CommunitiesViewModel.TWO_PEOPLE_ONLY, CommunitiesViewModel.fullRoomLine(room(null), null))
    }

    @Test
    fun a_larger_room_that_is_full_is_just_full() {
        assertEquals(CommunitiesViewModel.ROOM_FULL, CommunitiesViewModel.fullRoomLine(room(100), null))
        // The room's own max_people wins over what an older refusal taught.
        assertEquals(CommunitiesViewModel.ROOM_FULL, CommunitiesViewModel.fullRoomLine(room(100), 2))
    }
}
