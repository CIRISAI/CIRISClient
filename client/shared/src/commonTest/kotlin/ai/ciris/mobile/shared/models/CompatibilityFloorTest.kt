package ai.ciris.mobile.shared.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The floor, and the ordering trap underneath it.
 *
 * The equality check this replaces never ordered anything, so it could not be
 * wrong about order. A floor is nothing BUT order.
 */
class CompatibilityFloorTest {

    // ---- the trap ----------------------------------------------------

    @Test
    fun numeric_not_lexical_at_the_versions_we_actually_ship() {
        // Lexically "0.5.9" > "0.5.190" because '9' > '1'. Every one of these
        // is a pair this repo could really see: three-digit patch numbers make
        // it the common case, not an edge.
        assertTrue(compareVersions("0.5.9", "0.5.190") < 0, "0.5.9 is BELOW 0.5.190")
        assertTrue(compareVersions("0.5.191", "0.5.9") > 0, "0.5.191 is ABOVE 0.5.9")
        assertTrue(compareVersions("0.5.88", "0.5.190") < 0, "0.5.88 is BELOW 0.5.190")
        assertEquals(0, compareVersions("0.5.191", "v0.5.191"))
        assertEquals(0, compareVersions("0.5", "0.5.0"))
    }

    @Test
    fun a_malformed_version_cannot_crash_the_banner() {
        compareVersions("", "0.5.190")
        compareVersions("not-a-version", "0.5.190")
        compareVersions("0.5.x", "0.5.190")
    }

    // ---- the point of decoupling -------------------------------------

    @Test
    fun a_mixed_but_compatible_pair_says_nothing() {
        // THE case the range exists for: client ahead of the node, both above
        // the floor. Equality nagged here, permanently, on every such pair.
        assertFalse(isVersionMismatch("0.5.192", clientVersion = "0.5.193"))
        assertFalse(isVersionMismatch("0.5.190", clientVersion = "0.5.199"))
        assertFalse(isVersionMismatch("0.5.191", clientVersion = "0.5.191"))
    }

    @Test
    fun a_node_below_the_floor_still_says_so() {
        // The signal the nag exists for. "Never complain" would have deleted it.
        assertTrue(isVersionMismatch("0.5.188", clientVersion = "0.5.191"))
        assertTrue(isVersionMismatch("0.5.186", clientVersion = "0.5.191"))
        // And the lexical trap must not rescue a too-old node: "0.5.9" reads as
        // greater than "0.5.190" to a string compare.
        assertTrue(isVersionMismatch("0.5.9", clientVersion = "0.5.191"))
    }

    // ---- the direction only the node can answer ----------------------

    @Test
    fun a_node_may_declare_a_client_floor_of_its_own() {
        // A newer node needing a newer client: no client-held constant can ever
        // know this, which is why the node has to be able to say it.
        assertTrue(isVersionMismatch("0.6.0", clientVersion = "0.5.191", nodeMinClientVersion = "0.6.0"))
        assertFalse(isVersionMismatch("0.6.0", clientVersion = "0.6.1", nodeMinClientVersion = "0.6.0"))
    }

    @Test
    fun a_node_that_declares_nothing_is_not_taken_as_content() {
        // Absent means "did not say", not "is happy". Every node today is here,
        // so this is the live path, and the floor this side holds still applies.
        assertFalse(isVersionMismatch("0.5.191", clientVersion = "0.5.191", nodeMinClientVersion = null))
        assertTrue(isVersionMismatch("0.5.186", clientVersion = "0.5.191", nodeMinClientVersion = null))
    }

    // ---- unchanged behaviour -----------------------------------------

    @Test
    fun an_unknown_node_version_is_not_a_verdict() {
        assertFalse(isVersionMismatch(null))
        assertFalse(isVersionMismatch(""))
        assertFalse(isVersionMismatch("   "))
    }
}
