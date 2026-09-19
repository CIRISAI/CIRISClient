package ai.ciris.mobile.shared.ui.glyphs

import androidx.compose.ui.graphics.vector.PathParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 71 glyphs, every path parseable, the four dashed ones dashed. */
class CirisGlyphsTest {

    @Test
    fun seventyOneGlyphsEveryOneDrawable() {
        assertEquals(71, GlyphName.entries.size)
        assertEquals(71, GLYPH_SEGMENTS.size)
        for (name in GlyphName.entries) {
            val segs = GLYPH_SEGMENTS[name]
            assertTrue(!segs.isNullOrEmpty(), "$name has no segments")
            for (seg in segs) {
                assertTrue(seg.fill || seg.stroke, "$name has a segment that paints nothing")
                val nodes = PathParser().parsePathString(seg.d).toNodes()
                assertTrue(nodes.isNotEmpty(), "$name: path did not parse: ${seg.d}")
            }
        }
    }

    @Test
    fun exactlyTheFourDashedGlyphsCarryDashes() {
        val dashed = GlyphName.entries.filter { n -> GLYPH_SEGMENTS[n]!!.any { it.dash != null } }.toSet()
        assertEquals(setOf(GlyphName.FILE_GONE, GlyphName.SHARE_OUT, GlyphName.PULL_IN, GlyphName.BLURRED), dashed)
        for (n in dashed) for (seg in GLYPH_SEGMENTS[n]!!) {
            seg.dash?.let { d -> assertTrue(d.size % 2 == 0 && d.all { it > 0f }, "$n: dash intervals must be even and positive") }
        }
    }

    @Test
    fun theVectorBridgeBuilds() {
        val v = glyphVector(GlyphName.PERSON, androidx.compose.ui.graphics.Color.Unspecified)
        assertEquals("person", v.name)
    }
}
