package ai.ciris.mobile.shared.models

import ai.ciris.mobile.shared.viewmodels.DutyConferralViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The ladder and the duty menu come from the node, not from a compiled
 * list** (CIRISClient#108, #109; CSD-065 §3, CSD-090 §3).
 *
 * The failure both issues name is silent: a compiled copy compiles and skews,
 * and nothing fails when the node's table stops agreeing with it. These pin
 * the join: a served column wins over the compiled one, a served member this
 * app has never seen is offered rather than dropped, and the fallback is
 * still a ladder.
 */
class NodeCatalogueTest {

    private fun row(
        op: String,
        route: String = "/v1/admin/$op",
        tier: Int? = 0,
        scope: String? = "review",
        quorum: Int = 1,
        reverses: String? = null,
        reachesSubstrate: Boolean = false,
    ) = OperationRow(op, route, tier, scope, quorum, reverses, reachesSubstrate)

    // ── #109: the served scope is the rung's scope ──

    @Test
    fun a_served_scope_replaces_the_compiled_one() {
        // Today annotate takes `review`; the node says `slash`. The confirm
        // sheet must name the served scope: the node re-derives authority from
        // the row it holds, and a card naming the old scope sends the operator
        // for the wrong delegation.
        val rungs = LadderRung.fromCatalogue(listOf(row("annotate", scope = "slash")))
        assertEquals(1, rungs.size)
        assertEquals("slash", rungs.single().scope)
        assertEquals(AdminLadderOp.ANNOTATE, rungs.single().local, "joined to its compiled labels by op token")
    }

    @Test
    fun a_served_op_this_app_has_never_seen_is_still_a_rung() {
        val rungs = LadderRung.fromCatalogue(
            listOf(row("annotate"), row("expunge", route = "/v1/admin/expunge", tier = 5, scope = "slash")),
        )
        val expunge = rungs.firstOrNull { it.op == "expunge" }
        assertNotNull(expunge, "a served op is rendered, never dropped")
        assertNull(expunge.local, "this app has no labels for it")
        assertEquals("/v1/admin/expunge", expunge.route, "the commit posts to the SERVED route")
        assertEquals("expunge", expunge.tagStem)
        assertTrue(expunge.irreversible, "no inverse on either side reads as irreversible: the heavier gate")
    }

    @Test
    fun the_preview_row_is_not_a_rung() {
        val rungs = LadderRung.fromCatalogue(
            listOf(row("preview", route = "/v1/admin/preview", tier = null, scope = null, quorum = 0), row("annotate")),
        )
        assertEquals(listOf("annotate"), rungs.map { it.op })
    }

    @Test
    fun quorum_reversal_and_substrate_reach_come_from_the_row() {
        val rungs = LadderRung.fromCatalogue(
            listOf(
                row("throttle", tier = 1, scope = "moderate", reverses = "throttle_release"),
                row("throttle_release", route = "/v1/admin/un-throttle", tier = 1, scope = "moderate"),
                row("descend", tier = 3, scope = "slash", quorum = 2, reachesSubstrate = true),
            ),
        )
        val throttle = rungs.first { it.op == "throttle" }
        val release = rungs.first { it.op == "throttle_release" }
        val descend = rungs.first { it.op == "descend" }
        assertEquals("throttle_release", throttle.reverses)
        assertEquals("throttle", release.reversedBy, "the inverse is derived from the row that names it")
        assertEquals(AdminLadderOp.UN_THROTTLE, release.local, "joined by token, not by name")
        assertTrue(descend.requiresQuorum)
        assertEquals(2, descend.quorum)
        assertEquals(true, descend.reachesSubstrate)
        assertEquals(false, throttle.reachesSubstrate)
    }

    @Test
    fun the_fallback_is_the_compiled_ladder_and_says_it_never_saw_the_node() {
        val rungs = LadderRung.fallback()
        assertEquals(AdminLadderOp.entries.size, rungs.size)
        assertTrue(rungs.all { it.reachesSubstrate == null }, "the compiled table never had that column")
        assertTrue(rungs.all { it.reverses == null && it.reversedBy == null })
        assertEquals(AdminLadderOp.DESCEND_QUORUM_MIN, rungs.first { it.local == AdminLadderOp.DESCEND }.quorum)
        assertEquals("quarantine", rungs.first { it.local == AdminLadderOp.QUARANTINE }.tagStem)
    }

    @Test
    fun every_compiled_entry_names_its_wire_token_and_no_two_share_one() {
        assertTrue(AdminLadderOp.entries.all { it.wireOp.isNotBlank() })
        assertEquals(AdminLadderOp.entries.size, AdminLadderOp.entries.map { it.wireOp }.distinct().size)
    }

    // ── #108: the duty menu ──

    @Test
    fun a_served_duty_this_app_has_never_seen_is_offered_after_the_known_ones() {
        // The acceptance in CIRISClient#108: feed a vocabulary with `grant`
        // and assert the picker offers it. Red on the compiled ALL_DUTIES.
        val served = listOf("consent_revocation", "moderate", "takedown", "review", "slash", "license", "grant")
        val menu = dutyMenu(served, DutyConferralViewModel.ALL_DUTIES)
        assertTrue("grant" in menu, "a served member is offered, never dropped")
        assertTrue("license" in menu)
        assertEquals(DutyConferralViewModel.ALL_DUTIES, menu.take(5), "the compiled list is display order only")
        assertEquals(listOf("license", "grant"), menu.drop(5), "unknown members follow, in the node's order")
    }

    @Test
    fun a_compiled_duty_the_node_no_longer_serves_is_not_offered() {
        val menu = dutyMenu(listOf("moderate", "review"), DutyConferralViewModel.ALL_DUTIES)
        assertEquals(listOf("moderate", "review"), menu)
        assertFalse("slash" in menu)
    }

    @Test
    fun no_read_means_the_compiled_list_and_nothing_else() {
        assertEquals(DutyConferralViewModel.ALL_DUTIES, dutyMenu(null, DutyConferralViewModel.ALL_DUTIES))
    }

    @Test
    fun what_a_duty_set_unlocks_is_read_off_the_served_scopes() {
        val rungs = LadderRung.fromCatalogue(
            listOf(
                row("annotate", scope = "review"),
                row("throttle", tier = 1, scope = "moderate"),
                row("quarantine", tier = 2, scope = "slash"),
                row("descend", tier = 3, scope = "slash", quorum = 2),
                row("de_admission", route = "/v1/admin/deadmit", tier = 4, scope = "slash"),
            ),
        )
        assertEquals(listOf(2, 3, 4), LadderRung.tiersUnlockedBy(rungs, setOf("slash")))
        assertEquals(listOf(0, 2, 3, 4), LadderRung.tiersUnlockedBy(rungs, setOf("slash", "review")))
        assertEquals(emptyList(), LadderRung.tiersUnlockedBy(rungs, setOf("takedown")), "takedown unlocks no rung")
    }
}
