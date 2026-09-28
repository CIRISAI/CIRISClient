package ai.ciris.mobile.shared.viewmodels.federation

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.RouteNotOnThisHost
import ai.ciris.mobile.shared.models.ClientMode
import ai.ciris.mobile.shared.models.federation.FederationIdentity
import ai.ciris.mobile.shared.models.federation.FederationMetricsResponse
import ai.ciris.mobile.shared.models.federation.peerCountReading
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import ai.ciris.mobile.shared.viewmodels.BaseFederationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The real client, pointed at a port nothing listens on. */
private fun deadClient() = CIRISApiClient(baseUrl = "http://127.0.0.1:9")

/** Exposes the base's primary-read helper to the test. */
private class ProbeVm : BaseFederationViewModel(deadClient()) {
    override val tag = "ProbeVM"
    suspend fun read(block: suspend () -> String) = runRead("probe", block)
}

/**
 * CSD-032/033/046/047/048/049: the Everyone › Global Commons tiles. Each test
 * here was run red against the code before its fix.
 */
class NetworkScreensHonestyTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private val json = Json { ignoreUnknownKeys = true }

    // ── CSD-048 Interfaces: the node keys reachability `<peer>:<medium>` ──

    @Test
    fun reachabilityKeyedPeerColonMediumCollatesToTheMedium() {
        // CIRISServer src/federation_surface.rs:222 — format!("{peer}:{medium}").
        val snapshot = FederationMetricsResponse(
            transportBytesInTotal = mapOf("tcp" to 10L),
            peerReachabilityRatio = mapOf("peer-a:tcp" to 1.0, "peer-b:tcp" to 0.5),
        )
        val rows = NetworkInterfacesViewModel(deadClient()).collateTransports(snapshot)
        assertEquals(listOf("tcp"), rows.map { it.id }, "one row per medium, not one per peer")
        assertEquals(2, rows.single().peerCount)
        assertEquals(0.75, rows.single().reachRatio)
    }

    // ── CSD-049 Queue: the metrics the node actually sends ────────────────

    @Test
    fun theReplicationPlaneAndTheSubscriberCountDecode() {
        val body = """{"envelopes_sent_total":{},"inline_text_subscriber_count":3,
            "replication_envelopes_served_total":{"trace":15},"replication_applied_total":{"trace":4},
            "carriage_standing":"moving","receive_standing":"converged","receive_decided_total":9}"""
        val m = json.decodeFromString(FederationMetricsResponse.serializer(), body)
        assertEquals(3L, m.verifiedFeedSubscriberCount, "the node's key is inline_text_subscriber_count")
        assertEquals("moving", m.carriageStanding)
        assertEquals("converged", m.receiveStanding)
        assertEquals(15L, m.replicationServed())
        assertEquals(4L, m.replicationApplied())
        assertEquals(9L, m.receiveDecidedTotal)
    }

    @Test
    fun anOlderNodeWithoutTheReplicationPlaneSaysNothingRatherThanZero() {
        val m = json.decodeFromString(FederationMetricsResponse.serializer(), """{"envelopes_sent_total":{}}""")
        assertNull(m.carriageStanding)
        assertNull(m.replicationServed(), "absent is not zero")
    }

    @Test
    fun queueCountersAreNotReadUntilAReadArrives() {
        val vm = NetworkQueueViewModel(deadClient())
        assertNull(vm.queueDepth, "a counter nobody read is not 0")
        assertNull(vm.envelopesSent)
        assertNull(vm.sendFailures)
    }

    // ── CSD-032 Identity: a zero the node cannot vouch for ────────────────

    @Test
    fun peerCountsThatWereNotMeasuredAreNotRenderedAsZero() {
        val unreadable = json.decodeFromString(
            FederationIdentity.serializer(),
            """{"signer_key_id":"k","crate_version":"1","peer_count_total":0,"peer_count_canonical":0,"peer_counts_standing":"store_unavailable"}""",
        )
        assertNull(unreadable.peerCountReading(unreadable.peerCountTotal))
        val measured = unreadable.copy(peerCountsStanding = "measured")
        assertEquals("0", measured.peerCountReading(measured.peerCountTotal))
        // An older node sends no standing: its number is all it has.
        val older = unreadable.copy(peerCountsStanding = null)
        assertEquals("0", older.peerCountReading(older.peerCountTotal))
    }

    // ── The primary read: which failure, not just that it failed ──────────

    @Test
    fun aFailedPrimaryReadIsRecordedAndASuccessClearsIt() = runTest {
        val vm = ProbeVm()
        assertNull(vm.read { throw RouteNotOnThisHost("/v1/x") })
        assertIs<ReadFailure.NotOnThisNode>(vm.readFailure.value)
        assertNull(vm.read { throw RuntimeException("boom: 503") })
        assertIs<ReadFailure.Failed>(vm.readFailure.value)
        assertEquals("ok", vm.read { "ok" })
        assertNull(vm.readFailure.value)
    }

    // ── Agent-only routes on a bare node ──────────────────────────────────

    @Test
    fun theAgentsPeerRoutesRaiseNotOnThisHostOnABareNode() = runTest {
        val c = deadClient().also { it.setClientMode(ClientMode.NODE) }
        assertFailsWith<RouteNotOnThisHost> { c.addPeerFromNodeCode("CIRIS-V1-x") }
        assertFailsWith<RouteNotOnThisHost> { c.getFederationIdentityAggregate() }
        assertFailsWith<RouteNotOnThisHost> { c.getMyNodeCode() }
    }
}
