package ai.ciris.mobile.shared.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TWO CREDENTIALS, TWO CONCLUSIONS (CIRISClient#171).
 *
 * The session is the CIRIS access token the node minted; the billing
 * credential is the provider's ID token the agent sends to the hosted proxy.
 * They expire on different clocks and are renewed by different doors, and
 * TokenManager used to fold both into one flag — so an hour after setup, when
 * only the billing token had expired, the person was told their session was
 * no longer valid while their own agent was answering them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenManagerCredentialSplitTest {

    @Test
    fun a_billing_rejection_with_no_silent_path_is_a_billing_state_not_a_session_one() = runTest {
        val tm = TokenManager(backgroundScope)

        tm.onBillingCredentialRejected()
        runCurrent()

        assertTrue(tm.billingCredentialStale.value, "the billing credential is stale")
        assertFalse(tm.needsInteractiveLogin.value, "the SESSION was never in question")
    }

    @Test
    fun a_session_401_with_no_silent_path_keeps_todays_behaviour() = runTest {
        val tm = TokenManager(backgroundScope)

        tm.on401Error()
        runCurrent()

        assertTrue(tm.needsInteractiveLogin.value, "a session 401 still asks for a sign-in")
        assertFalse(tm.billingCredentialStale.value, "and says nothing about credits")
    }

    @Test
    fun a_fresh_token_retires_the_billing_state() = runTest {
        val tm = TokenManager(backgroundScope)
        tm.onBillingCredentialRejected()
        runCurrent()
        assertTrue(tm.billingCredentialStale.value)

        tm.handleNewToken(jwtExpiringIn(3600), "google")

        assertFalse(tm.billingCredentialStale.value, "a new credential ends the stale state")
        assertEquals(tm.currentToken.value, jwtExpiringIn(3600))
    }

    @Test
    fun a_silent_refresh_that_succeeds_never_raises_either_flag() = runTest {
        val tm = TokenManager(backgroundScope)
        tm.setSilentSignInCallback { SilentSignInResult.Success(jwtExpiringIn(3600), provider = "google") }

        tm.onBillingCredentialRejected()
        runCurrent()

        assertFalse(tm.billingCredentialStale.value)
        assertFalse(tm.needsInteractiveLogin.value)
        assertEquals(TokenState.VALID, tm.tokenState.value)
    }

    @Test
    fun a_silent_refresh_that_needs_a_person_lands_on_the_billing_flag_for_billing() = runTest {
        val tm = TokenManager(backgroundScope)
        tm.setSilentSignInCallback { SilentSignInResult.NeedsInteractiveLogin(4) }

        tm.onBillingCredentialRejected()
        runCurrent()

        assertTrue(tm.billingCredentialStale.value)
        assertFalse(tm.needsInteractiveLogin.value)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun jwtExpiringIn(seconds: Long): String {
        val exp = kotlinx.datetime.Clock.System.now().epochSeconds + seconds
        // Deterministic within a test: the same `seconds` yields the same token
        // for the equality assertion above as long as the second does not roll.
        val header = Base64.UrlSafe.encode("""{"alg":"none"}""".encodeToByteArray()).trimEnd('=')
        val payload = Base64.UrlSafe.encode("""{"exp":$exp}""".encodeToByteArray()).trimEnd('=')
        return "$header.$payload.sig"
    }
}
