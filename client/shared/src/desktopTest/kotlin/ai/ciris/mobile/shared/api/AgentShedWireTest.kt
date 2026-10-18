package ai.ciris.mobile.shared.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **A SHED 503 on the wire** (CIRISClient#173; CIRISAgent#1242, agent 2.15+).
 *
 * The real client against a real socket that answers `POST /v1/agent/interact`
 * the way `routes/agent.py` `_raise_if_shed` does on v2.16.2-stable: `503`,
 * `Retry-After: <seconds>`, and `{"detail": {"error": "resource_shed",
 * "reason": "RESOURCE_SHED", ...}}`. The refusal happens before any task
 * exists (`base_observer.handle_incoming_message`), which is why the SAME
 * request is sent again.
 */
class AgentShedWireTest {

    private class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    /** Answers the interact route from [script] in order; the last one repeats. */
    private class Agent(private val script: List<Reply>) {
        val bodies: MutableList<String> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.use { it.readBytes().decodeToString() }
                val reply = if (ex.requestURI.path == "/v1/agent/interact") {
                    bodies += body
                    script[minOf(bodies.size - 1, script.size - 1)]
                } else {
                    Reply(404, """{"detail":"Not Found"}""")
                }
                val bytes = reply.body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                reply.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                ex.sendResponseHeaders(reply.status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private fun shed(retryAfter: String) = Reply(
        503,
        """{"detail":{"error":"resource_shed","reason":"RESOURCE_SHED","message":"The agent is not admitting new work while a resource is under pressure. Retry later.","resources":["memory_mb"],"retry_after_seconds":30}}""",
        mapOf("Retry-After" to retryAfter),
    )

    private val ok = Reply(
        200,
        """{"data":{"message_id":"m-1","response":"hello back","state":"WORK","processing_time_ms":12}}""",
    )

    @Test
    fun aShedWaitsOutRetryAfterThenTheSameMessageGoesAgainAndIsAnswered() {
        val agent = Agent(listOf(shed("1"), ok))
        try {
            val client = CIRISApiClient(agent.url, "tok")
            val ticks = mutableListOf<ShedWait?>()
            val started = System.nanoTime()
            val reply = runBlocking {
                AgentShed.sendWithShedRetry(onWait = { ticks += it }) {
                    client.sendMessage("are you there?", "api_owner", null, null)
                }
            }
            val waitedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals("hello back", reply.response)
            assertEquals(2, agent.bodies.size, "one shed, one resend: ${agent.bodies}")
            assertEquals(agent.bodies[0], agent.bodies[1], "the resend must be the SAME request")
            assertTrue(agent.bodies[0].contains("are you there?"))
            assertTrue(waitedMs >= 1000, "Retry-After: 1 was not honoured (resent after ${waitedMs}ms)")
            assertEquals(listOf(ShedWait(1, 2, 3, listOf("memory_mb")), null), ticks)
        } finally {
            agent.server.stop(0)
        }
    }

    @Test
    fun threeShedsGiveUpWithoutAFourthSend() {
        val agent = Agent(listOf(shed("1")))
        try {
            val client = CIRISApiClient(agent.url, "tok")
            val gaveUp = assertFailsWith<AgentShedGaveUp> {
                runBlocking {
                    AgentShed.sendWithShedRetry { client.sendMessage("still there?", "api_owner", null, null) }
                }
            }
            assertEquals(3, gaveUp.attempts)
            assertEquals(3, agent.bodies.size, "bounded at three sends: ${agent.bodies.size}")
            assertEquals(AgentShedException.REASON_ID, gaveUp.lastShed.reasonId)
        } finally {
            agent.server.stop(0)
        }
    }

    @Test
    fun anHttpDateRetryAfterIsTheSecondsUntilThatDate() {
        val at = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(5)
        val agent = Agent(listOf(shed(DateTimeFormatter.RFC_1123_DATE_TIME.format(at))))
        try {
            val client = CIRISApiClient(agent.url, "tok")
            val e = assertFailsWith<AgentShedException> {
                runBlocking { client.sendMessage("x", "api_owner", null, null) }
            }
            assertTrue(e.retryAfterSeconds in 3..6, "HTTP-date 5 s ahead read as ${e.retryAfterSeconds}s")
            assertEquals(listOf("memory_mb"), e.resources)
        } finally {
            agent.server.stop(0)
        }
    }

    @Test
    fun aRetryAfterLongerThanTheBudgetGivesUpAtOnceInsteadOfWaiting() {
        val agent = Agent(listOf(shed("600")))
        try {
            val client = CIRISApiClient(agent.url, "tok")
            val started = System.nanoTime()
            assertFailsWith<AgentShedGaveUp> {
                runBlocking { AgentShed.sendWithShedRetry { client.sendMessage("x", "api_owner", null, null) } }
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "waited on a 600 s Retry-After")
            assertEquals(1, agent.bodies.size)
        } finally {
            agent.server.stop(0)
        }
    }

    @Test
    fun aNonShed503StaysAnErrorAndIsNotResent() {
        val agent = Agent(listOf(Reply(503, """{"detail":"Message handler not configured"}""")))
        try {
            val client = CIRISApiClient(agent.url, "tok")
            val e = assertFailsWith<RuntimeException> {
                runBlocking { AgentShed.sendWithShedRetry { client.sendMessage("x", "api_owner", null, null) } }
            }
            assertFalse(e is AgentShedException || e is AgentShedGaveUp, "a handler-missing 503 is not a shed: $e")
            assertTrue(e.message!!.contains("503"), e.message)
            assertEquals(1, agent.bodies.size)
        } finally {
            agent.server.stop(0)
        }
    }
}
