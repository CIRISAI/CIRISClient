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
            val n = asks.merge(url, 1, Int::plus)!!
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
        val node = "http://127.0.0.1:4243"
        val b = FakeBackends(alive = setOf(node))
        b.answers = { _, _ -> 404 } // a bare node without the route
        val r = stopAttachedBackend(listOf(node), b::request, b::answering, timeoutMs = 5_000, pollMs = 100)
        assertEquals(AttachedStop.StillRunning(listOf(node)), r)
        assertEquals(1, b.asks[node], "a definite answer is asked once, not hammered")
    }

    @Test
    fun aBackendThatIsAlreadyGoneIsNotAsked() = runTest {
        val b = FakeBackends(alive = emptySet())
        val r = stopAttachedBackend(listOf("http://127.0.0.1:8080"), b::request, b::answering)
        assertEquals(AttachedStop.Stopped(listOf("http://127.0.0.1:8080")), r)
        assertTrue(b.asks.isEmpty())
    }
}
