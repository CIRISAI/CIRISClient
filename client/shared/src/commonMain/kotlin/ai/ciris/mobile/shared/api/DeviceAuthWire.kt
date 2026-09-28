package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.ConnectNodeResult
import ai.ciris.mobile.shared.models.NodeAuthPollResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The wire shapes of the node's Portal device grant (CIRISServer
 * `src/auth/device_auth.rs`, the port of the agent's retired
 * `device_auth_routes.py`): `POST /v1/setup/connect-node`,
 * `GET /v1/setup/connect-node/status`, `POST /v1/setup/reset-device-auth`.
 *
 * The node answers with BARE bodies — `ConnectNodeResponse` and
 * `ConnectNodeStatusResponse` are serialized directly (`device_auth.rs:184-195`,
 * `:320-329`), with no `{"data": …}` envelope — and refuses with
 * `{"error": msg}` (`err`, `:94-96`), not FastAPI's `{"detail": …}`. The
 * client required the agent's envelope and read `detail`, so against the node
 * every connect failed as "Invalid response format". Both shapes are read.
 */
object DeviceAuthWire {

    /** The node's bare body, or the agent's `data` envelope around it. */
    private fun payload(root: JsonObject): JsonObject = root["data"] as? JsonObject ?: root

    private fun str(o: JsonObject, k: String): String? =
        (o[k] as? JsonPrimitive)?.contentOrNull

    /** The node's reason for a refusal: `error` (node) or `detail` (agent). */
    fun refusalReason(root: JsonObject?): String? =
        root?.let { str(it, "error") ?: str(it, "detail") }

    fun parseConnect(root: JsonObject, enteredUrl: String): ConnectNodeResult {
        val d = payload(root)
        val portal = str(d, "portal_url")
            ?: if (enteredUrl.startsWith("http://") || enteredUrl.startsWith("https://"))
                enteredUrl.trimEnd('/') else "https://${enteredUrl.trimEnd('/')}"
        return ConnectNodeResult(
            verificationUriComplete = str(d, "verification_uri_complete") ?: "",
            deviceCode = str(d, "device_code") ?: "",
            userCode = str(d, "user_code") ?: "",
            portalUrl = portal,
            expiresIn = (d["expires_in"] as? JsonPrimitive)?.intOrNull ?: 900,
            interval = (d["interval"] as? JsonPrimitive)?.intOrNull ?: 5,
        )
    }

    fun parsePoll(root: JsonObject): NodeAuthPollResult {
        val d = payload(root)
        return NodeAuthPollResult(
            status = str(d, "status") ?: "error",
            template = str(d, "template"),
            adapters = (d["adapters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            orgId = str(d, "org_id"),
            signingKeyB64 = str(d, "signing_key_b64"),
            keyId = str(d, "key_id"),
            stewardshipTier = (d["stewardship_tier"] as? JsonPrimitive)?.intOrNull,
            error = str(d, "error"),
            packageDownloadUrl = str(d, "package_download_url"),
            packageTemplateId = str(d, "package_template_id"),
        )
    }
}

/**
 * The hosted-services switch, both directions (CIRISAgent
 * `routes/system/llm_routes.py:1029` disable, `:1081` enable). Enable only
 * clears `CIRIS_SERVICES_DISABLED`; providers register on the next restart.
 */
fun cirisServicesTogglePath(enable: Boolean): String =
    "/v1/system/llm/ciris-services/" + if (enable) "enable" else "disable"
