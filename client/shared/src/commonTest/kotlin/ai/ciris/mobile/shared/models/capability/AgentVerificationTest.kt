package ai.ciris.mobile.shared.models.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentVerificationTest {

    @Test
    fun the_three_wire_statuses_decode() {
        assertEquals(AgentStatus.REGISTERED, AgentStatus.fromWire("AGENT_STATUS_REGISTERED"))
        assertEquals(AgentStatus.DEPRECATED, AgentStatus.fromWire("AGENT_STATUS_DEPRECATED"))
        assertEquals(AgentStatus.REVOKED, AgentStatus.fromWire("AGENT_STATUS_REVOKED"))
    }

    @Test
    fun an_unreadable_status_is_never_guessed_in_either_direction() {
        // Guess REGISTERED and a revoked build looks fine; guess REVOKED and a
        // good one is condemned. On a revocation check both are lies.
        for (raw in listOf("AGENT_STATUS_SOMETHING_NEW", "", null, "garbage")) {
            assertEquals(AgentStatus.UNKNOWN, AgentStatus.fromWire(raw), "raw=$raw")
        }
        assertFalse(AgentStatus.UNKNOWN.isDiscouraged, "unknown must not imply a warning")
    }

    @Test
    fun deprecated_and_revoked_both_warn() {
        assertTrue(AgentStatus.DEPRECATED.isDiscouraged)
        assertTrue(AgentStatus.REVOKED.isDiscouraged)
        assertFalse(AgentStatus.REGISTERED.isDiscouraged)
    }

    @Test
    fun no_record_and_no_answer_are_different_results() {
        // The distinction the sealed type exists for: telling an operator a
        // build is unregistered when the registry was merely unreachable is a
        // claim we did not earn.
        val absent: LookupResult = LookupResult.NotFound
        val unreachable: LookupResult = LookupResult.Unavailable("connection refused")
        assertTrue(absent is LookupResult.NotFound)
        assertTrue(unreachable is LookupResult.Unavailable)
        assertEquals("connection refused", (unreachable as LookupResult.Unavailable).reason)
    }

    @Test
    fun a_found_record_keeps_the_raw_status_for_display() {
        val r = AgentRecord(
            agentHash = "abc123",
            status = AgentStatus.fromWire("AGENT_STATUS_QUARANTINED"),
            rawStatus = "AGENT_STATUS_QUARANTINED",
        )
        assertEquals(AgentStatus.UNKNOWN, r.status)
        assertEquals("AGENT_STATUS_QUARANTINED", r.rawStatus, "the operator sees what the registry said")
    }
}
