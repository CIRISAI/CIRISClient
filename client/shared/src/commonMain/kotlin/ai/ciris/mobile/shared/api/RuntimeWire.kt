package ai.ciris.mobile.shared.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The runtime-control wire, read by hand where the generated model reads it
 * wrong (CSD-024).
 *
 * `POST /v1/system/runtime/step` is served by `system_extensions.py:292` on
 * CIRISAgent main, and it answers a `SingleStepResponse` — `step_point`,
 * `processing_time_ms`, `tokens_used`, `step_result`, `pipeline_state` — not
 * the `RuntimeControlResponse` the generated client decodes it as. Decoded
 * that way the step point (`current_step`, which the step route never sends)
 * was always null, and the processing time was thrown away and set to null in
 * code: the one screen that walks the H3ERE pipeline never showed where it was.
 */

/** A runtime action the agent answered with `success: false` — said, not drawn as done. */
class RuntimeActionDeclined(message: String) : RuntimeException(message)

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Parse a `POST /v1/system/runtime/step` body (`SuccessResponse[SingleStepResponse]`).
 * Step points are normalised to the upper-case keys the pipeline card uses.
 *
 * @throws RuntimeActionDeclined when the agent says `success: false`.
 */
fun parseRuntimeStepBody(raw: String): SingleStepResponse {
    val root = lenient.parseToJsonElement(raw).jsonObject
    val data = root["data"] as? JsonObject ?: throw RuntimeException("API returned null data")
    val success = (data["success"] as? JsonPrimitive)?.booleanOrNull
    val message = data.str("message")
    if (success == false) throw RuntimeActionDeclined(message ?: "step declined")
    val stepPoint = data.str("step_point")
        ?: (data["step_result"] as? JsonObject)?.str("step_point")
    val ms = (data["processing_time_ms"] as? JsonPrimitive)?.doubleOrNull
    return SingleStepResponse(
        stepPoint = stepPoint?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
        message = message,
        processingTimeMs = ms?.toLong(),
        tokensUsed = (data["tokens_used"] as? JsonPrimitive)?.intOrNull,
    )
}

/**
 * The `POST /v1/scheduler/tasks` body, built by the serializer.
 *
 * It was hand-concatenated and escaped only `"`, so a newline or a backslash
 * in a trigger prompt — an ordinary multi-line prompt — produced invalid JSON
 * and a 422 the person could not read (CSD-012).
 */
fun schedulerCreateBody(
    name: String,
    goalDescription: String,
    triggerPrompt: String,
    deferUntil: String?,
    scheduleCron: String?,
): String = buildJsonObject {
    put("name", JsonPrimitive(name))
    put("goal_description", JsonPrimitive(goalDescription))
    put("trigger_prompt", JsonPrimitive(triggerPrompt))
    deferUntil?.let { put("defer_until", JsonPrimitive(it)) }
    scheduleCron?.let { put("schedule_cron", JsonPrimitive(it)) }
}.toString()
