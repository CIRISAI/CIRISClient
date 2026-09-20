package ai.ciris.mobile.shared.ui.nav

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every key the shell builds dynamically resolves in the canonical bundle.
 * The localization gate only sees literal `localizedString("…")` calls; the
 * shell derives circle, tab, instrument and empty-state keys from data, so
 * this is where they are checked.
 */
class CirclesNavCopyTest {

    private fun enJson(): JsonObject {
        val candidates = listOf(
            File("src/desktopMain/resources/localization/en.json"),
            File("shared/src/desktopMain/resources/localization/en.json"),
            File("client/shared/src/desktopMain/resources/localization/en.json"),
        )
        val f = candidates.firstOrNull { it.exists() } ?: error("en.json not found from ${File(".").absolutePath}")
        return Json.parseToJsonElement(f.readText()) as JsonObject
    }

    private fun resolves(root: JsonObject, key: String): Boolean {
        var node: Any? = root
        for (part in key.split('.')) {
            node = (node as? JsonObject)?.get(part) ?: return false
        }
        return node is JsonPrimitive && node.isString
    }

    @Test
    fun everyShellKeyResolves() {
        val en = enJson()
        val keys = mutableListOf<String>()
        for (c in CirclesNav.circles) {
            keys += CirclesNav.circleNameKey(c); keys += CirclesNav.circleSubtitleKey(c); keys += CirclesNav.circleRuleKey(c)
            for (t in Tab.entries) keys += CirclesNav.emptyKey(c, t)
        }
        for (t in Tab.entries) keys += t.labelKey
        for (i in CirclesNav.instruments) keys += i.labelKey
        keys += listOf("nav.my_things", "nav.stop_everything", "nav.stop.title", "nav.stop.fact_what_label", "nav.stop.fact_what",
            "nav.stop.fact_keeps_label", "nav.stop.fact_keeps", "nav.stop.fact_again_label", "nav.stop.fact_again",
            "nav.stop.confirm", "nav.stop.nothing_body", "settings.brightness", "settings.brightness_light",
            "settings.brightness_system", "settings.brightness_dark", "nav.surface.help")
        val missing = keys.filter { !resolves(en, it) }
        assertTrue(missing.isEmpty(), "shell keys missing from en.json: $missing")
    }
}
