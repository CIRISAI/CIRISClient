package ai.ciris.mobile.shared.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A withdrawal goes to the node, and says what the node said.**
 *
 * Two real servers stand in for the two machines on a with-AI install: the
 * AGENT at the client's `baseUrl`, which does not proxy the withdraw routes
 * (CIRISAgent#1213), and the NODE. Real sockets rather than a fake, for the
 * reason [EndpointAnsweringTest] gives: which host a request lands on, and how
 * a 409 body reads, is the contract, and a fake would agree with whatever the
 * author assumed.
 */
class WithdrawConsentWireTest {

    /** A server that records "METHOD path body" and answers [status] with [body]. */
    private class Recording(status: Int, body: String) {
        val hits: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val reqBody = ex.requestBody.readBytes().decodeToString()
                hits += "${ex.requestMethod} ${ex.requestURI.path} $reqBody".trim()
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        fun stop() = server.stop(0)
    }

    private fun <T> twoMachines(nodeStatus: Int, nodeBody: String, block: suspend (agent: Recording, node: Recording, client: CIRISApiClient) -> T): T {
        val agent = Recording(404, "")
        val node = Recording(nodeStatus, nodeBody)
        try {
            return runBlocking { block(agent, node, CIRISApiClient(baseUrl = agent.url)) }
        } finally {
            agent.stop(); node.stop()
        }
    }

    @Test
    fun removeContactIsADeleteOnTheNodeNotTheAgent() = twoMachines(
        200,
        """{"key_id":"p1","withdrawn":[{"grant":"g1","withdraws":"w1","cohort_scope":"federation"}],"remaining_grants":[],"contact":false}""",
    ) { agent, node, client ->
        val resp = client.removeContact("p1", nodeUrl = node.url)
        assertEquals(listOf("DELETE /v1/contacts/p1"), node.hits.toList())
        assertTrue(agent.hits.isEmpty(), "the agent was asked: ${agent.hits}")
        assertTrue(resp.complete)
    }

    @Test
    fun remainingGrantsOnA200AreNotComplete() = twoMachines(
        200,
        """{"key_id":"p1","withdrawn":[{"grant":"g1","withdraws":"w1"}],"remaining_grants":["n1"],"contact":true}""",
    ) { _, node, client ->
        val resp = client.removeContact("p1", nodeUrl = node.url)
        assertFalse(resp.complete)
        assertEquals(listOf("n1"), resp.remainingGrants)
    }

    @Test
    fun theNodeAuthored409IsAnAnswerNamingTheGrantsStillActive() = twoMachines(
        409,
        """{"error":"that consent was authored by the node","reason_id":"consent.grant_not_owner_authored","grants":["n1","n2"]}""",
    ) { _, node, client ->
        val resp = client.removeContact("p1", nodeUrl = node.url)
        assertFalse(resp.complete)
        assertEquals(listOf("n1", "n2"), resp.remainingGrants)
        assertTrue(resp.withdrawn.isEmpty())
    }

    @Test
    fun revokePostsTheGrantIdToTheNode() = twoMachines(
        200,
        """{"attestation_id":"g1","peer_key_ids":["b"],"withdraws":"w1","cohort_scope":"federation"}""",
    ) { agent, node, client ->
        val resp = client.revokePeeringGrant("g1", nodeUrl = node.url)
        val hit = node.hits.single()
        assertTrue(hit.startsWith("POST /v1/federation/peering/revoke"), hit)
        assertTrue(hit.contains("\"attestation_id\":\"g1\""), hit)
        assertTrue(agent.hits.isEmpty())
        assertTrue(resp.complete)
    }

    @Test
    fun aBare404OnRevokeIsARouteMissingRefusal() = twoMachines(404, "") { _, node, client ->
        val e = runCatching { client.revokePeeringGrant("g1", nodeUrl = node.url) }.exceptionOrNull()
        assertTrue(e is NodeRefusal && e.isRouteMissing(), "got $e")
    }

    @Test
    fun theProbeReads405AsMountedAndABare404AsMissing() {
        twoMachines(405, "") { _, node, client -> assertEquals(true, client.isPeeringRevokeMounted(node.url)) }
        twoMachines(404, "") { _, node, client -> assertEquals(false, client.isPeeringRevokeMounted(node.url)) }
        twoMachines(200, "<html></html>") { _, node, client -> assertNull(client.isPeeringRevokeMounted(node.url)) }
    }
}
