package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.DelegationConstraints
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The request bodies of `/v1/auth/device/{delegate,approve,deny,revoke}`
 * (CSD-055), built by a JSON encoder rather than by string concatenation.
 *
 * They used to be concatenated by hand, so a `"` in a label, a key id or a goal
 * — all things the owner types — produced a 400, or worse a body carrying a
 * field the owner never chose (`…","sub_delegation":true,"x":"`). Only the
 * owner can send these, so that was a correctness bug rather than an exploit,
 * but a delegation whose terms are not the ones on the screen is the defect
 * this card exists to prevent.
 */
internal object DelegationBodies {
    fun delegate(
        label: String,
        mode: String,
        existingKeyId: String?,
        scope: List<String>,
        constraints: DelegationConstraints?,
    ): String = buildJsonObject {
        put("mode", mode.trim())
        put("label", label.trim())
        existingKeyId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("existing_key_id", it) }
        put("scope", JsonArray(scope.map { JsonPrimitive(it) }))
        constraints?.takeIf { !it.isUnconstrained() }?.let { put("constraints", constraints(it)) }
    }.toString()

    fun userCode(userCode: String, constraints: DelegationConstraints? = null): String = buildJsonObject {
        put("user_code", userCode.trim())
        constraints?.takeIf { !it.isUnconstrained() }?.let { put("constraints", constraints(it)) }
    }.toString()

    fun clientId(clientId: String): String = buildJsonObject { put("client_id", clientId.trim()) }.toString()

    /**
     * The tri-state allow-list is kept exactly: absent when `null` (every owner
     * verb), `[]` when read-only, else the subset. Deny is sent only when set.
     */
    fun constraints(c: DelegationConstraints): JsonObject = buildJsonObject {
        c.actionsAllow?.let { put("actions_allow", JsonArray(it.map { v -> JsonPrimitive(v) })) }
        if (c.actionsDeny.isNotEmpty()) put("actions_deny", JsonArray(c.actionsDeny.map { JsonPrimitive(it) }))
        c.goal?.takeIf { it.isNotBlank() }?.let { put("goal", it) }
    }
}
