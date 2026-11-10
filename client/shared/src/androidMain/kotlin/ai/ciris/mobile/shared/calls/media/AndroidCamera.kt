package ai.ciris.mobile.shared.calls.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.WindowManager

/**
 * The call camera (CSD-134), on Camera2 directly: CameraX is in the build for
 * the QR scanner, but it wants a LifecycleOwner and a call outlives any one
 * screen. Front camera where there is one, 640x480 (or the nearest size the
 * camera offers), throttled to 15 fps, delivered as I420.
 *
 * Frames are turned upright for the way the device is held when capture starts
 * (sensor orientation plus display rotation), so remote tiles are upright for a
 * phone held still; they are NOT mirrored, since a remote viewer should see the
 * person as others do. Only the local self-view mirrors, and that is the screen's.
 *
 * Needs CAMERA, asked by the call screen first; without it [androidCameraSource]
 * returns null and the call is audio only.
 */
private const val TAG = "CallCamera"

class AndroidCameraSource internal constructor(
    private val context: Context,
    private val cameraId: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
) : VideoSource {
    override val kind = VideoKind.CAMERA

    private var thread: HandlerThread? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var running = false
    // 0, not Long.MIN_VALUE: `now - MIN_VALUE` overflows negative and the throttle drops every frame.
    private var lastEmitUs = 0L

    @SuppressLint("MissingPermission") // androidCameraSource checked CAMERA before this exists
    override fun start(onFrame: (VideoFrame) -> Unit) {
        if (running) return
        running = true
        val t = HandlerThread("ciris-call-camera").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(cameraId)
        val rotation = uprightRotation(chars)
        val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        reader = r
        val minGapUs = 1_000_000L / fps - 5_000L
        r.setOnImageAvailableListener({ ir ->
            val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            image.use {
                val now = SystemClock.elapsedRealtimeNanos() / 1000
                if (!running || now - lastEmitUs < minGapUs) return@use
                lastEmitUs = now
                val frame = I420.read(it, now) ?: return@use
                onFrame(I420.rotate(frame, rotation))
            }
        }, handler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!running) { camera.close(); return }
                device = camera
                @Suppress("DEPRECATION") // the List<Surface> form is the one minSdk 24 has
                camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (!running) { s.close(); return }
                        session = s
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(r.surface)
                            fpsRange(chars)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }.build()
                        runCatching { s.setRepeatingRequest(req, null, handler) }
                            .onFailure { Log.e(TAG, "camera request failed", it) }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "camera session could not be configured")
                    }
                }, handler)
            }

            override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "camera error $error")
                camera.close(); device = null
            }
        }, handler)
    }

    /** A range around [fps] the camera offers, lowest ceiling first: no more frames than the call sends. */
    private fun fpsRange(chars: CameraCharacteristics): Range<Int>? =
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper >= fps }
            ?.minWithOrNull(compareBy<Range<Int>>({ it.upper }, { -it.lower }))

    private fun uprightRotation(chars: CameraCharacteristics): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val degrees = when (display) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        return if (front) (sensor + degrees) % 360 else (sensor - degrees + 360) % 360
    }

    override fun stop() {
        if (!running) return
        running = false
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null
    }
}

/**
 * The call camera, or null when there is no context, no CAMERA permission, or
 * no camera. Prefers the front camera; [width]x[height] is a target, and the
 * nearest offered YUV size is used.
 */
fun androidCameraSource(
    context: Context? = androidCallMediaContext,
    width: Int = 640,
    height: Int = 480,
    fps: Int = 15,
): VideoSource? {
    val ctx = context?.applicationContext ?: return null
    if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return null
    val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
    return runCatching {
        val ids = manager.cameraIdList
        val id = ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: ids.firstOrNull() ?: return null
        val sizes = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            .filter { it.width % 2 == 0 && it.height % 2 == 0 }
        val size = sizes.minByOrNull { kotlin.math.abs(it.width * it.height - width * height) + kotlin.math.abs(it.width - width) }
        AndroidCameraSource(ctx, id, size?.width ?: width, size?.height ?: height, fps)
    }.onFailure { Log.e(TAG, "no camera", it) }.getOrNull()
}

/**
 * The application context call media uses for the camera and screen share.
 * Set by [initCallMedia]; [ai.ciris.mobile.shared.platform.initUrlOpener] also
 * sets it, so a host that already calls that needs nothing new.
 */
@Volatile
var androidCallMediaContext: Context? = null
    private set

/** Call from Application.onCreate() or MainActivity.onCreate(). */
fun initCallMedia(context: Context) {
    androidCallMediaContext = context.applicationContext
}
