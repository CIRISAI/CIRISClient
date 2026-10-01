package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.InviteAnswer
import ai.ciris.mobile.shared.models.federation.InviteInbox

/**
 * The invitee's side of an invitation (CIRISServer 0.5.218,
 * `src/membership_invites.rs`, `/v1/self/invites`), as the hubs' inbox reads
 * and answers it (CSD-106).
 *
 * An interface of its own, like [HouseholdsApi], so the view model is driven
 * by a fake in tests. Every call goes to the NODE: invitations are the node
 * owner's own surface, and on a with-AI install `$baseUrl` is the agent, which
 * does not proxy them (CIRISAgent#1213). A non-2xx throws [NodeRefusal] with
 * the node's `membership.*` id; a bare 404 is a node older than 0.5.218.
 */
interface MembershipInvitesApi {
    /** `GET /v1/self/invites` — every live, unanswered invitation naming this node's owner. */
    suspend fun myInvites(): InviteInbox

    /** `POST /v1/self/invites/{proposal_id}/accept` — the invitee's own signature. Accepted is not joined. */
    suspend fun accept(proposalId: String): InviteAnswer

    /** `POST /v1/self/invites/{proposal_id}/decline` — final for this invitation. */
    suspend fun decline(proposalId: String): InviteAnswer
}

/** [MembershipInvitesApi] over the real client: the node at [nodeBaseUrl], with the client's session. */
class ClientMembershipInvites(
    private val client: CIRISApiClient,
    private val nodeBaseUrl: String = CIRISApiClient.LOCAL_NODE_URL,
) : MembershipInvitesApi {
    override suspend fun myInvites(): InviteInbox = client.listMyInvites(nodeBaseUrl)
    override suspend fun accept(proposalId: String): InviteAnswer = client.acceptInvite(proposalId, nodeBaseUrl)
    override suspend fun decline(proposalId: String): InviteAnswer = client.declineInvite(proposalId, nodeBaseUrl)
}
