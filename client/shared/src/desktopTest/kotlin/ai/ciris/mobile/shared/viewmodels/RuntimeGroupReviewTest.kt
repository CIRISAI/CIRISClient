package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.parseConfigListBody
import ai.ciris.mobile.shared.localization.LocalizationHelper
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The runtime group review (CSD-002, 011, 012, 023, 024, 025, 029, 031).
 *
 * Driven against a REAL socket serving the shapes CIRISAgent main and
 * CIRISServer origin/main actually send, because every defect here lived in
 * the gap between the wire and the mapper: a step point read from a field the
 * step route never sends, a node's bare config map read as a missing envelope,
 * a WARN the agent spells WARNING, agent routes asked of a bare node.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeGroupReviewTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    data class Seen(val method: String, val path: String, val query: String?, val body: String)

    /** Serves "METHOD /path" (or "/path") → status to body; records every request. */
    private class Stub(routes: Map<String, Pair<Int, String>>, fallback: Pair<Int, String> = 404 to "{}") {
        val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                val path = ex.requestURI.path
                seen += Seen(ex.requestMethod, path, ex.requestURI.query, body)
                val (status, out) = routes["${ex.requestMethod} $path"] ?: routes[path] ?: fallback
                val bytes = out.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        fun client() = CIRISApiClient(url)
        fun paths() = synchronized(seen) { seen.map { it.path } }
    }

    private fun using(stub: Stub, block: suspend (CIRISApiClient) -> Unit) {
        try { runBlocking { block(stub.client()) } } finally { stub.server.stop(0) }
    }

    private suspend fun awaitThat(what: String, condition: () -> Boolean) {
        try { withTimeout(15_000) { while (!condition()) delay(20) } } catch (e: Exception) { fail("timed out waiting for: $what") }
    }

    private val health = """{"data":{"status":"healthy","cognitive_state":"work"}}"""

    // ── Runtime (CSD-024) ────────────────────────────────────────────────────

    @Test
    fun runtime_step_reads_the_step_route_shape() = using(Stub(mapOf(
        "POST /v1/system/runtime/step" to (200 to """{"data":{"success":true,"message":"Processed","processor_state":"paused",
            "cognitive_state":"WORK","queue_depth":2,"step_point":"perform_dmas","processing_time_ms":412.7,"tokens_used":null}}"""),
    ))) { client ->
        val vm = RuntimeViewModel(client)
        vm.singleStep()
        awaitThat("step recorded") { vm.runtimeData.value.lastStepResult != null || vm.error.value != null }
        assertNull(vm.error.value)
        assertEquals("PERFORM_DMAS", vm.runtimeData.value.currentStepPoint, "the step point the pipeline card marks")
        assertEquals(412L, vm.runtimeData.value.lastStepTimeMs)
    }

    @Test
    fun runtime_a_resume_the_agent_declined_is_not_reported_done() = using(Stub(mapOf(
        "POST /v1/system/runtime/resume" to (200 to """{"data":{"success":false,"message":"Not paused","processor_state":"unknown"}}"""),
    ))) { client ->
        val vm = RuntimeViewModel(client)
        vm.resumeRuntime()
        awaitThat("resume settled") { vm.error.value != null || vm.statusMessage.value != null }
        assertNotEquals("Runtime resumed", vm.statusMessage.value)
        assertNotNull(vm.error.value, "a declined resume is said")
    }

    @Test
    fun runtime_offers_no_control_before_the_agent_says_yes() {
        val vm = RuntimeViewModel(CIRISApiClient("http://127.0.0.1:9"))
        assertFalse(vm.isAdmin.value, "no control offered on a guess")
        assertFalse(vm.adminRefused.value)
    }

    @Test
    fun runtime_a_403_is_the_admin_card_not_a_broken_read() = using(Stub(mapOf(),
        fallback = 403 to """{"detail":"Insufficient permissions. Requires admin role or higher."}""")) { client ->
        val vm = RuntimeViewModel(client)
        vm.refresh()
        awaitThat("refusal") { vm.adminRefused.value }
        assertFalse(vm.isAdmin.value)
        assertNull(vm.runtimeData.value.readFailure, "a refusal is not a failed read")
        assertNull(vm.runtimeData.value.cognitiveState)
    }

    // ── System (CSD-025) ─────────────────────────────────────────────────────

    @Test
    fun system_on_a_bare_node_asks_no_agent_route_and_claims_no_state() {
        val stub = Stub(mapOf("/v1/system/health" to (200 to """{"data":{"status":"healthy"}}""")))
        using(stub) { client ->
            val vm = SystemViewModel(client)
            vm.loadSystemData()
            awaitThat("health read") { vm.systemData.value.health != null }
            delay(300)
            val asked = stub.paths()
            assertFalse(asked.any { it.startsWith("/v1/telemetry") || it.startsWith("/v1/agent") || it.startsWith("/v1/system/runtime") },
                "a bare node was asked agent routes: $asked")
            assertFalse(vm.systemData.value.agentAttached)
            assertNull(vm.systemData.value.cognitiveState, "no defaulted WORK")
        }
    }

    @Test
    fun system_with_an_agent_a_failed_channels_read_is_not_no_channels() {
        val stub = Stub(mapOf(
            "/v1/system/health" to (200 to health),
            "/v1/agent/channels" to (500 to """{"detail":"boom"}"""),
        ))
        using(stub) { client ->
            val vm = SystemViewModel(client)
            vm.setAgentAttached(true)
            vm.loadSystemData()
            awaitThat("agent half read") { vm.systemData.value.agentAttached && vm.systemData.value.health != null }
            assertNotNull(vm.systemData.value.channelsFailure, "a 500 drew as an empty channel list")
            assertNotNull(vm.systemData.value.agentReadFailure, "telemetry 404 must be said, not drawn as zeros")
            assertEquals("WORK", vm.systemData.value.cognitiveState)
        }
    }

    // ── Sessions (CSD-011) ───────────────────────────────────────────────────

    @Test
    fun sessions_a_403_says_admin_only() = using(Stub(mapOf(
        "POST /v1/system/state/transition" to (403 to """{"detail":"Insufficient permissions."}"""),
    ))) { client ->
        val vm = SessionsViewModel(client)
        vm.initiateSession("DREAM")
        awaitThat("transition settled") { vm.errorMessage.value != null }
        assertEquals(LocalizationHelper.getString("mobile.sessions_error_forbidden"), vm.errorMessage.value)
    }

    @Test
    fun sessions_an_accepted_request_is_not_written_as_an_arrival() = using(Stub(mapOf(
        "POST /v1/system/state/transition" to (200 to """{"data":{"success":true,"message":"ok","current_state":"dream","previous_state":"work"}}"""),
        "/v1/system/health" to (200 to health),
    ))) { client ->
        val vm = SessionsViewModel(client)
        vm.initiateSession("DREAM")
        awaitThat("re-read") { vm.currentState.value.state != null }
        assertEquals("WORK", vm.currentState.value.state, "the reading is the health read, not the request's echo")
    }

    @Test
    fun sessions_setup_is_not_a_state_you_return_to_work_from() = using(Stub(mapOf(
        "/v1/system/health" to (200 to """{"data":{"status":"healthy","cognitive_state":"setup"}}"""),
    ))) { client ->
        val vm = SessionsViewModel(client)
        vm.refresh()
        awaitThat("read") { vm.currentState.value.state != null }
        assertFalse(vm.canReturnToWork())
    }

    // ── Scheduler (CSD-012) ──────────────────────────────────────────────────

    @Test
    fun scheduler_a_multi_line_prompt_is_valid_json() {
        val stub = Stub(mapOf("POST /v1/scheduler/tasks" to (200 to
            """{"data":{"task_id":"t1","name":"n","goal_description":"g","status":"PENDING","trigger_prompt":"p","created_at":"2026-09-27T00:00:00Z"}}""")))
        using(stub) { client ->
            runCatching { client.createScheduledTask("n", "g", "line one\nline \\two", deferUntil = "2026-10-01T00:00:00Z") }
            val body = stub.seen.single { it.method == "POST" }.body
            val parsed = Json.parseToJsonElement(body).jsonObject
            assertEquals("line one\nline \\two", parsed["trigger_prompt"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun scheduler_reads_the_quarantined_count() = using(Stub(mapOf(
        "/v1/scheduler/stats" to (200 to """{"data":{"tasks_pending":1,"tasks_dead_lettered":3}}"""),
    ))) { client ->
        assertEquals(3, client.getSchedulerStats().tasksDeadLettered)
    }

    // ── Config / Transport (CSD-023 / CSD-031) ───────────────────────────────

    private val nodeConfigMap = """{"net.radio.enabled":{"key":"net.radio.enabled","value":true,"version":2,"updated_by":"owner","scope":"local"},
        "net.radio.frequency_hz":{"key":"net.radio.frequency_hz","value":915000000,"version":1,"updated_by":"owner","scope":"local"}}"""

    @Test
    fun config_reads_the_nodes_bare_map() {
        val list = parseConfigListBody(nodeConfigMap)
        assertEquals(listOf("net.radio.enabled", "net.radio.frequency_hz"), list.configs.map { it.key })
        assertEquals("915000000", list.configs[1].displayValue)
    }

    @Test
    fun config_a_put_the_node_accepted_is_not_a_failure() = using(Stub(mapOf(
        "PUT /v1/config/net.radio.serial_port" to (200 to """{"key":"net.radio.serial_port","value":"/dev/ttyUSB0","version":3,"updated_by":"owner","scope":"local"}"""),
    ))) { client ->
        val item = client.updateConfig("net.radio.serial_port", "/dev/ttyUSB0", "r")
        assertEquals("/dev/ttyUSB0", item.displayValue)
    }

    @Test
    fun transport_writes_typed_values_at_the_node_and_reads_them_back() {
        val node = Stub(mapOf("GET /v1/config" to (200 to nodeConfigMap)), fallback = 200 to """{"key":"k","value":null}""")
        val agent = Stub(emptyMap(), fallback = 500 to "{}")
        try {
            runBlocking {
                val vm = TransportViewModel(agent.client(), nodeUrl = { node.url })
                vm.loadRadioConfig()
                awaitThat("read back") { vm.state.value.radioConfigRead }
                assertTrue(vm.state.value.radioEnabled)
                assertEquals("915000000", vm.state.value.frequencyHz)
                vm.applyRadioConfig()
                awaitThat("saved") { vm.state.value.successMessage != null || vm.state.value.error != null }
                assertNull(vm.state.value.error)
                val puts = synchronized(node.seen) { node.seen.filter { it.method == "PUT" } }
                assertEquals(7, puts.size, "every key reached the node")
                val enabled = Json.parseToJsonElement(puts.single { it.path.endsWith("net.radio.enabled") }.body).jsonObject["value"]
                assertEquals(JsonPrimitive(true), enabled, "a boolean, not \"true\"")
                val freq = Json.parseToJsonElement(puts.single { it.path.endsWith("frequency_hz") }.body).jsonObject["value"] as JsonPrimitive
                assertFalse(freq.isString, "an integer, not a string")
                assertTrue(agent.seen.isEmpty(), "radio keys went to the agent: ${agent.paths()}")
            }
        } finally {
            node.server.stop(0); agent.server.stop(0)
        }
    }

    // ── Environment (CSD-002) ────────────────────────────────────────────────

    @Test
    fun environment_a_failed_items_read_is_not_no_items_and_a_bare_node_is_not_asked_for_enrichment() {
        val stub = Stub(mapOf("POST /v1/memory/query" to (500 to """{"error":"boom"}""")))
        using(stub) { client ->
            val vm = EnvironmentInfoViewModel(client)
            vm.startPolling()
            awaitThat("load settled") { !vm.state.value.isLoading && stub.paths().isNotEmpty() && vm.state.value.itemsFailure != null }
            assertIs<ReadFailure.Failed>(vm.state.value.itemsFailure)
            assertFalse(vm.state.value.agentAttached)
            assertFalse(stub.paths().any { it.contains("context-enrichment") }, "a bare node was asked for enrichment")
        }
    }

    // ── Logs (CSD-029) ───────────────────────────────────────────────────────

    @Test
    fun logs_warn_asks_both_spellings() {
        assertEquals(listOf("WARNING", "WARN"), wireLevels("WARN"))
        assertEquals(listOf<String?>("ERROR"), wireLevels("ERROR"))
        assertEquals(listOf<String?>(null), wireLevels(null))
    }
}
