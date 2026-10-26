package ai.ciris.mobile.shared.api

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every agent-bound client sends `localhost` as the loopback address
 * (LoopbackLiteral.kt; the maintainer's phone could not resolve "localhost").
 * The Host header is the witness: it names the host the client connected to.
 */
class LoopbackHostTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val hosts = CopyOnWriteArrayList<String>()

    init {
        server.createContext("/") { ex ->
            hosts += ex.requestHeaders.getFirst("Host") ?: ""
            ex.sendResponseHeaders(200, -1); ex.close()
        }
        server.start()
    }

    @AfterTest fun tearDown() {
        server.stop(0)
        runBlocking { AgentRateGovernor.resetForTests() }
    }

    @Test fun a_request_to_localhost_goes_to_127_0_0_1() = runBlocking {
        val port = server.address.port
        HttpClient { install(AgentRateLimit) }.use { it.get("http://localhost:$port/v1/agent/status") }
        assertEquals(listOf("127.0.0.1:$port"), hosts.toList())
    }
}
