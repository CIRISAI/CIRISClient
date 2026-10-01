package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **Invitations into a household or a community** — CIRISServer 0.5.218,
 * `src/membership_invites.rs` (the invitee's side, `/v1/self/invites`) and the
 * `invite` / `list_invites` / `withdraw_invite` handlers in `src/family_api.rs`
 * and `src/communities.rs` (the group's side). CSD-106.
 *
 * Nobody joins without saying yes (the maintainer's ruling of 2026-09-30,
 * CIRISConstitution#133; persist v52, CIRISPersist#955): one inviter signs a
 * `membership:proposal:v1`, the invitee signs an acceptance or a decline with
 * their own key, and the group seats them with a widening that persist admits
 * only on that acceptance. Accepted is NOT joined: under a quorum rule the
 * group still has to sign. Only [GroupInvite.state] `joined` says membership.
 *
 * Every member but the id is optional-with-default, as in [FamilyDto]: one row
 * a reader cannot decode must not cost the whole list.
 */

/** The six states the server reports (`INVITE_STATES`, `membership_invites.rs`). */
object InviteState {
    const val PENDING = "pending"
    /** Accepted; the group has not seated them yet. */
    const val ACCEPTED = "accepted"
    /** Accepted AND on the roster now. The only state that is membership. */
    const val JOINED = "joined"
    const val DECLINED = "declined"
    const val EXPIRED = "expired"
    const val WITHDRAWN = "withdrawn"
}

/**
 * `POST …/{families,communities}/{id}/invites` (and, on 0.5.218, the
 * `…/members` alias) → **202** `{state: "invited", proposal_id, group_kind,
 * group_id, invitee_key_id, role, expires_at}`. `state` is always `invited`,
 * so no caller mistakes an invitation for a membership.
 */
@Serializable
data class InviteSent(
    val state: String = "",
    @SerialName("proposal_id") val proposalId: String = "",
    @SerialName("group_kind") val groupKind: String? = null,
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("invitee_key_id") val inviteeKeyId: String? = null,
    val role: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    companion object {
        const val STATE_INVITED = "invited"
    }
}

/** One invitation into a group, as its members see it (`InviteView`). */
@Serializable
data class GroupInvite(
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("invitee_key_id") val inviteeKeyId: String = "",
    val role: String? = null,
    @SerialName("proposer_key_id") val proposerKeyId: String = "",
    @SerialName("proposed_at") val proposedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    /** One of [InviteState]. */
    val state: String = InviteState.PENDING,
    @SerialName("reply_id") val replyId: String? = null,
)

/**
 * `GET …/{families,communities}/{id}/invites` → `{invites, seated_now}` (a
 * household adds `family_id` and `dek_rewrap`, a community `community_id`).
 * `seated_now` is who THIS read seated: under `founder_only` a founder's list
 * is when an accepted invitee is widened in, if the bridge has not done it.
 */
@Serializable
data class GroupInviteList(
    val invites: List<GroupInvite> = emptyList(),
    @SerialName("seated_now") val seatedNow: List<String> = emptyList(),
    /**
     * The caller's own key, as the node read it from the session (0.5.219+;
     * absent on 0.5.218). Compared with [GroupInvite.proposerKeyId] to offer
     * Withdraw on your own invitations only.
     */
    @SerialName("viewer_key_id") val viewerKeyId: String? = null,
)

/** One row of the invitee's inbox, `GET /v1/self/invites`. */
@Serializable
data class InboxInvite(
    @SerialName("proposal_id") val proposalId: String,
    /** `family` or `community`. */
    @SerialName("group_kind") val groupKind: String = "",
    @SerialName("group_id") val groupId: String = "",
    /** Null when this node does not hold the group's record (the invitee is not in it yet). */
    @SerialName("group_name") val groupName: String? = null,
    /** A two-person chat's invitation; answered by opening the chat (CSD-091), not here. */
    @SerialName("is_pair_room") val isPairRoom: Boolean = false,
    val role: String? = null,
    @SerialName("proposer_key_id") val proposerKeyId: String = "",
    @SerialName("proposed_at") val proposedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    /**
     * `community` or `affiliations` — the room's audience tier (0.5.219+; null
     * for a household, a pair room, and on 0.5.218, which does not send it).
     * When present, each community hub shows only its own tier's invitations.
     */
    val tier: String? = null,
) {
    companion object {
        const val KIND_FAMILY = "family"
        const val KIND_COMMUNITY = "community"
    }
}

/** `GET /v1/self/invites` → `{invitee_key_id, invites}`. */
@Serializable
data class InviteInbox(
    @SerialName("invitee_key_id") val inviteeKeyId: String? = null,
    val invites: List<InboxInvite> = emptyList(),
)

/**
 * `POST /v1/self/invites/{proposal_id}/{accept,decline}` → `{state, proposal_id,
 * reply_id, group_kind, group_id, awaiting}`. `awaiting` is set on an accept:
 * the group's widening still has to seat them.
 */
@Serializable
data class InviteAnswer(
    val state: String = "",
    @SerialName("proposal_id") val proposalId: String = "",
    @SerialName("reply_id") val replyId: String? = null,
    @SerialName("group_kind") val groupKind: String? = null,
    @SerialName("group_id") val groupId: String? = null,
    val awaiting: String? = null,
)

/** `DELETE …/invites/{proposal_id}` → `{state: "withdrawn", proposal_id, withdrawal_id}`. */
@Serializable
data class InviteWithdrawn(
    val state: String = "",
    @SerialName("proposal_id") val proposalId: String = "",
    @SerialName("withdrawal_id") val withdrawalId: String? = null,
)
