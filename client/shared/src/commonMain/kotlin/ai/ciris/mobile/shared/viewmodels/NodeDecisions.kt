package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.GroupInvite
import ai.ciris.mobile.shared.models.federation.InviteState
import ai.ciris.mobile.shared.models.federation.NodeDecision
import ai.ciris.mobile.shared.ui.screens.isInviteRouteMissing

/**
 * One read of the node's decision queue (`GET /v1/self/decisions`), in the
 * same five states as every other read here: a failure is never drawn as "no
 * decisions", and a node without the route is its own state.
 */
sealed interface DecisionsRead {
    data object NotAsked : DecisionsRead
    data object Loading : DecisionsRead
    data class Loaded(val decisions: List<NodeDecision>) : DecisionsRead
    /** A bare 404: a node older than the queue. The room keeps its old line. */
    data object NotOnThisNode : DecisionsRead
    data class Failed(val refusal: NodeRefusal?, val detail: String?) : DecisionsRead

    companion object {
        fun of(e: Throwable): DecisionsRead = when {
            isInviteRouteMissing(e) -> NotOnThisNode
            e is NodeRefusal -> Failed(e, e.detail)
            else -> Failed(null, e.message ?: e::class.simpleName)
        }
    }
}

/**
 * What an ACCEPTED invitation's row says about seating them, read from the
 * node's own queue and never computed here: who may approve and how many
 * voices the rule needs are the node's answer (`eligible`, `required`,
 * `signed`), not this app's reading of the room's rule.
 */
sealed interface SeatStatus {
    /** This person may approve and has not. Carries the decision to answer. */
    data class CanApprove(val decision: NodeDecision) : SeatStatus
    /** This person approved; [signed] of [required] when the node counts them. */
    data class Approved(val signed: Int?, val required: Int?) : SeatStatus
    /** This person declined. */
    data object Declined : SeatStatus
    /**
     * The queue was read and holds no decision for this person on this seat:
     * it is not theirs to make, or the inviter's proposal has not reached this
     * node yet. Both read the same from here, so the row says both.
     */
    data object Waiting : SeatStatus
    /** The queue was not read, failed, or this node has none: say nothing new. */
    data object Unknown : SeatStatus
    /** Not an accepted invitation: no seat question. */
    data object NotApplicable : SeatStatus
}

fun seatStatus(invite: GroupInvite, read: DecisionsRead): SeatStatus {
    if (invite.state != InviteState.ACCEPTED) return SeatStatus.NotApplicable
    val decisions = (read as? DecisionsRead.Loaded)?.decisions ?: return SeatStatus.Unknown
    val id = NodeDecision.seatDecisionId(invite.proposalId)
    val d = decisions.firstOrNull { it.kind == NodeDecision.KIND_QUORUM_COSIGN && it.decisionId == id }
        ?: return SeatStatus.Waiting
    return when (d.myAnswer) {
        null, "" -> SeatStatus.CanApprove(d)
        NodeDecision.APPROVE -> SeatStatus.Approved(d.signed, d.required)
        else -> SeatStatus.Declined
    }
}
