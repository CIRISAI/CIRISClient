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
        assertFalse(s.publish(OutgoingFrame(CallFrames.CODEC_OPAQUE, Layer.BASE, ByteArray(60))), "nothing before ready")
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"ready","frame_budget_bytes":244}""")); runCurrent()
        assertTrue(s.publish(OutgoingFrame(CallFrames.CODEC_OPAQUE, Layer.BASE, ByteArray(60))))
        assertFalse(s.publish(OutgoingFrame(CallFrames.CODEC_OPAQUE, Layer.BASE, ByteArray(245))))
        assertEquals(1, t.socket.sent.size)
        assertEquals(CallNotice.OverBudget(244), assertIs<CallState.InCall>(s.state.value).notice)
    }

    /** A codec change swaps the tile in place: no departure, and the new stream's frames are not a rejoin. */
    @Test fun aReplacedStreamKeepsTheTile() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = true); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Binary(frame("alice", CallFrames.CODEC_AV1_SVC, 1)))
        val newHex = stream(2).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"stream_replaced","member":"alice","old_stream_id":"x","new_stream_id":"$newHex"}"""))
        t.socket.inbox.send(CallSocketMessage.Binary(frame("alice", 0x04, 2)))
        runCurrent()
        val call = assertIs<CallState.InCall>(s.state.value)
        val alice = call.attendees.getValue("alice")
        assertTrue(alice.sendingVideo, "the tile carried on across the swap")
        assertTrue(alice.streamId!!.contentEquals(stream(2)))
        assertTrue(call.departed.isEmpty(), "nobody left")
    }

    /** From Edge 45.0.0 the node fragments: the budget is information, and a whole keyframe goes. */
    @Test fun aFragmentingNodeTakesAWholeFrame() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = true); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"ready","frame_budget_bytes":244,"fragmenting":true}""")); runCurrent()
        assertTrue(s.publish(OutgoingFrame(CallVideoCodecId, Layer.BASE, ByteArray(40_000))))
        assertEquals(1, t.socket.sent.size)
    }

    /** The server names the person beside the node; the tile is grouped and named by the person. */
    @Test fun theEventNamesThePersonBesideTheNode() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"attendee_joined","member":"node-bob","person":"bob-person"}"""))
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"attendee_joined","member":"node-dan","person":null}"""))
        runCurrent()
        val call = assertIs<CallState.InCall>(s.state.value)
        assertEquals("bob-person", call.attendees.getValue("node-bob").person)
        assertNull(call.attendees.getValue("node-dan").person, "unbound: someone in this room")
    }

    /**
     * CIRISServer 0.5.246: video only while the call has at most two people. Over
     * that the node refuses every video frame, so none is sent, audio carries on,
     * and the state says why. Back at two, video goes again.
     */
    @Test fun aCallOverTheVideoLimitSendsAudioOnlyAndSaysSo() = runTest {
        val answer = CallJoinAnswer.parse(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"call_id":"c","stream_id":"s","epoch":1,"frame_budget_bytes":244,"attendees":["alice","bob"],
                   "video_allowed":false,"video_max_people":2}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        val t = FakeTransport(answer)
        val s = CallSession("r", t, backgroundScope)
        s.join(video = true); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"ready","frame_budget_bytes":244,"fragmenting":true}""")); runCurrent()
        var call = assertIs<CallState.InCall>(s.state.value)
        assertFalse(call.videoAllowed, "the join answer already said so")
        assertEquals(2, call.videoMaxPeople)
        assertFalse(s.publish(OutgoingFrame(CallFrames.CODEC_AV1_SVC, Layer.BASE, ByteArray(60))), "AV1 video")
        assertFalse(s.publish(OutgoingFrame(CallVideoCodecId, Layer.BASE, ByteArray(60))), "H.264 video")
        assertTrue(s.publish(OutgoingFrame(CallFrames.CODEC_OPAQUE, Layer.BASE, ByteArray(60))), "audio carries on")
        assertEquals(1, t.socket.sent.size, "only the audio frame reached the socket")

        // Someone left: the node says video is allowed again.
        t.socket.inbox.send(CallSocketMessage.Text(
            """{"type":"video_allowed","allowed":true,"people":2,"max_people":2,"reason_id":"call.video_two_people_only"}""",
        )); runCurrent()
        assertTrue(assertIs<CallState.InCall>(s.state.value).videoAllowed)
        assertTrue(s.publish(OutgoingFrame(CallFrames.CODEC_AV1_SVC, Layer.BASE, ByteArray(60))))

        // A third person again, and this time the event is lost: the refusal says the same.
        t.socket.inbox.send(CallSocketMessage.Text(
            """{"type":"publish_refused","reason":"video_two_people_only","reason_id":"call.video_two_people_only","max_people":2}""",
        )); runCurrent()
        call = assertIs<CallState.InCall>(s.state.value)
        assertFalse(call.videoAllowed)
        assertNull(call.notice, "it is the call's size, not a fault with the frame")
        assertFalse(s.publish(OutgoingFrame(CallFrames.CODEC_AV1_SVC, Layer.BASE, ByteArray(60))))
    }

    /** A node that says nothing about video has no limit; a value that is not true or false is not a ruling. */
    @Test fun withoutAWordFromTheNodeVideoIsAllowed() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = true); runCurrent()
        assertTrue(assertIs<CallState.InCall>(s.state.value).videoAllowed)
        val o = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"call_id":"c","stream_id":"s","epoch":3,"frame_budget_bytes":244,"attendees":[]}""",
        ) as kotlinx.serialization.json.JsonObject
        assertTrue(CallJoinAnswer.parse(o).videoAllowed)
        assertNull(CallJoinAnswer.parse(o).videoMaxPeople)
        assertNull(CallEvent.parse("""{"type":"video_allowed","allowed":"false","max_people":2}"""), "a string is not a boolean")
        assertNull(CallEvent.parse("""{"type":"video_allowed","max_people":2}"""))
        assertEquals(CallEvent.VideoAllowed(false, 2), CallEvent.parse("""{"type":"video_allowed","allowed":false,"people":3,"max_people":2}"""))
        // Any other refusal is still a notice.
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"publish_refused","reason":"frame_over_budget","budget":244}""")); runCurrent()
        val call = assertIs<CallState.InCall>(s.state.value)
        assertTrue(call.videoAllowed)
        assertEquals(CallNotice.PublishRefused("frame_over_budget", 244), call.notice)
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

private const val CallVideoCodecId = 0x04
