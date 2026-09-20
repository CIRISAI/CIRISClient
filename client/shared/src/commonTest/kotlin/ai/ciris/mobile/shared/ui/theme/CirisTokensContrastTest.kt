package ai.ciris.mobile.shared.ui.theme

import ai.ciris.mobile.shared.ui.nav.CohortScope
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE THREE TOKEN RULES, MEASURED.
 *
 * The design says every text token clears 4.5:1 on the worst surface it can
 * sit on and the surface list is closed at three. That claim was wrong once
 * already (`ok` paper shipped at 4.43:1 on sunken), which is why this is a
 * test and not a comment. Change a token, re-measure here.
 */
class CirisTokensContrastTest {

    private fun lum(c: Color): Double {
        fun ch(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = lum(a); val lb = lum(b)
        val hi = maxOf(la, lb); val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private val grounds = listOf("paper" to PaperTokens, "instrument" to InstrumentTokens)

    @Test
    fun everyForegroundClears4point5OnEverySurface() {
        val failures = mutableListOf<String>()
        for ((groundName, t) in grounds) {
            for ((fgName, fg) in t.foregrounds) {
                for ((i, surface) in t.surfaces.withIndex()) {
                    val ratio = contrast(fg, surface)
                    if (ratio < 4.5) failures += "$groundName $fgName on surface#$i = ${(ratio * 100).toInt() / 100.0}"
                }
            }
        }
        assertTrue(failures.isEmpty(), "tokens below 4.5:1 — re-resolve them, do not widen the rule:\n" + failures.joinToString("\n"))
    }

    @Test
    fun theSurfaceListIsClosedAtThree() {
        for ((_, t) in grounds) assertEquals(3, t.surfaces.size)
    }

    @Test
    fun surfacesDoNotInvert() {
        // raised is lighter than ground and sunken darker — in BOTH grounds. Only ink reverses.
        for ((name, t) in grounds) {
            assertTrue(lum(t.raised) > lum(t.ground), "$name: raised must be lighter than ground")
            assertTrue(lum(t.sunken) < lum(t.ground), "$name: sunken must be darker than ground")
        }
        assertTrue(lum(PaperTokens.ink) < lum(InstrumentTokens.ink), "ink reverses between grounds")
    }

    @Test
    fun onAccentReadsOnBrandOkAndDanger() {
        for ((name, t) in grounds) {
            for ((fill, fillName) in listOf(t.brand to "brand", t.ok to "ok", t.danger to "danger")) {
                val ratio = contrast(t.onAccent, fill)
                assertTrue(ratio >= 4.5, "$name onAccent on $fillName = $ratio")
            }
        }
    }

    @Test
    fun everyCircleHasAColourInBothGrounds() {
        for ((_, t) in grounds) {
            val colours = CohortScope.entries.map { t.circle(it) }
            assertEquals(CohortScope.entries.size, colours.toSet().size, "five circles, five distinct colours")
        }
    }

    @Test
    fun groundResolvesItsOwnTokens() {
        assertEquals(PaperTokens, Ground.PAPER.tokens)
        assertEquals(InstrumentTokens, Ground.INSTRUMENT.tokens)
    }

    @Test
    fun layoutBandsAreTheThreeBreakpointsAndNothingElse() {
        assertEquals(LayoutBand.PHONE, LayoutBand.forWidth(androidx.compose.ui.unit.Dp(390f)))
        assertEquals(LayoutBand.TWO_COLUMN, LayoutBand.forWidth(androidx.compose.ui.unit.Dp(560f)))
        assertEquals(LayoutBand.TABS_FIT, LayoutBand.forWidth(androidx.compose.ui.unit.Dp(700f)))
        assertEquals(LayoutBand.RAIL, LayoutBand.forWidth(androidx.compose.ui.unit.Dp(900f)))
        assertEquals(LayoutBand.PHONE, LayoutBand.forWidth(androidx.compose.ui.unit.Dp(559f)))
        assertTrue(LayoutBand.RAIL.twoColumnFields && LayoutBand.RAIL.tabsFit && LayoutBand.RAIL.rail)
        assertTrue(!LayoutBand.PHONE.twoColumnFields)
    }
}
