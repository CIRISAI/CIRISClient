package ai.ciris.mobile.shared.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ringing (CSD-134), against CIRISServer 0.5.255's vectors
 * (`tests/vectors/call_frames_v1.json` § ringing, dbd447cf).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RingingTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private val ringJson = """{"type":"call_ringing","room":"community-7f3a",
        "call_id":"5c1e0b7a9d2f4e6a8b0c1d2e3f405162738495a6b7c8d9e0f1a2b3c4d5e6f708",
        "by":{"member":"native-alice-node-q2w3e4","person":"alice-person-user-x9y8z7"},
        "video":false,"since_ms":1795000000000}"""

    @Test fun theVectorsRingParses() {
        val e = assertIs<RingEvent.Ringing>(RingEvent.parse("call_ringing", obj(ringJson)))
        assertEquals("community-7f3a", e.ring.room)
        assertEquals("native-alice-node-q2w3e4", e.ring.byMember)
        assertEquals("alice-person-user-x9y8z7", e.ring.byPerson)
        assertEquals(false, e.ring.video)
        assertEquals(1795000000000, e.ring.sinceMs)
    }

    @Test fun theEventNameOrTheTypeNamesTheFrame() {
        assertIs<RingEvent.Ringing>(RingEvent.parse(null, obj(ringJson)))
        val ended = assertIs<RingEvent.Ended>(
            RingEvent.parse("call_ring_ended", obj("""{"room":"r","call_id":"c","why":"answered_elsewhere"}""")),
        )
        assertEquals(RingEndReason.ANSWERED_ELSEWHERE, ended.why)
        assertEquals(RingEndReason.OTHER, RingEndReason.of("evaporated"))
        assertIs<RingEvent.Resync>(RingEvent.parse("resync", obj("""{"fetch":"/v1/calls/ringing"}""")))
        assertNull(RingEvent.parse("something_new", obj("""{}""")))
        // A ring with no caller is not a ring.
        assertNull(RingEvent.parse("call_ringing", obj("""{"room":"r","call_id":"c"}""")))
    }

    @Test fun ringingNowListsWhatRings() {
        val now = assertIs<RingEvent.Now>(RingEvent.parse("ringing_now", obj("""{"ringing":[$ringJson]}""")))
        assertEquals(listOf("community-7f3a"), now.ringing.map { it.room })
    }

    @Test fun aDeclineAnswerSaysWhetherTheCallerHeard() {
        assertEquals(
            DeclineAnswer.Declined(callerTold = true),
            DeclineAnswer.parse(obj("""{"room":"r","call_id":"c","reason":"declined","caller_told":true}""")),
        )
        assertEquals(DeclineAnswer.NotRinging, DeclineAnswer.parse(obj("""{"room":"r","declined":"not_ringing"}""")))
    }

    private class FakeRing : RingTransport {
        val declined = mutableListOf<Pair<String, DeclineReason>>()
        var now: List<IncomingRing> = emptyList()
        override suspend fun stream(onEvent: suspend (RingEvent) -> Unit) = Unit
        override suspend fun ringingNow() = now
        override suspend fun decline(room: String, reason: DeclineReason): DeclineAnswer {
            declined += room to reason
            return DeclineAnswer.Declined(true)
        }
    }

    private fun ring(room: String, call: String = "c-$room") = IncomingRing(room, call, "n-$room", "p-$room", false, null)

    @Test fun aRingIsHeldUntilItEnds() = runTest {
        val w = RingWatcher(FakeRing(), backgroundScope)
        w.apply(RingEvent.Ringing(ring("a")))
        w.apply(RingEvent.Ringing(ring("b")))
        assertEquals(listOf("a", "b"), w.ringing.value.map { it.room })
        w.apply(RingEvent.Ended("a", "c-a", RingEndReason.CANCELLED))
        assertEquals(listOf("b"), w.ringing.value.map { it.room })
        // An end for an older call in the same room does not stop the newer ring.
        w.apply(RingEvent.Ended("b", "c-old", RingEndReason.TIMEOUT))
        assertEquals(listOf("b"), w.ringing.value.map { it.room })
    }

    @Test fun theOpeningFrameReplacesWhatWasHeld() = runTest {
        val w = RingWatcher(FakeRing(), backgroundScope)
        w.apply(RingEvent.Ringing(ring("stale")))
        w.apply(RingEvent.Now(listOf(ring("fresh"))))
        assertEquals(listOf("fresh"), w.ringing.value.map { it.room })
    }

    @Test fun aResyncFetchesWhatRings() = runTest {
        val t = FakeRing().apply { now = listOf(ring("x")) }
        val w = RingWatcher(t, backgroundScope)
        w.apply(RingEvent.Resync)
        assertEquals(listOf("x"), w.ringing.value.map { it.room })
    }

    @Test fun declineGoesAtOnceAndSaysWhy() = runTest {
        val t = FakeRing()
        val w = RingWatcher(t, backgroundScope)
        w.apply(RingEvent.Ringing(ring("a")))
        w.decline("a", DeclineReason.BUSY)
        assertTrue(w.ringing.value.isEmpty())
        assertEquals(listOf("a" to DeclineReason.BUSY), t.declined)
        // The stream reopening with the ring still listed does not bring it back.
        w.apply(RingEvent.Now(listOf(ring("a"))))
        assertTrue(w.ringing.value.isEmpty())
        // A new ring in that room does.
        w.apply(RingEvent.Ringing(ring("a", "c-2")))
        assertEquals(listOf("c-2"), w.ringing.value.map { it.callId })
    }

    @Test fun answeringStopsTheRingHere() = runTest {
        val w = RingWatcher(FakeRing(), backgroundScope)
        w.apply(RingEvent.Ringing(ring("a")))
        w.answered("a")
        assertTrue(w.ringing.value.isEmpty())
    }

    @Test fun theToneIsTwoTonesThenQuiet() {
        val f = RingTone.frame(0)
        assertEquals(ai.ciris.mobile.shared.calls.media.CallAudioFormat.FRAME_SAMPLES, f.size)
        assertTrue(f.any { it != 0.toShort() })
    }

    // ── The caller's side: the frames socket's ringing, declined and no_answer ──

    private class FakeSocket : CallSocket {
        val inbox = Channel<CallSocketMessage>(Channel.UNLIMITED)
        override val incoming: Flow<CallSocketMessage> = inbox.receiveAsFlow()
        override fun send(bytes: ByteArray) = Unit
        override fun close() { inbox.close() }
    }

    private class FakeCall : CallTransport {
        val socket = FakeSocket()
        override suspend fun join(room: String, video: Boolean) = CallJoinAnswer("c1", "s-me", 1, 244, null, emptyList())
        override suspend fun leave(room: String) = Unit
        override suspend fun openFrames(room: String): CallSocket = socket
    }

    @Test fun theCallerSeesHowTheRingWent() = runTest {
        val t = FakeCall()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Text(
            """{"type":"ringing","members":[{"member":"native-bob","person":"bob","state":"ringing"},
               {"member":"native-cat","person":"cat","state":"unreachable"}]}""",
        ))
        runCurrent()
        assertEquals(
            mapOf("bob" to RungState.RINGING, "cat" to RungState.UNREACHABLE),
            assertIs<CallState.InCall>(s.state.value).rung,
        )
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"declined","member":"native-bob","person":"bob","reason":"busy"}"""))
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"no_answer","why":"declined"}"""))
        runCurrent()
        val call = assertIs<CallState.InCall>(s.state.value)
        assertEquals(RungState.BUSY, call.rung["bob"])
        assertEquals(NoAnswer.DECLINED, call.noAnswer)
    }

    @Test fun aRingNobodyAnsweredSaysSoAfterTheCallEnds() = runTest {
        val t = FakeCall()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"no_answer","why":"timeout"}"""))
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"call_ended","ended":"left"}"""))
        runCurrent()
        assertEquals(NoAnswer.TIMEOUT, assertIs<CallState.Left>(s.state.value).noAnswer)
    }

    /** The node's own end after 45 s (CIRISServer 0.5.255 `call_ended_values.no_answer`). */
    @Test fun theNodesNoAnswerEndIsNotALostCall() = runTest {
        val t = FakeCall()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"no_answer","why":"timeout"}"""))
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"call_ended","ended":"no_answer","reason_id":"call.no_answer"}"""))
        runCurrent()
        val left = assertIs<CallState.Left>(s.state.value)
        assertEquals(LeaveReason.NO_ANSWER, left.reason)
        assertEquals(NoAnswer.TIMEOUT, left.noAnswer)
    }

    @Test fun anUnknownRungStateIsOther() {
        assertEquals(RungState.OTHER, RungState.of("teleported"))
    }
}
