package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The shared half of call video: the test pattern, I420 to ARGB, and per-stream decoding. */
class CallVideoTest {
    /** SPS, PPS, IDR: an H.264 keyframe access unit in Annex B. */
    private val KEY = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0x42, 0, 0, 0, 1, 0x65, 0x11)
    /** An AV1 sequence header OBU then a frame OBU: what the format puts at a keyframe. */
    private val AV1_KEY = byteArrayOf(0x0A, 0x02, 0x00, 0x00, 0x32, 0x01, 0x00)
    /** A non-IDR slice only. */
    private val P_FRAME = byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte())

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
        override fun decode(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit) {
            log += "decode $codec"
            onFrame(VideoFrame(2, 2, ByteArray(4), ByteArray(1), ByteArray(1), 0))
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
        v.receive("ada", ByteArray(32) { 1 }, 0x01, Layer.BASE, AV1_KEY)
        v.receive("ada", ByteArray(32) { 2 }, 0x04, Layer.BASE, KEY)
        v.receive("ada", ByteArray(32) { 1 }, 0x01, Layer.BASE, AV1_KEY)
        assertEquals(listOf("decode 1", "decode 4", "decode 1"), log)
        assertEquals(3, rendered.size)
    }

    /** A codec this device cannot decode is said, never a black tile. */
    @Test fun anUndecodableStreamIsSaid() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered, decodable = setOf(0x01))
        v.receive("sam", ByteArray(32) { 3 }, 0x04, Layer.BASE, KEY)
        assertEquals(listOf("undecodable sam 4"), rendered)
    }

    /** A codec change on the same stream replaces its decoder. */
    @Test fun aCodecChangeReplacesTheDecoder() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered)
        val s = ByteArray(32) { 1 }
        v.receive("ada", s, 0x01, Layer.BASE, AV1_KEY)
        v.receive("ada", s, 0x04, Layer.BASE, KEY)
        assertEquals(listOf("decode 1", "close 1", "decode 4"), log)
        assertNotEquals(0, rendered.size)
    }

    /** The witness CC asked for: a late joiner skips until its first keyframe, then decodes. */
    @Test fun aLateJoinerStartsAtTheFirstKeyframe() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered)
        val s = ByteArray(32) { 9 }
        v.receive("ada", s, 0x04, Layer.BASE, P_FRAME)
        v.receive("ada", s, 0x04, Layer(0, 1, 0), P_FRAME)
        assertEquals(emptyList(), log, "nothing before a keyframe")
        v.receive("ada", s, 0x04, Layer.BASE, KEY)
        v.receive("ada", s, 0x04, Layer(0, 1, 0), P_FRAME)
        assertEquals(listOf("decode 4", "decode 4"), log)
    }

    /**
     * A picture that never arrived: the ones after it are built on it, so the tile
     * holds what it has until a keyframe, instead of decoding a smear.
     */
    @Test fun aLostPictureHoldsTheTileUntilTheNextKeyframe() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered)
        val s = ByteArray(32) { 9 }
        v.receive("ada", s, 0x04, Layer.BASE, KEY, 0u)
        v.receive("ada", s, 0x04, Layer.BASE, P_FRAME, 1u)
        assertEquals(2, log.size)
        v.receive("ada", s, 0x04, Layer.BASE, P_FRAME, 3u) // 2 was lost
        v.receive("ada", s, 0x04, Layer.BASE, P_FRAME, 4u)
        assertEquals(2, log.size, "nothing is decoded on top of a missing picture")
        v.receive("ada", s, 0x04, Layer.BASE, KEY, 5u)
        v.receive("ada", s, 0x04, Layer.BASE, P_FRAME, 6u)
        assertEquals(4, log.size, "the next keyframe starts the picture again")
    }

    private val T1 = Layer(0, 1, 0)
    private val T2 = Layer(0, 2, 0)

    /**
     * Each layer counts its own frames, so the base at 41 beside the layer above at
     * 3 is two healthy layers, not a loss. (Edge and the CC, 2026-11-10.)
     */
    @Test fun eachLayerCountsItsOwnFrames() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered)
        val s = ByteArray(32) { 9 }
        v.receive("ada", s, 0x04, Layer.BASE, KEY, 40u)
        v.receive("ada", s, 0x04, T1, P_FRAME, 2u)
        v.receive("ada", s, 0x04, Layer.BASE, P_FRAME, 41u)
        v.receive("ada", s, 0x04, T1, P_FRAME, 3u)
        v.receive("ada", s, 0x04, Layer.BASE, P_FRAME, 42u)
        assertEquals(5, log.size)
    }

    /**
     * A frame lost on a higher layer leaves that layer out, and the ones above it,
     * until the next keyframe. The layers below carry the picture on: no freeze.
     */
    @Test fun aLostFrameOnAHigherLayerDropsToTheLayersBelow() {
        val decoded = mutableListOf<Layer>()
        val v = CallVideo(
            session = ai.ciris.mobile.shared.calls.CallSession("r", NoTransport, kotlinx.coroutines.GlobalScope),
            source = null, encoder = null, scope = kotlinx.coroutines.GlobalScope,
            render = { _, _, _ -> },
            newDecoder = { _ ->
                object : VideoDecoder {
                    override fun decode(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit) { decoded += layer }
                    override fun close() {}
                }
            },
        )
        val s = ByteArray(32) { 9 }
        v.receive("ada", s, 0x04, Layer.BASE, KEY, 0u)
        v.receive("ada", s, 0x04, T1, P_FRAME, 0u)
        v.receive("ada", s, 0x04, T2, P_FRAME, 0u)
        decoded.clear()
        v.receive("ada", s, 0x04, T1, P_FRAME, 2u) // T1's frame 1 was lost
        v.receive("ada", s, 0x04, T2, P_FRAME, 1u) // whole itself, but may be built on T1
        v.receive("ada", s, 0x04, Layer.BASE, P_FRAME, 1u)
        v.receive("ada", s, 0x04, T1, P_FRAME, 3u)
        assertEquals(listOf(Layer.BASE), decoded, "the base layer plays on; T1 and T2 wait")
        decoded.clear()
        v.receive("ada", s, 0x04, Layer.BASE, KEY, 2u)
        v.receive("ada", s, 0x04, T1, P_FRAME, 4u)
        v.receive("ada", s, 0x04, T2, P_FRAME, 2u)
        assertEquals(listOf(Layer.BASE, T1, T2), decoded, "a keyframe brings every layer back")
    }

    /** A layer that was not being sent (a cap, or the node shedding) comes back with a jump: it waits for a keyframe, and nothing freezes. */
    @Test fun aLayerThatComesBackWaitsForAKeyframe() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered)
        val s = ByteArray(32) { 9 }
        v.receive("ada", s, 0x04, Layer.BASE, KEY, 0u)
        v.receive("ada", s, 0x04, T1, P_FRAME, 0u)
        repeat(5) { v.receive("ada", s, 0x04, Layer.BASE, P_FRAME, (it + 1).toUInt()) } // T1 shed: no gap anywhere
        assertEquals(7, log.size)
        v.receive("ada", s, 0x04, T1, P_FRAME, 6u) // back, five frames on
        assertEquals(7, log.size, "not decoded on top of frames this device never had")
        v.receive("ada", s, 0x04, Layer.BASE, P_FRAME, 6u)
        assertEquals(8, log.size, "the base layer never stopped")
        v.receive("ada", s, 0x04, Layer.BASE, KEY, 7u)
        v.receive("ada", s, 0x04, T1, P_FRAME, 7u)
        assertEquals(10, log.size)
    }

    /** A picture starts at the base layer: keyframe bytes on a higher layer start nothing. */
    @Test fun aKeyframeOnAHigherLayerDoesNotRestartThePicture() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered)
        val s = ByteArray(32) { 9 }
        v.receive("ada", s, 0x04, T1, KEY, 0u)
        assertEquals(emptyList(), log, "a picture starts at the base layer")
    }

    /** The counter is 32 bits and wraps; the picture after the largest count is 0. */
    @Test fun theCountWrappingIsNotALostPicture() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered)
        val s = ByteArray(32) { 9 }
        v.receive("ada", s, 0x04, Layer.BASE, KEY, UInt.MAX_VALUE)
        v.receive("ada", s, 0x04, Layer.BASE, P_FRAME, 0u)
        v.receive("ada", s, 0x04, Layer.BASE, P_FRAME, 1u)
        assertEquals(3, log.size)
    }

    /** A node on Edge before v45 sends no count: nothing to judge by, so nothing is held. */
    @Test fun aStreamWithNoCountIsNeverHeld() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered)
        val s = ByteArray(32) { 9 }
        v.receive("ada", s, 0x04, Layer.BASE, KEY)
        repeat(3) { v.receive("ada", s, 0x04, Layer.BASE, P_FRAME) }
        assertEquals(4, log.size)
    }

    /** One stream's lost picture is that tile's alone. */
    @Test fun aLostPictureOnOneStreamLeavesTheOthersPlaying() {
        val log = mutableListOf<String>(); val rendered = mutableListOf<String>()
        val v = video(log, rendered)
        val cam = ByteArray(32) { 1 }; val share = ByteArray(32) { 2 }
        v.receive("ada", cam, 0x04, Layer.BASE, KEY, 0u)
        v.receive("ada", share, 0x04, Layer.BASE, KEY, 0u)
        v.receive("ada", cam, 0x04, Layer.BASE, P_FRAME, 5u) // the camera lost pictures
        v.receive("ada", share, 0x04, Layer.BASE, P_FRAME, 1u)
        assertEquals(3, log.size)
    }

    @Test fun keyframesAreReadFromTheBytes() {
        assertTrue(Keyframes.isKeyframe(0x04, KEY))
        assertTrue(!Keyframes.isKeyframe(0x04, P_FRAME))
        // AV1: sequence header OBU (type 1, has_size) then a frame OBU (type 6).
        val av1Key = byteArrayOf(0x0A, 0x02, 0x00, 0x00, 0x32, 0x01, 0x00)
        val av1Inter = byteArrayOf(0x32, 0x01, 0x00)
        assertTrue(Keyframes.isKeyframe(0x01, av1Key))
        assertTrue(!Keyframes.isKeyframe(0x01, av1Inter))
        assertEquals(listOf(1, 6), Keyframes.av1ObuTypes(av1Key))
    }

    /** Camera off takes effect on the very next frame: nothing is encoded, so nothing is sent. */
    @Test fun withTheCameraOffNothingIsEncoded() {
        var deliver: (VideoFrame) -> Unit = {}
        val source = object : VideoSource {
            override val kind = VideoKind.TEST_PATTERN
            override fun start(onFrame: (VideoFrame) -> Unit) { deliver = onFrame }
            override fun stop() {}
        }
        val encoded = mutableListOf<String>()
        val encoder = object : VideoEncoder {
            override val codecId = 0x01
            override val codecName = "fake"
            override fun encode(frame: VideoFrame, forceKeyframe: Boolean, onChunk: (EncodedChunk) -> Unit) {
                encoded += if (forceKeyframe) "key" else "inter"
            }
            override fun close() {}
        }
        val v = CallVideo(
            session = ai.ciris.mobile.shared.calls.CallSession("r", NoTransport, kotlinx.coroutines.GlobalScope),
            source = source, encoder = encoder, scope = kotlinx.coroutines.GlobalScope,
            render = { _, _, _ -> }, receive = false,
        )
        v.start()
        var n = 0L
        fun frame() = VideoFrame(2, 2, ByteArray(4), ByteArray(1), ByteArray(1), ++n * 66_000)
        deliver(frame()); deliver(frame())
        assertEquals(listOf("key", "inter"), encoded)
        v.cameraOff = true
        deliver(frame()); deliver(frame())
        assertEquals(listOf("key", "inter"), encoded, "nothing is encoded while the camera is off")
        v.cameraOff = false
        deliver(frame())
        assertEquals(listOf("key", "inter", "inter"), encoded)
    }

    private object NoTransport : ai.ciris.mobile.shared.calls.CallTransport {
        override suspend fun join(room: String, video: Boolean) = error("not used")
        override suspend fun leave(room: String) = Unit
        override suspend fun openFrames(room: String) = error("not used")
    }
}
