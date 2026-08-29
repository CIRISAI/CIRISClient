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

    @Test
    fun could_not_ask_is_not_the_node_being_old() {
        // The probe first mapped transport failure onto UNDECLARED, so the UI
        // told an operator with a dropped connection that their CURRENT node
        // predates capability declarations and should be upgraded — a false
        // version diagnosis from a timeout (Codex, PR #20).
        val unreachable = NodeCapabilities.UNREACHABLE
        assertEquals(CapabilityState.UNREACHABLE, unreachable.state(Capability.REGISTRY_LOOKUP))
        assertFalse(unreachable.has(Capability.REGISTRY_LOOKUP))

        // and it is a DIFFERENT state from a document that was read and had no list
        assertEquals(CapabilityState.UNDECLARED, NodeCapabilities.UNDECLARED.state(Capability.REGISTRY_LOOKUP))
        assertTrue(
            unreachable.state(Capability.REGISTRY_LOOKUP) != NodeCapabilities.UNDECLARED.state(Capability.REGISTRY_LOOKUP),
            "unreachable and undeclared must not collapse",
        )
    }

    @Test
    fun unreachable_wins_over_a_stale_declaration() {
        // If we could not read the document, whatever we hold is not current.
        val stale = NodeCapabilities(setOf(Capability.REGISTRY_LOOKUP), unreachable = true)
        assertEquals(CapabilityState.UNREACHABLE, stale.state(Capability.REGISTRY_LOOKUP))
        assertFalse(stale.has(Capability.REGISTRY_LOOKUP), "a probe that failed cannot license the UI")
    }

    @Test
    fun no_state_except_present_is_usable() {
        for (s in CapabilityState.entries) {
            assertEquals(s == CapabilityState.PRESENT, s.isUsable, "$s")
        }
    }

    @Test
    fun the_node_saying_it_does_not_know_is_its_own_answer() {
        // CIRISServer#499 emits `capabilities: null` when the node cannot read
        // its own key record, and says explicitly that this must not collapse
        // with `[]`. Three different remedies, so three different states.
        val undetermined = NodeCapabilities.UNDETERMINED
        assertEquals(CapabilityState.UNDETERMINED, undetermined.state(Capability.REGISTRY_LOOKUP))
        assertFalse(undetermined.has(Capability.REGISTRY_LOOKUP))

        val all = listOf(
            NodeCapabilities.UNDETERMINED.state(Capability.REGISTRY_LOOKUP),
            NodeCapabilities.UNREACHABLE.state(Capability.REGISTRY_LOOKUP),
            NodeCapabilities.UNDECLARED.state(Capability.REGISTRY_LOOKUP),
            NodeCapabilities(emptySet()).state(Capability.REGISTRY_LOOKUP),
        )
        assertEquals(all.size, all.toSet().size, "all four not-usable answers must stay distinct")
    }
}