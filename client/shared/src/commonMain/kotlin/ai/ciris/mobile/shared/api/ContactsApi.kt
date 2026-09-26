package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.AddContactResponse
import ai.ciris.mobile.shared.models.federation.AnnounceOwnershipResponse
import ai.ciris.mobile.shared.models.federation.ContactCodeResponse
import ai.ciris.mobile.shared.models.federation.ContactListResponse
import ai.ciris.mobile.shared.models.federation.FederationPeerListResponse

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
    /** `GET /v1/contacts` — on the client's active node, as before. */
    suspend fun listContacts(): ContactListResponse

    /** `GET /v1/federation/peers` — the picker's known identities. */
    suspend fun listPeers(): FederationPeerListResponse

    /** `POST {nodeUrl}/v1/contacts {key_id}` — a fed-ID or a contact code. */
    suspend fun addContact(nodeUrl: String, keyId: String): AddContactResponse

    /** `GET {nodeUrl}/v1/self/contact-code?nodes=` — null = every announced device. */
    suspend fun contactCode(nodeUrl: String, nodes: String?): ContactCodeResponse

    /** `POST {nodeUrl}/v1/federation/announce` — make THIS device reachable. */
    suspend fun announceThisDevice(nodeUrl: String): AnnounceOwnershipResponse
}

/** [ContactsApi] over the real client, with the client's session. */
class ClientContactsApi(private val client: CIRISApiClient) : ContactsApi {
    override suspend fun listContacts(): ContactListResponse = client.listContacts()
    override suspend fun listPeers(): FederationPeerListResponse = client.listFederationPeers()
    override suspend fun addContact(nodeUrl: String, keyId: String): AddContactResponse =
        client.addContact(keyId, nodeUrl = nodeUrl)
    override suspend fun contactCode(nodeUrl: String, nodes: String?): ContactCodeResponse =
        client.getContactCode(nodes, nodeUrl = nodeUrl)
    override suspend fun announceThisDevice(nodeUrl: String): AnnounceOwnershipResponse =
        client.announceOwnership(localNodeUrl = nodeUrl)
}
