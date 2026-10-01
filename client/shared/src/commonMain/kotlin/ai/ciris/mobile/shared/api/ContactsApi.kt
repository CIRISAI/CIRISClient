package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.AddContactResponse
import ai.ciris.mobile.shared.models.federation.AnnounceOwnershipResponse
import ai.ciris.mobile.shared.models.federation.ContactCodeResponse
import ai.ciris.mobile.shared.models.federation.ContactListResponse
import ai.ciris.mobile.shared.models.federation.FederationPeerListResponse
import ai.ciris.mobile.shared.models.chat.PairRoomInviteAnswer
import ai.ciris.mobile.shared.models.chat.PairRoomInviteInbox

/**
 * What the People screen asks a node (CSD-005, CSD-092).
 *
 * An interface of its own, like [SelfDevicesApi], so the view model can be
 * driven by a fake in tests and no test touches whatever is listening on the
 * local node's port. The calls that name a [nodeUrl] take it from the caller,
 * which is what lets a test see WHERE a call went.
 *
 * Refusals throw [NodeRefusal] with the server's id.
 */
interface ContactsApi {
    /** `GET {nodeUrl}/v1/contacts` — the node's, like every other People call (CSD-005). */
    suspend fun listContacts(nodeUrl: String): ContactListResponse

    /** `GET /v1/federation/peers` — the picker's known identities. */
    suspend fun listPeers(): FederationPeerListResponse

    /** `POST {nodeUrl}/v1/contacts {key_id}` — a fed-ID or a contact code. */
    suspend fun addContact(nodeUrl: String, keyId: String): AddContactResponse

    /** `GET {nodeUrl}/v1/self/contact-code?nodes=` — null = every announced device. */
    suspend fun contactCode(nodeUrl: String, nodes: String?): ContactCodeResponse

    /** `POST {nodeUrl}/v1/federation/announce` — make THIS device reachable. */
    suspend fun announceThisDevice(nodeUrl: String): AnnounceOwnershipResponse

    /**
     * `GET {nodeUrl}/v1/self/invites` (0.5.218) — the invitations waiting for
     * this person, of which People shows the PAIR-ROOM ones on their contact's
     * row (CSD-005, CSD-091). Defaulted so a fake that does not care answers
     * "none"; the real client reads the node.
     */
    suspend fun pairRoomInvites(nodeUrl: String): PairRoomInviteInbox = PairRoomInviteInbox()

    /** `POST {nodeUrl}/v1/self/invites/{proposal_id}/decline` — the person's own signed decline. */
    suspend fun declinePairRoomInvite(nodeUrl: String, proposalId: String): PairRoomInviteAnswer =
        throw UnsupportedOperationException("declinePairRoomInvite")
}

/** [ContactsApi] over the real client, with the client's session. */
class ClientContactsApi(private val client: CIRISApiClient) : ContactsApi {
    override suspend fun listContacts(nodeUrl: String): ContactListResponse = client.listContacts(nodeUrl)
    override suspend fun listPeers(): FederationPeerListResponse = client.listFederationPeers()
    override suspend fun addContact(nodeUrl: String, keyId: String): AddContactResponse =
        client.addContact(keyId, nodeUrl = nodeUrl)
    override suspend fun contactCode(nodeUrl: String, nodes: String?): ContactCodeResponse =
        client.getContactCode(nodes, nodeUrl = nodeUrl)
    override suspend fun announceThisDevice(nodeUrl: String): AnnounceOwnershipResponse =
        client.announceOwnership(localNodeUrl = nodeUrl)
    override suspend fun pairRoomInvites(nodeUrl: String): PairRoomInviteInbox =
        client.listPairRoomInvites(nodeUrl)
    override suspend fun declinePairRoomInvite(nodeUrl: String, proposalId: String): PairRoomInviteAnswer =
        client.declinePairRoomInvite(proposalId, nodeUrl)
}
