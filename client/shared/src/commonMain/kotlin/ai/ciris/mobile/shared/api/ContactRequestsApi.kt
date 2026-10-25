package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.AddContactResponse
import ai.ciris.mobile.shared.models.federation.ContactRequest
import ai.ciris.mobile.shared.models.federation.ContactRequestIgnoreAnswer
import ai.ciris.mobile.shared.models.federation.ContactRequestList
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What People asks a node about contact requests (CSD-109). An interface of
 * its own, like [ContactsApi], so the view model is driven by a fake in tests.
 * Every call names the [nodeUrl] it goes to. Refusals throw [NodeRefusal].
 */
interface ContactRequestsApi {
    /** `GET {nodeUrl}/v1/contacts/requests`. */
    suspend fun list(nodeUrl: String): ContactRequestList

    /** The entry's own `accept` route: `POST {nodeUrl}/v1/contacts {key_id}` (CSD-005's add). */
    suspend fun accept(nodeUrl: String, request: ContactRequest): AddContactResponse

    /** `POST {nodeUrl}/v1/contacts/requests/{grant_attestation_id}/ignore`. */
    suspend fun ignore(nodeUrl: String, grantAttestationId: String): ContactRequestIgnoreAnswer

    /** `DELETE {nodeUrl}/v1/contacts/requests/{grant_attestation_id}/ignore`. */
    suspend fun unignore(nodeUrl: String, grantAttestationId: String): ContactRequestIgnoreAnswer
}

/** The add route an `accept` may name. Anything else is refused locally, never sent. */
internal const val CONTACT_REQUEST_ACCEPT_PATH = "/v1/contacts"

/**
 * The key an entry's `accept` route adds: its body's `key_id`, else the
 * requester's. Null when the route is not `POST /v1/contacts` — the client
 * sends the server's own body, but only to the one route this card names.
 */
fun acceptKeyOf(request: ContactRequest): String? {
    val route = request.accept
    if (route != null && (!route.method.equals("POST", ignoreCase = true) || route.path != CONTACT_REQUEST_ACCEPT_PATH)) {
        return null
    }
    val fromBody = (route?.body?.get("key_id") as? JsonPrimitive)?.contentOrNull
    return fromBody?.takeIf { it.isNotBlank() } ?: request.requesterKeyId.takeIf { it.isNotBlank() }
}

/** [ContactRequestsApi] over the real client, with the client's session. */
class ClientContactRequestsApi(private val client: CIRISApiClient) : ContactRequestsApi {
    override suspend fun list(nodeUrl: String): ContactRequestList = client.listContactRequests(nodeUrl)

    override suspend fun accept(nodeUrl: String, request: ContactRequest): AddContactResponse {
        val key = acceptKeyOf(request)
            ?: throw IllegalStateException(
                "accept route ${request.accept?.method} ${request.accept?.path} is not POST $CONTACT_REQUEST_ACCEPT_PATH; not sent"
            )
        return client.addContact(key, nodeUrl = nodeUrl)
    }

    override suspend fun ignore(nodeUrl: String, grantAttestationId: String): ContactRequestIgnoreAnswer =
        client.ignoreContactRequest(grantAttestationId, nodeUrl)

    override suspend fun unignore(nodeUrl: String, grantAttestationId: String): ContactRequestIgnoreAnswer =
        client.unignoreContactRequest(grantAttestationId, nodeUrl)
}
