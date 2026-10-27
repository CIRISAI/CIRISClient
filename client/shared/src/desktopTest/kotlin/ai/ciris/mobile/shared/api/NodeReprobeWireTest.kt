package ai.ciris.mobile.shared.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A node that binds after Login asked** (CIRISClient#254).
 *
 * The real client against a real port that REFUSES connections for the first
 * probes and then binds, as the SM-S737TL's in-process node did, 36 s or more
 * after Login composed. Before the fix both probes ran once, the refused
 * connect was the final verdict, and the claim section never rendered.
 *
 * The backoff's sleeps are replaced, not shortened. Each sleep is where the
 * "node" gets a chance to bind, so the test counts probes instead of timing them.
 */
class NodeReprobeWireTest {

    /** A loopback port nobody is listening on, yet. */
    private fun freePort(): Int = ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }

    private class Node(port: Int, private val routes: Map<String, Pair<Int, String>>) {
        val hits = mutableListOf<String>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
            createContext("/") { ex ->
                synchronized(hits) { hits += ex.requestURI.path }
                val (status, body) = routes[ex.requestURI.path] ?: (404 to """{"detail":"Not Found"}""")
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    /** Sleeps that bind the node at the [bindAfter]-th sleep and record the backoff asked for. */
    private class LateBind(private val bindAfter: Int, private val bind: () -> Unit) {
        val asked = mutableListOf<Long>()
        val sleep: suspend (Long) -> Unit = { ms ->
            asked += ms
            if (asked.size == bindAfter) bind()
        }
    }

    @Test
    fun anUnclaimedNodeThatBindsLateIsReachedAndOffersTheCreatePath() {
        // The phone's case: a fresh node, waiting to be claimed. owned-nodes
        // answers with no owner and there is no self key yet. That is "reached,
        // no identity": the section that lets the owner claim it.
        val port = freePort()
        var node: Node? = null
        val late = LateBind(bindAfter = 3) {
            node = Node(port, mapOf("/v1/setup/owned-nodes" to (200 to """{"owner":null,"nodes":[]}""")))
        }
        val results = mutableListOf<FederationProbe>()
        try {
            val client = CIRISApiClient("http://127.0.0.1:1", null)
            val final = runBlocking {
                NodeReprobe.untilAnswered<FederationProbe>(
                    answered = { it.reached },
                    onResult = { results += it },
                    sleep = late.sleep,
                ) { client.probeFederationIdentity("http://127.0.0.1:$port") }
            }
            assertTrue(final.reached, "the probe must reach the node once it binds")
            assertNull(final.keyId, "an unclaimed node has no owner identity")
            assertEquals(listOf(false, false, false, true), results.map { it.reached }, "three refusals, then an answer")
            assertEquals(listOf(2_000L, 4_000L, 8_000L), late.asked, "backoff between refusals")
            assertTrue(node!!.hits.contains("/v1/setup/owned-nodes"))
        } finally {
            node?.server?.stop(0)
        }
    }

    @Test
    fun aClaimedNodeThatBindsLateOffersTheOwnersIdentity() {
        val port = freePort()
        var node: Node? = null
        val late = LateBind(bindAfter = 1) {
            node = Node(port, mapOf("/v1/setup/owned-nodes" to (200 to """{"owner":"wa-owner-1","nodes":[]}""")))
        }
        try {
            val client = CIRISApiClient("http://127.0.0.1:1", null)
            val final = runBlocking {
                NodeReprobe.untilAnswered<FederationProbe>(answered = { it.reached }, sleep = late.sleep) {
                    client.probeFederationIdentity("http://127.0.0.1:$port")
                }
            }
            assertEquals(FederationProbe(reached = true, keyId = "wa-owner-1"), final)
        } finally {
            node?.server?.stop(0)
        }
    }

    @Test
    fun capabilitiesGoFromUnreachableToDeclaredWithoutAnyoneAsking() {
        val port = freePort()
        var node: Node? = null
        val late = LateBind(bindAfter = 2) {
            node = Node(
                port,
                mapOf("/v1/federation/conformance" to (200 to """{"capabilities":["infra:attest","infra:serve","registry:lookup"]}""")),
            )
        }
        val seen = mutableListOf<Boolean>()
        try {
            val client = CIRISApiClient("http://127.0.0.1:1", null)
            val final = runBlocking {
                NodeReprobe.untilAnswered<ai.ciris.mobile.shared.models.capability.NodeCapabilities>(
                    answered = { !it.unreachable && !it.undetermined },
                    onResult = { seen += it.unreachable },
                    sleep = late.sleep,
                ) { client.getNodeCapabilities("http://127.0.0.1:$port") }
            }
            assertEquals(listOf(true, true, false), seen, "unreachable twice, then declared")
            assertFalse(final.unreachable)
            assertEquals(3, final.declared?.size)
        } finally {
            node?.server?.stop(0)
        }
    }

    @Test
    fun theBackoffIsCappedAndNeverStopsAskingANodeThatNeverComes() {
        val asked = mutableListOf<Long>()
        var calls = 0
        val final = runBlocking {
            NodeReprobe.untilAnswered<Boolean>(
                answered = { it },
                sleep = { asked += it },
            ) { ++calls >= 9 }
        }
        assertTrue(final)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L), asked)
    }
}
