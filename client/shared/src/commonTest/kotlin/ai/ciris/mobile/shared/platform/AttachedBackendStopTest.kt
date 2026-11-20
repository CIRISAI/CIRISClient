package ai.ciris.mobile.shared.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reset on desktop stops an ATTACHED local backend before it deletes that
 * backend's files (CIRISAgent five-platform, Windows, ciris-server 0.5.249:
 * `could not remove ...\data`). What must hold: only loopback is asked, a
 * "retry later" is retried, a stop is only reported once nothing answers, and
 * a backend that will not stop is reported as still running.
 */
class AttachedBackendStopTest {

    private class FakeBackends(alive: Set<String>, private val stopsAfterAsks: Map<String, Int> = emptyMap()) {
        val alive = alive.toMutableSet()
        val asks = mutableMapOf<String, Int>()
        var answers: (String, Int) -> Int? = { _, _ -> 200 }

        suspend fun request(url: String): Int? {
            val n = (asks[url] ?: 0) + 1; asks[url] = n
            val status = answers(url, n)
            if (status == 200 && n >= (stopsAfterAsks[url] ?: 1)) alive -= url
            return status
        }

        suspend fun answering(url: String) = url in alive
    }

    @Test
    fun loopbackRecognition() {
        assertTrue(isLoopbackUrl("http://127.0.0.1:8080"))
        assertTrue(isLoopbackUrl("http://localhost:4243/"))
        assertTrue(isLoopbackUrl("http://[::1]:4243"))
        assertFalse(isLoopbackUrl("http://108.61.242.236:4243"))
        assertFalse(isLoopbackUrl("http://127.0.0.1.example.com:8080"))
        assertFalse(isLoopbackUrl("https://node.example.org"))
        assertFalse(isLoopbackUrl("http://localhost:secret@remote.example:8080"))
        assertFalse(isLoopbackUrl("http://127.0.0.1@remote.example"))
        assertTrue(isLoopbackUrl("http://user:pw@127.0.0.1:8080/x@y"))
        assertFalse(isLoopbackUrl("http://remote.example?h=127.0.0.1"))
    }

    @Test
    fun aRemoteUrlIsNeverAskedToStop() = runTest {
        val b = FakeBackends(alive = setOf("http://10.0.0.5:8080"))
        val r = stopAttachedBackend(listOf("http://10.0.0.5:8080"), b::request, b::answering)
        assertEquals(AttachedStop.NotLocal, r)
        assertTrue(b.asks.isEmpty())
    }

    @Test
    fun theAgentAndTheNodeAreBothAskedOnceAndWaitedOn() = runTest {
        val agent = "http://127.0.0.1:8080"
        val node = "http://127.0.0.1:4243"
        val b = FakeBackends(alive = setOf(agent, node))
        val r = stopAttachedBackend(listOf(agent, "$node/", node), b::request, b::answering)
        assertEquals(AttachedStop.Stopped(listOf(agent, node)), r)
        assertEquals(mapOf(agent to 1, node to 1), b.asks)
    }

    @Test
    fun retryLaterIsRetriedUntilTheBackendStops() = runTest {
        val agent = "http://127.0.0.1:8080"
        val b = FakeBackends(alive = setOf(agent))
        b.answers = { _, n -> if (n < 3) 409 else 200 }
        val r = stopAttachedBackend(listOf(agent), b::request, b::answering, timeoutMs = 10_000, pollMs = 100)
        assertEquals(AttachedStop.Stopped(listOf(agent)), r)
        assertEquals(3, b.asks[agent])
    }

    @Test
    fun aBackendThatKeepsAnsweringIsReportedStillRunning() = runTest {
        val agent = "http://127.0.0.1:8080"
        val b = FakeBackends(alive = setOf(agent))
        b.answers = { _, _ -> 202 } // accepted, and then never stops
        val r = stopAttachedBackend(listOf(agent), b::request, b::answering, timeoutMs = 5_000, pollMs = 100)
        assertEquals(AttachedStop.StillRunning(listOf(agent)), r)
        assertEquals(1, b.asks[agent], "a definite answer is asked once, not hammered")
    }

    @Test
    fun aRefusalIsNotWaitedOn() = runTest {
        // CIRISServer 0.5.250: 403 system.not_a_personal_node / system.proxied / system.not_loopback.
        val node = "http://127.0.0.1:4243"
        val b = FakeBackends(alive = setOf(node))
        b.answers = { _, _ -> 403 }
        val started = testScheduler.currentTime
        val r = stopAttachedBackend(listOf(node), b::request, b::answering, timeoutMs = 30_000, pollMs = 500)
        assertEquals(AttachedStop.CannotAsk(listOf(node), stopped = emptyList()), r)
        assertEquals(0L, testScheduler.currentTime - started)
    }

    @Test
    fun aBareNodeWithoutTheRouteIsNotWaitedOn() = runTest {
        // CIRISAgent run 44346546111: :4243 answered 404 and Reset waited 30 s.
        val node = "http://127.0.0.1:4243"
        val b = FakeBackends(alive = setOf(node))
        b.answers = { _, _ -> 404 }
        val started = testScheduler.currentTime
        val r = stopAttachedBackend(listOf(node), b::request, b::answering, timeoutMs = 30_000, pollMs = 500)
        assertEquals(AttachedStop.CannotAsk(listOf(node), stopped = emptyList()), r)
        assertEquals(0L, testScheduler.currentTime - started, "no wait for a backend that cannot be asked")
    }

    @Test
    fun theAgentStillStopsWhenItsNodeCannotBeAsked() = runTest {
        val agent = "http://127.0.0.1:8080"
        val node = "http://127.0.0.1:4243"
        val b = FakeBackends(alive = setOf(agent, node))
        b.answers = { url, _ -> if (url == node) 404 else 200 }
        val r = stopAttachedBackend(listOf(agent, node), b::request, b::answering)
        assertEquals(AttachedStop.CannotAsk(listOf(node), stopped = listOf(agent)), r)
        assertFalse(agent in b.alive)
    }

    @Test
    fun anAgentThatWillNotStopIsKeptBesideANodeThatCannotBeAsked() = runTest {
        val agent = "http://127.0.0.1:8080"
        val node = "http://127.0.0.1:4243"
        val b = FakeBackends(alive = setOf(agent, node))
        b.answers = { url, _ -> if (url == node) 404 else 202 } // the agent accepts and never goes
        val r = stopAttachedBackend(listOf(agent, node), b::request, b::answering, timeoutMs = 2_000, pollMs = 100)
        assertEquals(AttachedStop.CannotAsk(listOf(node), stopped = emptyList(), running = listOf(agent)), r)
    }

    @Test
    fun aProbeThatTimesOutReachedALiveBackend() {
        assertTrue(probeFailureMeansAlive(io.ktor.client.plugins.HttpRequestTimeoutException("http://127.0.0.1:8080/v1/system/health", 2_000)))
        assertTrue(probeFailureMeansAlive(io.ktor.client.network.sockets.ConnectTimeoutException("connect timeout")))
        assertTrue(probeFailureMeansAlive(io.ktor.client.network.sockets.SocketTimeoutException("socket timeout")))
        assertFalse(probeFailureMeansAlive(IllegalStateException("Connection refused")))
    }

    @Test
    fun aBackendThatIsAlreadyGoneIsNotAsked() = runTest {
        val b = FakeBackends(alive = emptySet())
        val r = stopAttachedBackend(listOf("http://127.0.0.1:8080"), b::request, b::answering)
        assertEquals(AttachedStop.Stopped(listOf("http://127.0.0.1:8080")), r)
        assertTrue(b.asks.isEmpty())
    }

    @Test
    fun aRateLimitIsRetriedLikeRetryLater() = runTest {
        val agent = "http://127.0.0.1:8080"
        val b = FakeBackends(alive = setOf(agent))
        b.answers = { _, n -> if (n == 1) 429 else 200 }
        val r = stopAttachedBackend(listOf(agent), b::request, b::answering, timeoutMs = 10_000, pollMs = 100)
        assertEquals(AttachedStop.Stopped(listOf(agent)), r)
        assertEquals(2, b.asks[agent])
    }

    @Test
    fun theStopRouteIsNotPacedByTheGovernor() {
        // Codex, CIRISClient#316: a 60 s pause would outlast the 30 s stop.
        assertTrue(ai.ciris.mobile.shared.api.AgentRateGovernor.isExempt("/v1/system/local-shutdown"))
    }
}
