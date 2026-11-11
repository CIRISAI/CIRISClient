package ai.ciris.mobile.shared.calls.media

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The call camera on this device (an emulator's emulated front camera counts):
 * frames arrive as even-sized I420 at about the call rate, and they encode.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCameraSourceTest {
    @Test
    fun theCameraDeliversI420ThatEncodes() {
        assumeTrue("grantRuntimePermission needs API 28", Build.VERSION.SDK_INT >= 28)
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        inst.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.CAMERA)
        assumeTrue("this device has no camera", ctx.packageManager.hasSystemFeature("android.hardware.camera.any"))

        val camera = assertNotNull(androidCameraSource(ctx), "no camera source with CAMERA granted")
        assertEquals(VideoKind.CAMERA, camera.kind)
        val frames = Collections.synchronizedList(mutableListOf<VideoFrame>())
        camera.start { frames += it }
        try {
            val end = System.currentTimeMillis() + 15_000
            while (frames.size < 20 && System.currentTimeMillis() < end) Thread.sleep(50)
        } finally {
            camera.stop()
        }
        assertTrue(frames.size >= 20, "camera delivered ${frames.size} frames in 15 s")
        val f = frames.last()
        assertTrue(f.width * f.height in (300 * 300)..(700 * 700), "about 640x480: ${f.width}x${f.height}")
        // 15 fps ceiling: 20 frames take at least ~1.2 s of capture time.
        val spanUs = frames[19].timestampUs - frames[0].timestampUs
        assertTrue(spanUs >= 1_100_000, "throttled to the call rate (19 gaps in ${spanUs}us)")

        val enc = assertNotNull(createVideoEncoder(f.width, f.height, 15, 500_000))
        val chunks = Collections.synchronizedList(mutableListOf<EncodedChunk>())
        try {
            frames.take(10).forEachIndexed { i, fr -> enc.encode(fr, i == 0) { chunks += it }; Thread.sleep(30) }
            val end = System.currentTimeMillis() + 5_000
            while (chunks.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        } finally {
            enc.close()
        }
        assertTrue(chunks.isNotEmpty() && chunks.first().keyframe, "camera frames encode, keyframe first")
    }
}
