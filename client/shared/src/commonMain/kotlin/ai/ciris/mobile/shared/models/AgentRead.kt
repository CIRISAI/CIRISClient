package ai.ciris.mobile.shared.models

import ai.ciris.mobile.shared.ui.screens.ReadFailure
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * One read of an ADMIN-GATED agent route, as the three answers it can give.
 *
 * The agent's `/v1/partnership` and `/v1/connectors` routes answer a
 * non-administrator with 403 (`routes/partnership.py:447`,
 * `routes/connectors.py:340`). That is not "there are none" and not "the read
 * failed": it is a fact about WHO IS LOOKING, and it gets its own sentence. So
 * this is three cases, not the two a [ReadFailure] has.
 */
sealed interface AgentRead<out T> {
    data class Ok<T>(val value: T) : AgentRead<T>

    /** 403 — this signed-in person is not one of the agent's administrators. */
    data object AdminOnly : AgentRead<Nothing>

    data class Failed(val failure: ReadFailure) : AgentRead<Nothing>
}

private val agentJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Classify one HTTP answer. Pure, so each case is pinned in a test.
 *
 *  * 2xx → decode the `StandardResponse` envelope's `data` with [serializer];
 *    a body that does not decode is a FAILED read, never an empty one.
 *  * 403 → [AgentRead.AdminOnly].
 *  * 404 → the route is not on this host ([ReadFailure.NotOnThisNode]).
 *  * anything else → [ReadFailure.Failed] carrying the status.
 */
fun <T> agentReadOf(status: Int, body: String, serializer: KSerializer<T>): AgentRead<T> = when {
    status in 200..299 -> try {
        AgentRead.Ok(agentJson.decodeFromJsonElement(serializer, standardResponseData(body)))
    } catch (e: Exception) {
        AgentRead.Failed(ReadFailure.Failed("${e::class.simpleName}: ${e.message}"))
    }
    status == 403 -> AgentRead.AdminOnly
    status == 404 -> AgentRead.Failed(ReadFailure.NotOnThisNode("HTTP 404"))
    else -> AgentRead.Failed(ReadFailure.Failed("HTTP $status: ${body.take(200)}"))
}

/** The agent's `StandardResponse` wraps its payload in `data`; tolerate a bare payload. */
internal fun standardResponseData(body: String): JsonObject {
    val root = agentJson.parseToJsonElement(body).jsonObject
    return (root["data"] as? JsonObject) ?: root
}
