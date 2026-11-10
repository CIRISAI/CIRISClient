package ai.ciris.mobile.shared.calls.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The receive side of call audio: concealment of gaps, and a new stream resetting a decoder. */
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

    @Test fun inOrderFramesAreDecodedNotConcealed() {
        val sink = Recording()
        val a = audio(sink)
        a.receive("ada", s1, 1u, byteArrayOf(0x78))
        a.receive("ada", s1, 2u, byteArrayOf(0x78))
        assertEquals(listOf("ada" to false, "ada" to false), sink.played)
    }

    /** Edge drops a chunk at its deadline and never re-fills it: each missing 20 ms is concealed. */
    @Test fun aGapIsConcealedOneFrameAtATime() {
        val sink = Recording()
        val a = audio(sink)
        a.receive("ada", s1, 1u, byteArrayOf(0x78))
        a.receive("ada", s1, 4u, byteArrayOf(0x78))
        assertEquals(listOf(false, true, true, false), sink.played.map { it.second })
    }

    /** A long silence is not a burst of invented sound. */
    @Test fun concealmentIsCapped() {
        val sink = Recording()
        val a = audio(sink)
        a.receive("ada", s1, 1u, byteArrayOf(0x78))
        a.receive("ada", s1, 1000u, byteArrayOf(0x78))
        assertEquals(CallAudio.MAX_CONCEALED, sink.played.count { it.second })
    }

    @Test fun aLateOrRepeatedFrameIsDropped() {
        val sink = Recording()
        val a = audio(sink)
        a.receive("ada", s1, 5u, byteArrayOf(0x78))
        a.receive("ada", s1, 5u, byteArrayOf(0x78))
        a.receive("ada", s1, 3u, byteArrayOf(0x78))
        assertEquals(1, sink.played.size)
    }

    /** A rejoin or a replaced stream is a fresh decoder, not a gap from the old one. */
    @Test fun aNewStreamResetsTheDecoder() {
        val sink = Recording()
        val closed = mutableListOf<Int>()
        val a = audio(sink, closed)
        a.receive("ada", s1, 10u, byteArrayOf(0x78))
        a.receive("ada", s2, 1u, byteArrayOf(0x78))
        assertEquals(listOf(0), closed, "the old stream's decoder is closed")
        assertTrue(sink.played.none { it.second }, "no concealment across streams")
    }

    @Test fun eachPublisherHasItsOwnDecoder() {
        val sink = Recording()
        val a = audio(sink)
        a.receive("ada", s1, 1u, byteArrayOf(0x78))
        a.receive("sam", s2, 7u, byteArrayOf(0x78))
        a.receive("ada", s1, 2u, byteArrayOf(0x78))
        assertTrue(sink.played.none { it.second })
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
