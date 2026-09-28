package ai.ciris.mobile.shared.models.federation

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **A contact** — a federation identity this node has emitted a
 * `consent:replication:v1` grant to, as served by ``GET /v1/contacts``.
 *
 * The wire shape is a [LocalPeerState] (the same projection
 * ``GET /v1/federation/peers`` serves) with four contact-only members added by
 * `src/contacts_chat.rs::list_contacts`: [contact], [chatCommunityId],
 * [chatStarted] and [occurrenceKeyIds].
 *
 * # Why this is a separate DTO and not `LocalPeerState` + four fields
 *
 * The server has a documented degraded arm: a contact whose key it can no
 * longer PROJECT (a de-admitted key with the consent grant still standing) is
 * still reported, as `{key_id, canonical, trust}` plus the four contact members
 * — deliberately, so a live grant the human authored never becomes invisible.
 * `LocalPeerState` declares `pubkey_ed25519_base64` and `first_seen`
 * non-nullable with no default, so decoding that arm into it throws and the
 * WHOLE list fails. Every field the degraded arm can omit is optional here.
 */
@Serializable
data class Contact(
    @SerialName("key_id")
    val keyId: String,
    @SerialName("pubkey_ed25519_base64")
    val pubkeyEd25519Base64: String? = null,
    @SerialName("pubkey_ml_dsa_65_base64")
    val pubkeyMlDsa65Base64: String? = null,
    val canonical: Boolean = false,
    val trust: PeerTrustState = PeerTrustState.UNKNOWN,
    @SerialName("first_seen")
    val firstSeen: Instant? = null,
    val appearance: PeerAppearance? = null,
    @SerialName("alias_override")
    val aliasOverride: String? = null,
    val notes: String? = null,
    @SerialName("last_seen")
    val lastSeen: Instant? = null,
    /** Always `true` on this route — the discriminator against a bare peer card. */
    val contact: Boolean = true,
    /**
     * The DERIVED two-party community id for a chat with this contact. Present
     * whether or not the room exists yet; [chatStarted] is what says it does.
     */
    @SerialName("chat_community_id")
    val chatCommunityId: String = "",
    /** The pair community already exists on this node (the room has been opened). */
    @SerialName("chat_started")
    val chatStarted: Boolean = false,
    /** The devices this contact actually speaks from. Reported, never required. */
    @SerialName("occurrence_key_ids")
    val occurrenceKeyIds: List<String> = emptyList(),
    /**
     * The consent grant this row IS, as its envelope (CIRISServer#616, since
     * ciris-server 0.5.213). Absent on an older node, and `null` on the wire
     * when the node holds no readable receipt for the key; either way the
     * receipt says "not sent" rather than filling the facts in by rule.
     */
    val grant: ContactGrant? = null,
) {
    /**
     * The key material this node can project for the contact is missing — the
     * de-admitted-but-still-consented arm above. Rendered as a warning rather
     * than hidden, because the grant is real and only the human can retract it.
     */
    val projectionMissing: Boolean get() = pubkeyEd25519Base64.isNullOrBlank()
}

/**
 * **The grant as a receipt** — `peer.rs::grant_receipt` on ciris-server, the
 * CC 2.1 envelope members of the `consent:replication:v1` row a contact is,
 * read off the row and never inferred.
 *
 * [attestingKeyId] is whoever actually SIGNED the grant. Since 0.5.211 that is
 * the owner's federation identity — the PERSON who consented — not this node
 * (consent is by humans), which is why the client is sent it rather than
 * assuming CC 3.3.7's `G` is the node.
 *
 * Every member is optional: a field a given node cannot compute (an
 * unparseable payload, a pre-v44.6 persist without `for_key_id`) is reported
 * as absent, and the receipt says so for that one row.
 */
@Serializable
data class ContactGrant(
    @SerialName("attestation_id")
    val attestationId: String? = null,
    @SerialName("attesting_key_id")
    val attestingKeyId: String? = null,
    val dimension: String? = null,
    @SerialName("subject_key_ids")
    val subjectKeyIds: List<String> = emptyList(),
    @SerialName("cohort_scope")
    val cohortScope: String? = null,
    /** The one agent of mine this consent is FOR (persist v44.6.0). */
    @SerialName("for_key_id")
    val forKeyId: String? = null,
    /**
     * What the grant covers. The server folds an unparseable payload into
     * `[]`, so an empty list cannot be told apart from "not read".
     */
    @SerialName("consent_prefixes")
    val consentPrefixes: List<String> = emptyList(),
    @SerialName("asserted_at")
    val assertedAt: String? = null,
    /** The POLICY's expiry the person chose — distinct from [rowExpiresAt]. */
    @SerialName("valid_until")
    val validUntil: String? = null,
    @SerialName("row_expires_at")
    val rowExpiresAt: String? = null,
)

/** ``GET /v1/contacts`` → `{ "contacts": [...], "total": N }`. */
@Serializable
data class ContactListResponse(
    val contacts: List<Contact> = emptyList(),
    val total: Int = 0,
)

/** ``POST /v1/contacts`` body. */
@Serializable
data class AddContactRequest(
    @SerialName("key_id")
    val keyId: String,
)

/** ``POST /v1/contacts`` → the emitted (or already-standing) consent grant. */
@Serializable
data class AddContactResponse(
    @SerialName("key_id")
    val keyId: String,
    @SerialName("consent_attestation_id")
    val consentAttestationId: String = "",
    /**
     * A grant row was WRITTEN — first grant OR a widening of a narrower standing
     * one (CIRISServer#458). `false` is the true no-op: the standing grant already
     * covered everything this add needed.
     *
     * Note the meaning changed with the widening fix: it no longer means "the
     * first grant", so it must not be read as "this contact is new".
     */
    @SerialName("freshly_emitted")
    val freshlyEmitted: Boolean = false,
    /**
     * The narrower grant this add SUPERSEDED, when adding widened one.
     * Omitted when nothing was superseded — absence is the common case, not an
     * error.
     */
    @SerialName("superseded_attestation_id")
    val supersededAttestationId: String? = null,
    /**
     * What the LIVE grant actually covers.
     *
     * The one field that answers "can I message this person": `"chat:"` present
     * means messages to this contact are eligible to replicate. Its ABSENCE is
     * the failure #458 stayed invisible for — the contact is added, the row is
     * green, and nothing ever leaves the room. Read it through [chatEligible]
     * and say so in the UI rather than letting silence stand.
     */
    @SerialName("consent_prefixes")
    val consentPrefixes: List<String> = emptyList(),
    @SerialName("occurrence_key_ids")
    val occurrenceKeyIds: List<String> = emptyList(),
    @SerialName("chat_community_id")
    val chatCommunityId: String = "",
) {
    /**
     * Does the standing grant cover chat?
     *
     * Absent `consent_prefixes` (an older node that does not send the field) is
     * NOT treated as ineligible — that would show every contact on a lagging
     * node as broken. Only an explicitly-returned list that lacks the prefix is
     * a negative answer.
     */
    val chatEligible: Boolean
        get() = consentPrefixes.isEmpty() || consentPrefixes.any { it.startsWith(CHAT_PREFIX) }

    companion object {
        /** The attestation-prefix the chat plane replicates under. */
        const val CHAT_PREFIX = "chat:"
    }
}
