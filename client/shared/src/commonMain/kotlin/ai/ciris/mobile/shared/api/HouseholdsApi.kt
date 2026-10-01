package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.FamilyChangeProposal
import ai.ciris.mobile.shared.models.federation.FamilyCosignResponse
import ai.ciris.mobile.shared.models.federation.FamilyDto
import ai.ciris.mobile.shared.models.federation.FamilyListResponse
import ai.ciris.mobile.shared.models.federation.FamilySignatureDto
import ai.ciris.mobile.shared.models.federation.GroupInviteList
import ai.ciris.mobile.shared.models.federation.InviteSent
import ai.ciris.mobile.shared.models.federation.InviteWithdrawn
import kotlinx.serialization.json.JsonObject

/**
 * The node's household routes (`/v1/families`, CIRISServer 0.5.216,
 * `src/family_api.rs`), as the Households and Household members cards read
 * and write them (CSD-100, CSD-101).
 *
 * An interface of its own, like [SelfDevicesApi], so the view model is driven
 * by a fake in tests and never by whatever is listening on the node's port.
 *
 * Every call goes to the NODE (`$nodeUrl`), never `$baseUrl`: households are
 * the node owner's own surface, and on a with-AI install `$baseUrl` is the
 * agent, which does not proxy them (CIRISAgent#1213). Every non-2xx answer
 * throws [NodeRefusal] carrying the node's `family.*` id; a bare 404 with no
 * id is a node that predates the routes.
 */
interface HouseholdsApi {
    /** `GET /v1/families?after=` — one page of the households the owner is an active member of. */
    suspend fun listFamilies(after: String?): FamilyListResponse

    /** `POST /v1/families` `{name, consensus_protocol?, members?}` — the caller becomes the founder. */
    suspend fun createFamily(name: String, consensusProtocol: String?, members: List<String>): FamilyDto

    /** `DELETE /v1/families/{id}` — dissolve (founder_only). */
    suspend fun dissolveFamily(familyId: String)

    /**
     * `POST /v1/families/{id}/members` `{key_id, role?}` — the direct add a
     * 0.5.216–0.5.217 node serves. Null when they were added; the invitation
     * when the node answered `{state: "invited"}` instead (0.5.218's alias).
     */
    suspend fun addMember(familyId: String, keyId: String, role: String?): InviteSent?

    /** `POST /v1/families/{id}/invites` `{key_id, role?}` — 0.5.218+. A bare 404 is an older node (CSD-106). */
    suspend fun inviteMember(familyId: String, keyId: String, role: String?): InviteSent

    /** `GET /v1/families/{id}/invites` — every invitation into the household and its state. */
    suspend fun listInvites(familyId: String): GroupInviteList

    /** `DELETE /v1/families/{id}/invites/{proposal_id}` — only the proposer may. */
    suspend fun withdrawInvite(familyId: String, proposalId: String): InviteWithdrawn

    /** `DELETE /v1/families/{id}/members/{key_id}`. */
    suspend fun removeMember(familyId: String, keyId: String)

    /** `POST /v1/families/{id}/members/{key_id}/role` `{role}`. */
    suspend fun changeRole(familyId: String, keyId: String, role: String)

    /** `POST /v1/families/{id}/leave` — always the caller's own act, whatever the protocol. */
    suspend fun leave(familyId: String)

    /** `POST /v1/families/{id}/changes/envelope` `{action, key_id?, role?}` — quorum families only. */
    suspend fun proposeChange(familyId: String, action: String, keyId: String?, role: String?): FamilyChangeProposal

    /** `POST /v1/families/{id}/changes/cosign` — THIS node's owner signs; returns the running set. */
    suspend fun cosign(familyId: String, envelope: JsonObject, signatures: List<FamilySignatureDto>): FamilyCosignResponse

    /** `POST /v1/families/{id}/changes/assemble` — apply a change once enough members have signed. */
    suspend fun assemble(familyId: String, envelope: JsonObject, signatures: List<FamilySignatureDto>)

    /** The owner's contacts — the only people a member can be added from (CSD-101). */
    suspend fun contacts(): List<Contact>

    /** The owner's own fed-ID key, so the roster can say "you" and never offers to remove you. Null if unknown. */
    suspend fun myKeyId(): String?
}

/** [HouseholdsApi] over the real client: the node at [nodeBaseUrl], with the client's session. */
class ClientHouseholds(
    private val client: CIRISApiClient,
    private val nodeBaseUrl: String = CIRISApiClient.LOCAL_NODE_URL,
) : HouseholdsApi {
    override suspend fun listFamilies(after: String?): FamilyListResponse = client.listFamilies(after, nodeBaseUrl)
    override suspend fun createFamily(name: String, consensusProtocol: String?, members: List<String>): FamilyDto =
        client.createFamily(name, consensusProtocol, members, nodeBaseUrl)
    override suspend fun dissolveFamily(familyId: String) = client.dissolveFamily(familyId, nodeBaseUrl)
    override suspend fun addMember(familyId: String, keyId: String, role: String?): InviteSent? =
        client.addFamilyMember(familyId, keyId, role, nodeBaseUrl)
    override suspend fun inviteMember(familyId: String, keyId: String, role: String?): InviteSent =
        client.inviteFamilyMember(familyId, keyId, role, nodeBaseUrl)
    override suspend fun listInvites(familyId: String): GroupInviteList =
        client.listFamilyInvites(familyId, nodeBaseUrl)
    override suspend fun withdrawInvite(familyId: String, proposalId: String): InviteWithdrawn =
        client.withdrawFamilyInvite(familyId, proposalId, nodeBaseUrl)
    override suspend fun removeMember(familyId: String, keyId: String) =
        client.removeFamilyMember(familyId, keyId, nodeBaseUrl)
    override suspend fun changeRole(familyId: String, keyId: String, role: String) =
        client.changeFamilyRole(familyId, keyId, role, nodeBaseUrl)
    override suspend fun leave(familyId: String) = client.leaveFamily(familyId, nodeBaseUrl)
    override suspend fun proposeChange(familyId: String, action: String, keyId: String?, role: String?): FamilyChangeProposal =
        client.proposeFamilyChange(familyId, action, keyId, role, nodeBaseUrl)
    override suspend fun cosign(familyId: String, envelope: JsonObject, signatures: List<FamilySignatureDto>): FamilyCosignResponse =
        client.cosignFamilyChange(familyId, envelope, signatures, nodeBaseUrl)
    override suspend fun assemble(familyId: String, envelope: JsonObject, signatures: List<FamilySignatureDto>) =
        client.assembleFamilyChange(familyId, envelope, signatures, nodeBaseUrl)
    override suspend fun contacts(): List<Contact> = client.listContacts(nodeBaseUrl).contacts
    override suspend fun myKeyId(): String? = client.getOwnedNodes(nodeBaseUrl).owner
}
