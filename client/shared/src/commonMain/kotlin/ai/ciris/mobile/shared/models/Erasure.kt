package ai.ciris.mobile.shared.models

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.RouteNotOnThisHost
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * ERASURE, AND PROOF THAT IT HAPPENED — the Data card (FSD/CSD/CSD-039 §3, §6).
 *
 * Every type here mirrors a wire shape read from the host's source, and every
 * classifier answers one question the card must never get wrong: **did the
 * host report that the thing is gone?** A partial result, a refusal or an
 * unexpected body is never "done". The person reads what the host reported,
 * not what we hoped it did (GDPR Art. 17; CC 3.3.1 D1: proof of deletion is
 * the absence of the row, not a sentence saying so).
 */

private val erasureJson = Json { ignoreUnknownKeys = true; isLenient = true }

// ─── Node: POST /v1/federation/erase-agent-traces ─────────────────────────────

/**
 * The node's answer to a trace erasure — CIRISServer `src/federation_admin.rs`
 * `erase_agent_traces` (~:477, body ~:533-548). Persist does it in one
 * transaction: `trace_events` and `trace_llm_calls` hard-deleted, the derived
 * `detection_events` TOMBSTONED (the analytics survive, the link to the agent
 * is cut), and a `hard_case:trace_erasure` audit row emitted. Idempotent: a
 * second call returns all zeros.
 */
@Serializable
data class TraceErasureResult(
    val erased: Boolean = false,
    @SerialName("agent_id_hash") val agentIdHash: String = "",
    @SerialName("trace_events") val traceEvents: Long = 0,
    @SerialName("trace_llm_calls") val traceLlmCalls: Long = 0,
    @SerialName("detection_events_tombstoned") val detectionEventsTombstoned: Long = 0,
    @SerialName("erased_at") val erasedAt: String? = null,
    @SerialName("scope_note") val scopeNote: String? = null,
)

// ─── Node: POST /v1/auth/erasure (the person's own; blocked on CIRISServer#677) ─

/**
 * The node's answer to an actor eviction — CIRISServer `src/auth/erasure.rs:53-60`.
 * FAIL-HONEST by design: the blob is deleted even when its `withdraws` could not
 * be emitted, and `withdraws_failed` says so; `incomplete` means more holdings
 * may remain and the caller must ask again until `blobs_evicted == 0`.
 *
 * Nothing in this client can reach that route today — it authorizes by a
 * SUBJECT-SIGNED request and the client does no crypto (CIRISServer#677). The
 * shape and its classifier live here so the rule "a partial result is never
 * done" is pinned before the route becomes reachable, not after.
 */
@Serializable
data class ActorErasureReport(
    @SerialName("blobs_evicted") val blobsEvicted: Int = 0,
    @SerialName("withdraws_emitted") val withdrawsEmitted: Int = 0,
    @SerialName("withdraws_failed") val withdrawsFailed: Int = 0,
    val incomplete: Boolean = false,
)

/** What a finished erasure call means, in the host's words. */
sealed interface ErasureOutcome {
    /** The host reports rows removed, and nothing it reported failed. */
    data object Erased : ErasureOutcome

    /** The host looked and there was nothing to remove (an idempotent repeat, or a wrong id). */
    data object NothingThere : ErasureOutcome

    /**
     * The host did part of it, or said something we cannot read as done. NEVER
     * rendered as done: the reason is the host's report — `withdraws_failed`,
     * `incomplete`, or `erased: false`.
     */
    data class Partial(
        val withdrawsFailed: Int = 0,
        val moreMayRemain: Boolean = false,
        val unconfirmed: Boolean = false,
    ) : ErasureOutcome
}

fun TraceErasureResult.outcome(): ErasureOutcome = when {
    // The route only answers 200 with erased=true; anything else is a body we
    // did not expect, and an unexpected body is not a deletion.
    !erased -> ErasureOutcome.Partial(unconfirmed = true)
    traceEvents == 0L && traceLlmCalls == 0L && detectionEventsTombstoned == 0L -> ErasureOutcome.NothingThere
    else -> ErasureOutcome.Erased
}

fun ActorErasureReport.outcome(): ErasureOutcome = when {
    withdrawsFailed > 0 || incomplete ->
        ErasureOutcome.Partial(withdrawsFailed = withdrawsFailed, moreMayRemain = incomplete)
    blobsEvicted == 0 && withdrawsEmitted == 0 -> ErasureOutcome.NothingThere
    else -> ErasureOutcome.Erased
}

// ─── Agent: /v1/verification/* (the receipt half) ────────────────────────────

/**
 * `DeletionProof` — CIRISAgent `routes/verification.py:29-37`. Ed25519 over the
 * RFC 8785 (JCS) canonical bytes of the four data fields. `sources_deleted` is
 * carried as the raw object because the agent re-canonicalizes it: any rewrite
 * of a value here would change the bytes the signature covers.
 */
@Serializable
data class DeletionProof(
    @SerialName("deletion_id") val deletionId: String,
    @SerialName("user_identifier") val userIdentifier: String,
    @SerialName("sources_deleted") val sourcesDeleted: JsonObject,
    @SerialName("deleted_at") val deletedAt: String,
    val signature: String,
    @SerialName("public_key_id") val publicKeyId: String,
) {
    /**
     * What the proof CLAIMS was deleted, summed the way the agent sums it
     * (`verification.py:164-170`). A claim until the signature checks: the card
     * labels it so and never shows it as a verified count.
     */
    val claimedRecords: Int
        get() = sourcesDeleted.values.sumOf { v ->
            ((v as? JsonObject)?.get("total_records_deleted") as? JsonPrimitive)?.intOrNull ?: 0
        }
}

/** `SignatureVerificationResult` — `verification.py:40-50`. */
@Serializable
data class DeletionVerification(
    val valid: Boolean = false,
    @SerialName("deletion_id") val deletionId: String = "",
    @SerialName("user_identifier") val userIdentifier: String = "",
    @SerialName("deleted_at") val deletedAt: String = "",
    @SerialName("sources_count") val sourcesCount: Int = 0,
    @SerialName("total_records") val totalRecords: Int = 0,
    val message: String = "",
    @SerialName("verified_at") val verifiedAt: String = "",
)

/** `GET /v1/verification/keys/current` — `verification.py:432-463`. */
@Serializable
data class DeletionSigningKey(
    @SerialName("public_key_id") val publicKeyId: String = "",
    @SerialName("download_url") val downloadUrl: String? = null,
    val algorithm: String? = null,
)

/** A pasted proof, read. */
sealed interface ProofParse {
    data class Read(val proof: DeletionProof) : ProofParse
    data class Unreadable(val why: String) : ProofParse
}

private val PROOF_FIELDS = listOf(
    "deletion_id", "user_identifier", "sources_deleted", "deleted_at", "signature", "public_key_id",
)

/**
 * Read a proof the person pasted. Accepts the bare `DeletionProof` object or the
 * `{"deletion_proof": {…}}` request body. Never fills a missing field: a proof
 * with a field missing cannot be verified, and is refused before any call.
 */
fun parseDeletionProof(raw: String): ProofParse {
    val text = raw.trim()
    if (text.isEmpty()) return ProofParse.Unreadable("empty")
    val obj = try {
        erasureJson.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return ProofParse.Unreadable("not a JSON object")
    val inner = (obj["deletion_proof"] as? JsonObject) ?: obj
    val missing = PROOF_FIELDS.filter { it !in inner }
    if (missing.isNotEmpty()) return ProofParse.Unreadable("missing " + missing.joinToString(", "))
    if (inner["sources_deleted"] !is JsonObject) return ProofParse.Unreadable("sources_deleted is not an object")
    for (k in PROOF_FIELDS - "sources_deleted") {
        val p = inner[k] as? JsonPrimitive
        if (p == null || !p.isString) return ProofParse.Unreadable("$k is not a string")
    }
    return try {
        ProofParse.Read(erasureJson.decodeFromJsonElement(DeletionProof.serializer(), inner))
    } catch (e: Exception) {
        ProofParse.Unreadable(e.message ?: "unreadable")
    }
}

/**
 * The agent verifies against its CURRENT key only (`verification.py:144`,
 * `engine.local_public_key_b64()`): the proof's own `public_key_id` is never
 * consulted. A proof signed before a key rotation therefore reads INVALID, and
 * the card must say why rather than let "invalid" read as "forged".
 */
fun proofKeyIsNotCurrent(proof: DeletionProof, current: DeletionSigningKey?): Boolean =
    current != null && current.publicKeyId.isNotBlank() && proof.publicKeyId != current.publicKeyId

// ─── Why a call produced no answer ────────────────────────────────────────────

/**
 * Why an erasure or receipt call did not produce a result. Three different facts:
 * the host has no such route (a fact about the host), the host refused with a
 * reason (a fact about this request), or the call failed.
 */
sealed interface ErasureFailure {
    data class NotOnThisHost(val detail: String?) : ErasureFailure
    data class Refused(val reasonId: String?, val detail: String?, val status: Int) : ErasureFailure
    data class Failed(val detail: String?) : ErasureFailure

    companion object {
        /**
         * An unmatched route answers 404 with no reason: axum with an empty body,
         * FastAPI with `{"detail": "Not Found"}`. A 404 that names something
         * ("Public key X not found") is the host answering about the request.
         */
        fun of(e: Throwable): ErasureFailure = when (e) {
            is RouteNotOnThisHost -> NotOnThisHost(e.message)
            is NodeRefusal ->
                if (e.statusCode == 404 && e.reasonId == null && (e.detail.isNullOrBlank() || e.detail == "Not Found")) {
                    NotOnThisHost(e.detail)
                } else {
                    Refused(e.reasonId, e.detail, e.statusCode)
                }
            else -> Failed(e.message ?: e::class.simpleName)
        }
    }
}

// ─── Decoding helpers for the API client ─────────────────────────────────────

/** A body as a JSON object, or null. */
internal fun decodeErasure(raw: String): JsonObject? = try {
    erasureJson.parseToJsonElement(raw) as? JsonObject
} catch (e: Exception) {
    null
}

/** A `StandardResponse` body's `data` object, or null. */
internal fun standardData(raw: String): JsonObject? = decodeErasure(raw)?.get("data") as? JsonObject

internal fun <T> decodeErasure(serializer: KSerializer<T>, obj: JsonObject): T =
    erasureJson.decodeFromJsonElement(serializer, obj)
