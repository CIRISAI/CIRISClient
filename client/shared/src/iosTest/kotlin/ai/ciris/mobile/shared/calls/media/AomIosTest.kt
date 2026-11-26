package ai.ciris.mobile.shared.calls.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * libaom on iOS, run (CSD-134, AV1 both ways): the encoder and decoder the call
 * uses when the device has no hardware AV1, through the `aom` cinterop. A
 * desktop sends AV1 and, by default, shows only AV1, so this pair is what lets
 * an iPhone and a desktop see each other.
 */
class AomIosTest {
    @Test
    fun aMovingPictureSurvivesEncodeAndDecode() {
        val pattern = TestPatternSource(kotlinx.coroutines.GlobalScope)
        val enc = assertNotNull(AomVideoEncoder.open(pattern.width, pattern.height, pattern.fps, 300_000), "libaom encoder")
        val dec = assertNotNull(AomVideoDecoder.open(), "libaom decoder")
        val chunks = mutableListOf<EncodedChunk>()
        val frames = mutableListOf<VideoFrame>()
        try {
            repeat(30) { i ->
                enc.encode(pattern.nextFrame(), forceKeyframe = i == 0 || i == 15) { chunks += it }
            }
            for (c in chunks) dec.decode(c.layer, c.bytes) { frames += it }
        } finally {
            enc.close()
            dec.close()
        }
        assertEquals(30, chunks.size, "one temporal unit per frame")
        assertTrue(chunks[0].keyframe && chunks[15].keyframe, "the forced keyframes")
        for (k in chunks.filter { it.keyframe }) assertTrue(1 in Keyframes.av1ObuTypes(k.bytes), "a keyframe carries its sequence header")
        assertEquals(30, frames.size, "every unit decoded")
        assertEquals(pattern.width, frames.first().width)
        assertEquals(pattern.height, frames.first().height)
        assertTrue(!frames[1].y.contentEquals(frames[10].y), "the square moved")
    }

    @Test
    fun aDecoderJoiningLateStartsAtAKeyframe() {
        val pattern = TestPatternSource(kotlinx.coroutines.GlobalScope)
        val enc = assertNotNull(AomVideoEncoder.open(pattern.width, pattern.height, pattern.fps, 300_000))
        val chunks = mutableListOf<EncodedChunk>()
        repeat(20) { i -> enc.encode(pattern.nextFrame(), forceKeyframe = i == 0 || i == 10) { chunks += it } }
        enc.close()
        val late = assertNotNull(AomVideoDecoder.open())
        var pictures = 0
        for (c in chunks.drop(10)) late.decode(c.layer, c.bytes) { pictures++ }
        late.close()
        assertEquals(10, pictures)
    }
}
