package ai.ciris.mobile.shared.calls.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Screen sharing on Android (CSD-134): MediaProjection into a VirtualDisplay
 * whose surface is an ImageReader, scaled to at most 1280 wide, about 4 fps
 * (a still screen repeats its last picture once a second, so a late joiner
 * still gets one), as I420 with [VideoKind.SCREEN].
 *
 * Video only: system audio is NOT captured in this version.
 *
 * How a host uses it (MediaProjection needs an Activity result, and from
 * Android 14 a running foreground service of type `mediaProjection`, both of
 * which this library supplies the halves of):
 *
 * ```
 * val launcher = registerForActivityResult(StartActivityForResult()) { r ->
 *     if (r.resultCode == Activity.RESULT_OK && r.data != null) {
 *         val share = androidScreenSource(context, r.resultCode, r.data!!,
 *             title, text) // localized by the call screen; no keys for them exist yet
 *         // publish `share` as a second video stream; share.stop() ends it
 *     }
 * }
 * launcher.launch(androidScreenShareIntent(context))
 * ```
 *
 * [VideoSource.start] starts [CallScreenShareService] in the foreground, and the
 * service obtains the projection once it is (the order Android 14 requires).
 * [VideoSource.stop], or the person stopping it from the system, ends the
 * projection and the service. A result is good for ONE start: Android 14 refuses
 * a reused consent, so a second share asks again.
 */
private const val TAG = "CallScreenShare"
private const val MAX_WIDTH = 1280
private const val FPS = 4
private const val REPEAT_MS = 1000L

/** The system's "start recording or casting?" consent prompt, for an Activity result. */
fun androidScreenShareIntent(context: Context): Intent =
    (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()

/**
 * The screen as a call video source, from the consent prompt's result. See
 * [AndroidScreenSource]. [notificationTitle] and [notificationText] are the
 * ongoing notification Android requires while sharing; the caller passes them
 * already localized (this library has no English fallback to slip in).
 */
fun androidScreenSource(
    context: Context,
    resultCode: Int,
    data: Intent,
    notificationTitle: String,
    notificationText: String,
): AndroidScreenSource = AndroidScreenSource(context.applicationContext, resultCode, data, notificationTitle, notificationText)

class AndroidScreenSource internal constructor(
    private val context: Context,
    private val resultCode: Int,
    private val data: Intent,
    internal val notificationTitle: String,
    internal val notificationText: String,
) : VideoSource {
    override val kind = VideoKind.SCREEN

    private val lock = Any()
    private var onFrame: ((VideoFrame) -> Unit)? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var last: VideoFrame? = null
    private var lastEmitMs = 0L
    private var used = false

    /** Called when the share ends from outside (the system's stop control, or a refused consent). */
    var onEnded: (() -> Unit)? = null

    override fun start(onFrame: (VideoFrame) -> Unit) {
        synchronized(lock) {
            if (this.onFrame != null) return
            check(!used) { "a screen share consent is good for one share; ask again" }
            used = true
            this.onFrame = onFrame
        }
        CallScreenShareService.pending = this
        val intent = Intent(context, CallScreenShareService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    /** The service is in the foreground: now the projection may be taken. */
    internal fun attach() {
        synchronized(lock) {
            if (onFrame == null) return
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
            if (p == null) {
                Log.e(TAG, "the screen share consent was refused or expired")
                endFromOutside(); return
            }
            val t = HandlerThread("ciris-call-share").apply { start() }
            val h = Handler(t.looper)
            thread = t
            handler = h
            projection = p
            // Android 14: a callback is registered before the virtual display exists.
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { endFromOutside() }
            }, h)
            val metrics = context.resources.displayMetrics
            val scale = minOf(1.0, MAX_WIDTH.toDouble() / metrics.widthPixels)
            val w = ((metrics.widthPixels * scale).toInt() / 16 * 16).coerceAtLeast(16)
            val hgt = ((metrics.heightPixels * scale).toInt() / 16 * 16).coerceAtLeast(16)
            val r = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ ir ->
                val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                image.use {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEmitMs < 1000L / FPS) return@use
                    val plane = it.planes[0]
                    val frame = I420.fromRgba(plane.buffer, plane.rowStride, plane.pixelStride, it.width, it.height, now * 1000)
                    emit(frame, now)
                }
            }, h)
            display = p.createVirtualDisplay(
                "ciris-call-share", w, hgt, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h,
            )
            h.postDelayed(object : Runnable {
                override fun run() {
                    val now = SystemClock.elapsedRealtime()
                    val f = last
                    if (f != null && now - lastEmitMs >= REPEAT_MS) {
                        emit(VideoFrame(f.width, f.height, f.y, f.u, f.v, now * 1000), now)
                    }
                    if (handler === h) h.postDelayed(this, REPEAT_MS)
                }
            }, REPEAT_MS)
        }
    }

    private fun emit(frame: VideoFrame, nowMs: Long) {
        last = frame
        lastEmitMs = nowMs
        onFrame?.invoke(frame)
    }

    private fun endFromOutside() {
        val wasRunning = synchronized(lock) { onFrame != null }
        stop()
        if (wasRunning) onEnded?.invoke()
    }

    override fun stop() {
        synchronized(lock) {
            if (onFrame == null) return
            onFrame = null
            handler = null
            runCatching { display?.release() }
            display = null
            reader?.close()
            reader = null
            runCatching { projection?.stop() }
            projection = null
            thread?.quitSafely()
            thread = null
            last = null
        }
        if (CallScreenShareService.pending === this) CallScreenShareService.pending = null
        context.stopService(Intent(context, CallScreenShareService::class.java))
    }
}

/**
 * The foreground service a screen share runs under (type `mediaProjection`,
 * declared in this library's manifest). It does nothing but hold the
 * notification and hand the projection to the share once it is foreground.
 */
class CallScreenShareService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, pending?.notificationTitle ?: CHANNEL, NotificationManager.IMPORTANCE_LOW))
        }
        val share = pending
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(share?.notificationTitle.orEmpty())
            .setContentText(share?.notificationText.orEmpty())
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "screen share service could not go foreground", e)
            pending?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (share == null) stopSelf() else share.attach()
        return START_NOT_STICKY
    }

    internal companion object {
        const val CHANNEL = "ciris_call_screen_share"
        const val NOTIFICATION_ID = 0x5C4E
        @Volatile var pending: AndroidScreenSource? = null
    }
}
