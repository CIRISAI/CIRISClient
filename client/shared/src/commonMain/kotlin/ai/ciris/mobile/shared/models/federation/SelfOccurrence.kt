package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for **self-occurrence enrollment** — the "manage my self + log in as
 * myself on another device" surface (CIRISServer#76, CEG §5.6.8.8 / §11.7).
 *
 * A "self" is a roster of occurrence rows over ONE root identity (fed-ID) key.
 * Any ACTIVE occurrence stands in for the self, so adding a second device makes
 * the founder's fed-ID survive the loss of the first device (OR-of-N redundancy a
 * single hardware-sealed key cannot give you).
 *
 * Mirrors `CIRISServer/src/auth/occurrence.rs`:
 *   GET  /v1/self/occurrences?identity_key_id=… → [SelfOccurrencesResponse]
 *   POST /v1/self/occurrence                     → [AddOccurrenceRequest] / [AddOccurrenceResponse]
 *   POST /v1/self/occurrence/revoke              → [RevokeOccurrenceRequest] / [RevokeOccurrenceResponse]
 *
 * and `CIRISServer/src/self_devices.rs` (0.5.216, `FSD/ROSTER_AND_DRIVE_CRUD.md` §2):
 *   POST /v1/self/occurrence/label               → [LabelOccurrenceRequest] / [LabelOccurrenceResponse]
 *   POST /v1/self/nodes/{node_key_id}/release    → [ReleaseNodeRequest] / [ReleaseNodeResponse]
 *
 * ARCHITECTURE: the app holds NO keys and performs NO crypto. The ADD / REVOKE are
 * federation-signed requests; the signing is performed by THIS device's local
 * ciris-server in its substrate (the user's resolved fed-ID signer), driven by a
 * plain loopback POST — the same posture as the consent / peering / claim-remote
 * cards. The app only DRIVES the node and surfaces the public result.
 */

/**
 * One occurrence (device) of a self — a row in the device roster. Active, or,
 * under `include_revoked=true`, revoked ([revoked] == true).
 */
@Serializable
data class SelfOccurrence(
    /** The occurrence's signing `key_id` (a `federation_keys.key_id`). */
    @SerialName("occurrence_key_id")
    val occurrenceKeyId: String,
    /** Closed set: `phone | laptop | agent` (persist's `check_device_class`). */
    @SerialName("device_class")
    val deviceClass: String = "laptop",
    /** `true` when the occurrence registered content-encryption pubkeys (i.e. it is
     *  a Self-DEK recipient and can decrypt the self's at-rest content). */
    @SerialName("has_encryption_pubkeys")
    val hasEncryptionPubkeys: Boolean = false,
    /** Present when the occurrence carries hardware attestation (TPM / SE / StrongBox). */
    @SerialName("hardware_attestation")
    val hardwareAttestation: String? = null,
    /** RFC-3339 binding-asserted time. */
    @SerialName("asserted_at")
    val assertedAt: String? = null,
    /**
     * `true` for a device a revocation took out of the self; `false` for an
     * active one. NULL means the node did not say — it predates 0.5.216, which
     * added the field. Null is not "active": a node that cannot report
     * revocations also cannot name or release, and the screen says so rather
     * than guessing (see [nodeReportsRevocation]).
     */
    val revoked: Boolean? = null,
    /**
     * The owner's display name for this device (`POST /v1/self/occurrence/label`).
     * The node returns it only to the identity's own owner session, so its
     * absence is normal and means "unnamed", not "unknown device".
     */
    val label: String? = null,
)

/**
 * Whether the node that answered this roster reports revocation (0.5.216+).
 *
 * Every 0.5.216 row carries `revoked`; an older node's rows carry none. Null
 * when the roster is empty, because an empty roster says nothing about the
 * node's version.
 */
fun nodeReportsRevocation(rows: List<SelfOccurrence>): Boolean? =
    if (rows.isEmpty()) null else rows.all { it.revoked != null }

/** Response of `GET /v1/self/occurrences?identity_key_id=…` — the device roster. */
@Serializable
data class SelfOccurrencesResponse(
    @SerialName("identity_key_id")
    val identityKeyId: String = "",
    /** The ACTIVE occurrences — followed, under `include_revoked=true`, by the revoked ones. */
    val occurrences: List<SelfOccurrence> = emptyList(),
)

/** The new device's content-encryption pubkeys (the wrap_algorithm:v2 recipient
 *  inputs). REQUIRED for the device to decrypt the self's at-rest content — an
 *  occurrence WITHOUT them is fail-secure EXCLUDED from the Self-DEK cascade. */
@Serializable
data class OccurrenceEncryptionPubkeys(
    @SerialName("x25519_base64")
    val x25519Base64: String,
    @SerialName("ml_kem_768_base64")
    val mlKem768Base64: String,
)

/** The occurrence to admit in [AddOccurrenceRequest]. */
@Serializable
data class AddOccurrenceBody(
    @SerialName("occurrence_key_id")
    val occurrenceKeyId: String,
    /** `phone | laptop | agent`. */
    @SerialName("device_class")
    val deviceClass: String,
    @SerialName("encryption_pubkeys")
    val encryptionPubkeys: OccurrenceEncryptionPubkeys? = null,
    @SerialName("hardware_attestation")
    val hardwareAttestation: String? = null,
    /** Optional reachability rows `[(transport_kind, destination)]`. */
    @SerialName("transport_destinations")
    val transportDestinations: List<List<String>>? = null,
)

/**
 * Body of `POST /v1/self/occurrence` (ADD). Enrolls [occurrence] under
 * [identityKeyId]. When the new device's signing key is not yet in the directory,
 * supply [occurrenceKeyRecord] to admit it via the fail-secure proof-of-possession
 * gate; when the key already exists this is ignored.
 */
@Serializable
data class AddOccurrenceRequest(
    @SerialName("identity_key_id")
    val identityKeyId: String,
    val occurrence: AddOccurrenceBody,
    @SerialName("occurrence_key_record")
    val occurrenceKeyRecord: SignedKeyRecord? = null,
)

/** Response of `POST /v1/self/occurrence` (ADD). */
@Serializable
data class AddOccurrenceResponse(
    @SerialName("identity_key_id")
    val identityKeyId: String = "",
    @SerialName("occurrence_key_id")
    val occurrenceKeyId: String = "",
    @SerialName("device_class")
    val deviceClass: String = "",
    /** `true` when this call admitted the device's key from the supplied record. */
    @SerialName("key_freshly_registered")
    val keyFreshlyRegistered: Boolean = false,
    /** How many `cohort_scope: self` at-rest DEKs were (re-)wrapped to this device. */
    @SerialName("self_dek_granted")
    val selfDekGranted: Int = 0,
    /** Occurrence key_ids fail-secure EXCLUDED from the cascade (no encryption pubkeys). */
    @SerialName("self_dek_excluded")
    val selfDekExcluded: List<String> = emptyList(),
    @SerialName("transport_destinations_registered")
    val transportDestinationsRegistered: Int = 0,
)

/** Body of `POST /v1/self/occurrence/revoke` (REVOKE). */
@Serializable
data class RevokeOccurrenceRequest(
    @SerialName("identity_key_id")
    val identityKeyId: String,
    @SerialName("occurrence_key_id")
    val occurrenceKeyId: String,
    /** Optional operator annotation (e.g. "laptop lost 2026-06-23"). */
    val reason: String? = null,
    /**
     * Required (0.5.218) when the occurrence is one of the keys of the node
     * SERVING the request: evicting it withdraws this node's owner-binding and
     * ends the owner's authority here, as a self-release does. Without it the
     * node refuses `self.release_self_requires_force` (409). The client sends
     * it only from the second confirm, which says this device loses access —
     * never on a first attempt (`IdentityManagementViewModel.RevokeState`).
     * Omitted when false, so the body a 0.5.217 node reads is unchanged.
     */
    @SerialName("force_self")
    val forceSelf: Boolean? = null,
)

/** Response of `POST /v1/self/occurrence/revoke` (REVOKE). */
@Serializable
data class RevokeOccurrenceResponse(
    @SerialName("identity_key_id")
    val identityKeyId: String = "",
    @SerialName("occurrence_key_id")
    val occurrenceKeyId: String = "",
    /** The surviving key that authorized the revocation. */
    @SerialName("revoked_by")
    val revokedBy: String = "",
    /** RFC-3339 effective time (== now; effective immediately). */
    @SerialName("effective_at")
    val effectiveAt: String = "",
    /** 0.5.218: `true` when every part of the eviction completed. */
    val revoked: Boolean? = null,
    /** 0.5.218: the device evicted was the node answering — this session's authority ended with it. */
    @SerialName("released_self")
    val releasedSelf: Boolean = false,
    /** 0.5.218: the node's own sentence — already-shared history stays readable to the evicted device. */
    val history: String? = null,
)

/**
 * **What an eviction did, part by part** (ciris-server 0.5.218, CIRISServer#700,
 * `src/self_devices.rs::EvictionReport`). Both `POST /v1/self/occurrence/revoke`
 * and `POST /v1/self/nodes/{id}/release` are now ONE act — withdraw the
 * owner-binding, write a signed occurrence revocation, kick replication, read
 * both halves back — and when any part does not finish the node answers 500
 * `self.evict_incomplete` (or `self.release_incomplete`) carrying THIS report,
 * so the person sees what DID complete (it stays done) and what did not.
 *
 * Read off [ai.ciris.mobile.shared.api.NodeRefusal.body] by [fromBody]; never
 * re-derived. Every member is optional: a part of the report this client does
 * not decode must not cost the person the parts it does.
 */
@Serializable
data class EvictionReport(
    val nodes: List<String> = emptyList(),
    /** One per withdrawn owner-binding: `{node_key_id, binding, withdraws, cohort_scope}`. */
    val withdrawn: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    /** One per signed occurrence revocation: `{occurrence_key_id, attesting_key_id, effective_at}`. */
    @SerialName("occurrences_revoked")
    val occurrencesRevoked: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    @SerialName("occurrences_already_revoked")
    val occurrencesAlreadyRevoked: List<String> = emptyList(),
    val failed: List<EvictFailure> = emptyList(),
    @SerialName("replication_kicked")
    val replicationKicked: Boolean = false,
    /** `EVICTION_HISTORY_NOTE`, verbatim: already-shared history stays readable by the evicted device. */
    val history: String? = null,
) {
    /**
     * The report as a list a person reads: every part done, then every part
     * not done, each naming its kind ([EvictPart.part], one of [EvictPart.KNOWN]
     * or a word this client does not know) and what it was about.
     */
    val parts: List<EvictPart>
        get() = buildList {
            withdrawn.forEach { add(EvictPart(EvictPart.OWNER_BINDING_WITHDRAWAL, it.str("node_key_id").orEmpty(), done = true)) }
            occurrencesRevoked.forEach { add(EvictPart(EvictPart.OCCURRENCE_REVOCATION, it.str("occurrence_key_id").orEmpty(), done = true)) }
            occurrencesAlreadyRevoked.forEach { add(EvictPart(EvictPart.OCCURRENCE_REVOCATION, it, done = true)) }
            failed.forEach { add(EvictPart(it.part, it.target, done = false, error = it.error)) }
        }

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

        /** The report a refusal body carries, or null when it carries none (an older node, or another refusal). */
        fun fromBody(body: String?): EvictionReport? {
            if (body.isNullOrBlank()) return null
            return try {
                val obj = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return null
                // A refusal without the report's own members is not a report.
                if (!obj.containsKey("failed") && !obj.containsKey("history")) return null
                json.decodeFromJsonElement(serializer(), obj)
            } catch (_: Exception) {
                null
            }
        }

        private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** One part of an eviction that did not complete (`EvictFailure`). */
@Serializable
data class EvictFailure(
    val part: String = "",
    val target: String = "",
    val error: String = "",
)

/** One line of [EvictionReport.parts]: what kind of part, about which key, and whether it finished. */
data class EvictPart(val part: String, val target: String, val done: Boolean, val error: String? = null) {
    companion object {
        const val OWNER_BINDING_WITHDRAWAL = "owner_binding_withdrawal"
        const val OWNER_BINDING_WITNESS = "owner_binding_witness"
        const val OCCURRENCE_REVOCATION = "occurrence_revocation"
        const val OCCURRENCE_WITNESS = "occurrence_witness"
        /** The four parts `src/self_devices.rs::EvictFailure.part` documents. */
        val KNOWN = setOf(OWNER_BINDING_WITHDRAWAL, OWNER_BINDING_WITNESS, OCCURRENCE_REVOCATION, OCCURRENCE_WITNESS)
    }
}

/** Body of `POST /v1/self/occurrence/label`. The label is display-only, 1–64 characters. */
@Serializable
data class LabelOccurrenceRequest(
    @SerialName("occurrence_key_id")
    val occurrenceKeyId: String,
    val label: String,
)

/** Response of `POST /v1/self/occurrence/label`. */
@Serializable
data class LabelOccurrenceResponse(
    @SerialName("occurrence_key_id")
    val occurrenceKeyId: String = "",
    /** The label as stored (trimmed by the node). */
    val label: String = "",
    @SerialName("attestation_id")
    val attestationId: String = "",
    /** The previous label row this one supersedes, or null for a first name. */
    val supersedes: String? = null,
)

/**
 * Body of `POST /v1/self/nodes/{node_key_id}/release`.
 *
 * [forceSelf] is required to release the node that is SERVING the request —
 * doing so ends the owner's authority there, including the session that sent
 * it. The node refuses without it (`self.release_self_requires_force`).
 */
@Serializable
data class ReleaseNodeRequest(
    @SerialName("force_self")
    val forceSelf: Boolean = false,
)

/** Response of `POST /v1/self/nodes/{node_key_id}/release`. */
@Serializable
data class ReleaseNodeResponse(
    @SerialName("node_key_id")
    val nodeKeyId: String = "",
    val released: Boolean = false,
    /** `true` when the released node was the one that answered — this session ended with it. */
    @SerialName("released_self")
    val releasedSelf: Boolean = false,
    /** The owner's nodes after the release, as the node re-read them. */
    @SerialName("nodes_owned_by")
    val nodesOwnedBy: List<String> = emptyList(),
)
