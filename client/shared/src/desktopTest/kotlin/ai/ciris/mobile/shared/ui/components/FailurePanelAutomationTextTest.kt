package ai.ciris.mobile.shared.ui.components

import ai.ciris.mobile.shared.platform.TestAutomation
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A failure a driver cannot read is a failure it reports as silence.
 *
 * Android, run 36746575125: the first-run claim failed with "claim PIN not
 * captured", the panel said so on screen, and `/tree` carried the panel's tags
 * with NO text — `testable(tag)` registers text only when it is handed one. The
 * session fixture then reported "(no reason on screen)" beside a tag list that
 * included `failure_panel_detail`, and the cause took a logcat dig to find.
 *
 * Rendered headless and read back through the same registration a harness sees.
 */
class FailurePanelAutomationTextTest {

    private val registered = mutableMapOf<String, String?>()

    private fun armAutomation() = TestAutomation.configure(
        onRegister = { tag, _, _, _, _, text -> registered[tag] = text },
        onUnregister = { registered.remove(it) },
        onSetScreen = {},
        onClear = { registered.clear() },
        isEnabled = { true },
    )

    @AfterTest
    fun disarm() = TestAutomation.configure(
        onRegister = { _, _, _, _, _, _ -> },
        onUnregister = {},
        onSetScreen = {},
        onClear = {},
        isEnabled = { false },
    )

    /** What was registered while the panel was on screen — read BEFORE the
     *  scene closes, because closing disposes every element (as it should). */
    private fun render(title: String, detail: String, kind: FailureKind): Map<String, String?> {
        armAutomation()
        return ImageComposeScene(width = 900, height = 1600) {
            FailurePanel(title = title, detail = detail, kind = kind, context = "first-run claim")
        }.use {
            it.render()
            it.render() // onGloballyPositioned lands after the first layout pass
            registered.toMap()
        }
    }

    @Test
    fun the_panel_registers_its_title_and_detail_as_text() {
        val detail = "claim PIN not captured — this node's one-time ownership PIN never reached the app"
        val registered = render("This node could not be claimed", detail, FailureKind.Unrecoverable)

        assertTrue("failure_panel_detail" in registered, "the detail must be on /tree at all: $registered")
        assertEquals(detail, registered["failure_panel_detail"],
            "the verbatim reason must be readable by a driver, not only by eye")
        assertEquals("This node could not be claimed", registered["failure_panel_title"])
        assertTrue(!registered["failure_panel_guidance"].isNullOrBlank(),
            "the guidance names the kind (retry or report) and must be readable too")
    }
}
