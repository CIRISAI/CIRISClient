package ai.ciris.mobile.shared.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the client looks for its backend (CIRISAgent#1149).
 *
 * Every failure here is the same shape from the user's side: a spinner that
 * never resolves, because the client is polling a port nothing is listening on.
 * There is no error to show — the app cannot tell a slow boot from a wrong
 * address — which is why this is a pure function with tests rather than a
 * constant in two platform files.
 */
class BackendEndpointTest {

    @Test
    fun with_an_ai_assistant_the_agent_serves() {
        val e = backendEndpoint(runWithoutAi = false)
        assertEquals(8080, e.port)
        assertEquals("/v1/system/health", e.healthPath)
        // Android must say `localhost` (WebView Same-Origin Policy); iOS says
        // 127.0.0.1. The host belongs to the caller, so both are expressible.
        assertEquals("http://localhost:8080/v1/system/health", e.healthUrl("localhost"))
        assertEquals("http://127.0.0.1:8080/v1/system/health", e.healthUrl("127.0.0.1"))
    }

    @Test
    fun without_one_only_the_node_serves() {
        // From 2.11.0 the brain shuts down and :8080 goes away entirely.
        val e = backendEndpoint(runWithoutAi = true)
        assertEquals(4243, e.port)
        assertEquals("/health", e.healthPath)
        assertEquals("http://localhost:4243/health", e.healthUrl("localhost"))
    }

    @Test
    fun the_two_endpoints_are_actually_different() {
        // Guards the copy-paste that would make the whole switch a no-op.
        assertTrue(backendEndpoint(true) != backendEndpoint(false))
        assertTrue(AGENT_ENDPOINT.port != NODE_ONLY_ENDPOINT.port)
    }

    // ── reading the decision back out of .env ────────────────────────────

    @Test
    fun a_true_flag_is_read() {
        assertTrue(runWithoutAiFromEnv("CIRIS_RUN_WITHOUT_AI=true"))
    }

    @Test
    fun the_agents_own_truthy_spellings_are_accepted() {
        // Matched to the agent's reader, so the two cannot disagree about what
        // "true" looks like and end up on different ports.
        for (v in listOf("true", "TRUE", "True", "1", "yes", "YES")) {
            assertTrue(runWithoutAiFromEnv("CIRIS_RUN_WITHOUT_AI=$v"), v)
        }
    }

    @Test
    fun spellings_that_must_NOT_be_truthy_stay_false() {
        // THE DEFECT THIS PAIR OF TESTS EXISTS FOR. Reviewing our reader, the
        // agent found theirs also accepted `on` — so CIRIS_RUN_WITHOUT_AI=on
        // would have sent the agent to :4243 while we polled :8080. A dead app,
        // and nothing in either log to explain it.
        //
        // The two sets must stay identical, so a widening on EITHER side has to
        // fail in CI rather than on someone's device. These are the plausible
        // widenings.
        for (v in listOf("on", "y", "t", "enabled", "on ", "ON", "sure", "2")) {
            assertFalse(runWithoutAiFromEnv("CIRIS_RUN_WITHOUT_AI=$v"), v)
        }
    }

    @Test
    fun quoting_and_spacing_do_not_change_the_answer() {
        assertTrue(runWithoutAiFromEnv("""CIRIS_RUN_WITHOUT_AI="true""""))
        assertTrue(runWithoutAiFromEnv("CIRIS_RUN_WITHOUT_AI = 'true'"))
        assertTrue(runWithoutAiFromEnv("  CIRIS_RUN_WITHOUT_AI=true  "))
    }

    @Test
    fun an_absent_key_means_the_brain_is_expected() {
        // EVERY INSTALL PREDATING THE FLAG. Reading absence as "run without AI"
        // would send every existing user to a port nothing serves. Correct only
        // while the legacy default is brain-present — see backendEndpoint's doc.
        assertFalse(runWithoutAiFromEnv("CIRIS_CONFIGURED=true\nOPENAI_API_KEY=sk-x"))
        assertFalse(runWithoutAiFromEnv(""))
        assertFalse(runWithoutAiFromEnv(null))
        assertEquals(AGENT_ENDPOINT, backendEndpoint(runWithoutAiFromEnv(null)))
    }

    @Test
    fun an_explicit_false_brings_the_brain_back() {
        // The documented one-run override: CIRIS_RUN_WITHOUT_AI=false without
        // touching the wizard's choice.
        assertFalse(runWithoutAiFromEnv("CIRIS_RUN_WITHOUT_AI=false"))
        assertFalse(runWithoutAiFromEnv("CIRIS_RUN_WITHOUT_AI=0"))
    }

    @Test
    fun a_commented_out_flag_is_not_in_effect() {
        assertFalse(runWithoutAiFromEnv("# CIRIS_RUN_WITHOUT_AI=true"))
    }

    @Test
    fun a_key_that_merely_contains_the_name_is_not_it() {
        // `CIRIS_RUN_WITHOUT_AI_CONFIRMED` is a different key; a substring match
        // would read it as the flag.
        assertFalse(runWithoutAiFromEnv("CIRIS_RUN_WITHOUT_AI_CONFIRMED=true"))
        assertFalse(runWithoutAiFromEnv("OLD_CIRIS_RUN_WITHOUT_AI=true"))
    }

    @Test
    fun the_flag_is_found_among_other_lines() {
        val env = """
            # written by setup
            CIRIS_CONFIGURED="true"
            CIRIS_RUN_WITHOUT_AI=true
            CIRIS_NODE_KEY_ID=cirisuser-1234
        """.trimIndent()
        assertTrue(runWithoutAiFromEnv(env))
        assertEquals(NODE_ONLY_ENDPOINT, backendEndpoint(runWithoutAiFromEnv(env)))
    }

    // ── the resolved holder the platform runtimes read ───────────────────

    @Test
    fun the_active_backend_defaults_to_the_agent() {
        // LOAD-BEARING. A first run, a wiped home, or a platform that has not
        // wired resolveFrom yet must still reach :8080 — that is where every
        // install predating the flag serves.
        ActiveBackend.reset()
        assertEquals(AGENT_ENDPOINT, ActiveBackend.endpoint)
    }

    @Test
    fun resolving_from_env_switches_the_backend() {
        try {
            ActiveBackend.resolveFrom("CIRIS_RUN_WITHOUT_AI=true")
            assertEquals(NODE_ONLY_ENDPOINT, ActiveBackend.endpoint)
            ActiveBackend.resolveFrom("CIRIS_RUN_WITHOUT_AI=false")
            assertEquals(AGENT_ENDPOINT, ActiveBackend.endpoint)
        } finally {
            ActiveBackend.reset()
        }
    }

    @Test
    fun an_unreadable_env_leaves_the_agent_selected() {
        try {
            ActiveBackend.resolveFrom(null)
            assertEquals(AGENT_ENDPOINT, ActiveBackend.endpoint)
        } finally {
            ActiveBackend.reset()
        }
    }
}
