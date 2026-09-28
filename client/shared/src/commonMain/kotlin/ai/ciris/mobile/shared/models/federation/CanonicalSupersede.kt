package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ─── Rotating a canonical server (CIRISServer src/accord_provision.rs:3570-3640) ──
//
// `POST /v1/accord/canonical/supersede` is the STRUCTURAL canonical op: 2-of-3,
// authorised by a stored accord proposal (its digest is pasted; persist re-tallies
// the holder participations), carrying the successor's completed anchor-scrubbed
// `SignedKeyRecord` verbatim. The server admits the successor BEFORE tombstoning
// the predecessor, so the canonical set is never momentarily empty. The confirm
// names the successor from the record — `record.key_id`, the field the server
// reads for its own `successor` answer — so what the person reads is what the
// node will do, not a second box they typed.

/** A rotation parsed locally and named before anything is sent. */
data class SupersedeDraft(
    /** The successor's record, verbatim (never re-encoded before it rides). */
    val record: JsonElement,
    /** `record.key_id`, or null when the pasted record names none. */
    val successorKeyId: String?,
)

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Pure. The pasted text as a rotation ready to confirm, or null when it is not a
 * JSON object at all — refused here, nothing sent. A record with no `key_id` is
 * still a draft (the node decides) but the confirm says the successor is unnamed.
 */
fun prepareSupersede(text: String): SupersedeDraft? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val element = try {
        lenientJson.parseToJsonElement(trimmed)
    } catch (_: Exception) {
        return null
    }
    val obj = element as? JsonObject ?: return null
    val record = obj["record"] as? JsonObject
    val id = (record?.get("key_id") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    return SupersedeDraft(element, id)
}

/**
 * Pure. The first STRING under [key] anywhere in [element], depth-first, or null.
 * For display-only reads of a signed envelope whose nesting this app does not
 * own (the provision response's `custody_attestation`, the ceremony's assembled
 * genesis): the value is shown as the node's, never interpreted.
 */
fun firstStringField(element: JsonElement?, key: String): String? {
    when (element) {
        null -> return null
        is JsonObject -> {
            val direct = element[key]
            if (direct is JsonPrimitive && direct.isString && direct.content.isNotBlank()) return direct.content
            for (child in element.values) {
                firstStringField(child, key)?.let { return it }
            }
            return null
        }
        is JsonArray -> {
            for (child in element) {
                firstStringField(child, key)?.let { return it }
            }
            return null
        }
        else -> return null
    }
}
