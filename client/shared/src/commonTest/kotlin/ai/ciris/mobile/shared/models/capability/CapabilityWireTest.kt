package ai.ciris.mobile.shared.models.capability

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE CONTRACT, ENUMERATED.
 *
 * These cases are the specification other repos can read: every wire shape, the
 * state it produces, and the operator remedy that state implies. If CIRISServer
 * or CIRISAgent emit a shape not listed here, that is the gap to close — in
 * this file first, then in the reader.
 */
class CapabilityWireTest {

    private fun state(doc: String, field: String = CapabilityWire.FIELD_CONFERRED) =
        CapabilityWire.parse(doc, field).state(Capability.REGISTRY_LOOKUP)

    // ── the four wire shapes ────────────────────────────────────────────────

    @Test
    fun field_absent_is_an_older_peer() {
        // Remedy: upgrade the peer. Every node released before CIRISServer#499.
        assertEquals(
            CapabilityState.UNDECLARED,
            state("""{"build_profiles":["CCP","CCC","CCS"]}"""),
        )
    }

    @Test
    fun explicit_null_is_the_peer_saying_it_does_not_know() {
        // Remedy: retry. The peer answered; it could not read its own record.
        // NOT the same as an older peer, and rendering it as one tells an
        // operator to upgrade a node that is already current.
        assertEquals(CapabilityState.UNDETERMINED, state("""{"capabilities":null}"""))
    }

    @Test
    fun empty_list_is_a_declaration_of_nothing() {
        // Remedy: use a peer that carries it. The peer read its record and holds
        // no capabilities — a real answer, not a silence.
        assertEquals(CapabilityState.ABSENT, state("""{"capabilities":[]}"""))
    }

    @Test
    fun a_list_is_membership() {
        assertEquals(
            CapabilityState.PRESENT,
            state("""{"capabilities":["infra:attest","infra:serve","registry:lookup"]}"""),
        )
        assertEquals(
            CapabilityState.ABSENT,
            state("""{"capabilities":["infra:serve"]}"""),
        )
    }

    // ── envelopes ───────────────────────────────────────────────────────────

    @Test
    fun both_envelopes_are_read() {
        // Bare, and the agent's SuccessResponse `{"data": ...}` wrapper.
        val ids = """["registry:lookup"]"""
        assertEquals(CapabilityState.PRESENT, state("""{"capabilities":$ids}"""))
        assertEquals(CapabilityState.PRESENT, state("""{"data":{"capabilities":$ids}}"""))
    }

    // ── provenance is not merged ────────────────────────────────────────────

    @Test
    fun the_agent_field_is_read_separately_from_the_conferred_one() {
        // A conferred scope is signed by the trust root; an agent feature is a
        // property of the running brain. Different authorities, different
        // remedies, so they are never unioned — CIRISServer refuses the same
        // laundering at its own tier.
        val doc = """{"capabilities":["infra:serve"],"agent_capabilities":["registry:lookup"]}"""
        assertEquals(CapabilityState.ABSENT, state(doc, CapabilityWire.FIELD_CONFERRED))
        assertEquals(CapabilityState.PRESENT, state(doc, CapabilityWire.FIELD_AGENT))
    }

    @Test
    fun each_field_carries_its_own_four_states() {
        // The agent tier gets the same discipline, or the collapse reappears one
        // layer up.
        assertEquals(CapabilityState.UNDECLARED, state("""{"capabilities":[]}""", CapabilityWire.FIELD_AGENT))
        assertEquals(CapabilityState.UNDETERMINED, state("""{"agent_capabilities":null}""", CapabilityWire.FIELD_AGENT))
    }

    // ── we could not ask ────────────────────────────────────────────────────

    @Test
    fun a_document_we_cannot_read_says_nothing_about_the_peer() {
        for (doc in listOf("", "   ", "not json", "{unclosed", "[]")) {
            assertEquals(CapabilityState.UNREACHABLE, state(doc), "doc=$doc")
        }
    }

    @Test
    fun a_field_that_is_neither_null_nor_a_list_is_unreadable_not_guessed() {
        for (doc in listOf(
            """{"capabilities":"registry:lookup"}""",
            """{"capabilities":42}""",
            """{"capabilities":{"registry":true}}""",
        )) {
            assertEquals(CapabilityState.UNREACHABLE, state(doc), "doc=$doc")
        }
    }

    @Test
    fun a_list_carrying_non_strings_is_not_silently_narrowed() {
        // Dropping the unreadable entries would report a SMALLER declaration
        // than the peer made, which is a confident wrong answer about authority.
        assertEquals(
            CapabilityState.UNREACHABLE,
            state("""{"capabilities":["registry:lookup",42]}"""),
        )
    }

    // ── the whole point ─────────────────────────────────────────────────────

    @Test
    fun the_five_not_usable_answers_stay_distinct() {
        val seen = listOf(
            state("""{"other":1}"""),                 // UNDECLARED
            state("""{"capabilities":null}"""),       // UNDETERMINED
            state("""{"capabilities":[]}"""),         // ABSENT
            state("not json"),                        // UNREACHABLE
        )
        assertEquals(seen.size, seen.toSet().size, "four shapes must not collapse into fewer states")
        assertEquals(
            CapabilityState.PRESENT,
            state("""{"capabilities":["registry:lookup"]}"""),
            "and the fifth is the only one that renders the feature",
        )
    }
}
