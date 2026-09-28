package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.viewmodels.ConfigItemData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `/v1/config` has TWO servers with two shapes (CSD-023 / CSD-031).
 *
 *  * **CIRISAgent** (`routes/config.py`, main): `{data: {configs: [{key, value:
 *    {string_value|int_value|float_value|bool_value|list_value|dict_value},
 *    updated_at, updated_by, is_sensitive}], total}}`; a PUT answers
 *    `{data: ConfigItemResponse}`.
 *  * **CIRISServer** (`src/config_api.rs`, origin/main): a BARE map
 *    `{key: {key, value, version, updated_by, scope}}` with a plain-JSON value,
 *    and a PUT answers a bare `ConfigEntry`.
 *
 * The generated client knew only the first, so against a node the list threw
 * "API returned null data" and a PUT that SUCCEEDED was reported as a failure —
 * which, in Transport's seven-key loop, stopped the write after the first key.
 *
 * The node also reads its values TYPED (`snap.bool()`, `snap.i64()` in
 * `graph_config.rs`, no coercion from strings), so a value is written with the
 * JSON type it has, not as a string.
 */

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The agent wraps values in `{string_value: …}` etc.; the node sends them plain. Unwrap either. */
fun unwrapConfigValue(value: JsonElement?): JsonElement {
    if (value == null) return JsonNull
    if (value is JsonObject) {
        val keys = listOf("string_value", "int_value", "float_value", "bool_value", "list_value", "dict_value")
        if (value.keys.isNotEmpty() && value.keys.all { it in keys }) {
            keys.forEach { k -> value[k]?.takeIf { it !is JsonNull }?.let { return it } }
            return JsonNull
        }
    }
    return value
}

/** How a config value reads on screen. */
fun configDisplay(value: JsonElement): String = when (value) {
    is JsonNull -> "(empty)"
    is JsonPrimitive -> value.content
    is JsonArray -> value.joinToString(", ") { configDisplay(it) }
    is JsonObject -> value.entries.joinToString(", ") { "${it.key}: ${configDisplay(it.value)}" }
}

/**
 * What the text editor opens with: a primitive as it displays, a list or a
 * dict as its JSON — the one form [configValueFor] can write back as the same
 * type. (`a, b` reads well and cannot be parsed back into anything but a string.)
 */
fun configEditText(value: JsonElement): String = when (value) {
    is JsonArray, is JsonObject -> value.toString()
    else -> configDisplay(value)
}

private fun itemFrom(key: String, obj: JsonObject): ConfigItemData {
    val raw = unwrapConfigValue(obj["value"])
    return ConfigItemData(
        key = obj.str("key") ?: key,
        displayValue = configDisplay(raw),
        updatedAt = obj.str("updated_at"),
        updatedBy = obj.str("updated_by") ?: "",
        isSensitive = (obj["is_sensitive"] as? JsonPrimitive)?.booleanOrNull ?: false,
        rawValue = raw,
        editValue = configEditText(raw),
    )
}

/** Parse a `GET /v1/config` body from either server. */
fun parseConfigListBody(raw: String): ConfigListData {
    val root = lenient.parseToJsonElement(raw) as? JsonObject
        ?: throw RuntimeException("config list: not a JSON object")
    val data = root["data"]
    if (data is JsonObject && data["configs"] is JsonArray) {
        val items = (data["configs"] as JsonArray).mapNotNull { el ->
            (el as? JsonObject)?.let { itemFrom(it.str("key") ?: "", it) }
        }
        val total = (data["total"] as? JsonPrimitive)?.longOrNull?.toInt() ?: items.size
        return ConfigListData(configs = items, total = total)
    }
    if (root.containsKey("data")) throw RuntimeException("API returned null data")
    // The node: a bare map of key → ConfigEntry.
    val items = root.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { itemFrom(k, it) } }
        .sortedBy { it.key }
    return ConfigListData(configs = items, total = items.size)
}

/** Parse a `PUT /v1/config/{key}` body from either server. */
fun parseConfigItemBody(key: String, raw: String): ConfigItemData {
    val root = lenient.parseToJsonElement(raw) as? JsonObject
        ?: throw RuntimeException("config item: not a JSON object")
    val data = root["data"]
    return when {
        data is JsonObject -> itemFrom(key, data)
        root.containsKey("data") -> throw RuntimeException("API returned null data")
        else -> itemFrom(key, root)
    }
}

/**
 * What an edited text becomes on the wire: the JSON type the value HAD, when
 * the text still parses as that type; otherwise a string. A boolean key stays a
 * boolean and a number stays a number — the node does not coerce.
 *
 * A list or a dict is written back only as a list or a dict: the text must
 * parse as JSON of that type (the form [configEditText] opened it in), or the
 * edit is refused with the reason. Wrapping it as a string would replace the
 * value with text that merely looks like it, which is what an unchanged save
 * used to do.
 */
fun configValueFor(text: String, previous: JsonElement?): JsonElement {
    val t = text.trim()
    if (previous is JsonArray || previous is JsonObject) {
        val kind = if (previous is JsonArray) "list" else "object"
        val parsed = runCatching { lenient.parseToJsonElement(t) }.getOrNull()
        if (parsed != null && parsed::class == previous::class) return parsed
        throw IllegalArgumentException(
            "this value is a $kind; edit it as JSON (a $kind), not as text — not written",
        )
    }
    val prev = previous as? JsonPrimitive
    if (prev != null && !prev.isString) {
        prev.booleanOrNull?.let { _ ->
            if (t.equals("true", true)) return JsonPrimitive(true)
            if (t.equals("false", true)) return JsonPrimitive(false)
        }
        if (prev.longOrNull != null) t.toLongOrNull()?.let { return JsonPrimitive(it) }
        if (prev.doubleOrNull != null) t.toDoubleOrNull()?.let { return JsonPrimitive(it) }
    }
    return JsonPrimitive(text)
}
