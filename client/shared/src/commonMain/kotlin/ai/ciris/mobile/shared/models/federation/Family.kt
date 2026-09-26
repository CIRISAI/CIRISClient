package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * **Households** — the node's `/v1/families` routes (CIRISServer 0.5.216,
 * `src/family_api.rs`; `FSD/ROSTER_AND_DRIVE_CRUD.md` §3). CSD-100 / CSD-101.
 *
 * A household is a CC 3.3.4 `family`: a roster of identity keys, each with a
 * role, governed by the family's own `consensus_protocol`. The node mints the
 * id (`family:v1:<uuid>`), the owner's fed-ID signs every row, and membership
 * is read through the FOLD (record minus revocations), so [FamilyDto.members]
 * is who is in it now, not who was ever written onto the record.
 *
 * Every member here is optional-with-default except the id: the view is built
 * by one function on the node (`view`, `family_api.rs:541-571`), but a reader
 * that throws on a missing `founded_at` would lose the whole list for one row.
 */
@Serializable
data class FamilyMemberDto(
    @SerialName("key_id") val keyId: String,
    /** `founder` or `member` (the node defaults an unset role to `member`). */
    val role: String = "member",
    @SerialName("joined_at") val joinedAt: String? = null,
)

/** The row's envelope (FSD §1 rule 8): what it is about, who signed it, and its audience. */
@Serializable
data class FamilyEnvelopeDto(
    val subject: String? = null,
    /** The key that signed the current record. Null for a row with no signed read (the node says so). */
    val attester: String? = null,
    @SerialName("cohort_scope") val cohortScope: String? = null,
    val dimension: String? = null,
    @SerialName("persist_row_hash") val persistRowHash: String? = null,
)

/** `GET /v1/families/{id}`, and each row of the list; also what every governed write answers. */
@Serializable
data class FamilyDto(
    @SerialName("family_id") val familyId: String,
    val name: String = "",
    /** `founder_only` or `quorum:M/N` (`majority` / `unanimous` are stored in their quorum form). */
    @SerialName("consensus_protocol") val consensusProtocol: String = "founder_only",
    @SerialName("founded_at") val foundedAt: String? = null,
    val members: List<FamilyMemberDto> = emptyList(),
    /** The caller's own role. The node always sends it for a member; null means it did not. */
    @SerialName("my_role") val myRole: String? = null,
    val envelope: FamilyEnvelopeDto? = null,
)

/** `GET /v1/families` → `{families, resume}`; `resume` is the next page's `after`, null on the last. */
@Serializable
data class FamilyListResponse(
    val families: List<FamilyDto> = emptyList(),
    val resume: String? = null,
)

/**
 * One member's signature over a change envelope — verify's `ThresholdSignature`
 * (`ciris-verify-core/src/threshold.rs:122`), carried back to the node byte-for-byte.
 */
@Serializable
data class FamilySignatureDto(
    @SerialName("member_id") val memberId: String,
    @SerialName("ed25519_signature_base64") val ed25519SignatureBase64: String,
    @SerialName("mldsa65_signature_base64") val mldsa65SignatureBase64: String? = null,
)

/**
 * `POST /v1/families/{id}/changes/envelope` — a proposed change for a quorum
 * family. The node keeps NOTHING: the envelope travels with whoever carries it,
 * and each member signs it on their own node (`family_api.rs:1219-1231`).
 */
@Serializable
data class FamilyChangeProposal(
    @SerialName("change_envelope") val changeEnvelope: JsonObject,
    @SerialName("signing_bytes_base64") val signingBytesBase64: String? = null,
    @SerialName("required_signatures") val requiredSignatures: Int = 0,
    /** Every active member: who may sign. */
    val signers: List<String> = emptyList(),
)

/** `POST /v1/families/{id}/changes/cosign`: this node's owner signed; the running set and whether it is enough. */
@Serializable
data class FamilyCosignResponse(
    val signature: FamilySignatureDto? = null,
    val signatures: List<FamilySignatureDto> = emptyList(),
    @SerialName("required_signatures") val requiredSignatures: Int = 0,
    @SerialName("quorum_met") val quorumMet: Boolean = false,
)

/**
 * A change as it is handed from one member to the next: the envelope and the
 * signatures gathered so far. This is the text a person copies to the others
 * and pastes back — the node has no inbox for it (see CSD-100 §3).
 */
@Serializable
data class FamilyChangeCarry(
    @SerialName("change_envelope") val changeEnvelope: JsonObject,
    val signatures: List<FamilySignatureDto> = emptyList(),
)
