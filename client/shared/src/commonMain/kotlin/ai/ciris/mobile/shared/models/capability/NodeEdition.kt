package ai.ciris.mobile.shared.models.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * WHICH MESH THIS NODE IS ON. `GET /v1/federation/conformance` → `edition`.
 *
 * A 0.5.x node has no `edition` member (it reports `ceg_wire_version`
 * "1.0.0-rc2"); a 0.6 node, the v62 re-genesis, carries `"edition": 2`. The rule
 * the CIRISServer session set: edition == 2 → v62 paths, absent → 0.5.x paths,
 * and the edition is NEVER inferred from the server's version string.
 *
 * Fails closed: a body that cannot be read, or an `edition` that is not a known
 * number, is [UNKNOWN], and the acts that differ between editions are not
 * offered on an unknown node (see `AccordEditionGate`). The two meshes share no
 * rows (v62 is a total re-genesis), so offering a 0.5.x act to a v62 node is a
 * request it can only refuse.
 */
enum class NodeEdition {
    /** 0.5.x: the earlier mesh. No `edition` member. */
    EDITION_1,
    /** v62 (server 0.6): `"edition": 2`. */
    EDITION_2,
    /** Unread, unreachable, or a value this client does not know. */
    UNKNOWN;

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Read the conformance body (bare or `{data: …}`). */
        fun fromConformance(body: String?): NodeEdition {
            if (body.isNullOrBlank()) return UNKNOWN
            val root = runCatching { lenient.parseToJsonElement(body).jsonObject }.getOrNull() ?: return UNKNOWN
            val doc = (root["data"] as? JsonObject) ?: root
            if (!doc.containsKey("edition")) return EDITION_1
            val n = (doc["edition"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            return when (n) {
                2 -> EDITION_2
                else -> UNKNOWN
            }
        }
    }
}
