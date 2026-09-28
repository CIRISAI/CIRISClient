package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.OwnedNodesDto
import ai.ciris.mobile.shared.models.federation.PeeringRequest
import ai.ciris.mobile.shared.models.federation.PeeringResponse
import ai.ciris.mobile.shared.models.federation.SignedKeyRecord

/**
 * What the Manage Consent card asks the nodes to SET UP a bilateral
 * `consent:replication` grant (CSD-053); withdrawing one is [ConsentWithdrawApi].
 *
 * An interface of its own, like [ContactsApi] and [ConsentWithdrawApi], so the
 * view model can be driven by a fake — and so the calls stay statically
 * traceable: `packaging/check_csd_routes.py` follows an interface to its
 * implementor and on to the client method, which is how CSD-053's §3 is
 * checked against the routes the screen really calls. A function-valued
 * constructor default hides the call from that walk, and the checker then
 * reports the route as an unused citation while the code still calls it
 * (Codex, PR #116).
 *
 * Every call that addresses a node names the node AND the session to use on
 * it: node A's own, never the client's, which after a switch is another node's.
 */
interface PeeringApi {
    /** `GET {localNodeUrl}/v1/setup/owned-nodes` — the node pair the card defaults from. */
    suspend fun ownedNodes(): OwnedNodesDto

    /** `GET {nodeUrl}/v1/federation/self-key-record`, as [token]. */
    suspend fun selfKeyRecord(nodeUrl: String, token: String?): SignedKeyRecord

    /** `POST {nodeUrl}/v1/federation/peering`, as [token] — the node authors its grant to the peer named. */
    suspend fun peer(nodeUrl: String, token: String?, request: PeeringRequest): PeeringResponse
}

/** [PeeringApi] over the real client. */
class ClientPeeringApi(private val client: CIRISApiClient) : PeeringApi {
    override suspend fun ownedNodes(): OwnedNodesDto = client.getOwnedNodes()

    override suspend fun selfKeyRecord(nodeUrl: String, token: String?): SignedKeyRecord =
        client.getSelfKeyRecord(nodeUrl, token)

    override suspend fun peer(nodeUrl: String, token: String?, request: PeeringRequest): PeeringResponse =
        client.postPeering(request, nodeUrl, token)
}
