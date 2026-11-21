package ai.ciris.mobile.shared.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The answer to an identity resolve (CIRISAgent v2.23.1-stable): the usual
 * `SuccessResponse` wrapper, plus the receipts of the signed request and the WA's
 * signed answer — both nullable, since an older agent writes neither.
 */
data class IdentityResolveData(
    val deferralId: String,
    val success: Boolean,
    val resolvedAt: String?,
    val requestAttestationId: String?,
    val responseAttestationId: String?,
)

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject?.s(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Read `{data: {success, deferral_id, resolved_at, request_attestation_id, response_attestation_id}}`. */
fun parseIdentityResolve(raw: String, deferralId: String): IdentityResolveData {
    val root = lenientJson.parseToJsonElement(raw) as? JsonObject
        ?: throw IllegalStateException("resolve answered a body that is not an object")
    val data = root["data"] as? JsonObject ?: root
    return IdentityResolveData(
        deferralId = data.s("deferral_id") ?: deferralId,
        success = (data["success"] as? JsonPrimitive)?.booleanOrNull ?: false,
        resolvedAt = data.s("resolved_at"),
        requestAttestationId = data.s("request_attestation_id"),
        responseAttestationId = data.s("response_attestation_id"),
    )
}

/**
 * A refusal from the AGENT's API, with its reason id. The agent nests it as
 * `{error: {code, message, details: {reason_id, kind}}}`; a node behind it
 * answers `{error, reason_id}` (a `deferral.*` id on a 403/409/503). Anything
 * else falls back to [NodeRefusal.fromBody], and then to the status alone.
 */
fun agentRefusal(statusCode: Int, raw: String): NodeRefusal {
    val root = runCatching { lenientJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    val error = root?.get("error") as? JsonObject
    val details = error?.get("details") as? JsonObject
    val reasonId = details.s("reason_id")
    if (error != null && reasonId != null) {
        return NodeRefusal(
            reasonId = reasonId,
            detail = error.s("message") ?: error.s("detail"),
            statusCode = statusCode,
            body = raw.takeIf { it.isNotBlank() },
        )
    }
    // FastAPI's `{"detail": {"error": {...}}}` form of the same envelope.
    val nestedError = (root?.get("detail") as? JsonObject)?.get("error") as? JsonObject
    val nestedId = (nestedError?.get("details") as? JsonObject).s("reason_id")
    if (nestedError != null && nestedId != null) {
        return NodeRefusal(nestedId, nestedError.s("message"), statusCode, raw)
    }
    return NodeRefusal.fromBody(statusCode, raw)
}
