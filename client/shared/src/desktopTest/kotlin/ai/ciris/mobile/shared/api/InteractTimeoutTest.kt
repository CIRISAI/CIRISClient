package ai.ciris.mobile.shared.api

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Run 41885890061, windows-ai: the agent took 31.5 s, the client gave up at
 * 30 s. Driven against a real socket, with the base timeout scaled down, so
 * the plugin is proven by what actually times out.
 */
class InteractTimeoutTest {
    private lateinit var server: HttpServer

    @BeforeTest fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/") { ex ->
            Thread.sleep(600)
            val body = "answered".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest fun tearDown() = server.stop(0)

    private fun client() = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 200
            socketTimeoutMillis = 200
        }
        install(InteractOutlastsTheAgent)
    }

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Test fun the_wait_is_longer_than_the_agents() {
        assertTrue(INTERACT_TIMEOUT_MS > AGENT_INTERACT_WAIT_MS)
    }

    @Test fun interact_outlasts_a_reply_slower_than_the_base_timeout() = runBlocking {
        client().use { assertEquals("answered", it.post("$base/v1/agent/interact").bodyAsText()) }
    }

    @Test fun every_other_route_keeps_the_base_timeout() = runBlocking {
        client().use { c ->
            val thrown = assertFailsWith<Exception> { c.get("$base/v1/agent/history").bodyAsText() }
            assertTrue(thrown is HttpRequestTimeoutException || thrown is java.net.SocketTimeoutException ||
                thrown::class.simpleName.orEmpty().contains("Timeout"), "got $thrown")
        }
    }
}
