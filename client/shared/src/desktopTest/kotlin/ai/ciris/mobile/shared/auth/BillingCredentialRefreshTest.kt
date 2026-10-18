package ai.ciris.mobile.shared.auth

import ai.ciris.mobile.shared.models.OAuthHandoff
import ai.ciris.mobile.shared.platform.EnvFileUpdater
import ai.ciris.mobile.shared.platform.TokenHandshake
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * THE WRITE-THROUGH (CIRISClient#172). A fresh Google ID token from the
 * browser hand-off must land in `<CIRIS_HOME>/.env` under the name the agent
 * reads, replace the old one in place, disturb nothing else in the file, and
 * trip `.config_reload` so the agent re-reads it. Before this, a desktop
 * sign-in collected the token and dropped it: `.env` kept the one issued at
 * setup and every billing call after the hour was `401 token_expired`.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class BillingCredentialRefreshTest {

    private val oldToken = jwt(exp = 1_700_000_000)
    private val newToken = jwt(exp = 4_000_000_000)

    private val envBefore = """
        |# written by setup
        |OPENAI_API_KEY="$oldToken"
        |OPENAI_API_BASE=https://llm01.ciris-services-1.ai/v1
        |CIRIS_BILLING_GOOGLE_ID_TOKEN=$oldToken
        |CIRIS_BILLING_OAUTH_TOKEN=legacy-desktop-name
        |CIRIS_CONFIGURED="true"
        |
    """.trimMargin()

    private fun home(): File = Files.createTempDirectory("ciris-home-").toFile().also {
        File(it, ".env").writeText(envBefore)
    }

    @Test
    fun a_collected_hand_off_writes_the_new_token_through_and_clears_the_stale_state() = runTest {
        val home = home()
        val updater = EnvFileUpdater(home)
        val tm = TokenManager(backgroundScope)
        tm.onBillingCredentialRejected()
        runCurrent()
        assertTrue(tm.billingCredentialStale.value, "precondition: credits were stale")

        val outcome = refreshBillingCredentialFromBrowser(
            signIn = {
                BrowserSignInResult.Collected(
                    OAuthHandoff(accessToken = "a-new-session", provider = "google", idToken = newToken),
                )
            },
            envFileUpdater = updater,
            tokenManager = tm,
        )
        runCurrent()

        assertIs<BillingCredentialRefreshOutcome.Refreshed>(outcome)
        val lines = File(home, ".env").readText().lines()
        assertEquals("# written by setup", lines[0], "comments are untouched")
        assertEquals("OPENAI_API_KEY=\"$oldToken\"", lines[1], "the LLM key line is not this write's business")
        assertEquals("OPENAI_API_BASE=https://llm01.ciris-services-1.ai/v1", lines[2])
        assertEquals(
            "${TokenHandshake.GOOGLE_TOKEN_VAR}=$newToken",
            lines[3],
            "the billing token is replaced IN PLACE under the name the agent reads",
        )
        assertFalse(lines.any { it.startsWith("${TokenHandshake.LEGACY_DESKTOP_TOKEN_VAR}=") }, "the legacy name is dropped")
        assertEquals("CIRIS_CONFIGURED=\"true\"", lines[4])
        assertEquals(1, lines.count { it.startsWith("${TokenHandshake.GOOGLE_TOKEN_VAR}=") }, "one token line, not two")
        assertTrue(File(home, TokenHandshake.CONFIG_RELOAD_SIGNAL_FILE).exists(), "the agent is told to re-read .env")

        assertEquals(newToken, tm.currentToken.value, "TokenManager holds the fresh credential")
        assertFalse(tm.billingCredentialStale.value, "and the stale state is retired")
    }

    @Test
    fun a_node_that_hands_over_no_id_token_is_named_too_old_and_env_is_untouched() = runTest {
        val home = home()
        val tm = TokenManager(backgroundScope)

        val outcome = refreshBillingCredentialFromBrowser(
            signIn = { BrowserSignInResult.Collected(OAuthHandoff(accessToken = "sess", provider = "google", idToken = null)) },
            envFileUpdater = EnvFileUpdater(home),
            tokenManager = tm,
        )

        assertIs<BillingCredentialRefreshOutcome.NodeTooOld>(outcome)
        assertEquals(envBefore, File(home, ".env").readText(), ".env is byte-identical")
        assertFalse(File(home, TokenHandshake.CONFIG_RELOAD_SIGNAL_FILE).exists(), "nothing to reload")
    }

    @Test
    fun the_nodes_refusal_and_a_timeout_pass_through_and_write_nothing() = runTest {
        val home = home()
        val tm = TokenManager(backgroundScope)

        val refused = refreshBillingCredentialFromBrowser(
            signIn = { BrowserSignInResult.Refused("auth.oauth.flow_expired") },
            envFileUpdater = EnvFileUpdater(home),
            tokenManager = tm,
        )
        assertIs<BillingCredentialRefreshOutcome.Refused>(refused)
        assertEquals("auth.oauth.flow_expired", refused.reasonId)

        val timedOut = refreshBillingCredentialFromBrowser(
            signIn = { BrowserSignInResult.TimedOut(180) },
            envFileUpdater = EnvFileUpdater(home),
            tokenManager = tm,
        )
        assertIs<BillingCredentialRefreshOutcome.TimedOut>(timedOut)
        assertEquals(180, timedOut.waitedSeconds)

        assertEquals(envBefore, File(home, ".env").readText(), ".env is byte-identical")
    }

    @Test
    fun a_desktop_sign_in_adopts_its_id_token_for_billing() = runTest {
        // The existing-user Login path collected `id_token` and dropped it, so
        // "sign in again" left .env unchanged (CIRISClient#172, 11:47:04).
        val home = home()
        val tm = TokenManager(backgroundScope)

        val adopted = adoptBillingIdToken(newToken, "google", EnvFileUpdater(home), tm)

        assertTrue(adopted.isSuccess)
        assertTrue(File(home, ".env").readText().contains("${TokenHandshake.GOOGLE_TOKEN_VAR}=$newToken"))
        assertEquals(newToken, tm.currentToken.value)
    }

    private fun jwt(exp: Long): String {
        val header = Base64.UrlSafe.encode("""{"alg":"none"}""".encodeToByteArray()).trimEnd('=')
        val payload = Base64.UrlSafe.encode("""{"exp":$exp}""".encodeToByteArray()).trimEnd('=')
        return "$header.$payload.sig"
    }
}
