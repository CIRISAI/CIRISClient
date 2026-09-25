package ai.ciris.mobile.shared.api

/**
 * **A refusal the node TYPED**, carried to the UI without being flattened to a
 * sentence (CIRISServer#389).
 *
 * The auth surface's refusal body is `{error, reason_id}` — an English fallback
 * plus a stable id a client resolves in the reader's own language. Throwing a
 * plain `RuntimeException(error)` at the call site discards the id, which is the
 * whole point of the contract: `contacts.unknown_fed_id` and
 * `contacts.store_unavailable` both render as red text, and they have opposite
 * remedies (go admit the key vs. go look at the node).
 *
 * [reasonId] is the localization key. A screen resolves it and falls back to
 * [detail] — the server's English — when the bundle has no entry, which is the
 * designed degradation, not an error.
 */
class NodeRefusal(
    /** The node's `reason_id`, or null when the body carried none. */
    val reasonId: String?,
    /** The node's English `error` / `detail`, or null. */
    val detail: String?,
    /** The HTTP status that carried the refusal. */
    val statusCode: Int,
) : RuntimeException(detail ?: reasonId ?: "node refused ($statusCode)") {
    companion object {
        /**
         * Read a non-2xx body into a refusal, whatever answered.
         *
         * The node answers bare (`{error, reason_id}`); a FastAPI front door
         * nests the same thing under `detail` (`{"detail": {…}}`), sometimes
         * with `code`/`message` for the pair. Anything else — a proxy's HTML
         * 502, an empty 401 — yields a refusal with no id, and the caller falls
         * back to the status, which is still a better answer than a parse
         * exception (CIRISClient#70: the parse exception is what the person
         * used to see).
         */
        fun fromBody(statusCode: Int, raw: String): NodeRefusal {
            val obj = try {
                kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
            } catch (_: Exception) {
                null
            }
            fun kotlinx.serialization.json.JsonObject?.str(key: String): String? =
                (this?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            val nested = obj?.get("detail") as? kotlinx.serialization.json.JsonObject
            return NodeRefusal(
                reasonId = obj.str("reason_id") ?: nested.str("reason_id") ?: nested.str("code"),
                detail = obj.str("error") ?: nested.str("error") ?: nested.str("message") ?: obj.str("detail"),
                statusCode = statusCode,
            )
        }
    }
}
