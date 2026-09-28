package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **Withdrawing consent** (CIRISServer#657, ciris-server 0.5.218).
 *
 * CC 1.5: *"consent that cannot be withdrawn is not consent"*. The node has two
 * doors onto one mechanism — `DELETE /v1/contacts/{key_id}` (un-contact a
 * person) and `POST /v1/federation/peering/revoke {attestation_id}` (withdraw
 * one replication grant) — and both sign the `withdraws` with the PERSON's key
 * through the owner-signer capsule, never the node's.
 *
 * The limit these types carry, and the reason they exist: a grant the NODE
 * wrote before the person re-signed it (the provisional pre-claim row) is not
 * the person's to withdraw, and the node will not withdraw consent on their
 * behalf. Those grants stay live. A client that reports "removed" while one of
 * them stands has claimed a withdrawal that did not happen.
 */

/** One grant the person's `withdraws` now covers. */
@Serializable
data class WithdrawnGrant(
    /** The grant's `attestation_id`. */
    val grant: String,
    /** The `withdraws` row the person signed. */
    val withdraws: String = "",
    @SerialName("cohort_scope")
    val cohortScope: String? = null,
)

/**
 * `DELETE /v1/contacts/{key_id}` →
 * `{key_id, withdrawn: [...], remaining_grants: [...], contact}`.
 *
 * [remainingGrants] is the node's own re-read after the withdrawal (persist's
 * fold), not a prediction: the grants still live for this person. Also built
 * client-side from the 409 `consent.grant_not_owner_authored`, which is the
 * same fact with nothing withdrawn at all.
 */
@Serializable
data class RemoveContactResponse(
    @SerialName("key_id")
    val keyId: String,
    val withdrawn: List<WithdrawnGrant> = emptyList(),
    @SerialName("remaining_grants")
    val remainingGrants: List<String> = emptyList(),
    /** The node's own answer to "are they still a contact". */
    val contact: Boolean = false,
) {
    /**
     * The only condition under which the UI may say "removed": nothing remains
     * AND the node does not still call them a contact. Either one alone is not
     * enough — an absent field defaults to "nothing remains".
     */
    val complete: Boolean get() = remainingGrants.isEmpty() && !contact
}

/**
 * `POST /v1/federation/peering/revoke` →
 * `{attestation_id, peer_key_ids, withdraws, cohort_scope}`.
 *
 * [remainingGrants] is filled client-side from a 409
 * `consent.grant_not_owner_authored`: the grant asked about is node-authored
 * and still active. The node does not send it on a 200.
 */
@Serializable
data class RevokeGrantResponse(
    @SerialName("attestation_id")
    val attestationId: String,
    @SerialName("peer_key_ids")
    val peerKeyIds: List<String> = emptyList(),
    val withdraws: String? = null,
    @SerialName("cohort_scope")
    val cohortScope: String? = null,
    @SerialName("remaining_grants")
    val remainingGrants: List<String> = emptyList(),
) {
    /** Withdrawn, and signed: a `withdraws` id came back and nothing remains. */
    val complete: Boolean get() = remainingGrants.isEmpty() && !withdraws.isNullOrBlank()
}

/** The refusal whose body names grants that stay live (`{grants: [...]}`). */
const val REFUSAL_GRANT_NOT_OWNER_AUTHORED = "consent.grant_not_owner_authored"
