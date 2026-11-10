package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.Layer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Desktop call video (CSD-134) through FFmpeg: both encoders emit the agreed
 * payload format and round-trip through the decoders with the picture moving;
 * the server's real H.264 vector decodes; x11grab captures the screen where
 * there is an X display; the camera, only when CIRIS_CAMERA_TEST=1 (it may be busy).
 */
class DesktopCallVideoTest {
    private val w = 320
    private val h = 180
    private val fps = 15

    /** Mean column of the bright square (luma >= 250), or null if none is visible. */
    private fun squareX(f: VideoFrame): Double? {
        var sum = 0L
        var n = 0
        for (row in 0 until f.height) for (col in 0 until f.width) {
            if ((f.y[row * f.width + col].toInt() and 0xFF) >= 250) { sum += col; n++ }
        }
        return if (n < 50) null else sum.toDouble() / n
    }

    private var held = 0

    private fun roundTrip(enc: VideoEncoder): Pair<List<EncodedChunk>, List<VideoFrame>> {
        val pattern = TestPatternSource(CoroutineScope(Dispatchers.Unconfined), w, h, fps)
        val chunks = mutableListOf<EncodedChunk>()
        repeat(30) { i -> enc.encode(pattern.nextFrame(), i == 0 || i == 15) { chunks += it } }
        held = 30 - chunks.size
        (enc as FfmpegVideoEncoder).flush { chunks += it } // what SVT-AV1 still holds, in order
        enc.close()
        val dec = assertNotNull(createVideoDecoder(enc.codecId), "a decoder for ${enc.codecName}")
        val frames = mutableListOf<VideoFrame>()
        for (c in chunks) dec.decode(c.layer, c.bytes) { frames += it }
        dec.close()
        return chunks to frames
    }

    private fun assertPictureMoved(frames: List<VideoFrame>, codec: String) {
        assertTrue(frames.size >= 29, "$codec: decoded ${frames.size} of 30 frames")
        assertTrue(frames.all { it.width == w && it.height == h }, "$codec: decoded size ${frames.first().width}x${frames.first().height}")
        val first = assertNotNull(squareX(frames.first()), "$codec: the square is in the first frame")
        val last = assertNotNull(squareX(frames.last()), "$codec: the square is in the last frame")
        // The pattern moves the square 4 px a frame: ~116 px over 30 frames.
        assertTrue(last - first > 80, "$codec: the square moved $first -> $last")
    }

    @Test fun h264EncodesTheAgreedFormatAndRoundTrips() {
        val enc = assertNotNull(createDesktopVideoEncoder(DesktopVideoCodec.H264, w, h, fps, 300_000), "libopenh264")
        assertEquals(CallVideoCodec.TEMPORAL_LAYERED, enc.codecId)
        assertEquals("h264", enc.codecName)
        val (chunks, frames) = roundTrip(enc)
        assertEquals(30, chunks.size, "one access unit per frame")
        assertTrue(chunks.all { it.layer == Layer.BASE })
        for (i in listOf(0, 15)) {
            val c = chunks[i]
            assertTrue(Keyframes.isKeyframe(CallVideoCodec.TEMPORAL_LAYERED, c.bytes) && c.keyframe, "chunk $i was forced to a keyframe")
            assertTrue(Keyframes.h264NalTypes(c.bytes).containsAll(listOf(7, 8, 5)), "chunk $i carries SPS, PPS, IDR: ${Keyframes.h264NalTypes(c.bytes)}")
        }
        assertTrue(!Keyframes.isKeyframe(CallVideoCodec.TEMPORAL_LAYERED, chunks[5].bytes), "an unforced frame is not a keyframe")
        assertPictureMoved(frames, "h264")
    }

    @Test fun av1EncodesTheAgreedFormatAndRoundTrips() {
        val enc = assertNotNull(createDesktopVideoEncoder(DesktopVideoCodec.AV1, w, h, fps, 300_000), "libsvtav1")
        assertEquals(CallVideoCodec.AV1_SVC, enc.codecId)
        assertEquals("av1", enc.codecName)
        val (chunks, frames) = roundTrip(enc)
        // Pushed back to back, SVT-AV1's pipeline holds a frame or two; at end of stream
        // every frame has exactly one temporal unit, in order.
        assertTrue(held <= 2, "low delay: at most two frames held in a burst, held $held")
        assertEquals(30, chunks.size, "one temporal unit per frame")
        for (c in chunks) {
            // Low-overhead form: every OBU carries obu_has_size_field.
            val units = assertNotNull(Obu.parse(c.bytes), "chunk parses as OBUs")
            assertTrue(units.all { it.header[0].toInt() and 0x02 != 0 }, "every OBU has its size field")
        }
        for (i in listOf(0, 15)) {
            assertTrue(Keyframes.isKeyframe(CallVideoCodec.AV1_SVC, chunks[i].bytes) && chunks[i].keyframe, "chunk $i: sequence header before the keyframe: ${Keyframes.av1ObuTypes(chunks[i].bytes)}")
        }
        assertTrue(!Keyframes.isKeyframe(CallVideoCodec.AV1_SVC, chunks[5].bytes), "an unforced frame is not a keyframe")
        assertPictureMoved(frames, "av1")
    }

    @Test fun thePlatformEncoderIsOneOfTheTwo() {
        for ((sw, sh, sf) in listOf(Triple(320, 180, 15), Triple(640, 480, 15), Triple(1280, 720, 5))) {
            println("[call video] ${sw}x$sh@$sf: ${desktopVideoEncoderChoice(sw, sh, sf, 500_000)}")
        }
        val enc = assertNotNull(createVideoEncoder(640, 480, 15, 500_000))
        assertTrue(enc.codecName == "av1" || enc.codecName == "h264")
        enc.close()
    }

    /** The server's real vector (CIRISServer tests/vectors/call_frames_v1.json) decodes to its 32x32 picture. */
    @Test fun theServersH264VectorDecodes() {
        val file = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/vectors/call_frames_v1.json")).readText()).jsonObject
        val case = file["client_to_node"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "video_h264_keyframe" }
        val hex = case["payload_hex"]!!.jsonPrimitive.content
        val payload = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        val dec = assertNotNull(createVideoDecoder(CallVideoCodec.TEMPORAL_LAYERED))
        val frames = mutableListOf<VideoFrame>()
        dec.decode(Layer.BASE, payload) { frames += it }
        dec.close()
        assertEquals(1, frames.size, "one picture from one access unit")
        assertEquals(32 to 32, frames[0].width to frames[0].height)
    }

    private fun capture(src: VideoSource, n: Int, seconds: Long): List<VideoFrame> {
        val got = CopyOnWriteArrayList<VideoFrame>()
        val done = CountDownLatch(n)
        src.start { got += it; done.countDown() }
        done.await(seconds, TimeUnit.SECONDS)
        src.stop()
        return got
    }

    @Test fun x11grabCapturesTheScreen() {
        val reason = desktopShareUnavailableReason()
        if (DesktopOs.current != DesktopOs.LINUX || reason != null) {
            println("[call video] screen capture skipped: ${reason ?: "not Linux"}")
            return
        }
        val targets = desktopShareTargets()
        println("[call video] share targets: ${targets.map { "${it.kind} ${it.id} '${it.title}'" }}")
        val screen = targets.first { it.kind == VideoKind.SCREEN }
        val frames = capture(desktopShareSource(screen), 3, 10)
        assertTrue(frames.size >= 3, "3 screen frames in 10 s, got ${frames.size}")
        val f = frames.last()
        println("[call video] screen frame ${f.width}x${f.height}")
        assertTrue(f.width <= 1280 && f.width % 2 == 0 && f.height % 2 == 0)
        assertTrue(f.y.any { it != f.y[0] }, "the screen is not one flat colour")
        val window = targets.firstOrNull { it.kind == VideoKind.WINDOW }
        if (window != null) {
            val wf = capture(desktopShareSource(window), 2, 10)
            assertTrue(wf.isNotEmpty(), "a window frame from '${window.title}'")
            println("[call video] window '${window.title}' frame ${wf.last().width}x${wf.last().height}")
        }
    }

    @Test fun theCameraGrabs() {
        if (System.getenv("CIRIS_CAMERA_TEST") != "1") {
            println("[call video] camera test skipped (set CIRIS_CAMERA_TEST=1)")
            return
        }
        val cam = assertNotNull(desktopCamera(), "a camera opened")
        assertEquals(VideoKind.CAMERA, cam.kind)
        val frames = capture(cam, 5, 10)
        assertTrue(frames.size >= 5, "5 camera frames in 10 s, got ${frames.size}")
        val f = frames.last()
        println("[call video] camera frame ${f.width}x${f.height}")
        assertTrue(f.width <= 640 && f.height <= 480)
    }
}
