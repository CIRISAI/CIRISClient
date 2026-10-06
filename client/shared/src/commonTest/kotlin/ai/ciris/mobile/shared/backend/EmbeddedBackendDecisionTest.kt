package ai.ciris.mobile.shared.backend

import ai.ciris.mobile.shared.platform.AGENT_ENDPOINT
import ai.ciris.mobile.shared.platform.NODE_ONLY_ENDPOINT
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CIRISClient#138 — "Resuming… Please wait…" over a run-without-AI install.
 *
 * The matrix is the rule: (which backend this install runs) × (supervisor
 * state) → (overlay, may revive). Every cell is written out, because the bug
 * lived in a cell nobody had looked at: NODE_ONLY × Thawing/Reviving.
 */
class EmbeddedBackendDecisionTest {

    private val everyState: List<BackendState> = listOf(
        BackendState.Live,
        BackendState.Thawing(0, 5_000),
        BackendState.Down(DeathEvidence.Refused),
        BackendState.Down(DeathEvidence.BudgetExpired(5_000)),
        BackendState.Reviving(1, 1_000),
        BackendState.GaveUp(5, "could not write .restart_signal"),
        BackendState.Unreachable("no network"),
        BackendState.NotOurs,
    )

    @Test
    fun run_without_ai_never_shows_an_overlay_whatever_the_supervisor_says() {
        for (state in everyState) {
            assertEquals(
                HostOverlay.NONE,
                hostOverlayFor(NODE_ONLY_ENDPOINT, state),
                "run without AI: :8080 down is the intended state, not a resume ($state)",
            )
        }
    }

    @Test
    fun run_without_ai_never_attempts_recovery() {
        assertFalse(embeddedAgentExpected(NODE_ONLY_ENDPOINT))
        assertFalse(mayReviveEmbeddedBackend(NODE_ONLY_ENDPOINT))
    }

    @Test
    fun with_ai_keeps_the_resuming_overlay_and_recovery() {
        assertTrue(embeddedAgentExpected(AGENT_ENDPOINT))
        assertTrue(mayReviveEmbeddedBackend(AGENT_ENDPOINT))
        assertEquals(HostOverlay.RESUMING, hostOverlayFor(AGENT_ENDPOINT, BackendState.Thawing(0, 5_000)))
        assertEquals(HostOverlay.RESUMING, hostOverlayFor(AGENT_ENDPOINT, BackendState.Reviving(2, 2_000)))
        assertEquals(
            HostOverlay.RESTART_REQUIRED,
            hostOverlayFor(AGENT_ENDPOINT, BackendState.GaveUp(5, "x")),
        )
    }

    @Test
    fun with_ai_shows_nothing_over_a_working_or_unaskable_backend() {
        for (state in listOf(
            BackendState.Live,
            BackendState.Down(DeathEvidence.Refused),
            BackendState.Unreachable("no network"),
            BackendState.NotOurs,
        )) {
            assertEquals(HostOverlay.NONE, hostOverlayFor(AGENT_ENDPOINT, state), "$state")
        }
    }

    // ------------------------------------------------------------------
    // The supervisor honours it
    // ------------------------------------------------------------------

    private class Host(var host: HostLiveness) : LocalBackendController {
        var reviveCalls = 0
        override val canRevive: Boolean = true
        override suspend fun hostLiveness() = host
        override suspend fun revive(): Result<Unit> { reviveCalls++; return Result.success(Unit) }
    }

    /**
     * The field sequence from run 37427594052: the runtime thread keeps
     * executing (the node lives in it) while :8080 refuses. With the old wiring
     * the thaw budget ran out and the supervisor wrote `.restart_signal`.
     */
    @Test
    fun supervisor_does_not_revive_after_run_without_ai() = runTest {
        var t = 0L
        val host = Host(HostLiveness.ALIVE)
        var endpoint = AGENT_ENDPOINT
        val sup = BackendSupervisor(
            probe = { ProbeOutcome.REFUSED },
            controller = host,
            ownership = { Ownership.OURS },
            now = { t },
            mayRevive = { mayReviveEmbeddedBackend(endpoint) },
        )
        sup.onResumed()

        // The person chooses Run without AI; :8080 stops on purpose.
        endpoint = NODE_ONLY_ENDPOINT
        val seen = mutableListOf<BackendState>()
        repeat(200) {
            t += 1_000
            sup.tick()
            seen += sup.state.value
            assertEquals(HostOverlay.NONE, hostOverlayFor(endpoint, sup.state.value), "tick $it: ${sup.state.value}")
        }

        assertEquals(0, host.reviveCalls, "nothing to revive on a run-without-AI install")
        assertTrue(seen.none { it is BackendState.Reviving || it is BackendState.GaveUp })
        assertTrue(seen.any { it is BackendState.Down }, "still REPORTED honestly, just not acted on")
    }

    /** The other half: a with-AI agent that genuinely died is still revived. */
    @Test
    fun supervisor_still_revives_a_with_ai_agent_that_died() = runTest {
        var t = 0L
        val host = Host(HostLiveness.DEAD)
        val sup = BackendSupervisor(
            probe = { ProbeOutcome.REFUSED },
            controller = host,
            ownership = { Ownership.OURS },
            now = { t },
            mayRevive = { mayReviveEmbeddedBackend(AGENT_ENDPOINT) },
        )
        sup.onResumed()
        t += 500
        sup.tick()

        assertEquals(1, host.reviveCalls)
        assertEquals(HostOverlay.RESUMING, hostOverlayFor(AGENT_ENDPOINT, sup.state.value))
    }
}
