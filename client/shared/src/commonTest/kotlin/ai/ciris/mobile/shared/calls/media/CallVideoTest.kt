package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The shared half of call video: the test pattern, I420 to ARGB, and per-stream decoding. */
class CallVideoTest {
    @Test fun theTestPatternMovesFromFrameToFrame() {
        val p = TestPatternSource(kotlinx.coroutines.GlobalScope)
        val a = p.nextFrame()
        val b = p.nextFrame()
        assertEquals(320, a.width)
        assertEquals(180, a.height)
        assertTrue(!a.y.contentEquals(b.y), "the square swept")
    }

    @Test fun i420ToArgbKeepsWhiteWhiteAndBlackBlack() {
        val w = 2; val h = 2
        val white = VideoFrame(w, h, ByteArray(4) { 235.toByte() }, byteArrayOf(128.toByte()), byteArrayOf(128.toByte()), 0)
        val black = VideoFrame(w, h, ByteArray(4) { 16 }, byteArrayOf(128.toByte()), byteArrayOf(128.toByte()), 0)
        assertEquals(0xFFFFFFFF.toInt(), white.toArgb()[0])
        assertEquals(0xFF000000.toInt(), black.toArgb()[0])
    }

    private class CountingDecoder(val codec: Int, val log: MutableList<String>) : VideoDecoder {
        override fun decode(layer: Layer, bytes: ByteArray?): VideoFrame? {
            log += "decode $codec"
            return VideoFrame(2, 2, ByteArray(4), ByteArray(1), ByteArray(1), 0)
        }
        override fun close() { log += "close $codec" }
    }

    private fun video(log: MutableList<String>, rendered: MutableList<String>, decodable: Set<Int> = setOf(0x01, 0x04)) = CallVideo(
        session = ai.ciris.mobile.shared.calls.CallSession("r", NoTransport, kotlinx.coroutines.GlobalScope),
        source = null, encoder = null, scope = kotlinx.coroutines.GlobalScope,
        render = { pub, _, _ -> rendered += pub },
        newDecoder = { c -> if (c in decodable) CountingDecoder(c, log) else null },
        undecodable = { pub, c -> rendered += "undecodable $pub $c" },
    )

    /** A member's camera and their share are two streams, two decoders, two tiles. */
    @Test fun eachStreamHasItsOwnDecoder() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered)
        v.receive("ada", ByteArray(32) { 1 }, 0x01, Layer.BASE, byteArrayOf(1))
        v.receive("ada", ByteArray(32) { 2 }, 0x04, Layer.BASE, byteArrayOf(1))
        v.receive("ada", ByteArray(32) { 1 }, 0x01, Layer.BASE, byteArrayOf(1))
        assertEquals(listOf("decode 1", "decode 4", "decode 1"), log)
        assertEquals(3, rendered.size)
    }

    /** A codec this device cannot decode is said, never a black tile. */
    @Test fun anUndecodableStreamIsSaid() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered, decodable = setOf(0x01))
        v.receive("sam", ByteArray(32) { 3 }, 0x04, Layer.BASE, byteArrayOf(1))
        assertEquals(listOf("undecodable sam 4"), rendered)
    }

    /** A codec change on the same stream replaces its decoder. */
    @Test fun aCodecChangeReplacesTheDecoder() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered)
        val s = ByteArray(32) { 1 }
        v.receive("ada", s, 0x01, Layer.BASE, byteArrayOf(1))
        v.receive("ada", s, 0x04, Layer.BASE, byteArrayOf(1))
        assertEquals(listOf("decode 1", "close 1", "decode 4"), log)
        assertNotEquals(0, rendered.size)
    }

    private object NoTransport : ai.ciris.mobile.shared.calls.CallTransport {
        override suspend fun join(room: String, video: Boolean) = error("not used")
        override suspend fun leave(room: String) = Unit
        override suspend fun openFrames(room: String) = error("not used")
    }
}
