package ai.ciris.mobile.shared.calls.media

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avdevice.AVDeviceInfoList
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_BMP
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MJPEG
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_RAWVIDEO
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avdevice.avdevice_free_list_devices
import org.bytedeco.ffmpeg.global.avdevice.avdevice_list_input_sources
import org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all
import org.bytedeco.ffmpeg.global.avformat.av_find_input_format
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Desktop capture through FFmpeg's avdevice (CSD-134): the camera, a whole screen,
 * or one window, as I420 [VideoFrame]s.
 *
 * | | camera | screen | window |
 * |---|---|---|---|
 * | Linux (X11) | `video4linux2` `/dev/video*` | `x11grab` | `x11grab` `window_id` |
 * | Windows | `dshow` | `gdigrab` `desktop` | `gdigrab` `title=` |
 * | macOS | `avfoundation` | `avfoundation` `Capture screen N` | not available |
 * | Linux (Wayland) | `video4linux2` | not available | not available |
 *
 * Wayland has no X screen to grab; that needs the PipeWire screencast portal,
 * which is a later step, so [desktopShareTargets] is empty there and
 * [desktopShareUnavailableReason] says why. macOS avfoundation captures screens
 * only, never a single window.
 */

/** One thing that can be shared: a screen or a window. [id] is what the capture device takes. */
data class ShareTarget(val id: String, val title: String, val kind: VideoKind)

/** Why there is nothing to share on this desktop, or null when [desktopShareTargets] is the answer. */
fun desktopShareUnavailableReason(): String? = when (DesktopOs.current) {
    DesktopOs.LINUX -> when {
        System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true) ->
            "screen share on Wayland needs the PipeWire portal, which is not built yet"
        System.getenv("DISPLAY").isNullOrEmpty() -> "no X display (DISPLAY is not set)"
        !Ffmpeg.loaded -> "FFmpeg did not load: ${Ffmpeg.error}"
        else -> null
    }
    DesktopOs.OTHER -> "screen share is not built for this OS"
    else -> if (!Ffmpeg.loaded) "FFmpeg did not load: ${Ffmpeg.error}" else null
}

/** The screens and windows this desktop can share; empty where it cannot ([desktopShareUnavailableReason]). */
fun desktopShareTargets(): List<ShareTarget> {
    if (desktopShareUnavailableReason() != null) return emptyList()
    return when (DesktopOs.current) {
        DesktopOs.LINUX -> listOf(ShareTarget("screen", "Entire screen", VideoKind.SCREEN)) + x11Windows()
        DesktopOs.WINDOWS -> listOf(ShareTarget("desktop", "Entire screen", VideoKind.SCREEN)) + windowsWindows()
        // avfoundation names screens after the cameras; "Capture screen 0" is the main display.
        DesktopOs.MAC -> listOf(ShareTarget("Capture screen 0", "Entire screen", VideoKind.SCREEN))
        DesktopOs.OTHER -> emptyList()
    }
}

/** A source for one share target: ~5 fps, scaled to at most 1280 wide. */
fun desktopShareSource(target: ShareTarget): VideoSource {
    val fps = 5
    return when (DesktopOs.current) {
        DesktopOs.LINUX -> {
            val display = System.getenv("DISPLAY") ?: ":0"
            val opts = mutableMapOf("framerate" to "$fps", "draw_mouse" to "1")
            if (target.kind == VideoKind.WINDOW) opts["window_id"] = target.id
            FfmpegCaptureSource(target.kind, "x11grab", display, listOf(opts), SHARE_MAX_W, SHARE_MAX_H, fps)
        }
        DesktopOs.WINDOWS -> {
            val url = if (target.kind == VideoKind.WINDOW) "title=${target.title}" else "desktop"
            FfmpegCaptureSource(target.kind, "gdigrab", url, listOf(mapOf("framerate" to "$fps", "draw_mouse" to "1")), SHARE_MAX_W, SHARE_MAX_H, fps)
        }
        DesktopOs.MAC -> FfmpegCaptureSource(
            target.kind, "avfoundation", target.id,
            listOf(mapOf("framerate" to "$fps", "capture_cursor" to "1"), mapOf("capture_cursor" to "1")),
            SHARE_MAX_W, SHARE_MAX_H, fps,
        )
        DesktopOs.OTHER -> error("screen share is not built for this OS")
    }
}

/**
 * The camera, opened now so a busy or missing one is null here rather than a
 * source that never delivers: 640x480 at 15 fps, I420.
 */
fun desktopCamera(): VideoSource? {
    if (!Ffmpeg.loaded) return null
    val want = listOf(
        mapOf("video_size" to "640x480", "framerate" to "15"),
        mapOf("video_size" to "640x480", "framerate" to "30"), // avfoundation takes only modes the camera lists
        mapOf("framerate" to "30"),
        emptyMap(),
    )
    val (format, urls) = when (DesktopOs.current) {
        DesktopOs.LINUX -> "video4linux2" to linuxCameras()
        DesktopOs.WINDOWS -> "dshow" to listDevices("dshow").map { "video=$it" }
        DesktopOs.MAC -> "avfoundation" to listOf("0")
        DesktopOs.OTHER -> return null
    }
    for (url in urls) {
        val src = FfmpegCaptureSource(VideoKind.CAMERA, format, url, want, CAMERA_W, CAMERA_H, CAMERA_FPS)
        if (src.open()) return src
    }
    return null
}

private const val SHARE_MAX_W = 1280
private const val SHARE_MAX_H = 1280
private const val CAMERA_W = 640
private const val CAMERA_H = 480
private const val CAMERA_FPS = 15

internal enum class DesktopOs {
    LINUX, WINDOWS, MAC, OTHER;

    companion object {
        val current: DesktopOs = System.getProperty("os.name").lowercase().let {
            when {
                it.startsWith("linux") -> LINUX
                it.startsWith("windows") -> WINDOWS
                it.startsWith("mac") -> MAC
                else -> OTHER
            }
        }
    }
}

/** Capture devices FFmpeg lists for an input format (v4l2 lists only capture-capable nodes; dshow video and audio). */
internal fun listDevices(format: String): List<String> {
    if (!Ffmpeg.loaded) return emptyList()
    avdevice_register_all()
    val fmt = av_find_input_format(format) ?: return emptyList()
    val list = AVDeviceInfoList(null)
    return try {
        if (avdevice_list_input_sources(fmt, null as String?, null as AVDictionary?, list) < 0 || list.isNull) return emptyList()
        (0 until list.nb_devices()).mapNotNull { i ->
            val d = list.devices(i)
            val video = (0 until d.nb_media_types()).any { d.media_types().get(it.toLong()) == AVMEDIA_TYPE_VIDEO }
            if (d.nb_media_types() > 0 && !video) null else d.device_name().string
        }
    } catch (t: Throwable) {
        emptyList()
    } finally {
        if (!list.isNull) avdevice_free_list_devices(list)
    }
}

private fun linuxCameras(): List<String> {
    val listed = listDevices("video4linux2")
    if (listed.isNotEmpty()) return listed
    return File("/dev").listFiles { f -> f.name.matches(Regex("video\\d+")) }
        ?.map { it.path }?.sortedBy { it.removePrefix("/dev/video").toInt() } ?: emptyList()
}

/** Top-level X windows by the window manager's client list (`xprop`); empty if that tool is missing. */
private fun x11Windows(): List<ShareTarget> {
    val list = run("xprop", "-root", "_NET_CLIENT_LIST") ?: return emptyList()
    val ids = Regex("0x[0-9a-fA-F]+").findAll(list.substringAfter("#", "")).map { it.value }.toList()
    return ids.mapNotNull { id ->
        val out = run("xprop", "-id", id, "_NET_WM_NAME", "WM_NAME") ?: return@mapNotNull null
        val title = Regex("= \"(.*)\"").find(out)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ShareTarget(id, title, VideoKind.WINDOW)
    }
}

/** Windows with a visible main window title, by PowerShell; gdigrab captures a window by its title. */
private fun windowsWindows(): List<ShareTarget> {
    val out = run(
        "powershell", "-NoProfile", "-NonInteractive", "-Command",
        "Get-Process | Where-Object { \$_.MainWindowTitle } | ForEach-Object { \$_.MainWindowTitle }",
    ) ?: return emptyList()
    return out.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        .map { ShareTarget(it, it, VideoKind.WINDOW) }
}

private fun run(vararg cmd: String): String? = try {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) null else text
} catch (e: Exception) {
    null
}

/** What a capture device may hand over. Nothing here carries a patent licence question. */
internal val CAPTURE_CODECS = setOf(AV_CODEC_ID_RAWVIDEO, AV_CODEC_ID_MJPEG, AV_CODEC_ID_BMP)

/**
 * One avdevice input, read on its own thread: packets are decoded and scaled to
 * I420 inside [maxW]x[maxH], keeping the aspect, then thinned to [fps].
 *
 * Only the three formats in [CAPTURE_CODECS] are decoded: raw video, MJPEG (most
 * webcams) and BMP (Windows `gdigrab`). A camera that offers only H.264 does not
 * open, because this app does not ask FFmpeg for H.264 (see `Video.desktop.kt`).
 *
 * [attempts] are option sets tried in order until the device opens: a camera
 * that refuses 15 fps may take 30, which is then thinned.
 */
internal class FfmpegCaptureSource(
    override val kind: VideoKind,
    private val format: String,
    private val url: String,
    private val attempts: List<Map<String, String>>,
    private val maxW: Int,
    private val maxH: Int,
    private val fps: Int,
) : VideoSource {
    private var input: AVFormatContext? = null
    private var decoder: AVCodecContext? = null
    private var stream = -1
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Opens the device; false if it is missing, busy, or refuses every option set. */
    @Synchronized
    fun open(): Boolean {
        if (input != null) return true
        if (!Ffmpeg.loaded) return false
        avdevice_register_all()
        val fmt = av_find_input_format(format) ?: return false
        for (a in attempts) {
            val opts = AVDictionary(null)
            a.forEach { (k, v) -> av_dict_set(opts, k, v, 0) }
            val ctx = AVFormatContext(null)
            val r = avformat_open_input(ctx, url, fmt, opts)
            av_dict_free(opts)
            if (r < 0) continue
            if (avformat_find_stream_info(ctx, null as PointerPointer<*>?) < 0 || !openDecoder(ctx)) {
                avformat_close_input(ctx)
                continue
            }
            input = ctx
            return true
        }
        return false
    }

    private fun openDecoder(ctx: AVFormatContext): Boolean {
        for (i in 0 until ctx.nb_streams()) {
            val par = ctx.streams(i).codecpar()
            if (par.codec_type() != AVMEDIA_TYPE_VIDEO) continue
            if (par.codec_id() !in CAPTURE_CODECS) {
                System.err.println("[call] capture $format: codec ${par.codec_id()} is not one this app decodes")
                return false
            }
            val codec = avcodec_find_decoder(par.codec_id()) ?: return false
            val d = avcodec_alloc_context3(codec) ?: return false
            if (avcodec_parameters_to_context(d, par) < 0 || avcodec_open2(d, codec, null as AVDictionary?) < 0) {
                avcodec_free_context(d)
                return false
            }
            decoder = d
            stream = i
            return true
        }
        return false
    }

    @Synchronized
    override fun start(onFrame: (VideoFrame) -> Unit) {
        if (running) return
        if (!open()) return
        running = true
        thread = Thread({ readLoop(onFrame) }, "call-capture-$format").apply { isDaemon = true; start() }
    }

    private fun readLoop(onFrame: (VideoFrame) -> Unit) {
        val ctx = input ?: return
        val dec = decoder ?: return
        val pkt = av_packet_alloc()
        val frame = av_frame_alloc()
        val convert = ToI420()
        val intervalNs = 1_000_000_000L / fps
        var next = 0L
        val t0 = System.nanoTime()
        try {
            while (running && av_read_frame(ctx, pkt) >= 0) {
                if (pkt.stream_index() == stream && avcodec_send_packet(dec, pkt) >= 0) {
                    while (avcodec_receive_frame(dec, frame) == 0) {
                        val now = System.nanoTime()
                        if (now < next) continue // thinned to fps
                        next = maxOf(next + intervalNs, now - intervalNs)
                        val (w, h) = fit(frame.width(), frame.height())
                        onFrame(convert.convert(frame, w, h, (now - t0) / 1000))
                    }
                }
                av_packet_unref(pkt)
            }
        } catch (t: Throwable) {
            System.err.println("[call] capture $format $url stopped: $t")
        } finally {
            convert.close()
            av_frame_free(frame)
            av_packet_free(pkt)
            close()
        }
    }

    /** Inside maxW x maxH, aspect kept, even (I420). */
    private fun fit(w: Int, h: Int): Pair<Int, Int> {
        val s = minOf(1.0, maxW.toDouble() / w, maxH.toDouble() / h)
        return ((w * s).toInt() and 1.inv()).coerceAtLeast(2) to ((h * s).toInt() and 1.inv()).coerceAtLeast(2)
    }

    @Synchronized
    private fun close() {
        running = false
        decoder?.let { avcodec_free_context(it) }
        decoder = null
        input?.let { avformat_close_input(it) }
        input = null
    }

    override fun stop() {
        val t: Thread?
        synchronized(this) {
            if (!running) { close(); return }
            running = false
            t = thread
            thread = null
        }
        // The read loop closes the device when it sees running=false (within one frame).
        t?.join(2000)
    }
}
