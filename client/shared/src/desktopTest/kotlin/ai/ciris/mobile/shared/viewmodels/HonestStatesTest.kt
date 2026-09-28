package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.CapacityPayloadUnrecognised
import ai.ciris.mobile.shared.api.RouteNotOnThisHost
import ai.ciris.mobile.shared.models.ClientMode
import ai.ciris.mobile.shared.ui.screens.BALANCE_NOT_ON_THIS_NODE
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **A read that failed, or that has no host, is never drawn as a reading.**
 *
 * CSD/3 §2.2 and the primitives' `StateBlock`: error and empty never look
 * alike; a value that was not read is never shown as one; a node that lacks a
 * route is "this node doesn't have X", not "you have nothing". The CSD reports
 * found the opposite on these cards — a failed Runtime read drew "WORK · queue
 * 0", a failed Sessions read drew the seeded "WORK", Scheduler swallowed all
 * three reads into "No Scheduled Tasks", Services printed five constants as
 * if read, a node build's Audit said "try adjusting your filters", Consent
 * turned a missing route into "no consent record", Billing fabricated a
 * balance, and Capacity scored another host's payload as 0.00.
 *
 * These drive the REAL client against a REAL socket (as EndpointAnsweringTest
 * does next door): the whole defect lived in how a served 404 / 500 / foreign
 * payload travels through the mapper into the view model, and a fake client
 * would agree with whatever the author assumed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HonestStatesTest {

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Main; Unconfined lets the real HTTP call
        // resume wherever Ktor completes it.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Serves [routes] (path → status to body); anything else gets [fallback]. */
    private fun serving(
        routes: Map<String, Pair<Int, String>> = emptyMap(),
        fallback: Pair<Int, String> = 500 to """{"detail":"boom"}""",
    ): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val (status, body) = routes[exchange.requestURI.path] ?: fallback
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private fun HttpServer.client() = CIRISApiClient("http://127.0.0.1:${address.port}")

    private fun withServer(server: HttpServer, block: suspend (CIRISApiClient) -> Unit) {
        try {
            runBlocking { block(server.client()) }
        } finally {
            server.stop(0)
        }
    }

    private suspend fun awaitThat(what: String, condition: () -> Boolean) {
        try {
            withTimeout(15_000) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            fail("timed out waiting for: $what")
        }
    }

    // ── Runtime (CSD-024) ────────────────────────────────────────────────────

    @Test
    fun runtime_a_failed_read_is_not_WORK_with_an_empty_queue() = withServer(serving()) { client ->
        val vm = RuntimeViewModel(client)
        vm.refresh()
        awaitThat("runtime read failure") { vm.runtimeData.value.readFailure != null }
        val data = vm.runtimeData.value
        assertNull(data.cognitiveState, "a failed read must not draw a cognitive state")
        assertNull(data.queueDepth, "a failed read must not draw a queue depth")
        assertIs<ReadFailure.Failed>(data.readFailure)
    }

    @Test
    fun runtime_before_any_read_claims_nothing() {
        val data = ai.ciris.mobile.shared.ui.screens.RuntimeData()
        assertNull(data.cognitiveState)
        assertNull(data.queueDepth)
    }

    @Test
    fun runtime_a_404_is_this_node_not_a_broken_agent() = withServer(serving(fallback = 404 to "{}")) { client ->
        val vm = RuntimeViewModel(client)
        vm.refresh()
        awaitThat("runtime read failure") { vm.runtimeData.value.readFailure != null }
        assertIs<ReadFailure.NotOnThisNode>(vm.runtimeData.value.readFailure)
    }

    // ── Telemetry (CSD-030) ──────────────────────────────────────────────────

    @Test
    fun telemetry_a_failed_read_draws_no_metrics() = withServer(serving(fallback = 404 to "{}")) { client ->
        val vm = TelemetryViewModel(client)
        vm.refresh()
        vm.loadExportDestinations()
        awaitThat("telemetry read failure") {
            vm.telemetryData.value.readFailure != null && vm.telemetryData.value.destinationsFailure != null
        }
        val data = vm.telemetryData.value
        assertFalse(data.hasReading, "no reading, so the metric cards do not draw")
        assertNull(data.cognitiveState, "never a default WORK")
        assertIs<ReadFailure.NotOnThisNode>(data.readFailure)
        assertIs<ReadFailure.NotOnThisNode>(data.destinationsFailure, "no destinations read is not 'none configured'")
    }

    // ── Services (CSD-016) ───────────────────────────────────────────────────

    private val servicesBody = """
        {"data":{"services":[
          {"name":"OpenAICompatibleClient","type":"llm","healthy":true,"available":true},
          {"name":"LocalGraphMemoryService","type":"memory","healthy":false,"available":true}
        ],"total_services":2,"healthy_services":1,"timestamp":null}}
    """.trimIndent()

    @Test
    fun services_show_only_what_the_wire_carried() = withServer(
        serving(mapOf("/v1/system/services" to (200 to servicesBody)))
    ) { client ->
        val response = client.getServices()
        val providers = response.globalServices.values.flatten()
        assertEquals(2, providers.size)
        for (p in providers) {
            assertNull(p.priority, "priority is not on the wire; it was the constant NORMAL")
            assertNull(p.priorityGroup, "priority group is not on the wire; it was the constant 0")
            assertNull(p.strategy, "strategy is not on the wire; it was the constant FALLBACK")
            assertNull(p.capabilities, "capabilities are not on the wire; they were a constant []")
            assertNull(p.circuitBreakerState, "breaker state is not on the wire; it was derived from `healthy`")
        }
        assertEquals(setOf(true, false), providers.map { it.healthy }.toSet())

        val vm = ServicesViewModel(client)
        vm.refresh()
        awaitThat("services reading") { vm.servicesData.value.hasReading }
        val data = vm.servicesData.value
        assertEquals(1, data.healthyServices)
        assertEquals(1, data.unhealthyServices)
    }

    @Test
    fun services_a_404_is_not_No_Services_Found() = withServer(serving(fallback = 404 to "{}")) { client ->
        val vm = ServicesViewModel(client)
        vm.refresh()
        awaitThat("services read failure") { vm.servicesData.value.readFailure != null }
        assertFalse(vm.servicesData.value.hasReading)
        assertIs<ReadFailure.NotOnThisNode>(vm.servicesData.value.readFailure)
    }

    @Test
    fun services_reset_claims_no_reset() = withServer(serving()) { client ->
        val vm = ServicesViewModel(client)
        vm.resetCircuitBreakers(null)
        assertNull(vm.statusMessage.value, "no status may say a reset happened")
        assertTrue(vm.error.value != null, "the refusal is said")
    }

    // ── Scheduler (CSD-012) ──────────────────────────────────────────────────

    @Test
    fun scheduler_a_500_is_not_No_Scheduled_Tasks() = withServer(serving()) { client ->
        val vm = SchedulerViewModel(client)
        vm.refresh()
        awaitThat("scheduler refresh") { !vm.state.value.isRefreshing && vm.state.value.tasksFailure != null }
        val state = vm.state.value
        assertIs<ReadFailure.Failed>(state.tasksFailure)
        assertFalse(state.hasTasksReading, "the empty card needs a successful read")
        assertNull(state.overview.completedTotal, "an absent stats envelope is not 0 completed")
        assertNull(state.overview.pendingCount)
    }

    // ── Sessions (CSD-011) ───────────────────────────────────────────────────

    @Test
    fun sessions_is_not_seeded_WORK() = withServer(serving()) { client ->
        val vm = SessionsViewModel(client)
        assertNull(vm.currentState.value.state, "nothing was read yet; it was seeded \"WORK\"")
        vm.refresh()
        awaitThat("sessions read failure") { vm.currentState.value.failure != null }
        assertNull(vm.currentState.value.state)
        assertFalse(vm.canInitiateSession("DREAM"), "no transition is offered from an unread state")
    }

    // ── Config (CSD-023) ─────────────────────────────────────────────────────

    @Test
    fun config_a_failed_read_is_not_a_search_miss() = withServer(serving()) { client ->
        val vm = ConfigViewModel(client)
        vm.loadConfigs()
        awaitThat("config read failure") { vm.configData.value.readFailure != null }
        assertIs<ReadFailure.Failed>(vm.configData.value.readFailure)
    }

    // ── Consent (CSD-054) ────────────────────────────────────────────────────

    @Test
    fun consent_a_404_is_not_no_consent_record() = withServer(
        serving(
            // Only the status route is missing; the rest of the load succeeds,
            // which is exactly where the old swallow drew "no consent record".
            mapOf(
                "/v1/consent/status" to (404 to """{"detail":"Not Found"}"""),
                "/v1/consent/streams" to (200 to """{"streams":{},"default":"temporary"}"""),
            )
        )
    ) { client ->
        val vm = ConsentViewModel(client)
        vm.loadConsentData()
        awaitThat("consent read failure") { vm.consentData.value.readFailure != null }
        assertIs<ReadFailure.NotOnThisNode>(
            vm.consentData.value.readFailure,
            "the agent answers 'no record' with 200; a 404 means this host has no consent route",
        )
    }

    // ── Audit (CSD-071) ──────────────────────────────────────────────────────

    @Test
    fun audit_on_a_node_is_not_an_empty_success() = withServer(serving()) { client ->
        client.setClientMode(ClientMode.NODE)
        assertFailsWith<RouteNotOnThisHost> { client.getAuditEntries() }

        val vm = AuditViewModel(client)
        vm.refresh()
        awaitThat("audit read failure") { vm.state.value.readFailure != null }
        assertIs<ReadFailure.NotOnThisNode>(vm.state.value.readFailure, "not 'try adjusting your filters'")
    }

    // ── Billing (CSD-056) ────────────────────────────────────────────────────

    @Test
    fun billing_on_a_node_fabricates_no_balance() = withServer(serving()) { client ->
        client.setClientMode(ClientMode.NODE)
        assertFailsWith<RouteNotOnThisHost> { client.getCredits() }

        val vm = BillingViewModel(client)
        vm.loadBalance()
        awaitThat("billing settles") { vm.currentBalance.value == BALANCE_NOT_ON_THIS_NODE }
        assertTrue(vm.products.value.isEmpty(), "nothing to buy on a node with no billing")
        assertNull(vm.errorMessage.value, "a node without billing is not an error")
    }

    // ── Capacity (CSD-004) ───────────────────────────────────────────────────

    @Test
    fun capacity_the_nodes_payload_is_not_a_score_of_zero() = withServer(
        serving(
            mapOf(
                "/v1/my-data/capacity" to (200 to """
                    {"data":{"node_key_id":"k1","responsible_user_key_id":null,
                     "subjects":[{"key_id":"k1","relation":"node","any_standing":false,"rows":[]}],
                     "unscored":[],"truncated":false}}
                """.trimIndent())
            )
        )
    ) { client ->
        assertFailsWith<CapacityPayloadUnrecognised> { client.getCapacity() }
    }

    @Test
    fun capacity_the_agents_payload_still_parses() = withServer(
        serving(
            mapOf(
                "/v1/my-data/capacity" to (200 to """
                    {"data":{"agent_name":"a","composite_score":0.8,"fragility_index":1.0,"category":"healthy",
                     "factors":{"C":{"score":0.9},"I_int":{"score":0.8},"R":{"score":0.7},"I_inc":{"score":0.6},"S":{"score":0.5}},
                     "cached":false}}
                """.trimIndent())
            )
        )
    ) { client ->
        val data = client.getCapacity()
        assertEquals(0.8, data.compositeScore)
        assertEquals(0.5, data.s)
    }
}
