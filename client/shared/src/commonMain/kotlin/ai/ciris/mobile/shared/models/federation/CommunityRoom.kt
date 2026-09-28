package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * **A community room, as the node's fold sees it** — one row of
 * `GET /v1/communities`, or the body of `GET /v1/communities/{id}`
 * (CIRISServer `src/communities.rs::room_json`, 0.5.216; CSD-102).
 *
 * `members` is the EFFECTIVE roster (record ∪ widenings − revocations), never
 * the record's own list, so a removed member is already gone and a widened one
 * is already here. [moderators], [widenings] and [revocations] are sent only by
 * the single-room read (`detail = true`); on a list row they are absent, which
 * is why they are nullable rather than defaulted to empty — an empty list
 * would say "no moderators", which the list row never claimed.
 */
@Serializable
data class CommunityRoom(
    @SerialName("community_id")
    val communityId: String,
    val name: String = "",
    /** `pair` (a two-person chat whose id is derived from the pair) or `room`. */
    val kind: String = KIND_ROOM,
    /** `community` or `affiliations` — the audience tier on the signed record. */
    val tier: String = TIER_COMMUNITY,
    @SerialName("cohort_scope")
    val cohortScope: String? = null,
    /** `founder_only`, `unanimous`, `majority` or `quorum:M/N`. */
    @SerialName("consensus_protocol")
    val consensusProtocol: String = "",
    @SerialName("founded_at")
    val foundedAt: String? = null,
    @SerialName("member_count")
    val memberCount: Int = 0,
    /** The caller's own role, or null when the caller is not on the roster (never, on a served row). */
    @SerialName("my_role")
    val myRole: String? = null,
    val members: List<CommunityRoomMember> = emptyList(),
    /** role → key ids, the same roster grouped. */
    val roles: Map<String, List<String>> = emptyMap(),
    /** Appointed `moderate` duty holders (founder-rooted `delegates_to`). Single-room read only. */
    val moderators: List<String>? = null,
    /** How many widening rows the room has. Single-room read only. */
    val widenings: Int? = null,
    /** How many revocation rows the room has. Single-room read only. */
    val revocations: Int? = null,
) {
    val isPair: Boolean get() = kind == KIND_PAIR

    companion object {
        const val KIND_PAIR = "pair"
        const val KIND_ROOM = "room"
        const val TIER_COMMUNITY = "community"
        const val TIER_AFFILIATIONS = "affiliations"
    }
}

/** One member of the effective roster: `member_json` in `src/communities.rs`. */
@Serializable
data class CommunityRoomMember(
    @SerialName("key_id")
    val keyId: String,
    /** `founder`, `member`, or an operator-defined word. `member` is the absence of a role. */
    val role: String = "member",
    @SerialName("joined_at")
    val joinedAt: String? = null,
)

/** `GET /v1/communities` — `{communities, total, resume}`. */
@Serializable
data class CommunityRoomList(
    val communities: List<CommunityRoom> = emptyList(),
    val total: Int = 0,
    val resume: String? = null,
)

/**
 * A roster change the node APPLIED — `applied()` (add / remove / role /
 * dissolve / assemble) or `leave_room` (leave, which carries [keyId] and no
 * [members]).
 */
@Serializable
data class CommunityChangeApplied(
    @SerialName("community_id")
    val communityId: String,
    val op: String = "",
    val applied: Boolean = false,
    @SerialName("key_id")
    val keyId: String? = null,
    val members: List<CommunityRoomMember> = emptyList(),
)

/**
 * **A change the room's own rule has not yet admitted** — the body of a
 * `community.quorum_pending` refusal (409) and of `POST …/changes/envelope`
 * (200). The node keeps nothing: this envelope and these signatures ARE the
 * pending change, and the client is where they live until `…/assemble`.
 *
 * [changeEnvelope] and [signatures] are carried opaque: the client never
 * builds or edits either, it only hands them back, so decoding them into a
 * typed shape would be a second definition that could only drift.
 */
@Serializable
data class CommunityPendingChange(
    @SerialName("community_id")
    val communityId: String? = null,
    @SerialName("change_envelope")
    val changeEnvelope: JsonObject,
    @SerialName("signing_bytes_base64")
    val signingBytesBase64: String? = null,
    val signatures: List<JsonElement> = emptyList(),
    val valid: Int = 0,
    val required: Int = 0,
    @SerialName("eligible_signers")
    val eligibleSigners: List<String> = emptyList(),
    @SerialName("consensus_protocol")
    val consensusProtocol: String = "",
    /** The English sentence the refusal carried ("1 of 2 required signature(s)…"), when it was one. */
    val error: String? = null,
)

/** `POST …/changes/cosign` — this node's owner's signature over someone else's envelope. */
@Serializable
data class CommunityCosignature(
    @SerialName("community_id")
    val communityId: String,
    val op: String = "",
    val signature: JsonElement,
)

/**
 * What a governed roster write came back as. Two outcomes that are both
 * SUCCESSFUL requests and mean different things: the change is done, or the
 * room's rule wants more signatures first. A refusal is neither — it is thrown
 * as a [ai.ciris.mobile.shared.api.NodeRefusal].
 */
sealed interface CommunityChangeOutcome {
    data class Applied(val result: CommunityChangeApplied) : CommunityChangeOutcome
    data class Pending(val change: CommunityPendingChange) : CommunityChangeOutcome
}
