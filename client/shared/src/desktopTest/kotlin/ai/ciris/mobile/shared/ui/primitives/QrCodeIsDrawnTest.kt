package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.platform.TestAutomation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.jetbrains.skia.Bitmap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A QR with no size is no QR.
 *
 * Matrix runs 36905352239 / 36907392196, against ciris-server 0.5.218, every
 * desktop leg: CSD-092's `qr_contact_code` was "composed but off screen after
 * scrolling (scrolls: down: moved; up: moved)" — and the screenshot showed a
 * populated contact-code card with no symbol on it at all. Scrolling was never
 * the problem. [QrCode] was a `Canvas`, which is a `Spacer`, and a Spacer takes
 * a size only from FIXED constraints: `defaultMinSize(176.dp)` raises the
 * minimum, so the symbol measured 0 x 0, drew nothing, and registered a zero
 * box — the shape the harness reads as composed-but-off-screen. Both callers
 * (the contact-code card and My Identity's enrol card) pass no size.
 *
 * Rendered headless the way those callers place it, and read back through the
 * same registration a harness sees, plus the pixels: it must have the default
 * side, and it must actually paint dark modules on its light square.
 */
class QrCodeIsDrawnTest {

    private val registered = mutableMapOf<String, IntArray>()

    /** What had registered while the scene was up; closing it disposes and unregisters. */
    private var seen: Map<String, IntArray> = emptyMap()

    @AfterTest
    fun disarm() = TestAutomation.configure(
        onRegister = { _, _, _, _, _, _ -> },
        onUnregister = {},
        onSetScreen = {},
        onClear = {},
        isEnabled = { false },
    )

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit): Bitmap {
        TestAutomation.configure(
            onRegister = { tag, x, y, w, h, _ -> registered[tag] = intArrayOf(x, y, w, h) },
            onUnregister = { registered.remove(it) },
            onSetScreen = {},
            onClear = { registered.clear() },
            isEnabled = { true },
        )
        return ImageComposeScene(width = 600, height = 400, density = Density(1f), content = content).use {
            var t = 0L
            var img = it.render(t)
            repeat(5) { _ -> t += 16_000_000L; img = it.render(t) }
            seen = registered.toMap()
            Bitmap.makeFromImage(img)
        }
    }

    private fun darkPixels(bmp: Bitmap, b: IntArray): Int {
        var n = 0
        for (x in b[0] until b[0] + b[2]) for (y in b[1] until b[1] + b[3]) {
            val c = bmp.getColor(x, y)
            val luma = ((c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff)) / 3
            if ((c ushr 24) > 0x80 && luma < 0x60) n++
        }
        return n
    }

    @Test
    fun a_qr_placed_without_a_size_has_the_default_side_and_paints() {
        val bmp = render {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCode(value = "CIRIS-V3-AMA6-NFZT-TCRA-PU5J", contentDescription = "qr", tag = "qr_probe")
            }
        }
        val b = assertNotNull(seen["qr_probe"], "the QR must register: ${seen.keys}")
        assertEquals(176, b[2], "width of ${b.toList()}: a zero box is how the harness sees composed-but-off-screen")
        assertEquals(176, b[3], "height of ${b.toList()}")
        assertTrue(darkPixels(bmp, b) > 500, "the QR's box holds no dark modules: nothing was drawn")
    }

    @Test
    fun a_caller_size_still_wins() {
        render {
            QrCode(value = "CIRIS-V3-AMA6", contentDescription = "qr", tag = "qr_sized", modifier = Modifier.size(240.dp))
        }
        val b = assertNotNull(seen["qr_sized"])
        assertEquals(240, b[2]); assertEquals(240, b[3])
    }
}
