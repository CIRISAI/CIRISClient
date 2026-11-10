package ai.ciris.mobile.shared.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Remote administration through the owner's own node (CSD-133, `POST /v1/mesh/relay`). */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val metrics = """{"data":{"peers":{"connected":3},"links":[1,2],"uptime_s":42}}"""
    private val logs = """{"data":{"logs":[{"timestamp":"t2","level":"WARN","service":"edge","message":"late"},
        {"timestamp":"t1","level":"INFO","service":"edge","message":"up"}]}}"""

    private fun vm(answer: (String) -> RelayAnswer) = RemoteNodeViewModel { _, _, path -> answer(path) }

    @Test fun bothReadsMakeASnapshot() {
        val m = vm { if (it.startsWith(RemoteNodeViewModel.METRICS_PATH)) RelayAnswer(200, metrics) else RelayAnswer(200, logs) }
        m.load("node-abc")
        val s = assertIs<RemoteNodeState.Loaded>(m.state.value)
        assertEquals(listOf("peers.connected" to "3", "links" to "2", "uptime_s" to "42"), s.metrics)
        assertEquals(listOf("t2 WARN late", "t1 INFO up"), s.logs)
        assertNull(s.metricsFailure)
    }

    @Test fun logsAreAskedForAModestPage() {
        val asked = mutableListOf<String>()
        RemoteNodeViewModel { _, _, path -> asked += path; RelayAnswer(200, "{}") }.load("n")
        assertEquals(true, asked.any { it == "/v1/telemetry/logs?limit=100" })
    }

    @Test fun oneHalfCanFailAlone() {
        val m = vm {
            if (it.startsWith(RemoteNodeViewModel.METRICS_PATH)) RelayAnswer(200, metrics)
            else throw RelayRefusal(403, null, "GET /v1/telemetry/logs is not a relayable control op")
        }
        m.load("n")
        val s = assertIs<RemoteNodeState.Loaded>(m.state.value)
        assertEquals(RemoteFailure.NEEDS_UPDATE, s.logsFailure)
        assertEquals(3, s.metrics.size)
    }

    @Test fun everyRelayFailureHasItsOwnWords() {
        val cases = mapOf(
            RelayRefusal(502, "mesh_target_unreachable", "mesh_target_unreachable: no path") to RemoteFailure.UNREACHABLE,
            RelayRefusal(504, "mesh_relay_timeout", "mesh_relay_timeout") to RemoteFailure.TIMEOUT,
            RelayRefusal(502, "mesh_send_failed", "mesh_send_failed: x") to RemoteFailure.SEND_FAILED,
            RelayRefusal(502, "mesh_send_unwired", "mesh_send_unwired: none") to RemoteFailure.NO_TRANSPORT,
            RelayRefusal(403, null, "POST /v1/x is not a relayable control op") to RemoteFailure.NEEDS_UPDATE,
            RelayRefusal(403, null, "signer is not the owner") to RemoteFailure.NOT_OWNER,
        )
        for ((refusal, want) in cases) {
            val m = vm { throw refusal }
            m.load("n")
            assertEquals(want, assertIs<RemoteNodeState.Failed>(m.state.value).failure, refusal.detail)
        }
    }
}
