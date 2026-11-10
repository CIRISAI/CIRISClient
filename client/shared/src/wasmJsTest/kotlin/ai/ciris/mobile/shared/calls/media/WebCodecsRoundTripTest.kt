package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.Layer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real WebCodecs in the test browser (ChromeHeadless under Karma): what goes in
 * comes out, and what goes on the wire is in [CallVideoCodec]'s form. A browser
 * without WebCodecs skips (and says so) rather than passing on nothing.
 */
class WebCodecsRoundTripTest {
    private suspend fun <T> real(ms: Long, block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(ms) { block() } }

    @Test
    fun opusRoundTripKeepsTheTone() = runTest {
        if (!opusAvailable) { println("SKIP: no WebCodecs audio in this browser"); return@runTest }
        val n = 50
        val enc = createOpusEncoder()
        val packets = mutableListOf<ByteArray>()
        val encoded = CompletableDeferred<Unit>()
        var phase = 0.0
        repeat(n) {
            val pcm = ShortArray(CallAudioFormat.FRAME_SAMPLES) {
                val v = (sin(phase) * 8000).toInt().toShort(); phase += 2 * PI * 440 / 48_000; v
            }
            enc.encode(pcm) { p -> packets += p; if (packets.size == n) encoded.complete(Unit) }
        }
        real(10_000) { encoded.await() }
        enc.close()
        assertTrue(packets.all { it.size in 1..CallAudioFormat.MAX_PACKET_BYTES }, "packet sizes ${packets.map { it.size }}")
        val kbps = packets.sumOf { it.size } * 8 / (n * 20)
        println("opus: ${packets.size} packets, ~$kbps kbit/s")

        val dec = createOpusDecoder()
        val out = mutableListOf<ShortArray>()
        val decoded = CompletableDeferred<Unit>()
        val total = n + 1
        packets.forEachIndexed { i, p ->
            if (i == 25) dec.decode(null) { out += it; if (out.size == total) decoded.complete(Unit) } // one lost packet, in order
            dec.decode(p) { out += it; if (out.size == total) decoded.complete(Unit) }
        }
        real(10_000) { decoded.await() }
        dec.close()
        assertTrue(out.all { it.size == CallAudioFormat.FRAME_SAMPLES })
        assertTrue(out[25].all { it == 0.toShort() }, "concealment is silence, at its place in order")
        // Zero crossings over the last 20 decoded frames (400 ms) ≈ 2 × 440 × 0.4.
        val tail = out.takeLast(20).flatMap { it.toList() }
        val crossings = tail.zipWithNext().count { (a, b) -> (a < 0) != (b < 0) }
        assertTrue(abs(crossings - 352) < 30, "crossings=$crossings")
    }

    @Test
    fun h264RoundTripIsAnnexBWithParameterSetsOnKeyframes() = runTest { videoRoundTrip(EncoderChoice.H264) }

    @Test
    fun h264WithTemporalLayersRoundTrips() = runTest { videoRoundTrip(EncoderChoice.H264_L1T2) }

    @Test
    fun av1RoundTripIsLowOverheadObusWithSequenceHeaderOnKeyframes() = runTest { videoRoundTrip(EncoderChoice.AV1_L1T2) }

    @Test
    fun probedEncoderIsAvailable() = runTest {
        if (!videoAvailable()) return@runTest
        real(10_000) { prepareWebVideo() }
        val enc = assertNotNull(createWebVideoEncoder(320, 180, 15, 300_000))
        println("probed encoder: ${enc.codecName} (0x${enc.codecId.toString(16)})")
        enc.close()
    }

    private fun videoAvailable(): Boolean {
        val ok = createVideoDecoder(CallVideoCodec.TEMPORAL_LAYERED)?.also { it.close() } != null
        if (!ok) println("SKIP: no WebCodecs video in this browser")
        return ok
    }

    private suspend fun videoRoundTrip(choice: EncoderChoice) {
        if (!videoAvailable()) return
        real(10_000) { prepareWebVideo() }
        val src = TestPatternSource(kotlinx.coroutines.MainScope(), 320, 180, 15)
        val frames = List(30) { src.nextFrame() }
        val enc = WebVideoEncoder(choice, 320, 180, 15, 400_000)
        val chunks = mutableListOf<EncodedChunk>()
        // Paced like a camera: a burst is (rightly) thinned by the encoder's realtime backpressure.
        real(20_000) {
            frames.forEachIndexed { i, f ->
                enc.encode(f, forceKeyframe = i == 0 || i == 15) { c -> chunks += c }
                delay(40)
            }
            while (chunks.size < frames.size - 2) delay(20)
        }
        enc.close()
        val keyframes = chunks.withIndex().filter { it.value.keyframe }.map { it.index }
        println("${choice.codecName}: ${chunks.size}/${frames.size} chunks, ${chunks.sumOf { it.bytes.size }} bytes, keyframes at $keyframes, layers ${chunks.map { it.layer.temporal }}")
        assertTrue(chunks.first().keyframe)
        assertTrue(keyframes.size >= 2, "the forced keyframe at frame 15 came out")
        if (choice.codecId == CallVideoCodec.AV1_SVC) assertTrue(chunks.any { it.layer.temporal == 1 }, "L1T2 has a second temporal layer")
        for (c in chunks) {
            if (choice.codecId == CallVideoCodec.TEMPORAL_LAYERED) {
                assertTrue(AnnexB.startsWithStartCode(c.bytes))
                val types = AnnexB.split(c.bytes).map(AnnexB::type)
                if (c.keyframe) assertTrue(AnnexB.NAL_SPS in types && AnnexB.NAL_PPS in types && AnnexB.NAL_IDR in types, "keyframe NALs $types")
                assertEquals(c.keyframe, AnnexB.isKeyframe(c.bytes))
            } else {
                val units = assertNotNull(Obu.parse(c.bytes), "parses as OBUs")
                assertTrue(units.all { (it.header[0].toInt() and 0x02) != 0 }, "every OBU has a size field")
                if (c.keyframe) assertTrue(units.any { it.type == Obu.SEQUENCE_HEADER }, "keyframe OBUs ${units.map { it.type }}")
            }
        }

        val dec = assertNotNull(createVideoDecoder(choice.codecId))
        val out = mutableListOf<VideoFrame>()
        val decoded = CompletableDeferred<Unit>()
        for (c in chunks) dec.decode(c.layer, c.bytes) { f -> out += f; if (out.size == chunks.size) decoded.complete(Unit) }
        real(20_000) { decoded.await() }
        dec.close()
        val last = out.last()
        assertEquals(320, last.width)
        assertEquals(180, last.height)
        // The first bar is luma 235 and the last 16: both survive a lossy round trip roughly.
        val first = last.y[90 * 320 + 5].toInt() and 0xFF
        val lastBar = last.y[90 * 320 + 315].toInt() and 0xFF
        assertTrue(first > 200 && lastBar < 50, "bar luma $first / $lastBar")
        assertEquals(Layer(0, 0, 0), chunks.first().layer)
    }
}
