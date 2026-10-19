package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.viewmodels.AgentProcessingState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * THE AGENT CHAT'S COMPOSER TAKES A CLICK FROM AUTOMATION (CIRISClient#50's
 * with-AI iOS leg, PR #215).
 *
 * `input_message` was a plain `testable()`: tagged, with an input sink, and no
 * click handler. Desktop's `/click` hid that with a Robot click at the
 * element's centre (desktopApp TestAutomationServer, "NO HANDLER: STILL
 * CLICKED"), so linux-ai and windows-ai passed csd_010's focus step. iOS and
 * Android have no such fallback, and the same step answered
 * `"No click handler for: input_message"` (run 39296309752). The handler is
 * common code, so it is asserted here, headless, for every platform.
 */
class ComposerDrivableTest {

    @BeforeTest
    fun arm() {
        TestAutomation.configure(
            onRegister = { _, _, _, _, _, _ -> },
            onUnregister = {},
            onSetScreen = {},
            onClear = {},
            isEnabled = { true },
        )
    }

    @AfterTest
    fun disarm() {
        TestAutomation.configure(
            onRegister = { _, _, _, _, _, _ -> },
            onUnregister = {},
            onSetScreen = {},
            onClear = {},
            isEnabled = { false },
        )
    }

    @Test
    fun the_composer_field_registers_a_click_handler_and_a_click_focuses_it() {
        var focused = false
        ImageComposeScene(width = 1080, height = 400, density = Density(3f)) {
            ChatInputBarWithBubbles(
                text = "",
                onTextChange = {},
                onSend = {},
                enabled = true,
                onFocused = { focused = true },
                agentState = AgentProcessingState.IDLE,
                bubbleEmojis = emptyList(),
                sseConnected = true,
            )
        }.use { scene ->
            scene.render()
            assertTrue(
                TestAutomation.hasClickHandler("input_message"),
                "input_message is tagged but has no click handler — /click on iOS and Android answers " +
                    "\"No click handler for: input_message\"",
            )
            assertTrue(TestAutomation.triggerClick("input_message"), "the handler did not run")
            scene.render()
            assertTrue(focused, "a click on input_message did not focus the field")
        }
    }
}
