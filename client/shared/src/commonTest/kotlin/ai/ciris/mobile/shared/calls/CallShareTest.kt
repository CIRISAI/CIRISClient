package ai.ciris.mobile.shared.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Sharing a screen or window as its own stream (CSD-134). */
@OptIn(ExperimentalCoroutinesApi::class)
class CallShareTest {
    private class Sock : CallSocket {
        val inbox = Channel<CallSocketMessage>(Channel.UNLIMITED)
        val sent = mutableListOf<ByteArray>()
        override val incoming: Flow<CallSocketMessage> = inbox.receiveAsFlow()
        override fun send(bytes: ByteArray) { sent += bytes }
        override fun close() { inbox.close() }
    }

    private open class NoShare : CallTransport {
        override suspend fun join(room: String, video: Boolean) = error("unused")
        override suspend fun leave(room: String) = Unit
        override suspend fun openFrames(room: String) = error("unused")
    }

    private class Sharing : NoShare() {
        val sock = Sock()
        var stopped = 0
        override suspend fun startShare(room: String) = CallShareAnswer("ab".repeat(32), 244)
        override suspend fun openShareFrames(room: String): CallSocket = sock
        override suspend fun stopShare(room: String) { stopped++ }
    }

    /** A node before Edge 45.1 has no share route: the refusal is said by name. */
    @Test fun aNodeWithoutTheRouteRefusesByName() = runTest {
        val share = CallShare("r", NoShare(), backgroundScope)
        share.start(); runCurrent()
        assertEquals(CallShare.State.Refused(CallTransport.SHARE_UNAVAILABLE), share.state.value)
    }

    @Test fun aShareSendsOnItsOwnSocketAndStops() = runTest {
        val t = Sharing()
        val share = CallShare("r", t, backgroundScope)
        share.start(); runCurrent()
        assertIs<CallShare.State.Sharing>(share.state.value)
        val small = OutgoingFrame(0x04, Layer.BASE, ByteArray(100))
        val big = OutgoingFrame(0x04, Layer.BASE, ByteArray(5_000))
        assertTrue(share.publish(small))
        assertFalse(share.publish(big), "over the budget before the node fragments")
        t.sock.inbox.send(CallSocketMessage.Text("""{"type":"ready","frame_budget_bytes":244,"fragmenting":true}""")); runCurrent()
        assertTrue(share.publish(big), "a fragmenting node takes a whole frame")
        assertEquals(2, t.sock.sent.size)
        share.stop(); runCurrent()
        assertEquals(1, t.stopped)
        assertEquals(CallShare.State.Stopped, share.state.value)
    }

    /** Receivers learn of a share on the main socket: a second tile, keyed by its stream. */
    @Test fun aMembersShareIsItsOwnTile() {
        val e = CallEvent.parse("""{"type":"share_started","member":"node-ada","stream_id":"${"cd".repeat(32)}","person":"ada"}""")
        assertEquals(CallEvent.ShareStarted("node-ada", "cd".repeat(32), "ada"), e)
        assertEquals(CallEvent.ShareStopped("node-ada", "cd".repeat(32)), CallEvent.parse("""{"type":"share_stopped","member":"node-ada","stream_id":"${"cd".repeat(32)}","left":"left"}"""))
    }
}
