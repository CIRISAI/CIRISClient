package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.RemoveContactResponse
import ai.ciris.mobile.shared.models.federation.RevokeGrantResponse

/**
 * Withdrawing consent (CIRISServer#657), as the People and Manage Consent
 * screens drive it.
 *
 * An interface of its own, like [SelfDevicesApi], so the view models can be
 * driven by a fake. Every call names the NODE it goes to: the view model
 * decides the URL, and the fake records it, which is how a test pins that a
 * withdrawal never goes to the agent's `baseUrl` (CIRISAgent#1213).
 *
 * Non-2xx answers throw [NodeRefusal], except the node-authored-grant 409,
 * which comes back as a response with `remaining_grants` set.
 */
interface ConsentWithdrawApi {
    /** `DELETE {nodeUrl}/v1/contacts/{keyId}`. */
    suspend fun removeContact(nodeUrl: String, keyId: String): RemoveContactResponse

    /** `POST {nodeUrl}/v1/federation/peering/revoke`. */
    suspend fun revokeGrant(nodeUrl: String, attestationId: String): RevokeGrantResponse

    /** Is the revoke route mounted? `null` = could not tell. */
    suspend fun revokeRouteMounted(nodeUrl: String): Boolean?
}

/** [ConsentWithdrawApi] over the real client, with the client's session. */
class ClientConsentWithdraw(private val client: CIRISApiClient) : ConsentWithdrawApi {
    override suspend fun removeContact(nodeUrl: String, keyId: String): RemoveContactResponse =
        client.removeContact(keyId, nodeUrl)

    override suspend fun revokeGrant(nodeUrl: String, attestationId: String): RevokeGrantResponse =
        client.revokePeeringGrant(attestationId, nodeUrl)

    override suspend fun revokeRouteMounted(nodeUrl: String): Boolean? =
        client.isPeeringRevokeMounted(nodeUrl)
}

/** A route the node does not mount answers 404 with no `reason_id`; every refusal it authors carries one. */
fun NodeRefusal.isRouteMissing(): Boolean = statusCode == 404 && reasonId == null
