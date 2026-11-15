package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **What the signed-in person can decide, as THEIR NODE lists it** —
 * `GET /v1/self/decisions?circle=` and `POST /v1/self/decisions/{id}/answer`
 * (CIRISServer 0.5.246; 0.5.247 `src/communities.rs` `list_decisions`,
 * `answer_decision`). One queue on the node for everything its owner decides
 * (CC #198 §5).
 *
 * NOT the agent's queue. `approvals/ApprovalsApi.kt` reads the agent's
 * `/v1/wa/deferrals` and `/v1/tickets`; this is the node's, and on a bare node
 * it is the only one.
 *
 * Two kinds today:
 *  - [KIND_QUORUM_COSIGN]: an accepted invitee waiting for the room's members to
 *    seat them. The inviter's approval proposes the seat; another member sees
 *    the decision only once that proposal is held on their node, so it can
 *    appear later there than on the inviter's. Approving signs a cosignature
 *    held in the mesh until the room's rule is met (persist 61.4.0,
 *    CIRISPersist#1252). One approval seats only that person (0.5.247).
 *  - [KIND_DEFERRAL]: the node's agent waiting for its owner. The agent's
 *    question text does not travel, only a digest of it, so a card shows the
 *    domain hint and the task.
 *
 * Every field but the id defaults, as in [GroupInvite]: one row a reader cannot
 * decode must not cost the whole list.
 */
@Serializable
data class NodeDecision(
    @SerialName("decision_id") val decisionId: String,
    val kind: String = "",
    /** The room (or, for a deferral, the room it came from, if any). */
    val circle: String? = null,
    /** Who the decision is about: the invitee for a seat, the agent for a deferral. */
    val subject: String? = null,
    val role: String? = null,
    @SerialName("proposed_by") val proposedBy: String? = null,
    @SerialName("proposed_at") val proposedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("acceptance_id") val acceptanceId: String? = null,
    @SerialName("consensus_protocol") val consensusProtocol: String? = null,
    /** How many voices the room's rule needs. */
    val required: Int? = null,
    /** Who may answer, as key ids. */
    val eligible: List<String> = emptyList(),
    /** `approve` or `decline` when this person has answered; null when they have not. */
    @SerialName("my_answer") val myAnswer: String? = null,
    /** Voices signed so far, from the held signatures (0.5.247). Null on a node that does not count them. */
    val signed: Int? = null,
    // A deferral's fields.
    @SerialName("deferral_id") val deferralId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("domain_hint") val domainHint: String? = null,
    @SerialName("reason_digest") val reasonDigest: String? = null,
    @SerialName("request_attestation_id") val requestAttestationId: String? = null,
    @SerialName("reason_id") val reasonId: String? = null,
    /** The node's English line for [reasonId]. */
    val says: String? = null,
) {
    val answered: Boolean get() = !myAnswer.isNullOrBlank()

    companion object {
        const val KIND_QUORUM_COSIGN = "quorum_cosign"
        const val KIND_DEFERRAL = "deferral"
        const val APPROVE = "approve"
        const val DECLINE = "decline"

        /** A seat decision's id, from the invitation's proposal (`seat_decision_id`, communities.rs). */
        fun seatDecisionId(proposalId: String): String = "$KIND_QUORUM_COSIGN:$proposalId"
    }
}

/** `GET /v1/self/decisions` → `{decisions, count}`. */
@Serializable
data class NodeDecisionList(
    val decisions: List<NodeDecision> = emptyList(),
    val count: Int? = null,
)

/**
 * What `POST /v1/self/decisions/{id}/answer` said. 200 `applied` (a seat:
 * `seated`; a deferral: `response_attestation_id`), or 202
 * `decisions.awaiting_members` with `required` and `signed` when the room's
 * rule needs more voices. The 202 carries a `reason_id` and is NOT a refusal:
 * the answer is recorded.
 */
@Serializable
data class DecisionAnswered(
    @SerialName("decision_id") val decisionId: String = "",
    val answer: String? = null,
    /** `applied` or `awaiting_members`. */
    val state: String = "",
    val seated: List<String> = emptyList(),
    val required: Int? = null,
    val signed: Int? = null,
    @SerialName("response_attestation_id") val responseAttestationId: String? = null,
    @SerialName("reason_id") val reasonId: String? = null,
) {
    val applied: Boolean get() = state == STATE_APPLIED

    companion object {
        const val STATE_APPLIED = "applied"
        const val STATE_AWAITING = "awaiting_members"
    }
}
