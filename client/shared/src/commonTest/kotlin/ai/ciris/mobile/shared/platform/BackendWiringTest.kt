package ai.ciris.mobile.shared.platform

import ai.ciris.mobile.shared.api.CIRISApiClient
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The run-without-AI switch is actually CONNECTED (CIRISClient#43).
 *
 * `BackendEndpointTest` proves `resolveFrom` maps `.env` to a port. It passed
 * on every release while **nothing in production ever called it** — the only
 * callers in the tree were that test and a doc comment. So `ActiveBackend`
 * stayed at its `AGENT_ENDPOINT` default forever, and an install that had
 * recorded run-without-AI polled `:8080` after the agent had already handed
 * off to the node on `:4243`.
 *
 * These exercise the seam that was missing rather than the function that
 * worked: given a home, does the client end up pointed at the right backend,
 * and does it refuse to point away from a node once one is recorded.
 */
class BackendWiringTest {

    /** Stands in for a home directory holding (or not holding) a `.env`. */
    private class FakeEnv(private val content: String?) : EnvReader {
        var reads = 0
        override suspend fun readRawEnv(): String? {
            reads++
            return content
        }
    }

    private lateinit var api: CIRISApiClient

    @BeforeTest fun setup() {
        api = CIRISApiClient("http://127.0.0.1:8080", null)
        ActiveBackend.reset()
        CIRISApiClient.setLocalNodeUrl(CIRISApiClient.DEFAULT_LOCAL_NODE_URL)
    }

    @AfterTest fun tearDown() {
        ActiveBackend.reset()
        CIRISApiClient.setLocalNodeUrl(CIRISApiClient.DEFAULT_LOCAL_NODE_URL)
    }

    @Test
    fun a_recorded_run_without_ai_home_points_the_client_at_the_node() = runTest {
        val env = FakeEnv("CIRIS_CONFIGURED=\"true\"\nCIRIS_RUN_WITHOUT_AI=true\n")
        val endpoint = syncBackendFrom(env, api)
        assertEquals(NODE_ONLY_ENDPOINT, endpoint)
        assertEquals(4243, endpoint.port)
        assertTrue(env.reads > 0, "the .env has to actually be READ; that is the whole defect")
        assertTrue(
            CIRISApiClient.LOCAL_NODE_URL.endsWith(":4243"),
            "after the hand-off the local node must be the node, not the agent: ${CIRISApiClient.LOCAL_NODE_URL}",
        )
        // THE GAP THE FIRST CUT LEFT. LOCAL_NODE_URL is read by federation call
        // sites; every ORDINARY call — login included — goes through the
        // client's own baseUrl, which CIRISApp builds once inside a remember.
        // Moving one without the other sends login to a port that is gone.
        assertEquals(
            "http://$LOOPBACK_HOST:4243",
            api.baseUrl,
            "the api client must move with the backend, not just the node URL",
        )
    }

    @Test
    fun an_agent_home_is_left_alone() = runTest {
        // The node URL must NOT be forced in this direction: an operator
        // attached to someone else's node over CIRIS_NODE_URL keeps it
        // (CIRISClient#26).
        CIRISApiClient.setLocalNodeUrl("http://127.0.0.1:4343")
        val endpoint = syncBackendFrom(FakeEnv("CIRIS_CONFIGURED=\"true\"\n"), api)
        assertEquals(AGENT_ENDPOINT, endpoint)
        assertEquals("http://127.0.0.1:8080", api.baseUrl, "a with-AI home must not move the api client either")
        assertEquals(
            "http://127.0.0.1:4343",
            CIRISApiClient.LOCAL_NODE_URL,
            "a with-AI home must not stamp on an explicitly attached node",
        )
    }

    @Test
    fun no_home_at_all_means_the_agent() = runTest {
        // A first run, or a platform with no home. Every install predating the
        // flag serves :8080, so this default is load-bearing.
        assertEquals(AGENT_ENDPOINT, syncBackendFrom(FakeEnv(null), api))
    }

    @Test
    fun resolving_twice_across_a_hand_off_moves_the_client() = runTest {
        // Startup happens BEFORE setup answers the question, which is exactly
        // why one resolve is not enough: the client is not restarted by the
        // hand-off.
        assertEquals(AGENT_ENDPOINT, syncBackendFrom(FakeEnv(null), api))
        assertEquals(AGENT_ENDPOINT, ActiveBackend.endpoint)
        assertEquals("http://127.0.0.1:8080", api.baseUrl, "startup, before the answer exists")
        val after = syncBackendFrom(FakeEnv("CIRIS_RUN_WITHOUT_AI=true"), api)
        assertEquals(NODE_ONLY_ENDPOINT, after)
        assertTrue(CIRISApiClient.LOCAL_NODE_URL.endsWith(":4243"))
        assertTrue(api.baseUrl.endsWith(":4243"), "the in-session hand-off must move the api client")
    }

    @Test
    fun an_unreadable_home_does_not_take_the_client_down() = runTest {
        val boom = object : EnvReader {
            override suspend fun readRawEnv(): String? = throw RuntimeException("permission denied")
        }
        // A backend selector that throws on a corrupt home would fail the app
        // at startup over a file it can live without.
        assertEquals(AGENT_ENDPOINT, syncBackendFrom(boom, api))
    }

    @Test
    fun the_loopback_host_is_the_one_this_platform_can_reach() {
        // Android must say `localhost` (WebView Same-Origin); everything else
        // 127.0.0.1. Both were hardcoded at their call sites until a second
        // reader needed one.
        assertTrue(
            LOOPBACK_HOST == "localhost" || LOOPBACK_HOST == "127.0.0.1",
            "unexpected loopback host: $LOOPBACK_HOST",
        )
        assertTrue(NODE_ONLY_ENDPOINT.baseUrl(LOOPBACK_HOST).endsWith(":4243"))
    }
}

/**
 * Absence is not an answer (CIRISClient#48, second cut).
 *
 * `resolveFrom` used to read a Boolean, which folds "the flag is absent" into
 * "the owner said no" — right for a first read, wrong for every one after it.
 * A re-resolve over an unreadable or not-yet-written `.env` moved the client
 * back to the agent port, undoing a hand-off that had already happened, and
 * nothing logged that it had.
 */
class BackendRecordedStateTest {

    @kotlin.test.AfterTest fun tearDown() = ActiveBackend.reset()

    @kotlin.test.Test
    fun the_three_states_are_distinguishable() {
        assertEquals(true, runWithoutAiRecorded("CIRIS_RUN_WITHOUT_AI=true"))
        assertEquals(false, runWithoutAiRecorded("CIRIS_RUN_WITHOUT_AI=false"))
        assertEquals(null, runWithoutAiRecorded("CIRIS_CONFIGURED=true"), "absent is not false")
        assertEquals(null, runWithoutAiRecorded(null), "unreadable is not false")
        assertEquals(null, runWithoutAiRecorded(""), "empty is not false")
    }

    @kotlin.test.Test
    fun an_absent_flag_cannot_undo_a_recorded_hand_off() {
        // THE DEFECT. The node was selected; a later resolve over a home the
        // client could not read put it back on the agent port, and the desktop
        // no-AI login legs died polling :8080.
        ActiveBackend.resolveFrom("CIRIS_RUN_WITHOUT_AI=true")
        assertEquals(NODE_ONLY_ENDPOINT, ActiveBackend.endpoint)
        ActiveBackend.resolveFrom(null)
        assertEquals(NODE_ONLY_ENDPOINT, ActiveBackend.endpoint, "an unreadable home must not move the client")
        ActiveBackend.resolveFrom("CIRIS_CONFIGURED=true")
        assertEquals(NODE_ONLY_ENDPOINT, ActiveBackend.endpoint, "a home without the flag must not move the client")
    }

    @kotlin.test.Test
    fun an_explicit_false_still_moves_back_to_the_agent() {
        // The documented one-run override is a STATEMENT, not a silence, so it
        // must still work — otherwise this guard becomes a one-way trapdoor.
        ActiveBackend.resolveFrom("CIRIS_RUN_WITHOUT_AI=true")
        ActiveBackend.resolveFrom("CIRIS_RUN_WITHOUT_AI=false")
        assertEquals(AGENT_ENDPOINT, ActiveBackend.endpoint)
    }

    @kotlin.test.Test
    fun a_first_read_of_an_empty_home_still_means_the_agent() {
        // Absence keeps the CURRENT value, and the current value starts as the
        // agent — where every install predating the flag serves.
        ActiveBackend.reset()
        ActiveBackend.resolveFrom(null)
        assertEquals(AGENT_ENDPOINT, ActiveBackend.endpoint)
    }
}
