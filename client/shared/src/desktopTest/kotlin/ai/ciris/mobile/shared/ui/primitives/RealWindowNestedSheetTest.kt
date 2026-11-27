package ai.ciris.mobile.shared.ui.primitives

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [NestedSheetClickTest] in a REAL window, clicked by the operating system's own
 * mouse (java.awt.Robot). The headless scene took every click even with nested
 * sheets, while Eric's window froze (2026-11-27), so the freeze needs what only a
 * real window has: focus, and dialog layers as separate surfaces.
 *
 * Runs only with CIRIS_REAL_WINDOW_TEST=1 and a display: CI's desktop e2e job,
 * under xvfb. Never on a person's screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
class RealWindowNestedSheetTest {
    private val enabled = System.getenv("CIRIS_REAL_WINDOW_TEST") == "1" && !GraphicsEnvironment.isHeadless()

    private fun clicks(outer: @Composable (@Composable () -> Unit) -> Unit): Int {
        val clicks = AtomicInteger()
        var centre: Offset? = null
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow()
            window.setSize(800, 900)
            window.setLocation(0, 0)
            window.setContent {
                Box(Modifier.fillMaxSize()) {
                    outer {
                        var open by remember { mutableStateOf(true) }
                        if (open) {
                            ModalBottomSheet(onDismissRequest = { open = false }) {
                                Box(Modifier.fillMaxWidth().height(200.dp)) {
                                    Button(
                                        onClick = { clicks.incrementAndGet() },
                                        modifier = Modifier.onGloballyPositioned { centre = it.boundsInWindow().center },
                                    ) { Text("Close") }
                                }
                            }
                        }
                    }
                }
            }
            window.isVisible = true
        }
        try {
            Thread.sleep(2_500) // both layers animate in
            val at = checkNotNull(centre) { "the inner sheet's button was never laid out" }
            val robot = Robot().apply { autoDelay = 50 }
            // Compose reports window pixels; the content pane starts at the window's insets.
            var origin = java.awt.Point()
            SwingUtilities.invokeAndWait { origin = window.contentPane.locationOnScreen }
            val scale = window.graphicsConfiguration.defaultTransform.scaleX
            val x = origin.x + (at.x / scale).toInt()
            val y = origin.y + (at.y / scale).toInt()
            repeat(3) {
                robot.mouseMove(x, y)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                Thread.sleep(300)
            }
            robot.waitForIdle()
            Thread.sleep(500)
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
        return clicks.get()
    }

    @Test
    fun aSheetOpenedFromTheRoomPanelTakesRealClicks() {
        if (!enabled) return
        assertEquals(3, clicks { inner -> RoomPanel(onDismiss = {}) { inner() } })
    }

    @Test
    fun whatASheetInsideASheetDoesWithRealClicks() {
        if (!enabled) return
        // A record, not a gate: the answer for this Compose version, in CI's log.
        println("real window, nested ModalBottomSheet: ${clicks { inner -> ModalBottomSheet(onDismissRequest = {}) { inner() } }} of 3 clicks reached the inner sheet")
    }
}
