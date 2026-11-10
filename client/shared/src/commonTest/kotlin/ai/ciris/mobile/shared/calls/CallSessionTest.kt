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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CSD-134: the call's people and reasons, from the join answer, events and frames. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionTest {

    private class FakeSocket : CallSocket {
        val inbox = Channel<CallSocketMessage>(Channel.UNLIMITED)
        val sent = mutableListOf<ByteArray>()
        var closed = false
        override val incoming: Flow<CallSocketMessage> = inbox.receiveAsFlow()
        override fun send(bytes: ByteArray) { sent += bytes }
        override fun close() { closed = true; inbox.close() }
    }

    private class FakeTransport(
        val answer: CallJoinAnswer = CallJoinAnswer("c1", "s-me", 1, 244, "full", listOf("alice")),
        val refuse: String? = null,
    ) : CallTransport {
        val socket = FakeSocket()
        var left = 0
        override suspend fun join(room: String, video: Boolean): CallJoinAnswer {
            refuse?.let { throw CallRefusal(it) }
            return answer
        }
        override suspend fun leave(room: String) { left++ }
        override suspend fun openFrames(room: String): CallSocket = socket
    }

    private fun stream(b: Int) = ByteArray(32) { b.toByte() }
    private fun frame(publisher: String, codec: Int, streamByte: Int) = CallFrames.encode(
        ReceivedFrame(codec, Layer.BASE, publisher, stream(streamByte), 1uL, 1uL, byteArrayOf(0x78)),
    )

    @Test fun aRefusalIsSaidByItsId() = runTest {
        val s = CallSession("r", FakeTransport(refuse = "call.anonymous_mode"), backgroundScope)
        s.join(video = false); runCurrent()
        assertEquals(CallState.Refused("call.anonymous_mode"), s.state.value)
    }

    @Test fun joiningListsWhoIsThereAndTheBudget() = runTest {
        val s = CallSession("r", FakeTransport(), backgroundScope)
        s.join(video = false); runCurrent()
        val call = assertIs<CallState.InCall>(s.state.value)
        assertEquals(setOf("alice"), call.attendees.keys)
        assertEquals(244, call.frameBudgetBytes)
    }

    @Test fun eachPublisherIsItsOwnTileAndALateJoinerAppears() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = true); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Binary(frame("alice", CallFrames.CODEC_OPAQUE, 1)))
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"attendee_joined","member":"carol","stream_id":"x"}"""))
        t.socket.inbox.send(CallSocketMessage.Binary(frame("carol", CallFrames.CODEC_AV1_SVC, 3)))
        runCurrent()
        val call = assertIs<CallState.InCall>(s.state.value)
        assertEquals(setOf("alice", "carol"), call.attendees.keys)
        assertTrue(call.attendees.getValue("alice").sendingAudio)
        assertFalse(call.attendees.getValue("alice").sendingVideo)
        assertTrue(call.attendees.getValue("carol").sendingVideo)
    }

    @Test fun aRejoinOnANewStreamIsANewTile() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = true); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Binary(frame("alice", CallFrames.CODEC_AV1_SVC, 1)))
        t.socket.inbox.send(CallSocketMessage.Binary(frame("alice", CallFrames.CODEC_OPAQUE, 2)))
        runCurrent()
        val alice = assertIs<CallState.InCall>(s.state.value).attendees.getValue("alice")
        assertTrue(alice.streamId!!.contentEquals(stream(2)))
        assertFalse(alice.sendingVideo, "the old stream's video does not carry over")
    }

    @Test fun whySomeoneLeftIsKeptAndAnUnknownWordIsLost() = runTest {
        val t = FakeTransport(answer = CallJoinAnswer("c1", "s", 1, 244, null, listOf("alice", "bob", "dan")))
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"attendee_left","member":"alice","stream_id":"a","left":"backgrounded"}"""))
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"attendee_left","member":"bob","stream_id":"b","left":"removed"}"""))
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"attendee_left","member":"dan","stream_id":"d","left":"evaporated"}"""))
        runCurrent()
        val call = assertIs<CallState.InCall>(s.state.value)
        assertTrue(call.attendees.isEmpty())
        assertEquals(
            mapOf("alice" to LeaveReason.BACKGROUNDED, "bob" to LeaveReason.REMOVED, "dan" to LeaveReason.LOST),
            call.departed,
        )
    }

    /** The close reason tells "you were removed" from "you left". */
    @Test fun beingRemovedIsNotLeaving() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Closed("removed")); runCurrent()
        assertEquals(CallState.Left(LeaveReason.REMOVED), s.state.value)
    }

    @Test fun leavingTellsTheNodeAndClosesTheSocket() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false); runCurrent()
        s.leave(); runCurrent()
        assertEquals(CallState.Left(LeaveReason.LEFT), s.state.value)
        assertEquals(1, t.left)
        assertTrue(t.socket.closed)
    }

    /** Over the budget a frame is not sent, and the state says so; it is never cut. */
    @Test fun aFrameOverTheBudgetIsNotSent() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false); runCurrent()
        assertTrue(s.publish(OutgoingFrame(CallFrames.CODEC_OPAQUE, Layer.BASE, ByteArray(60))))
        assertFalse(s.publish(OutgoingFrame(CallFrames.CODEC_OPAQUE, Layer.BASE, ByteArray(245))))
        assertEquals(1, t.socket.sent.size)
        assertEquals(CallNotice.OverBudget(244), assertIs<CallState.InCall>(s.state.value).notice)
    }

    @Test fun anUnknownEventIsSkipped() {
        assertNull(CallEvent.parse("""{"type":"something_new","member":"x"}"""))
        assertNull(CallEvent.parse("not json"))
    }

    @Test fun theJoinAnswerReadsAttendeesInEitherShape() {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"call_id":"c","stream_id":"s","epoch":3,"frame_budget_bytes":244,"service_tier":"full",
               "attendees":["alice",{"member":"bob"},{"key_id":"carol"}],"legs":[]}""",
        ) as kotlinx.serialization.json.JsonObject
        assertEquals(listOf("alice", "bob", "carol"), CallJoinAnswer.parse(o).attendees)
    }
}
