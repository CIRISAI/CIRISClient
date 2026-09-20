package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.theme.InstrumentTokens
import ai.ciris.mobile.shared.ui.theme.PaperTokens
import ai.ciris.mobile.shared.ui.theme.Tone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The rules the primitives carry so no card can omit them. */
class PrimitiveRulesTest {

    @Test
    fun errorNeverLooksLikeEmpty() {
        val error = ListState.Error("x").style()
        val empty = ListState.Empty("x").style()
        assertNotEquals(error.tone, empty.tone)
        assertNotEquals(error.glyph, empty.glyph)
        assertTrue(error.bordered && !empty.bordered)
        assertTrue(error.labelled && !empty.labelled)
    }

    @Test
    fun unreviewedIsDistinctAndNeverGreen() {
        val u = ListState.Unreviewed("x").style()
        assertNotEquals(Tone.OK, u.tone)
        assertNotEquals(ListState.Error("x").style().tone, u.tone)
        assertNotEquals(ListState.Empty("x").style().tone, u.tone)
        assertTrue(u.labelled, "the label is what says UNREVIEWED")
        for (t in listOf(PaperTokens, InstrumentTokens)) {
            assertNotEquals(t.ok, ListState.Unreviewed("x").tint(t))
            assertNotEquals(t.ok, ListState.Error("x").tint(t))
        }
    }

    @Test
    fun goneAndHiddenUseTheDashedGlyphs() {
        assertEquals(GlyphName.FILE_GONE, ListState.FileGone("x").style().glyph)
        assertEquals(GlyphName.BLURRED, ListState.HiddenByRules("x").style().glyph)
    }

    @Test
    fun aConfirmNamesExactlyThreeFacts() {
        val f = ConfirmFact("a", "1")
        assertFailsWith<IllegalArgumentException> { confirmFacts(listOf(f, f)) }
        assertFailsWith<IllegalArgumentException> { confirmFacts(listOf(f, f, f, f)) }
        assertEquals(3, confirmFacts(listOf(f, f, f)).size)
    }

    @Test
    fun signerStatesReadDistinctly() {
        assertEquals(Tone.OK, SignerState.SIGNED.tone())
        assertNotEquals(Tone.OK, SignerState.PROPOSED.tone())
        assertNotEquals(Tone.OK, SignerState.NOT_YET.tone())
    }
}
