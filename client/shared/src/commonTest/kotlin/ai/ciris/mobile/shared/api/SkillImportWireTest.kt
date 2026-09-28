package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.SecurityReport
import ai.ciris.mobile.shared.models.importAllowed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSD-015: the scan verdict on the skill-import wire.
 *
 * Two defects this pins. The preview parse (the paste door, formerly
 * Screen.SkillImport) dropped `security` entirely, so a person importing
 * someone else's SKILL.md never saw what the scan found. And a report without
 * `safe_to_import` read as SAFE — the verdict failed open, while the agent's
 * own model (`skill_import.py:71` on main) now fails closed (CIRISAgent#1203).
 */
class SkillImportWireTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val previewWithFinding = """
        {"name":"weather","description":"d","version":"1.0","module_name":"imported_weather",
         "tools":["skill:weather","skill:weather:info"],"required_env_vars":["API_KEY"],
         "required_binaries":["curl"],"has_supporting_files":false,"source_url":null,
         "instructions_preview":"do it",
         "security":{"total_findings":1,"critical_count":1,"high_count":0,"medium_count":0,
           "low_count":0,"safe_to_import":false,"summary":"1 critical",
           "findings":[{"severity":"critical","category":"shell","title":"shell invocation",
             "description":"runs rm","evidence":"rm -rf","recommendation":"don't"}]}}
    """

    @Test
    fun preview_keeps_the_security_report() {
        val p = SkillImportWire.preview(obj(previewWithFinding))
        val sec = assertNotNull(p.security, "the preview parse must not drop `security`")
        assertEquals(1, sec.criticalCount)
        assertFalse(sec.safeToImport)
        assertEquals("shell invocation", sec.findings.single().title)
        assertEquals(listOf("curl"), p.requiredBinaries)
        assertFalse(p.importAllowed())
    }

    @Test
    fun a_report_without_a_verdict_is_not_a_pass() {
        val sec = assertNotNull(SkillImportWire.securityReport(obj("""{"total_findings":0,"summary":""}""")))
        assertFalse(sec.safeToImport, "absent safe_to_import must fail closed")
        assertFalse(SecurityReport().safeToImport, "the model default must fail closed too")
    }

    @Test
    fun a_preview_nobody_scanned_cannot_be_imported() {
        val p = SkillImportWire.preview(obj("""{"name":"x","description":"","version":"1","module_name":"imported_x"}"""))
        assertNull(p.security)
        assertFalse(p.importAllowed(), "no scan is not a clear scan")
    }

    @Test
    fun a_cleared_scan_can_be_imported() {
        val p = SkillImportWire.preview(obj(
            """{"name":"x","description":"","version":"1","module_name":"imported_x",
               "security":{"safe_to_import":true,"summary":"clean"}}"""
        ))
        assertTrue(p.importAllowed())
    }

    @Test
    fun imported_skills_list_parses_every_field() {
        val list = SkillImportWire.importedSkills(obj(
            """{"skills":[{"module_name":"imported_weather","original_skill_name":"weather",
               "version":"1.0","description":"d","adapter_path":"/a","source_url":"https://x"}],"total":1}"""
        ))
        val s = list.single()
        assertEquals("imported_weather", s.moduleName)
        assertEquals("weather", s.originalSkillName)
        assertEquals("https://x", s.sourceUrl)
    }
}
