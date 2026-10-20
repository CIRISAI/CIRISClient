package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * **The self roster's held changes** (CIRISServer#794, CC 3.3.6.3) — the wire
 * shapes of `GET /v1/self/roster/proposals` and the two answers, as CIRISServer
 * CIRISServer#802 @ `e1201b69` serves them (`src/self_devices.rs:1405-1541`,
 * routes `:1572-1583`). One standing is persist v59.0.0's `Standing`
 * (`src/federation/self_roster.rs:880-892`) with two keys the server adds
 * (`standing_view`, `:1417-1424`): `outcome` (the decision's token) and `lands`.
 *
 * Timestamps are chrono `DateTime<Utc>`: RFC 3339 strings.
 */

/** One proposal (`two_path::Proposal`, persist `src/federation/two_path.rs:562-577`). */
@Serializable
data class RosterProposal(
    val id: String,
    /** `add` or `evict` (`ProposalKind`, snake_case). */
    val kind: String = "",
    /** The roster key that proposed it: the device whose approve or revoke this is. */
    val proposer: String = "",
    /** The key added or evicted. */
    val target: String = "",
    @SerialName("asserted_at") val assertedAt: String? = null,
    /** Where this node's window started; the lone path counts W_self from here. */
    @SerialName("received_at") val receivedAt: String? = null,
)

/**
 * The decision now (`TwoPathDecision`, `#[serde(tag = "outcome")]`, persist
 * `two_path.rs:645-674`). One of:
 *  - `lands` with [path] `quorum` | `lone`;
 *  - `pending` with [earliestLoneAt] (null = the lone path is closed) and [needs];
 *  - `refused` with [reason] from [ROSTER_REFUSAL_REASONS];
 *  - `cancelled` with [reason] from [ROSTER_CANCEL_REASONS].
 */
@Serializable
data class RosterDecision(
    val outcome: String = "",
    val path: String? = null,
    @SerialName("earliest_lone_at") val earliestLoneAt: String? = null,
    val needs: Int? = null,
    val reason: String? = null,
)

/** One open change and where it stands (`standing_view`, `src/self_devices.rs:1417-1424`). */
@Serializable
data class RosterStanding(
    val proposal: RosterProposal,
    val decision: RosterDecision = RosterDecision(),
    @SerialName("decided_at") val decidedAt: String? = null,
    /** Live keys whose assent counts, the proposer excluded. */
    val approvers: List<String> = emptyList(),
    val objectors: List<String> = emptyList(),
    /** The decision's token: `lands`, `pending`, `cancelled`, or the refusal's reason. */
    val outcome: String = "",
    val lands: Boolean = false,
)

/** `GET /v1/self/roster/proposals` (`roster_proposals`, `src/self_devices.rs:1428-1451`). */
@Serializable
data class RosterProposalsResponse(
    @SerialName("identity_key_id") val identityKeyId: String = "",
    val roster: List<String> = emptyList(),
    @SerialName("w_self_secs") val wSelfSecs: Long = ROSTER_W_SELF_DEFAULT_SECS,
    val proposals: List<RosterStanding> = emptyList(),
)

/** `POST /v1/self/roster/proposals/{id}/assent|object` (`answer_proposal`, `:1478-1541`). */
@Serializable
data class RosterAnswerResponse(
    @SerialName("proposal_id") val proposalId: String = "",
    /** `assent` or `object`. */
    val answer: String = "",
    @SerialName("answer_attestation_id") val answerAttestationId: String? = null,
    /** Gone from the open set: landed, or cancelled by objections. */
    val settled: Boolean = false,
    val proposal: RosterStanding? = null,
)

/** persist `self_roster::W_SELF_SECS` at v59: the lone path's window, one day. */
const val ROSTER_W_SELF_DEFAULT_SECS: Long = 86_400

/** `TwoPathRefusal`, APPEND-ONLY (persist `two_path.rs:600-643`). Each has a bundle key `mobile.roster_refused_<token>`. */
val ROSTER_REFUSAL_REASONS: List<String> = listOf(
    "proposer_not_on_roster",
    "target_already_on_roster",
    "target_not_on_roster",
    "proposer_is_target",
    "proposer_has_open_proposal",
    "target_has_open_proposal",
)

/** `CancelReason` (persist `two_path.rs:470-500`). Each has a bundle key `mobile.roster_cancelled_<token>`. */
val ROSTER_CANCEL_REASONS: List<String> = listOf(
    "cancel_reached",
    "below_floor",
    "no_reverse_path",
    "target_spoke",
)
