package ai.ciris.mobile.shared.ceg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * THE GENERATED TABLE IS THE REGISTRY. The generator refuses most defects
 * before this runs; these pin what the UI leans on.
 */
class DimensionsTest {

    @Test
    fun oneRowPerFamilyAndNoDuplicates() {
        assertEquals(REGISTRY_FAMILY_COUNT, Dim.all.size)
        assertEquals(116, Dim.all.size, "rc5 @44ae7b2 has 116 families")
        assertEquals(Dim.all.size, Dim.all.map { it.prefix }.toSet().size)
        assertEquals(Dim.all.size, Dim.all.map { it.slug }.toSet().size)
        assertTrue(REGISTRY_SOURCE_SHA256.startsWith("95665a2c"), "pinned to the CC#104 ruling commit")
        assertEquals("1.0-rc5", REGISTRY_CC_VERSION)
    }

    @Test
    fun polarityIsAVisualRule() {
        for (d in Dim.all) {
            if (d.polarity.minusOnly) assertEquals(Renderer.VIOLATION_MARKER, d.renderer, d.prefix)
            if (d.renderer == Renderer.VIOLATION_MARKER) assertTrue(d.polarity.minusOnly, d.prefix)
            if (d.polarity == Polarity.POSITIVE_ONLY) assertTrue(d.renderer != Renderer.SIGNED_SCORE, d.prefix)
        }
        assertEquals(Renderer.VIOLATION_MARKER, Dim.prohibitedCategory.renderer)
        assertEquals(Renderer.POSITIVE_ONLY_ACCRUAL, Dim.creditsDomainLanguageSubject.renderer)
        assertEquals(Renderer.STATE_PILL, Dim.objectionState.renderer)
        assertEquals(Renderer.CONSENT_LEAF, Dim.consentKind.renderer)
        assertEquals(Renderer.CONTENT_REFERENCE, Dim.holdsBytesSha256Prefix.renderer)
    }

    @Test
    fun theFamiliesTheUiTouchesAreGlossed() {
        for (d in listOf(Dim.consentKind, Dim.trustJobVersion, Dim.holdsBytesSha256Prefix, Dim.objectionState,
                         Dim.contentClassClass, Dim.prohibitedCategory, Dim.deliveryReceiptStreamId)) {
            assertNotNull(d.labelKey, d.prefix); assertNotNull(d.glossKey, d.prefix)
            assertTrue(d.labelKey!!.startsWith("ceg.${d.slug}."), d.prefix)
        }
        assertEquals(5, Envelope.all.size)
        for (m in Envelope.all) assertTrue(m.labelKey.startsWith("ceg.envelope."), m.id)
        assertEquals("consent:scope", Envelope.consentScope.wire)
    }

    @Test
    fun aWireDimensionResolvesToItsFamily() {
        assertSame(Dim.consentKind, Dim.forWire("consent:replication:v1"))
        assertSame(Dim.holdsBytesSha256Prefix, Dim.forWire("holds_bytes:sha256:ab12"))
        assertSame(Dim.objectionState, Dim.forWire("objection:open"))
        assertNull(Dim.forWire("image:jpeg"), "not a registry family — cannot be rendered")
        assertSame(Dim.consentKind, Dim.byPrefix("consent:{kind}"))
    }

    @Test
    fun bindFillsPlaceholders() {
        assertEquals("consent:replication", Dim.consentKind.bind(mapOf("kind" to "replication")))
        assertEquals("holds_bytes:sha256:ab", Dim.holdsBytesSha256Prefix.bind(mapOf("prefix" to "ab")))
    }

    @Test
    fun formattersRefuseWhatPolarityForbids() {
        assertTrue(Renderer.VIOLATION_MARKER.format(0.5, null) is Formatted.Refused)
        assertTrue(Renderer.VIOLATION_MARKER.format(0.0, null) is Formatted.Refused)
        assertTrue(Renderer.VIOLATION_MARKER.format(-1.0, null) is Formatted.Text)
        assertTrue(Renderer.POSITIVE_ONLY_ACCRUAL.format(-3.0, null) is Formatted.Refused)
        assertEquals("42", (Renderer.POSITIVE_ONLY_ACCRUAL.format(42.0, null) as Formatted.Text).text)
        assertEquals("+0.8", (Renderer.SIGNED_SCORE.format(0.8, null) as Formatted.Text).text)
        assertTrue(Renderer.CONSENT_LEAF.format(null, null, null) is Formatted.Refused)
        assertEquals("abcd1234…wxyz", shortKey("abcd1234EFGHIJKLMNOPwxyz"))
        assertEquals("short", shortKey("short"))
    }

    @Test
    fun cohortScopeFoldsSevenToFive() {
        assertEquals(ai.ciris.mobile.shared.ui.nav.CohortScope.GLOBAL_COMMONS, cohortScopeOf("federation"))
        assertEquals(ai.ciris.mobile.shared.ui.nav.CohortScope.GLOBAL_COMMONS, cohortScopeOf("planet"))
        assertEquals(ai.ciris.mobile.shared.ui.nav.CohortScope.AGENT, cohortScopeOf("self"))
        assertNull(cohortScopeOf("everyone"), "not a wire value")
    }
}
