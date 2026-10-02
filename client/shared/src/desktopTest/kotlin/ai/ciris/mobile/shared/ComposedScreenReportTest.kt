package ai.ciris.mobile.shared

import ai.ciris.mobile.shared.platform.TestAutomation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/screen` reports the screen that is COMPOSED, not the navigation target
 * (CIRISClient#149, defect 2).
 *
 * CIRISAgent run 37010300145, iOS: the app showed Startup with an empty tree,
 * and `GET /screen` already said `Setup`. `currentScreen` was Setup, but the
 * Setup branch holds on an unprobed brain by composing StartupScreen. The
 * harness waited for "Setup", passed in 535 ms, and then timed out on a wizard
 * control. That sent the diagnosis to the wrong layer.
 *
 * Rendered headless and read back through the same `onSetScreen` hook the
 * desktop test server's `/screen` reads. Android and iOS read the same call:
 * their `setCurrentScreen` writes `TestAutomationState.currentScreen`.
 */
class ComposedScreenReportTest {

    private val reported = mutableListOf<String>()

    @BeforeTest
    fun arm() = TestAutomation.configure(
        onRegister = { _, _, _, _, _, _ -> },
        onUnregister = {},
        onSetScreen = { reported += it },
        onClear = {},
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

    @Test
    fun setup_held_on_an_unprobed_brain_composes_startup_and_says_so() {
        assertEquals(Screen.Startup, composedScreen(Screen.Setup, brainPresent = null))
        assertEquals(Screen.Setup, composedScreen(Screen.Setup, brainPresent = true))
        assertEquals(Screen.Setup, composedScreen(Screen.Setup, brainPresent = false))
        assertEquals(Screen.Login, composedScreen(Screen.Login, brainPresent = null))
    }

    @Test
    fun screen_follows_composition_not_the_navigation_intent() {
        var target by mutableStateOf<Screen>(Screen.Startup)
        var brainPresent by mutableStateOf<Boolean?>(null)

        ImageComposeScene(width = 400, height = 800) {
            ReportComposedScreen(composedScreen(target, brainPresent))
        }.use { scene ->
            scene.render()
            assertEquals("Startup", reported.lastOrNull(), "the first frame reports what it composed")

            // The navigation intent moves; nothing has composed it yet.
            target = Screen.Setup
            Snapshot.sendApplyNotifications()
            assertEquals("Startup", reported.lastOrNull(), "an intent that has not composed must not move /screen")

            // Composed: Setup with the brain unprobed renders StartupScreen, the iOS state in #149.
            scene.render()
            assertEquals("Startup", reported.lastOrNull(), "Setup holding on StartupScreen is Startup on /screen")

            // The gate lands, the wizard composes, and only now does /screen say Setup.
            brainPresent = true
            Snapshot.sendApplyNotifications()
            scene.render()
            assertEquals("Setup", reported.lastOrNull())

            target = Screen.Login
            Snapshot.sendApplyNotifications()
            scene.render()
            assertEquals("Login", reported.lastOrNull())
            assertTrue(reported.zipWithNext().none { (a, b) -> a == b }, "one report per change, not per frame: $reported")
        }
    }
}
