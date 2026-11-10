package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.calls.CallFrames
import ai.ciris.mobile.shared.calls.CallSession
import ai.ciris.mobile.shared.calls.CallState
import ai.ciris.mobile.shared.calls.Layer
import ai.ciris.mobile.shared.calls.LeaveReason
import ai.ciris.mobile.shared.calls.OutgoingFrame
import ai.ciris.mobile.shared.calls.ReceivedFrame
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The call routes over real sockets (CSD-134; CIRISServer release/0.5.246 d662d99a):
 * join by POST, the frames socket opened with the answer's single-use ticket,
 * nothing published before `ready`, one frame each way through [CallFrames], and
 * the close reason read as why this device left.
 */
class CallRoutesWireTest {
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0
    private val asked = ConcurrentLinkedQueue<String>()
    private val received = Channel<ByteArray>(Channel.UNLIMITED)
    private val closeWith = Channel<String>(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val bob = ReceivedFrame(CallFrames.CODEC_OPAQUE, Layer.BASE, "bob", ByteArray(32) { 7 }, 1uL, 1uL, byteArrayOf(0x78, 1, 2))

    @BeforeTest fun setUp() {
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                post("/v1/chat/{room}/call") {
                    asked += "POST ${call.parameters["room"]} ${call.receiveText()} auth=${call.request.headers["Authorization"] != null}"
                    call.respondText(
                        """{"call_id":"c1","stream_id":"${"ab".repeat(32)}","epoch":1,"frame_budget_bytes":244,
                           "service_tier":"full","attendees":["bob"],"legs":[],
                           "frames_socket":"/v1/chat/${call.parameters["room"]}/call/frames","frames_ticket":"t-123"}""",
                        ContentType.Application.Json,
                    )
                }
                delete("/v1/chat/{room}/call") {
                    asked += "DELETE ${call.parameters["room"]} left=${call.request.queryParameters["left"]}"
                    call.respondText("{}", ContentType.Application.Json)
                }
                webSocket("/v1/chat/{room}/call/frames") {
                    asked += "WS ticket=${call.request.queryParameters["ticket"]}"
                    outgoing.send(Frame.Text("""{"type":"ready","frame_budget_bytes":200}"""))
                    outgoing.send(Frame.Binary(true, CallFrames.encode(bob)))
                    val f = incoming.receive()
                    received.send(f.readBytes())
                    close(CloseReason(CloseReason.Codes.NORMAL, closeWith.receive()))
                }
            }
        }.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        server.stop(0, 0)
    }

    private val url get() = "http://127.0.0.1:$port"

    @Test fun aCallJoinsSendsAndHearsOverRealSockets() = runBlocking {
        val api = CIRISApiClient(url)
        val session = CallSession("room-1", api.callTransport(url, scope), scope)
        session.join(video = false)

        // Joined, ready at the socket's budget (which replaces the answer's), and Bob's frame on his tile.
        val ready = withTimeout(10_000) {
            session.state.first { it is CallState.InCall && it.ready && it.lastFrame != null }
        } as CallState.InCall
        assertEquals(200, ready.frameBudgetBytes, "ready's budget replaces the join answer's")
        assertTrue(ready.attendees.getValue("bob").sendingAudio)
        assertTrue(ready.lastFrame!!.payload.contentEquals(byteArrayOf(0x78, 1, 2)))

        // One Opus-sized frame out, byte for byte what the codec writes.
        val out = OutgoingFrame(CallFrames.CODEC_OPAQUE, Layer.BASE, ByteArray(60) { it.toByte() })
        assertTrue(session.publish(out))
        val got = withTimeout(10_000) { received.receive() }
        assertTrue(CallFrames.encode(out).contentEquals(got))

        // The node closes with a reason: that is why this device left.
        closeWith.send("removed")
        val left = withTimeout(10_000) { session.state.first { it is CallState.Left } }
        assertEquals(CallState.Left(LeaveReason.REMOVED), left)

        assertTrue(asked.any { it.startsWith("POST room-1 ") && "\"video\":false" in it }, "joined: $asked")
        assertTrue("WS ticket=t-123" in asked, "the socket opened with the single-use ticket: $asked")
    }

    /** Leaving because the app left the screen says so, so the others read "backgrounded". */
    @Test fun aBackgroundedLeaveTellsTheNodeWhy() = runBlocking {
        val session = CallSession("room-2", CIRISApiClient(url).callTransport(url, scope), scope)
        session.join(video = false)
        withTimeout(10_000) { session.state.first { it is CallState.InCall } }
        session.leave(LeaveReason.BACKGROUNDED)
        assertEquals(CallState.Left(LeaveReason.BACKGROUNDED), session.state.value)
        withTimeout(10_000) { while (asked.none { it.startsWith("DELETE room-2") }) kotlinx.coroutines.delay(50) }
        assertTrue("DELETE room-2 left=backgrounded" in asked, "$asked")
    }

    @Test fun aRefusalComesBackByItsId() = runBlocking {
        val refusing = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                post("/v1/chat/{room}/call") {
                    call.respondText(
                        """{"reason_id":"call.anonymous_mode","error":"Calls aren't available while this device is anonymous."}""",
                        ContentType.Application.Json, HttpStatusCode.Forbidden,
                    )
                }
            }
        }.start(wait = false)
        try {
            val p = refusing.engine.resolvedConnectors().first().port
            val u = "http://127.0.0.1:$p"
            val session = CallSession("r", CIRISApiClient(u).callTransport(u, scope), scope)
            session.join(video = true)
            val s = withTimeout(10_000) { session.state.first { it is CallState.Refused } }
            assertEquals(CallState.Refused("call.anonymous_mode"), assertIs<CallState.Refused>(s))
        } finally {
            refusing.stop(0, 0)
        }
    }
}
