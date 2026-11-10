package ai.ciris.mobile.shared.calls.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Call video on a real MediaCodec (CSD-134): the shared test pattern goes through
 * this device's encoder, the payloads are checked against the payload format
 * from the bytes themselves, and they come back through this device's decoder
 * at the size they went in.
 */
@RunWith(AndroidJUnit4::class)
class AndroidVideoCodecTest {
    private val pattern = TestPatternSource(CoroutineScope(Dispatchers.Default))

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test
    fun encodesThePayloadFormatAndDecodesBack() {
        val frames = 30
        val enc = assertNotNull(createVideoEncoder(pattern.width, pattern.height, pattern.fps, 300_000), "no video encoder")
        val chunks = Collections.synchronizedList(mutableListOf<EncodedChunk>())
        try {
            repeat(frames) { i ->
                // Keyframes at 0 and 15, as CallVideo forces them.
                enc.encode(pattern.nextFrame(), forceKeyframe = i == 0 || i == 15) { chunks += it }
                Thread.sleep(1000L / pattern.fps)
            }
            assertTrue(waitFor(10_000) { chunks.size >= frames - 2 }, "encoder produced ${chunks.size} of $frames chunks")
        } finally {
            enc.close()
        }
        val out = chunks.toList()
        val keys = out.filter { it.keyframe }
        assertTrue(out.first().keyframe, "the first chunk is a keyframe")
        assertTrue(keys.size >= 2, "the forced keyframe at frame 15 happened (${keys.size} keyframes)")

        if (enc.codecId == CallVideoCodec.TEMPORAL_LAYERED) {
            assertEquals("h264", enc.codecName)
            for (k in keys) {
                val types = Keyframes.h264NalTypes(k.bytes)
                assertTrue(7 in types && 8 in types && 5 in types, "keyframe NAL types $types carry SPS, PPS and IDR")
                assertTrue(Keyframes.isKeyframe(enc.codecId, k.bytes))
            }
            for (d in out.filter { !it.keyframe }) {
                assertTrue(5 !in Keyframes.h264NalTypes(d.bytes), "a delta frame has no IDR")
            }
        } else {
            assertEquals("av1", enc.codecName)
            for (k in keys) assertTrue(1 in Keyframes.av1ObuTypes(k.bytes), "AV1 keyframe carries its sequence header")
        }
        for (c in out) assertTrue(c.layer.spatial == 0 && c.layer.quality == 0 && c.layer.temporal in 0..1)

        val dec = assertNotNull(createVideoDecoder(enc.codecId), "no decoder for ${enc.codecName}")
        val decoded = Collections.synchronizedList(mutableListOf<VideoFrame>())
        try {
            for (c in out) dec.decode(c.layer, c.bytes) { decoded += it }
            assertTrue(waitFor(10_000) { decoded.size >= out.size - 4 }, "decoded ${decoded.size} of ${out.size}")
        } finally {
            dec.close()
        }
        for (f in decoded.toList()) {
            assertEquals(pattern.width, f.width)
            assertEquals(pattern.height, f.height)
        }
        // The picture survived: the left bar is bright, the right bar dark (luma 235 vs 16).
        val f = decoded.last()
        val row = f.height / 8
        val left = f.y[row * f.width + 4].toInt() and 0xFF
        val right = f.y[row * f.width + f.width - 4].toInt() and 0xFF
        assertTrue(left > 180 && right < 70, "bars survived the round trip (left $left, right $right)")
    }

    @Test
    fun aSizeChangeReconfiguresAndStartsWithAKeyframe() {
        val enc = assertNotNull(createVideoEncoder(320, 180, 15, 300_000))
        val chunks = Collections.synchronizedList(mutableListOf<EncodedChunk>())
        try {
            repeat(5) { enc.encode(pattern.nextFrame(), it == 0) { c -> chunks += c }; Thread.sleep(60) }
            assertTrue(waitFor(5_000) { chunks.size >= 4 })
            val before = chunks.size
            // A turned camera: 180x320 now.
            repeat(5) {
                enc.encode(I420.rotate(pattern.nextFrame(), 90), false) { c -> chunks += c }
                Thread.sleep(60)
            }
            assertTrue(waitFor(5_000) { chunks.size > before }, "no output after the size change")
            assertTrue(chunks[before].keyframe, "the first picture at a new size is a keyframe")
        } finally {
            enc.close()
        }
    }
}
