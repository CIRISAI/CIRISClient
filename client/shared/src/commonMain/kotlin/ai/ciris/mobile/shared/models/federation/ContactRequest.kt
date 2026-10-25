package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * **Contact requests** — CIRISServer 0.5.237 (#819, #820; `FSD/MEMBERSHIP_INVITES.md`
 * §8, §8.1). CSD-109.
 *
 * A request is a `consent:replication:v1` grant naming this node's owner from
 * someone the owner has not granted back: they added you by your code and you
 * have not added them. One entry per person (the newest live grant), keyed by
 * [grantAttestationId]. It lasts as long as its grant; there is no TTL.
 *
 * Every member but the id is optional-with-default, as in [InboxInvite]: one
 * row a reader cannot decode must not cost the whole list.
 */

/** A route the server hands the client to call, as `{method, path, body}`. */
@Serializable
data class RequestRoute(
    val method: String = "",
    val path: String = "",
    val body: JsonObject? = null,
)

/** One pending (or, under `ignored`, ignored) contact request. */
@Serializable
data class ContactRequest(
    @SerialName("grant_attestation_id") val grantAttestationId: String,
    val kind: String = "contact",
    @SerialName("requester_key_id") val requesterKeyId: String = "",
    /**
     * The label the requester typed when they minted their fed-ID key
     * (`<label>-<fingerprint>`): their own claim, never verified. Null for a key
     * not of that form, and on a node before #820's b784f8f9.
     */
    @SerialName("requester_label") val requesterLabel: String? = null,
    @SerialName("granted_by_key_id") val grantedByKeyId: String? = null,
    @SerialName("granted_to_key_id") val grantedToKeyId: String? = null,
    @SerialName("requested_at") val requestedAt: String? = null,
    /** The grant's envelope (key ids only); kept for the receipt, not read here. */
    val grant: JsonElement? = null,
    /** How to accept: `POST /v1/contacts {key_id}`, sent as given (§3). */
    val accept: RequestRoute? = null,
    /** How to ignore: `POST /v1/contacts/requests/{grant_attestation_id}/ignore`. */
    val ignore: RequestRoute? = null,
    /** Present on an `ignored` entry. */
    @SerialName("ignore_id") val ignoreId: String? = null,
)

/** `GET /v1/contacts/requests` → `{requests, total, ignored}`. */
@Serializable
data class ContactRequestList(
    val requests: List<ContactRequest> = emptyList(),
    val total: Int = 0,
    val ignored: List<ContactRequest> = emptyList(),
)

/**
 * `POST …/ignore` → `{state: "ignored", grant_attestation_id, ignore_id}`;
 * `DELETE …/ignore` → `{state: "pending", grant_attestation_id, withdrawal_id}`.
 */
@Serializable
data class ContactRequestIgnoreAnswer(
    val state: String = "",
    @SerialName("grant_attestation_id") val grantAttestationId: String = "",
    @SerialName("ignore_id") val ignoreId: String? = null,
    @SerialName("withdrawal_id") val withdrawalId: String? = null,
)
