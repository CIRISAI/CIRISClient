package ai.ciris.mobile.shared.calls.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The receive side of call audio: the playout buffer, concealment by time, and a new stream resetting a decoder. */
class CallAudioTest {

    private class Recording : AudioSink {
        val played = mutableListOf<Pair<String, Boolean>>() // publisher to "was concealed"
        override fun play(publisher: String, pcm: ShortArray) { played += publisher to (pcm[0] == CONCEALED) }
        override fun remove(publisher: String) = Unit
        override fun stop() = Unit
    }

    private class MarkingDecoder(val closed: MutableList<Int>, val id: Int) : OpusDecoder {
        override fun decode(packet: ByteArray?, onPcm: (ShortArray) -> Unit) =
            onPcm(ShortArray(CallAudioFormat.FRAME_SAMPLES) { if (packet == null) CONCEALED else 1 })
        override fun close() { closed += id }
    }

    private fun audio(sink: Recording, closed: MutableList<Int> = mutableListOf()): CallAudio {
        var n = 0
        return CallAudio(
            session = ai.ciris.mobile.shared.calls.CallSession("r", NoTransport, kotlinx.coroutines.GlobalScope),
            source = object : AudioSource { override fun start(onFrame: (ShortArray) -> Unit) = Unit; override fun stop() = Unit },
            sink = sink,
            scope = kotlinx.coroutines.GlobalScope,
            newEncoder = { error("not used") },
            newDecoder = { MarkingDecoder(closed, n++) },
        )
    }

    private val s1 = ByteArray(32) { 1 }
    private val s2 = ByteArray(32) { 2 }

    private val packet = byteArrayOf(0x78)

    /** Sound starts once the playout buffer has its target, not on the first packet. */
    @Test fun nothingPlaysUntilTheBufferHasFilled() {
        val sink = Recording()
        val a = audio(sink)
        a.receive("ada", s1, 1u, packet)
        a.tick()
        assertTrue(sink.played.isEmpty(), "one packet is not yet a steady stream")
        a.receive("ada", s1, 2u, packet)
        a.tick()
        assertEquals(listOf("ada" to false), sink.played)
    }

    /** One packet leaves per tick, however they arrived: a burst is not played as a burst. */
    @Test fun aBurstPlaysOnePacketPerTick() {
        val sink = Recording()
        val a = audio(sink)
        for (seq in 1..4) a.receive("ada", s1, seq.toULong(), packet)
        a.tick()
        assertEquals(1, sink.played.size)
        repeat(3) { a.tick() }
        assertEquals(listOf(false, false, false, false), sink.played.map { it.second })
    }

    /** Nothing arrived in time: that turn is concealed when it would have played. */
    @Test fun anEmptyTurnIsConcealed() {
        val sink = Recording()
        val a = audio(sink)
        a.receive("ada", s1, 1u, packet)
        a.receive("ada", s1, 2u, packet)
        repeat(3) { a.tick() }
        assertEquals(listOf(false, false, true), sink.played.map { it.second })
    }

    /** A long silence is not a run of invented sound: concealment stops, then the buffer fills again. */
    @Test fun concealmentIsCappedAndThenQuiet() {
        val sink = Recording()
        val a = audio(sink)
        a.receive("ada", s1, 1u, packet)
        a.receive("ada", s1, 2u, packet)
        repeat(2 + 50) { a.tick() }
        assertEquals(CallAudio.MAX_CONCEALED, sink.played.count { it.second })
        assertEquals(2 + CallAudio.MAX_CONCEALED, sink.played.size, "after the cap, nothing is played")
        a.receive("ada", s1, 3u, packet)
        a.tick()
        assertEquals(2 + CallAudio.MAX_CONCEALED, sink.played.size, "it fills again before the next word")
    }

    /**
     * A publisher's audio and video share one stream and one `chunk_seq`, so the
     * counter jumps between two audio packets whenever a video frame went between
     * them. That is not lost sound, and nothing is concealed for it.
     */
    @Test fun aVideoFrameInTheStreamIsNotLostSound() {
        val sink = Recording()
        val a = audio(sink)
        a.receive("ada", s1, 1u, packet)
        a.receive("ada", s1, 2u, packet)
        a.receive("ada", s1, 9u, packet) // 3..8 were a keyframe's chunks
        repeat(3) { a.tick() }
        assertEquals(listOf(false, false, false), sink.played.map { it.second })
    }

    @Test fun aRepeatedOrOlderFrameIsDropped() {
        val sink = Recording()
        val a = audio(sink)
        a.receive("ada", s1, 5u, packet)
        a.receive("ada", s1, 6u, packet)
        a.receive("ada", s1, 6u, packet)
        a.receive("ada", s1, 3u, packet)
        repeat(4) { a.tick() }
        assertEquals(2, sink.played.count { !it.second })
    }

    /** A rejoin or a replaced stream is a fresh decoder and a fresh buffer. */
    @Test fun aNewStreamResetsTheDecoder() {
        val sink = Recording()
        val closed = mutableListOf<Int>()
        val a = audio(sink, closed)
        a.receive("ada", s1, 10u, packet)
        a.receive("ada", s2, 1u, packet)
        assertEquals(listOf(0), closed, "the old stream's decoder is closed")
        a.tick()
        assertTrue(sink.played.isEmpty(), "the old stream's packet does not count toward the new one")
    }

    @Test fun eachPublisherHasItsOwnBuffer() {
        val sink = Recording()
        val a = audio(sink)
        a.receive("ada", s1, 1u, packet)
        a.receive("sam", s2, 7u, packet)
        a.receive("ada", s1, 2u, packet)
        a.tick()
        assertEquals(listOf("ada" to false), sink.played, "sam has one packet waiting, ada has two")
    }

    @Test fun aMemberWhoLeftIsForgotten() {
        val sink = Recording()
        val closed = mutableListOf<Int>()
        val a = audio(sink, closed)
        a.receive("ada", s1, 1u, packet)
        a.receive("ada", s1, 2u, packet)
        a.forgetNow("ada")
        a.tick()
        assertEquals(listOf(0), closed)
        assertTrue(sink.played.isEmpty())
    }

    /** A stall that ends in a burst is delay nobody asked for: the buffer skips ahead to its target. */
    @Test fun aLongBurstSkipsAheadToTheTarget() {
        val p = AudioPlayout()
        repeat(AudioPlayout.MAX_DEPTH) { p.offer(byteArrayOf(it.toByte())) }
        assertEquals(AudioPlayout.MAX_DEPTH, p.depth)
        p.offer(byteArrayOf(99))
        assertEquals(AudioPlayout.TARGET_DEPTH, p.depth)
        val first = p.next() as AudioPlayout.Step.Packet
        assertEquals(9, first.bytes[0].toInt(), "the newest packets are the ones kept")
    }

    /** Delay standing over the target is given back, 20 ms a second, not kept for the whole call. */
    @Test fun standingDelayIsGivenBack() {
        val p = AudioPlayout()
        repeat(AudioPlayout.TARGET_DEPTH + 3) { p.offer(byteArrayOf(0)) }
        val before = p.depth
        repeat(AudioPlayout.LEAK_AFTER_TICKS) {
            p.offer(byteArrayOf(0))
            assertTrue(p.next() is AudioPlayout.Step.Packet)
        }
        assertEquals(before - 1, p.depth)
    }

    @Test fun theToneIsTwentyMillisecondsOfFortyEightKilohertz() {
        val f = ToneSource(kotlinx.coroutines.GlobalScope).nextFrame()
        assertEquals(960, f.size)
        assertTrue(f.any { it > 7_000 } && f.any { it < -7_000 })
    }

    private object NoTransport : ai.ciris.mobile.shared.calls.CallTransport {
        override suspend fun join(room: String, video: Boolean) = error("not used")
        override suspend fun leave(room: String) = Unit
        override suspend fun openFrames(room: String) = error("not used")
    }

    companion object {
        const val CONCEALED: Short = -1
    }
}
