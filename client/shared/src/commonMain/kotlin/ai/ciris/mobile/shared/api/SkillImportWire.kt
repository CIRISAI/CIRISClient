package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.ImportedSkillData
import ai.ciris.mobile.shared.models.SecurityFinding
import ai.ciris.mobile.shared.models.SecurityReport
import ai.ciris.mobile.shared.models.SkillPreviewData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The skill-import wire (CSD-015), parsed in ONE place.
 *
 * `/v1/system/adapters/import-skill{,/preview,/validate}` and
 * `/imported-skills` on the agent (`routes/system/skill_import.py`, main). The
 * three import calls each used to hand-parse the same `SkillPreviewResponse`,
 * and the preview copy dropped `security` — so the paste door showed a
 * third party's skill with no scan result at all.
 *
 * The verdict fails CLOSED: a report without `safe_to_import` is not a pass
 * (the agent's own model defaults it `False` since CIRISAgent#1203).
 */
object SkillImportWire {
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: ""
    private fun JsonObject.strs(k: String) =
        this[k]?.let { runCatching { it.jsonArray.mapNotNull { e -> e.jsonPrimitive.contentOrNull } }.getOrNull() } ?: emptyList()
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull ?: 0
    private fun JsonObject.obj(k: String): JsonObject? =
        this[k]?.let { runCatching { it.jsonObject }.getOrNull() }

    fun securityReport(o: JsonObject?): SecurityReport? {
        if (o == null) return null
        return SecurityReport(
            totalFindings = o.int("total_findings"),
            criticalCount = o.int("critical_count"),
            highCount = o.int("high_count"),
            mediumCount = o.int("medium_count"),
            lowCount = o.int("low_count"),
            safeToImport = o["safe_to_import"]?.jsonPrimitive?.booleanOrNull ?: false,
            summary = o.str("summary"),
            findings = o["findings"]?.let { runCatching { it.jsonArray }.getOrNull() }?.mapNotNull { el ->
                val f = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                SecurityFinding(
                    severity = f.str("severity").ifBlank { "info" },
                    category = f.str("category"),
                    title = f.str("title"),
                    description = f.str("description"),
                    evidence = f["evidence"]?.jsonPrimitive?.contentOrNull,
                    recommendation = f.str("recommendation"),
                )
            } ?: emptyList(),
        )
    }

    /** A `SkillPreviewResponse`. [security] overrides the embedded report (validate carries it at the top level). */
    fun preview(o: JsonObject, security: SecurityReport? = null): SkillPreviewData = SkillPreviewData(
        name = o.str("name"),
        description = o.str("description"),
        version = o.str("version"),
        moduleName = o.str("module_name"),
        tools = o.strs("tools"),
        requiredEnvVars = o.strs("required_env_vars"),
        requiredBinaries = o.strs("required_binaries"),
        hasSupportingFiles = o["has_supporting_files"]?.jsonPrimitive?.booleanOrNull ?: false,
        sourceUrl = o["source_url"]?.jsonPrimitive?.contentOrNull,
        instructionsPreview = o.str("instructions_preview"),
        security = security ?: securityReport(o.obj("security")),
    )

    /** `ImportedSkillsListResponse` → its skills. */
    fun importedSkills(o: JsonObject): List<ImportedSkillData> =
        o["skills"]?.let { runCatching { it.jsonArray }.getOrNull() }?.mapNotNull { el ->
            val s = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            ImportedSkillData(
                moduleName = s.str("module_name"),
                originalSkillName = s.str("original_skill_name"),
                version = s.str("version"),
                description = s.str("description"),
                adapterPath = s.str("adapter_path"),
                sourceUrl = s["source_url"]?.jsonPrimitive?.contentOrNull,
            )
        } ?: emptyList()
}
