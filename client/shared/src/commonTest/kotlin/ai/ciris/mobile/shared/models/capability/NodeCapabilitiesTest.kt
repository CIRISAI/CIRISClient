package ai.ciris.mobile.shared.models.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The distinction the whole gate rests on: undeclared is not absent.
 */
class NodeCapabilitiesTest {

    @Test
    fun a_declared_capability_is_usable() {
        val caps = NodeCapabilities(setOf(Capability.REGISTRY_LOOKUP, Capability.INFRA_SERVE))
        assertEquals(CapabilityState.PRESENT, caps.state(Capability.REGISTRY_LOOKUP))
        assertTrue(caps.has(Capability.REGISTRY_LOOKUP))
    }

    @Test
    fun a_declaration_that_omits_it_is_absent() {
        // The node listed what it holds and this was not on the list. That is a
        // real answer, and the UI may say the node cannot do it.
        val caps = NodeCapabilities(setOf(Capability.INFRA_SERVE))
        assertEquals(CapabilityState.ABSENT, caps.state(Capability.REGISTRY_LOOKUP))
        assertFalse(caps.has(Capability.REGISTRY_LOOKUP))
    }

    @Test
    fun no_declaration_at_all_is_undeclared_not_absent() {
        // EVERY NODE RELEASED TODAY IS HERE. Collapsing this into ABSENT would
        // hide surfaces that a newer node will serve, and the operator would
        // have no way to tell a missing feature from an old node.
        val caps = NodeCapabilities.UNDECLARED
        assertEquals(CapabilityState.UNDECLARED, caps.state(Capability.REGISTRY_LOOKUP))
        assertFalse(caps.has(Capability.REGISTRY_LOOKUP), "undeclared must not be treated as usable")
    }

    @Test
    fun an_empty_declaration_is_a_declaration() {
        // A node that says "I hold nothing" has answered. Distinct from silence.
        val caps = NodeCapabilities(emptySet())
        assertEquals(CapabilityState.ABSENT, caps.state(Capability.REGISTRY_LOOKUP))
    }

    @Test
    fun an_unknown_capability_id_survives_the_round_trip() {
        // The node is the authority on what it confers. A client that could only
        // represent ids it knew about would have to drop the rest.
        val caps = NodeCapabilities(setOf("registry:something-we-have-not-shipped-yet"))
        assertEquals(CapabilityState.PRESENT, caps.state("registry:something-we-have-not-shipped-yet"))
    }

    @Test
    fun only_a_positive_declaration_earns_the_ui() {
        assertTrue(CapabilityState.PRESENT.isUsable)
        assertFalse(CapabilityState.ABSENT.isUsable)
        assertFalse(CapabilityState.UNDECLARED.isUsable)
    }
}
