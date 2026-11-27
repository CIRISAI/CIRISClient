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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A room's "Files in this room" panel hosts the Files screen, whose file view
 * is a ModalBottomSheet. With the panel ALSO a ModalBottomSheet the file view
 * opened as a sheet inside a sheet, and on desktop no click reached anything
 * again: Eric's window froze opening a file attached in a chat (2026-11-27).
 * The panel is now [RoomPanel], which is not a sheet. This drives a click on a
 * button inside a sheet opened from within each, and says which one lands.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
class NestedSheetClickTest {
    private val w = 800
    private val h = 900

    private fun clicksReachTheInnerSheet(outer: @Composable (@Composable () -> Unit) -> Unit): Int {
        var clicks = 0
        var centre: Offset? = null
        ImageComposeScene(width = w, height = h, density = Density(1f), content = {
            Box(Modifier.fillMaxSize()) {
                outer {
                    var open by remember { mutableStateOf(true) }
                    if (open) {
                        ModalBottomSheet(onDismissRequest = { open = false }) {
                            Box(Modifier.fillMaxWidth().height(200.dp)) {
                                Button(
                                    onClick = { clicks++ },
                                    modifier = Modifier.onGloballyPositioned { centre = it.boundsInWindow().center },
                                ) { Text("Close") }
                            }
                        }
                    }
                }
            }
        }).use { scene ->
            var t = 0L
            fun frames(n: Int) = repeat(n) { _ -> scene.render(t); t += 16_000_000L }
            frames(120) // both layers animate in
            val at = assertNotNull(centre, "the inner sheet's button was laid out")
            repeat(3) {
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at)
                scene.sendPointerEvent(PointerEventType.Release, at)
                frames(10)
            }
        }
        return clicks
    }

    @Test
    fun aSheetOpenedFromTheRoomPanelTakesItsClicks() {
        val clicks = clicksReachTheInnerSheet { inner -> RoomPanel(onDismiss = {}) { inner() } }
        assertEquals(3, clicks, "every click on the file view must land")
    }

    @Test
    fun aSheetInsideASheetIsWhatFroze() {
        // Not an assertion about Compose: a record of what the panel used to be. If a
        // later Compose makes nested sheets take clicks, this says so, and RoomPanel
        // is then a choice rather than a fix.
        val clicks = clicksReachTheInnerSheet { inner -> ModalBottomSheet(onDismissRequest = {}) { inner() } }
        println("nested ModalBottomSheet: $clicks of 3 clicks reached the inner sheet")
    }
}
