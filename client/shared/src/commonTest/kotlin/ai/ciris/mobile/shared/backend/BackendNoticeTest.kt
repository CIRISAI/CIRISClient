package ai.ciris.mobile.shared.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Esu's situation, asserted out of existence.
 *
 * A user on 32-bit Android read "Cannot connect to server. Please check your
 * connection." from an app whose backend runs inside itself. They restarted the
 * phone, then concluded it was a login problem — because an unreachable backend
 * surfaces on the login screen. The app knew the real reason and had put it in
 * a notification.
 *
 * These tests pin the three properties that make that impossible:
 *
 *   1. no state is silent — every one says something
 *   2. no broken state is a dead end — every one offers an action that works
 *   3. "check your connection" appears ONLY when we genuinely could not ask
 *
 * Exhaustiveness itself is the compiler's job: `noticeFor` is a `when` over a
 * sealed interface with no `else`, so a new state cannot be added without
 * answering these questions. These tests cover what the compiler cannot — that
 * the answers are honest.
 */
class BackendNoticeTest {

    /** One of every state. If a state is added, add it here too. */
    private val everyState: List<BackendState> = listOf(
        BackendState.Live,
        BackendState.Thawing(1_200, 5_000),
        BackendState.Down(DeathEvidence.Observed("python: ModuleNotFoundError: pydantic_core")),
        BackendState.Down(DeathEvidence.Refused),
        BackendState.Down(DeathEvidence.BudgetExpired(5_000)),
        BackendState.Reviving(2, 4_000),
        BackendState.GaveUp(5, "python: ModuleNotFoundError: pydantic_core"),
        BackendState.Unreachable("no network"),
        BackendState.NotOurs,
    )

    @Test
    fun no_state_is_silent() {
        for (s in everyState) {
            val n = noticeFor(s)
            assertTrue(n.headlineKey.isNotBlank(), "$s produced no headline")
            assertTrue(n.headlineKey.startsWith("mobile."), "$s must use a localization key, not raw text")
        }
    }

    @Test
    fun no_broken_state_is_a_dead_end() {
        // The state Esu was actually in: something is wrong and there is
        // nothing you can do. That must not be reachable.
        for (s in everyState) {
            val n = noticeFor(s)
            if (n.isError) {
                assertTrue(
                    n.action != BackendAction.None,
                    "$s is an error with no action — this is the situation being prevented",
                )
            }
        }
    }

    @Test
    fun check_your_connection_appears_only_when_we_could_not_ask() {
        // The original defect in one assertion. A dead local backend told the
        // user to check a network that was fine.
        for (s in everyState) {
            val n = noticeFor(s)
            if (n.action == BackendAction.CheckNetwork) {
                assertTrue(
                    s is BackendState.Unreachable,
                    "$s offers CheckNetwork, but only Unreachable means we could not ASK",
                )
            }
        }
        assertEquals(BackendAction.CheckNetwork, noticeFor(BackendState.Unreachable("no network")).action)
    }

    @Test
    fun a_dead_local_backend_never_blames_the_network() {
        for (s in listOf(
            BackendState.Down(DeathEvidence.Refused),
            BackendState.Down(DeathEvidence.Observed("service not running")),
            BackendState.GaveUp(5, "boom"),
        )) {
            assertTrue(
                noticeFor(s).action != BackendAction.CheckNetwork,
                "$s is our own backend failing; the user's connection is not the problem",
            )
        }
    }

    @Test
    fun the_real_error_reaches_the_user() {
        // The app HAD this string. It went to a notification while the screen
        // said something false.
        val real = "python: ModuleNotFoundError: pydantic_core"
        assertEquals(real, noticeFor(BackendState.GaveUp(5, real)).detail)
        assertEquals(real, noticeFor(BackendState.Down(DeathEvidence.Observed(real))).detail)
    }

    @Test
    fun waking_is_not_an_error() {
        // The common path after a phone unlocks. Styling it as a failure is how
        // a normal resume becomes a support thread.
        val n = noticeFor(BackendState.Thawing(800, 5_000))
        assertFalse(n.isError, "thawing is the healthy path")
        assertEquals(BackendAction.Waiting, n.action)
    }

    @Test
    fun restarting_is_not_an_error_either_but_says_which_attempt() {
        val n = noticeFor(BackendState.Reviving(3, 8_000))
        assertFalse(n.isError)
        assertEquals(BackendAction.Waiting, n.action)
        assertNotNull(n.detail)
        assertTrue(n.detail!!.contains("3"), "a user watching a retry loop should see which attempt")
    }

    @Test
    fun a_silent_remote_node_is_not_offered_a_retry_we_cannot_honour() {
        // We do not restart someone else's node, so a Retry button there would
        // be a lie of a different kind. Offer the thing that works.
        assertEquals(BackendAction.ChooseNode, noticeFor(BackendState.NotOurs).action)
    }

    @Test
    fun budget_expiry_is_reported_as_weaker_than_observed_death() {
        // "we stopped waiting" and "we saw it die" are different claims.
        val expired = noticeFor(BackendState.Down(DeathEvidence.BudgetExpired(5_000))).detail
        assertNotNull(expired)
        assertTrue(expired!!.contains("5"), "say how long we waited: $expired")

        val observed = noticeFor(BackendState.Down(DeathEvidence.Observed("service not running"))).detail
        assertEquals("service not running", observed)
    }

    @Test
    fun live_is_quiet() {
        val n = noticeFor(BackendState.Live)
        assertFalse(n.isError)
        assertEquals(BackendAction.None, n.action)
        assertEquals(null, n.detail)
    }
}
