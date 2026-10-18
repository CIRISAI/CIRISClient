package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.MessageType
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Chat's SHED state** (CIRISClient#173). [InteractViewModel] against a real
 * socket that sheds `POST /v1/agent/interact` (agent 2.15+): a countdown named
 * by `shedState`, the same message resent when it elapses, and — when the
 * bounded resend gives up — the user's text back in the composer with Retry,
 * never a generic error bubble and never a duplicate.
 *
 * The wait itself is replaced ([InteractViewModel.shedSleep]) so the countdown
 * is observed tick by tick without a clock; `AgentShedWireTest` proves the
 * real wait honours `Retry-After`.
 */
class InteractShedTest {

    private class Agent(private val sheds: Int, private val retryAfter: Int) {
        val bodies: MutableList<String> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.use { it.readBytes().decodeToString() }
                val (status, out, headers) = if (ex.requestURI.path == "/v1/agent/interact") {
                    bodies += body
                    if (bodies.size <= sheds) {
                        Triple(
                            503,
                            """{"detail":{"error":"resource_shed","reason":"RESOURCE_SHED","resources":["memory_mb"],"retry_after_seconds":$retryAfter}}""",
                            mapOf("Retry-After" to "$retryAfter"),
                        )
                    } else {
                        Triple(200, """{"data":{"message_id":"srv-1","response":"I am here","state":"WORK","processing_time_ms":5}}""", emptyMap())
                    }
                } else {
                    Triple(404, """{"detail":"Not Found"}""", emptyMap())
                }
                val bytes = out.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private suspend fun InteractViewModel.settled() = withContext(Dispatchers.Default) {
        withTimeout(15_000) { isSending.first { !it } }
    }

    @Test
    fun theCountdownIsNamedAndTheSameMessageIsAnsweredOnceItElapses() = runBlocking {
        val agent = Agent(sheds = 1, retryAfter = 3)
        try {
            val vm = InteractViewModel(CIRISApiClient(agent.url, "tok"))
            val seen = CopyOnWriteArrayList<ChatShedState>()
            vm.shedSleep = { seen += vm.shedState.value }
            vm.onInputTextChanged("are you there?")
            vm.sendMessage()
            withContext(Dispatchers.Default) { withTimeout(15_000) { vm.messages.first { m -> m.any { it.type == MessageType.AGENT } } } }
            vm.settled()

            assertEquals(
                listOf(ChatShedState.Waiting(3, 2, 3), ChatShedState.Waiting(2, 2, 3), ChatShedState.Waiting(1, 2, 3)),
                seen.toList(),
                "the countdown ticks down from Retry-After",
            )
            assertEquals(ChatShedState.None, vm.shedState.value)
            assertEquals(2, agent.bodies.size)
            assertEquals(agent.bodies[0], agent.bodies[1])
            val users = vm.messages.value.filter { it.type == MessageType.USER }
            assertEquals(1, users.size, "one user bubble, not one per send: ${vm.messages.value}")
            assertEquals("srv-1", users.single().id, "reconciled to the id the agent accepted")
            assertTrue(vm.messages.value.none { it.type == MessageType.SYSTEM }, "a shed is not an error bubble: ${vm.messages.value}")
        } finally {
            agent.server.stop(0)
        }
    }

    @Test
    fun threeShedsGiveUpWithTheTextKeptAndRetrySendsItAgain() = runBlocking {
        val agent = Agent(sheds = 3, retryAfter = 1)
        try {
            val vm = InteractViewModel(CIRISApiClient(agent.url, "tok"))
            vm.shedSleep = {}
            vm.onInputTextChanged("keep me")
            vm.sendMessage()
            withContext(Dispatchers.Default) { withTimeout(15_000) { vm.shedState.first { it is ChatShedState.GaveUp } } }
            vm.settled()

            assertEquals(ChatShedState.GaveUp(3), vm.shedState.value)
            assertEquals(3, agent.bodies.size, "bounded at three sends")
            assertEquals("keep me", vm.inputText.value, "the user's text is back in the composer")
            assertTrue(vm.messages.value.none { it.type == MessageType.USER }, "an unsent message is not in the transcript: ${vm.messages.value}")
            assertTrue(vm.messages.value.none { it.type == MessageType.SYSTEM }, "no generic error bubble: ${vm.messages.value}")

            // Retry: the fourth request is answered (the agent stopped shedding).
            vm.retryAfterShed()
            withContext(Dispatchers.Default) { withTimeout(15_000) { vm.messages.first { m -> m.any { it.type == MessageType.AGENT } } } }
            assertEquals(4, agent.bodies.size)
            assertTrue(agent.bodies[3].contains("keep me"))
            assertEquals(ChatShedState.None, vm.shedState.value)
            assertEquals(1, vm.messages.value.count { it.type == MessageType.USER })
        } finally {
            agent.server.stop(0)
        }
    }
}
