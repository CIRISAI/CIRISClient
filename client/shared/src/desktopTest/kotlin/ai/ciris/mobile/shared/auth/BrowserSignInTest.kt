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
