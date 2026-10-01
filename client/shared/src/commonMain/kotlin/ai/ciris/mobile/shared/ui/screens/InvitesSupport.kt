package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.RouteNotOnThisHost
import ai.ciris.mobile.shared.models.federation.GroupInvite
import ai.ciris.mobile.shared.models.federation.InboxInvite
import ai.ciris.mobile.shared.models.federation.InviteState

/**
 * The pure half of invitations (CSD-106): tags, which node this is, and what a
 * roster or a hub shows of an invitation. No Compose, so each rule is tested
 * directly.
 *
 * The inbox sits on the hubs (CSD-100's Family › Rules, CSD-102's two community
 * Rules hubs), because the person invited is not in the group yet and its
 * roster has nothing to show them. The pending rows and the Invite action sit
 * on the rosters (CSD-101, CSD-103), the screens that called the add routes.
 */
object InviteTags {
    // ── The inbox, on a hub ──
    const val INBOX_LIST = "invitations_list"
    const val INBOX_EMPTY = "invitations_empty"
    const val INBOX_LOADING = "invitations_loading"
    const val INBOX_ERROR = "invitations_error"
    const val INBOX_NOT_ON_THIS_NODE = "invitations_not_on_this_node"
    const val INBOX_REFUSAL = "invitation_refusal"
    const val INBOX_NOTICE = "invitation_notice"
    fun row(proposalId: String) = "invitation_row_${slug(proposalId)}"
    fun accept(proposalId: String) = "btn_invitation_accept_${slug(proposalId)}"
    fun decline(proposalId: String) = "btn_invitation_decline_${slug(proposalId)}"
    fun expires(proposalId: String) = "invitation_expires_${slug(proposalId)}"
    /** ConfirmSheet prefixes: `sheet_invitation_answer_accept`, `btn_invitation_answer_accept_confirm`, … */
    const val CONFIRM_ACCEPT = "invitation_answer_accept"
    const val CONFIRM_DECLINE = "invitation_answer_decline"

    // ── The pending rows, on a roster ──
    fun pending(proposalId: String) = "invitation_pending_${slug(proposalId)}"
    fun withdraw(proposalId: String) = "btn_invitation_withdraw_${slug(proposalId)}"
    /** Under a quorum rule: propose seating someone who accepted (the household's existing envelope). */
    fun seat(proposalId: String) = "btn_invitation_seat_${slug(proposalId)}"
    const val ROSTER_INVITES_ERROR = "invitations_roster_error"
    /** CSD-103's room sections: one confirm for an invitation sent from a room, one for withdrawing it. */
    const val COMMUNITY_CONFIRM_INVITE = "community_invite"
    const val COMMUNITY_CONFIRM_WITHDRAW = "community_invite_withdraw"

    /** The founding cards' sentence, on a node that admits only the founder at creation. */
    const val FOUND_ALONE_HOUSEHOLD = "household_create_found_alone"
    const val FOUND_ALONE_COMMUNITY = "community_create_found_alone"

    fun slug(id: String): String = id.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

/**
 * Does this node carry invitations? Answered by the ROUTE, never by a version
 * string: a bare 404 (no `reason_id`) on an invites route is a node older than
 * 0.5.218, where today's direct add still stands. A refusal WITH an id
 * (`family.not_found`, `membership.owner_session_required`, …) is a node that
 * has the route and said no, which is a different fact.
 */
enum class InviteSupport {
    /** Not asked yet, or the read failed for another reason. Act-time detection still applies. */
    UNKNOWN,
    /** 0.5.218+: adding someone is inviting them. */
    INVITES,
    /** 0.5.216–0.5.217: no invites routes; the direct add is what the node serves. */
    LEGACY,
}

/** The interim build (CIRISServer#700, never released) closed every roster door with this id. */
const val MEMBERSHIP_CONSENT_REQUIRED = "membership.consent_required"

/** An invites route that is not there: an id-less 404, or a host the client knew had no such route. */
fun isInviteRouteMissing(e: Throwable): Boolean =
    e is RouteNotOnThisHost || (e is NodeRefusal && e.statusCode == 404 && e.reasonId == null)

/**
 * Which invitations a roster draws. `joined` is already a member row, and a
 * `withdrawn` one is gone; every other state is shown as the node says it,
 * and none of them is counted as a member.
 */
fun rosterInvites(invites: List<GroupInvite>): List<GroupInvite> =
    invites.filter { it.state != InviteState.JOINED && it.state != InviteState.WITHDRAWN }
        .sortedWith(compareBy<GroupInvite> { stateOrder(it.state) }.thenByDescending { it.proposedAt.orEmpty() })

private fun stateOrder(state: String): Int = when (state) {
    InviteState.ACCEPTED -> 0
    InviteState.PENDING -> 1
    InviteState.DECLINED -> 2
    InviteState.EXPIRED -> 3
    else -> 4
}

/**
 * The invitations one hub shows. The household hub shows `family`; each
 * community hub shows `community`. A pair room's invitation is left out: a
 * two-person chat is answered by opening it (CSD-091), not here.
 */
fun inboxFor(invites: List<InboxInvite>, kind: String): List<InboxInvite> =
    invites.filter { !it.isPairRoom && it.groupKind == kind }
        .sortedByDescending { it.proposedAt.orEmpty() }

/** May this person withdraw it? Only its proposer, and only while nobody has answered. */
fun canWithdraw(invite: GroupInvite, me: String?): Boolean =
    me != null && invite.proposerKeyId == me && invite.state == InviteState.PENDING
