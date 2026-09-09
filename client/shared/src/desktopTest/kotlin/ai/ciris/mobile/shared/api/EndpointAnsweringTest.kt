package ai.ciris.mobile.shared.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Liveness is "did anything answer", and it is not a question about state.**
 *
 * CIRISClient#52. The post-setup reconfigure hold asked `isNodeReachable`, which
 * asked `isLocalNodeUp`, which asked **`/v1/identity`** — the node's identity
 * aggregate. That is a question about what the node HAS, used to decide whether
 * the node IS. On the slowest desktop it never answered the way the hold needed,
 * and from the 2026-09-09 nightly, in one continuous loop:
 *
 *   * ONE entry into the hold, 18 status re-asserts (~180 polls, three minutes),
 *   * ZERO routes, and the loop never reached its own 240-poll timeout,
 *   * while the backend supervisor, polling `/health` on the SAME node, logged
 *     "Server already running and healthy at http://127.0.0.1:4243" twice inside
 *     that window.
 *
 * Two probes, one node, opposite answers, and the app sat on "Restarting your
 * node…" forever.
 *
 * These bind real sockets rather than fake the HTTP layer, for the reason
 * ProbeContractTest gives next door: the exact behaviour on a refused connection
 * versus a served error is the whole contract, and a fake would simply agree
 * with whatever the author assumed.
 */
class EndpointAnsweringTest {

    /** A port nothing is listening on: bind it, learn the number, release it. */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    private fun serving(status: Int, body: String = "{}"): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    @Test
    fun a_2xx_means_the_node_is_answering() = runBlocking {
        val server = serving(200, """{"status":"ok"}""")
        try {
            val url = "http://127.0.0.1:${server.address.port}/health"
            assertTrue(CIRISApiClient(url).isEndpointAnswering(url))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun a_NON_2xx_ALSO_means_the_node_is_answering() = runBlocking {
        // THE CASE THAT FIXES #52, and the one an author is most likely to get
        // wrong. A 404 means something received the request, parsed it, and
        // replied — the socket is up and the node is listening. Treating it as
        // "not reachable" is how a hold waits out a node that is plainly there,
        // and it is why this probe must not be the same function as one that
        // asks whether a particular route is happy.
        val server = serving(404, """{"error":"no such route"}""")
        try {
            val url = "http://127.0.0.1:${server.address.port}/health"
            assertTrue(
                CIRISApiClient(url).isEndpointAnswering(url),
                "a served 404 proves something is listening; only transport failure is death",
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun a_503_from_a_node_still_coming_up_means_it_is_answering() = runBlocking {
        // A node mid-boot serves 503 on some routes. It is emphatically alive,
        // and a hold waiting for it to come back must exit.
        val server = serving(503)
        try {
            val url = "http://127.0.0.1:${server.address.port}/health"
            assertTrue(CIRISApiClient(url).isEndpointAnswering(url))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun a_closed_port_is_not_answering() = runBlocking {
        // The other half: this must still be able to say NO, or the hold would
        // exit immediately against a node that never came back and the caller
        // would route into a dead backend.
        val url = "http://127.0.0.1:${deadPort()}/health"
        assertFalse(CIRISApiClient(url).isEndpointAnswering(url))
    }
}
