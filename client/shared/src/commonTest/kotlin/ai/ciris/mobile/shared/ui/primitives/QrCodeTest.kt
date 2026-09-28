package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.ui.theme.InstrumentTokens
import ai.ciris.mobile.shared.ui.theme.PaperTokens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What QrCode draws, minus the drawing. The symbol itself is proved by
 * QrEncoderTest (desktopTest), which decodes it with zxing.
 */
class QrCodeTest {

    @Test
    fun dark_modules_on_a_light_ground_whatever_the_theme() {
        val (light, dark) = qrColours()
        // Fixed to Paper even though the dark ground's ink is light: a scanner
        // needs dark-on-light, and many do not try the inverse.
        assertEquals(PaperTokens.raised, light)
        assertEquals(PaperTokens.ink, dark)
        assertTrue(light != InstrumentTokens.raised && dark != InstrumentTokens.ink)
        assertTrue(contrast(light, dark) >= 15.0, "QR contrast ${contrast(light, dark)}")
        assertTrue(light.luminance() > dark.luminance())
    }

    @Test
    fun the_quiet_zone_is_never_less_than_four_modules() {
        for (modules in listOf(21, 25, 41, 57, 101, 177)) {
            for (px in listOf(60f, 176f, 200f, 351f, 462f, 528f, 1000f)) {
                val g = QrGeometry.fit(px, modules)
                assertTrue(g.inset >= QrGeometry.QUIET_ZONE_MODULES * g.cell - 0.001f, "$modules modules in $px px: $g")
                assertTrue(g.inset * 2 + g.cell * modules <= px + 0.001f, "$modules modules overflow $px px")
            }
        }
    }

    @Test
    fun modules_are_whole_pixels_when_there_is_room() {
        val g = QrGeometry.fit(462f, 29) // 29 + 8 = 37 modules across -> 12.48 px
        assertEquals(12f, g.cell)
        assertEquals(g.inset, kotlin.math.floor(g.inset))
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
