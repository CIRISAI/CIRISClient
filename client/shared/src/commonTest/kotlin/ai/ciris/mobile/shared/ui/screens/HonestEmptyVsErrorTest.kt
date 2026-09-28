package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.RouteNotOnThisHost
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.screens.graph.CellVizState
import ai.ciris.mobile.shared.ui.screens.graph.GraphBody
import ai.ciris.mobile.shared.ui.screens.graph.GraphDisplayState
import ai.ciris.mobile.shared.ui.screens.graph.graphBody
import ai.ciris.mobile.shared.viewmodels.TransportScreenState
import ai.ciris.mobile.shared.viewmodels.withLoadFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure halves of CSD/3 §2.2 on the cards whose rendering decision lives in
 * the screen: error and empty never drawn together, a factor nobody read never
 * drawn as 1.00, and "this node doesn't have X" never drawn as "you have nothing".
 */
class HonestEmptyVsErrorTest {

    // ── ReadFailure classification ────────────────────────────────────────────

    @Test
    fun an_absent_route_is_about_the_node_and_a_failure_is_a_failure() {
        assertIs<ReadFailure.NotOnThisNode>(ReadFailure.of(RouteNotOnThisHost("/v1/audit/entries")))
        assertIs<ReadFailure.NotOnThisNode>(ReadFailure.of(RuntimeException("API error: HTTP 404")))
        assertIs<ReadFailure.NotOnThisNode>(ReadFailure.of(RuntimeException("404 Not Found")))
        assertIs<ReadFailure.Failed>(ReadFailure.of(RuntimeException("API error: HTTP 500")))
        // A number that merely contains 404 is not a 404.
        assertIs<ReadFailure.Failed>(ReadFailure.of(RuntimeException("timeout after 14045 ms")))
    }

    @Test
    fun not_on_this_node_and_failed_never_share_a_look() {
        val absent = ReadFailure.NotOnThisNode().listState("no route here", "failed", null)
        val failed = ReadFailure.Failed("boom").listState("no route here", "failed", null)
        assertIs<ListState.Empty>(absent)
        assertIs<ListState.Error>(failed)
        assertEquals("no route here", absent.message, "the absent-route copy is the node's, not 'you have nothing'")
    }

    // ── GraphMemory (CSD-028) ────────────────────────────────────────────────

    @Test
    fun graph_memory_a_failed_read_is_not_also_empty() {
        assertEquals(GraphBody.FAILED, graphBody(GraphDisplayState(error = "Failed to load graph: 500")))
        assertEquals(GraphBody.EMPTY, graphBody(GraphDisplayState()))
        assertEquals(GraphBody.LOADING, graphBody(GraphDisplayState(isLoading = true)))
    }

    // ── Memory (CSD-027) and Logs (CSD-029) ──────────────────────────────────

    @Test
    fun memory_a_refused_query_is_not_no_memories() {
        assertFalse(memoryShowsEmpty(MemoryScreenState(error = "Failed to load memory data: 403")))
        assertTrue(memoryShowsEmpty(MemoryScreenState()))
    }

    @Test
    fun logs_a_failed_read_is_not_no_matching_logs() {
        assertFalse(logsShowsEmpty(LogsScreenState(error = "Failed to load logs: 500")))
        assertTrue(logsShowsEmpty(LogsScreenState()))
    }

    // ── Transport (CSD-031) ──────────────────────────────────────────────────

    @Test
    fun transport_a_failed_read_is_its_own_state_not_the_forms_error() {
        val state = TransportScreenState(isLoading = true).withLoadFailure(RuntimeException("API error: HTTP 404"))
        assertIs<ReadFailure.NotOnThisNode>(state.loadFailure)
        assertNull(state.error, "the save-error slot is the form's, not the read's")
        assertFalse(state.isLoading)
    }

    // ── Health & Reputation (CSD-044) ────────────────────────────────────────

    @Test
    fun health_factors_nobody_read_are_not_one_point_zero_zero() {
        // Pre-fetch: CellVizState.DEFAULT holds 1.0 in every factor.
        assertNull(factorReading(CellVizState(), 1f), "the hero says — pre-fetch; the rows said 1.00")
        // Fetched, but local-only: the factors are still the defaults.
        val localOnly = CellVizState(isPreFetch = false, federationDataPresent = false)
        assertNull(factorReading(localOnly, localOnly.c))
        // Read from the federation: drawn.
        val read = CellVizState(isPreFetch = false, federationDataPresent = true, c = 0.5f)
        assertEquals("0.50", factorReading(read, read.c))
        assertNotEquals(null, factorReading(read, read.s))
    }
}
