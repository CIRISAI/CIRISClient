package ai.ciris.mobile.shared.viewmodels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trust page's level 3 on both shapes of `verify-status`. Verify 21+ dropped
 * DNS as a source and selects a trust root from one verified host; an older
 * agent still reports 2-of-3 flags.
 */
class VerifySourcesTest {
    private fun host(name: String, reachable: Boolean = true, verified: Boolean = false, fp: String? = null) =
        VerifySource(endpoint = "https://$name/v1", reachable = reachable, verified = verified, bundleFingerprint = fp)

    /** The case the old rule failed: a healthy node on verify 21+, one host verified, DNS flags false. */
    @Test fun oneVerifiedHostAgreesTheRoot() {
        val status = VerifyStatusResponse(loaded = true,
            sourcesAgreeing = 1,
            sources = listOf(host("a", verified = true, fp = "f1"), host("b", reachable = false), host("c")),
        )
        val s = VerifySources.of(status)
        assertTrue(s.rootAgreed)
        assertEquals(3, s.checked)
        assertEquals(1, s.verified)
        assertEquals(1, s.unreachable)
        assertFalse(s.disagree)
    }

    /** Equivocation: two verified bundles that differ. Never a pass, always serious. */
    @Test fun twoVerifiedHostsThatDisagreeAgreeNothing() {
        val s = VerifySources.of(
            VerifyStatusResponse(loaded = true, sources = listOf(host("a", verified = true, fp = "f1"), host("b", verified = true, fp = "f2"))),
        )
        assertTrue(s.disagree)
        assertFalse(s.rootAgreed)
    }

    @Test fun noVerifiedHostIsNotAgreed() {
        val s = VerifySources.of(VerifyStatusResponse(loaded = true, sources = listOf(host("a"), host("b", reachable = false))))
        assertFalse(s.rootAgreed)
        assertEquals(0, s.verified)
    }

    /** An older agent sends no list: the 2-of-3 rule still applies, unchanged. */
    @Test fun anOlderAgentKeepsTheTwoOfThreeRule() {
        val two = VerifySources.of(VerifyStatusResponse(loaded = true, sourcesAgreeing = 2, dnsUsOk = true))
        assertNull(two.checked)
        assertTrue(two.rootAgreed)
        assertTrue(two.anyReachable)
        assertFalse(VerifySources.of(VerifyStatusResponse(loaded = true, sourcesAgreeing = 1)).rootAgreed)
    }
}
