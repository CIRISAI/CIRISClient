package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.viewmodels.NodeWait
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The node wait is DRIVABLE (CIRISClient#149): a harness can see the wait, see
 * the error, read why, and press Retry, all through `/tree` and `/click`.
 */
class NodeWaitBlockTest {

    private val registered = mutableMapOf<String, String?>()

    @AfterTest
    fun disarm() = TestAutomation.configure(
        onRegister = { _, _, _, _, _, _ -> },
        onUnregister = {},
        onSetScreen = {},
        onClear = {},
        isEnabled = { false },
    )

    private fun <T> render(state: NodeWait, onRetry: () -> Unit = {}, read: () -> T): T {
        TestAutomation.configure(
            onRegister = { tag, _, _, _, _, text -> registered[tag] = text },
            onUnregister = { registered.remove(it) },
            onSetScreen = {},
            onClear = { registered.clear() },
            isEnabled = { true },
        )
        return ImageComposeScene(width = 600, height = 1000) {
            NodeWaitBlock(nodeWait = state, onRetry = onRetry)
        }.use {
            it.render()
            it.render() // onGloballyPositioned lands after the first layout pass
            read()
        }
    }

    @Test
    fun idle_draws_nothing() {
        render(NodeWait.Idle) {
            assertFalse(StartupNodeTags.WAITING in registered)
            assertFalse(StartupNodeTags.UNREACHABLE in registered)
        }
    }

    @Test
    fun waiting_is_on_the_tree_with_its_words() {
        render(NodeWait.Waiting(elapsedSeconds = 3, attempt = 4)) {
            assertTrue(StartupNodeTags.WAITING in registered, "the wait must be visible to a driver: $registered")
            assertTrue(!registered[StartupNodeTags.WAITING].isNullOrBlank(), "and carry its sentence")
        }
    }

    @Test
    fun timed_out_is_an_error_with_a_retry_a_driver_can_press() {
        var retried = 0
        render(NodeWait.TimedOut("http://127.0.0.1:4243", 90), onRetry = { retried++ }) {
            assertTrue(StartupNodeTags.UNREACHABLE in registered, "the error state must be on /tree: $registered")
            val text = registered[StartupNodeTags.UNREACHABLE].orEmpty()
            // Headless there is no bundle, so strings resolve to their keys: the
            // check is that the BODY (which names the address) is readable too.
            assertEquals(2, text.lines().size, "title AND body on /tree: $text")
            assertTrue(text.lines()[1].contains("startup_node_unreachable_body"), text)
            assertTrue(StartupNodeTags.RETRY in registered, "Retry must be drivable: $registered")
            assertTrue(TestAutomation.triggerClick(StartupNodeTags.RETRY), "Retry must accept a /click")
        }
        assertEquals(1, retried)
    }
}
