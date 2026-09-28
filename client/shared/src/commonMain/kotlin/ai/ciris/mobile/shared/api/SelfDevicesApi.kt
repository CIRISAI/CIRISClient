package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.LabelOccurrenceResponse
import ai.ciris.mobile.shared.models.federation.OwnedNodesDto
import ai.ciris.mobile.shared.models.federation.ReleaseNodeResponse
import ai.ciris.mobile.shared.models.federation.SelfOccurrencesResponse

/**
 * The owner's own devices and nodes, as the Devices & keys screen reads them
 * (`FSD/ROSTER_AND_DRIVE_CRUD.md` §2 in CIRISServer).
 *
 * An interface of its own, rather than more members on the client protocol, so
 * the view model can be driven by a fake in tests. Every READ the screen makes
 * on load goes through here, which is what keeps a test from touching whatever
 * happens to be listening on the local node's port.
 *
 * The writes throw [NodeRefusal] with the server's id on a non-2xx answer.
 */
interface SelfDevicesApi {
    /** `GET /v1/setup/owned-nodes` — the bound owner and the nodes they own. */
    suspend fun ownedNodes(): OwnedNodesDto

    /** The node's own signer key id (`/v1/federation/self-key-record`), for an unclaimed node. */
    suspend fun selfKeyId(): String

    /** `GET /v1/self/occurrences?include_revoked=true` — active devices, then revoked ones. */
    suspend fun occurrences(identityKeyId: String): SelfOccurrencesResponse

    /** `POST /v1/self/occurrence/label`. */
    suspend fun labelOccurrence(occurrenceKeyId: String, label: String): LabelOccurrenceResponse

    /** `POST /v1/self/nodes/{node_key_id}/release`. */
    suspend fun releaseNode(nodeKeyId: String, forceSelf: Boolean): ReleaseNodeResponse

    /** The NODE's health warnings (not the brain's — see [NodeHealth.warnings]). */
    suspend fun nodeWarnings(): List<SystemWarning>
}

/** [SelfDevicesApi] over the real client: the LOCAL node, with the client's session. */
class ClientSelfDevices(
    private val client: CIRISApiClient,
    private val nodeBaseUrl: String = CIRISApiClient.LOCAL_NODE_URL,
) : SelfDevicesApi {
    override suspend fun ownedNodes(): OwnedNodesDto = client.getOwnedNodes()
    override suspend fun selfKeyId(): String = client.getSelfKeyRecord().keyId
    override suspend fun occurrences(identityKeyId: String): SelfOccurrencesResponse =
        client.getSelfOccurrences(identityKeyId, includeRevoked = true)
    override suspend fun labelOccurrence(occurrenceKeyId: String, label: String): LabelOccurrenceResponse =
        client.labelOccurrence(occurrenceKeyId, label)
    override suspend fun releaseNode(nodeKeyId: String, forceSelf: Boolean): ReleaseNodeResponse =
        client.releaseNode(nodeKeyId, forceSelf)
    override suspend fun nodeWarnings(): List<SystemWarning> = client.getNodeHealth(nodeBaseUrl).warnings
}
