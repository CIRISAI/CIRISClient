package ai.ciris.mobile.shared.ui.screens

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * EVERY TEXT FIELD HAS AN INPUT SINK.
 *
 * The three fields on Provision an accord holder carried `input_*` tags and
 * nothing subscribed to them, so `/input` had nothing to apply to. CSD-068's
 * flow reached the screen for the first time on 2026-09-29 (once the runner's
 * circle hop was verified) and failed its third step — "input into
 * 'input_provision_holder_usb_path' did not succeed" — on a form a person can
 * type into. `check_ui_drivable.py` carried the three as baseline debt; this
 * pins that the debt stays paid. No Compose UI harness in this module, so it
 * reads the source, the trade `QrNoStandInTest` makes.
 */
class ProvisionAccordHolderSinksTest {

    private fun commonMain(): File =
        listOf("src/commonMain/kotlin", "shared/src/commonMain/kotlin", "client/shared/src/commonMain/kotlin")
            .map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("commonMain not found from ${File(".").absolutePath}")

    @Test
    fun theThreeFieldsDeclareSinksAndDispatchThem() {
        val src = File(commonMain(), "ai/ciris/mobile/shared/ui/screens/ProvisionAccordHolderScreen.kt").readText()
        val declared = Regex("""rememberInputSinks\(([^)]*)\)""").find(src)?.groupValues?.get(1) ?: ""
        for (tag in listOf("input_provision_holder_key_id", "input_provision_holder_usb_path", "input_provision_holder_pin")) {
            assertTrue("\"$tag\"" in declared, "$tag has no declared input sink (rememberInputSinks)")
            assertTrue(Regex("\"$tag\"\\s*->").containsMatchIn(src), "$tag is declared but never dispatched")
        }
    }

    /**
     * THE EMPTY STATE'S TAG LEAVES WITH THE EMPTY STATE. CSD-068 declares
     * `txt_provision_holder_start` as the empty state ("the three steps, none
     * of them done yet") and `provision_holder_error` as the error; the screen
     * drew the intro in every state, so after a refused submit both tags were
     * on screen and `state: error` failed on the local Linux leg (2026-09-29).
     * Error and empty never look alike (CSD/3 §2.2).
     */
    @Test
    fun theIntroIsDrawnOnlyWhileNothingHasHappened() {
        val src = File(commonMain(), "ai/ciris/mobile/shared/ui/screens/ProvisionAccordHolderScreen.kt").readText()
        val guarded = Regex("""if \(!busy && error == null && provisionedKeyId == null\)[\s\S]{0,400}testable\("txt_provision_holder_start"\)""")
        assertTrue(guarded.containsMatchIn(src), "txt_provision_holder_start is not guarded on the empty state")
    }
}
