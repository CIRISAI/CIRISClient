package ai.ciris.mobile.shared.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE SIGNAL HALF OF THE HANDSHAKE, on desktop (CIRISClient#172).
 *
 * The agent asks for a fresh token by writing `.token_refresh_needed` with a
 * timestamp (`ciris_engine/logic/utils/token_handshake.py`,
 * `request_token_refresh`). The client must see each ask once, consume it,
 * and not re-fire on a timestamp it already answered.
 */
class EnvFileUpdaterDesktopTest {

    private fun home(): File = Files.createTempDirectory("ciris-home-").toFile()

    @Test
    fun the_file_names_and_the_variable_are_the_agents() {
        // Mirrors token_handshake.py: TOKEN_REFRESH_REQUEST_FILE,
        // CONFIG_RELOAD_SIGNAL_FILE, ENV_FILE and PROXY_TOKEN_VARS[0].
        assertEquals(".token_refresh_needed", TokenHandshake.TOKEN_REFRESH_REQUEST_FILE)
        assertEquals(".config_reload", TokenHandshake.CONFIG_RELOAD_SIGNAL_FILE)
        assertEquals(".env", TokenHandshake.ENV_FILE)
        assertEquals("CIRIS_BILLING_GOOGLE_ID_TOKEN", TokenHandshake.GOOGLE_TOKEN_VAR)
    }

    @Test
    fun an_ask_is_seen_once_and_consumed() {
        val home = home()
        val updater = EnvFileUpdater(home)
        val ask = File(home, TokenHandshake.TOKEN_REFRESH_REQUEST_FILE)

        assertFalse(updater.checkTokenRefreshSignal(), "no file, no ask")

        ask.writeText("1760000000.123")
        assertTrue(updater.checkTokenRefreshSignal(), "the ask is seen")
        assertFalse(ask.exists(), "and consumed")
        assertFalse(updater.checkTokenRefreshSignal(), "and not seen again")
    }

    @Test
    fun a_replayed_timestamp_does_not_refire_but_a_newer_one_does() {
        val home = home()
        val updater = EnvFileUpdater(home)
        val ask = File(home, TokenHandshake.TOKEN_REFRESH_REQUEST_FILE)

        ask.writeText("1760000000.0")
        assertTrue(updater.checkTokenRefreshSignal())

        ask.writeText("1760000000.0")
        assertFalse(updater.checkTokenRefreshSignal(), "the same ask, written again, was already answered")

        ask.writeText("1760000030.0")
        assertTrue(updater.checkTokenRefreshSignal(), "a later ask is a new ask")
    }

    @Test
    fun the_token_write_adds_the_line_when_env_has_none_and_trips_reload() {
        val home = home()
        File(home, ".env").writeText("OPENAI_API_KEY=\"byok\"\n")
        val updater = EnvFileUpdater(home)

        val written = kotlinx.coroutines.runBlocking { updater.updateEnvWithToken("x.y.z") }

        assertTrue(written.getOrThrow())
        val text = File(home, ".env").readText()
        assertTrue(text.startsWith("OPENAI_API_KEY=\"byok\"\n"), "the existing line is untouched")
        assertTrue(text.contains("${TokenHandshake.GOOGLE_TOKEN_VAR}=x.y.z"))
        assertTrue(File(home, TokenHandshake.CONFIG_RELOAD_SIGNAL_FILE).exists())
    }
}
