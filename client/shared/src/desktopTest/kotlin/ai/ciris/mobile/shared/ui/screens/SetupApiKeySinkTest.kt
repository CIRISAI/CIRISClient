package ai.ciris.mobile.shared.ui.screens

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * THE LLM API KEY IS A SENSITIVE SINK.
 *
 * The with-AI five-platform run (CIRISClient#166's release gate) types a real
 * provider key into the setup wizard's `input_api_key`. The field draws it
 * masked, but the automation surface recorded it: `/tree` and `/element`
 * echoed it as `inputValue`, readable by anything that can reach :9091 and by
 * every harness that dumps a tree into a log or a report. A tag marked
 * sensitive is still applied by `/input`, and is never stored or echoed
 * (SensitiveInputs) — the treatment the YubiKey PIN already gets.
 *
 * No Compose UI harness here, so it reads the source, the trade
 * `ProvisionAccordHolderSinksTest` makes.
 */
class SetupApiKeySinkTest {

    private fun commonMain(): File =
        listOf("src/commonMain/kotlin", "shared/src/commonMain/kotlin", "client/shared/src/commonMain/kotlin")
            .map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("commonMain not found from ${File(".").absolutePath}")

    @Test
    fun bothApiKeyFieldsAreDeclaredSensitiveAndStillDispatched() {
        val src = File(commonMain(), "ai/ciris/mobile/shared/ui/screens/SetupScreen.kt").readText()
        val calls = Regex("""rememberInputSinks\(([^)]*)\)""").findAll(src).map { it.groupValues[1] }.toList()
        for (tag in listOf("input_api_key", "quick_input_api_key")) {
            val declaring = calls.filter { "\"$tag\"" in it }
            assertTrue(declaring.isNotEmpty(), "$tag has no declared input sink: $calls")
            assertTrue(
                declaring.all { "sensitive = true" in it },
                "$tag is declared by a sink that is not sensitive, so /tree echoes the key: $declaring",
            )
            assertTrue(Regex("\"$tag\"").findAll(src).count() >= 2, "$tag is declared but never dispatched")
        }
    }
}
