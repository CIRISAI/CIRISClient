package ai.ciris.mobile.shared.auth

import ai.ciris.mobile.shared.models.OAuthHandoff
import ai.ciris.mobile.shared.models.OAuthHandoffPoll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The desktop browser hand-off, as one function both doors share: the Login
 * screen's Google button and the billing credential refresh (CIRISClient#172).
 * Before, the poll loop lived inline in the Login branch and a second copy for
 * billing would have been the second copy that drifts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSignInTest {

    private val handoff = OAuthHandoff(accessToken = "sess", provider = "google", idToken = "a.b.c")

    @Test
    fun pending_is_not_yet_and_ready_is_collected() = runTest {
        var polls = 0
        val result = collectBrowserSignIn(
            poll = { _, _ -> if (++polls < 3) OAuthHandoffPoll.Pending else OAuthHandoffPoll.Ready(handoff) },
            nonce = "n",
        )
        assertIs<BrowserSignInResult.Collected>(result)
        assertEquals("a.b.c", result.handoff.idToken)
        assertEquals(3, polls)
    }

    @Test
    fun a_stray_tab_is_accepted_only_after_our_own_had_its_chance() = runTest {
        val unbound = mutableListOf<Boolean>()
        var polls = 0
        collectBrowserSignIn(
            poll = { _, allowUnbound ->
                unbound += allowUnbound
                if (++polls < 12) OAuthHandoffPoll.Pending else OAuthHandoffPoll.Ready(handoff)
            },
            nonce = "n",
        )
        assertFalse(unbound.take(10).any { it }, "the first ten polls bind to this app's nonce")
        assertTrue(unbound.drop(10).all { it }, "after ~20s a sign-in that completed elsewhere is accepted")
    }

    @Test
    fun the_nodes_refusal_stops_the_loop_and_names_the_reason() = runTest {
        var polls = 0
        val result = collectBrowserSignIn(
            poll = { _, _ -> polls++; OAuthHandoffPoll.Failed("auth.oauth.no_local_identity", "failed") },
            nonce = "n",
        )
        assertIs<BrowserSignInResult.Refused>(result)
        assertEquals("auth.oauth.no_local_identity", result.reasonId)
        assertEquals(1, polls, "a terminal answer is not polled through")
    }

    @Test
    fun an_expired_answer_before_the_browser_arrives_is_not_yet() = runTest {
        var polls = 0
        val result = collectBrowserSignIn(
            poll = { _, _ ->
                polls++
                if (polls < 4) OAuthHandoffPoll.Failed("auth.oauth.flow_expired", "expired") else OAuthHandoffPoll.Ready(handoff)
            },
            nonce = "n",
        )
        assertIs<BrowserSignInResult.Collected>(result)
        assertEquals(4, polls)
    }

    /** CIRISClient#284: the browser never reached /login, so the node never learned the nonce. */
    @Test
    fun expired_throughout_the_grace_is_a_browser_that_never_arrived() = runTest {
        var polls = 0
        val result = collectBrowserSignIn(
            poll = { _, _ -> polls++; OAuthHandoffPoll.Failed("auth.oauth.flow_expired", "expired") },
            nonce = "n",
        )
        assertIs<BrowserSignInResult.BrowserNeverArrived>(result)
        assertEquals(30, polls, "about 60s of polling, then stop")
        assertEquals(60, result.waitedSeconds)
    }

    /** CIRISClient#284 acceptance: N expiries, then 204, then Ready. */
    @Test
    fun a_slow_browser_start_still_signs_in() = runTest {
        var polls = 0
        val result = collectBrowserSignIn(
            poll = { _, _ ->
                when (++polls) {
                    in 1..20 -> OAuthHandoffPoll.Failed("auth.oauth.flow_expired", "expired")
                    in 21..23 -> OAuthHandoffPoll.Pending
                    else -> OAuthHandoffPoll.Ready(handoff)
                }
            },
            nonce = "n",
        )
        assertIs<BrowserSignInResult.Collected>(result)
        assertEquals(24, polls)
    }

    /** Once the node has said "waiting", an expiry is real (CIRISClient#57 still holds). */
    @Test
    fun an_expired_answer_after_the_node_acknowledged_the_flow_stops_at_once() = runTest {
        var polls = 0
        val result = collectBrowserSignIn(
            poll = { _, _ ->
                if (++polls < 3) OAuthHandoffPoll.Pending else OAuthHandoffPoll.Failed("auth.oauth.flow_expired", "expired")
            },
            nonce = "n",
        )
        assertIs<BrowserSignInResult.Refused>(result)
        assertEquals("auth.oauth.flow_expired", result.reasonId)
        assertEquals(3, polls)
    }

    /** An unreadable answer keeps waiting but is not the node acknowledging the flow. */
    @Test
    fun an_unreadable_answer_does_not_count_as_the_node_knowing_the_flow() = runTest {
        var polls = 0
        val result = collectBrowserSignIn(
            poll = { _, _ ->
                when (++polls) {
                    1 -> OAuthHandoffPoll.Unclear
                    in 2..5 -> OAuthHandoffPoll.Failed("auth.oauth.flow_expired", "expired")
                    else -> OAuthHandoffPoll.Ready(handoff)
                }
            },
            nonce = "n",
        )
        assertIs<BrowserSignInResult.Collected>(result)
    }

    /** CIRISServer#868 (0.5.246): the node's own verdict after its 120 s grace is final, and is not an expiry. */
    @Test
    fun the_nodes_unknown_flow_is_a_browser_that_never_arrived() = runTest {
        var polls = 0
        val result = collectBrowserSignIn(
            poll = { _, _ -> polls++; OAuthHandoffPoll.Failed("auth.oauth.flow_unknown", "unknown") },
            nonce = "n",
        )
        assertIs<BrowserSignInResult.BrowserNeverArrived>(result)
        assertEquals(1, polls)
    }

    /** The wire shape, through classify: what a 0.5.244/0.5.245 node actually sends for an unseen nonce. */
    @Test
    fun a_real_410_expiry_before_the_browser_arrives_is_not_yet() = runTest {
        var polls = 0
        val result = collectBrowserSignIn(
            poll = { _, _ ->
                if (++polls < 6) OAuthHandoffPoll.classify(410, """{"status":"expired","reason_id":"auth.oauth.flow_expired"}""")
                else OAuthHandoffPoll.Ready(handoff)
            },
            nonce = "n",
        )
        assertIs<BrowserSignInResult.Collected>(result)
        assertEquals(6, polls)
    }

    @Test
    fun every_poll_is_logged() = runTest {
        val lines = mutableListOf<String>()
        var polls = 0
        collectBrowserSignIn(
            poll = { _, _ ->
                when (++polls) {
                    1 -> OAuthHandoffPoll.Failed("auth.oauth.flow_expired", "expired")
                    2 -> OAuthHandoffPoll.Pending
                    else -> OAuthHandoffPoll.Ready(handoff)
                }
            },
            nonce = "n",
            log = { lines += it },
        )
        assertEquals(
            listOf("poll n=1 status=expired reason=auth.oauth.flow_expired", "poll n=2 status=204 waiting", "poll n=3 status=ready"),
            lines.filter { it.startsWith("poll ") },
        )
    }

    @Test
    fun a_parked_failure_is_terminal_even_inside_the_grace() = runTest {
        var polls = 0
        val result = collectBrowserSignIn(
            poll = { _, _ -> polls++; OAuthHandoffPoll.Failed("auth.oauth.flow_expired", "failed") },
            nonce = "n",
        )
        assertIs<BrowserSignInResult.Refused>(result)
        assertEquals(1, polls)
    }

    @Test
    fun a_refusal_without_a_reason_is_the_expired_flow() = runTest {
        val result = collectBrowserSignIn(
            poll = { _, _ -> OAuthHandoffPoll.Failed(null, null) },
            nonce = "n",
        )
        assertIs<BrowserSignInResult.Refused>(result)
        assertEquals("auth.oauth.flow_expired", result.reasonId)
    }

    @Test
    fun silence_for_the_whole_budget_is_a_timeout_that_says_how_long() = runTest {
        val result = collectBrowserSignIn(
            poll = { _, _ -> OAuthHandoffPoll.Pending },
            nonce = "n",
            maxAttempts = 5,
        )
        assertIs<BrowserSignInResult.TimedOut>(result)
        assertEquals(10, result.waitedSeconds)
    }

    @Test
    fun the_nonce_is_what_the_browser_was_opened_with() = runTest {
        val seen = mutableListOf<String>()
        collectBrowserSignIn(
            poll = { nonce, _ -> seen += nonce; OAuthHandoffPoll.Ready(handoff) },
            nonce = "the-nonce",
        )
        assertEquals(listOf("the-nonce"), seen)
        assertEquals(32, newAppNonce().length)
    }
}
