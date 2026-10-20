package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.RosterAnswerResponse
import ai.ciris.mobile.shared.models.federation.RosterProposalsResponse

/**
 * **The self roster's held changes** (CSD-095, CIRISServer#794, CC 3.3.6.3):
 * the owner's door onto the changes to their own device keys that persist
 * holds for the other devices to answer. Proposing has no route of its own —
 * the add-device approval and the device revoke ARE the proposals when held.
 *
 * An interface so the card's view model is driven by a fake in tests. Every
 * non-2xx is a [NodeRefusal].
 */
interface SelfRosterApi {
    /** `GET /v1/self/roster/proposals`. */
    suspend fun proposals(): RosterProposalsResponse

    /** `POST /v1/self/roster/proposals/{id}/assent` (body `{}`). */
    suspend fun assent(proposalId: String): RosterAnswerResponse

    /** `POST /v1/self/roster/proposals/{id}/object` (body `{}`). */
    suspend fun objectTo(proposalId: String): RosterAnswerResponse
}

/**
 * [SelfRosterApi] over the real client, on the node the card is showing — read
 * on every call, like [ClientDeviceRequests]: answering a change to the wrong
 * identity's devices is an act on the wrong identity (Codex, PR #189).
 */
class ClientSelfRoster(
    private val client: CIRISApiClient,
    private val nodeUrl: () -> String,
) : SelfRosterApi {
    override suspend fun proposals() = client.getRosterProposals(nodeUrl())
    override suspend fun assent(proposalId: String) = client.answerRosterProposal(proposalId, assent = true, nodeUrl = nodeUrl())
    override suspend fun objectTo(proposalId: String) = client.answerRosterProposal(proposalId, assent = false, nodeUrl = nodeUrl())
}
