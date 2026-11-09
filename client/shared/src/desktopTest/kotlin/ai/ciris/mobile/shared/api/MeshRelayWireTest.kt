package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.viewmodels.RelayRefusal
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `POST /v1/mesh/relay` on a real socket, answering the way CIRISServer's relay does. */
class MeshRelayWireTest {
    private lateinit var server: HttpServer
    private val reply = AtomicReference(200 to "{}")
    private val asked = AtomicReference<String>()

    @BeforeTest fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/mesh/relay") { ex ->
            asked.set(ex.requestBody.readBytes().decodeToString())
            val (code, body) = reply.get()
            val b = body.toByteArray()
            ex.sendResponseHeaders(code, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
    }

    @AfterTest fun tearDown() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}"

    @Test fun theTargetsAnswerPassesThrough() = runBlocking {
        reply.set(200 to """{"data":{"uptime_s":1}}""")
        val a = CIRISApiClient(url).meshRelay("node-x", "GET", "/v1/federation/metrics", nodeUrl = url)
        assertEquals(200, a.status)
        assertEquals("""{"data":{"uptime_s":1}}""", a.body)
        assertTrue("\"target_key_id\":\"node-x\"" in asked.get() && "\"path\":\"/v1/federation/metrics\"" in asked.get())
    }

    @Test fun theRelaysOwnFailureIsARefusalWithItsToken() = runBlocking {
        reply.set(502 to """{"error":"mesh_target_unreachable: no path to node-x"}""")
        val e = assertFailsWith<RelayRefusal> {
            CIRISApiClient(url).meshRelay("node-x", "GET", "/v1/federation/metrics", nodeUrl = url)
        }
        assertEquals("mesh_target_unreachable", e.token)
        assertEquals(502, e.status)
    }

    @Test fun aRouteTheRelayRefusesIsARefusal() = runBlocking {
        reply.set(403 to """{"error":"GET /v1/memory/graph is not a relayable control op"}""")
        val e = assertFailsWith<RelayRefusal> {
            CIRISApiClient(url).meshRelay("node-x", "GET", "/v1/memory/graph", nodeUrl = url)
        }
        assertEquals(403, e.status)
    }
}
