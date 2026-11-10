@file:OptIn(ExperimentalForeignApi::class)

package ai.ciris.mobile.shared.calls.media

import kotlinx.cinterop.*
// Wildcards on purpose, as in QrScanner.ios.kt: some AVCaptureDevice class
// methods come from Objective-C categories.
import platform.AVFoundation.*
import platform.CoreFoundation.CFRetain
import platform.CoreMedia.*
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.ReplayKit.*
import platform.darwin.*

/**
 * The front camera as a call [VideoSource]: AVCaptureSession at 640×480, NV12,
 * portrait, throttled to [fps], each frame converted to I420. Requires
 * NSCameraUsageDescription and granted camera access (CallMediaPlatform asks).
 * Frames arrive on a private capture queue.
 */
fun iosFrontCameraSource(fps: Int = 15): VideoSource = IosCameraSource(fps)

/**
 * This app's screen as a call [VideoSource] ([VideoKind.SCREEN]), through
 * ReplayKit's in-app capture (`RPScreenRecorder.startCapture`): only this app's
 * own windows, never other apps, and iOS shows its own consent prompt. Scaled so
 * neither side exceeds [maxDimension], throttled to [fps].
 */
fun iosScreenShareSource(fps: Int = 5, maxDimension: Int = 960): VideoSource = IosScreenShareSource(fps, maxDimension)

/** Drops frames that come sooner than 1/fps after the last one kept. */
private class FrameThrottle(fps: Int) {
    private val intervalUs = 1_000_000L / fps.coerceAtLeast(1)
    private var lastUs = Long.MIN_VALUE

    fun take(nowUs: Long): Boolean {
        // A little slack so a 15 fps camera at a 15 fps target is not halved by jitter.
        if (lastUs != Long.MIN_VALUE && nowUs - lastUs < intervalUs * 9 / 10) return false
        lastUs = nowUs
        return true
    }
}

private class IosCameraSource(private val fps: Int) : VideoSource {
    override val kind = VideoKind.CAMERA
    private val lock = NSLock()
    private var session: AVCaptureSession? = null
    private var delegate: CameraDelegate? = null
    private val queue = dispatch_queue_create("ai.ciris.call.camera", null)

    override fun start(onFrame: (VideoFrame) -> Unit) {
        lock.lock()
        try {
            if (session != null) return
            val device = AVCaptureDevice.defaultDeviceWithDeviceType(
                AVCaptureDeviceTypeBuiltInWideAngleCamera,
                AVMediaTypeVideo,
                AVCaptureDevicePositionFront,
            ) ?: AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return
            val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: return
            val s = AVCaptureSession()
            s.beginConfiguration()
            if (s.canSetSessionPreset(AVCaptureSessionPreset640x480)) s.sessionPreset = AVCaptureSessionPreset640x480
            if (!s.canAddInput(input)) { s.commitConfiguration(); return }
            s.addInput(input)
            val output = AVCaptureVideoDataOutput()
            // CFString key bridged to NSString: retained first, so the bridge's release balances it.
            val formatKey = CFBridgingRelease(CFRetain(kCVPixelBufferPixelFormatTypeKey))
            output.videoSettings = mapOf(formatKey to NSNumber(unsignedInt = PIXEL_NV12_VIDEO))
            output.alwaysDiscardsLateVideoFrames = true
            val d = CameraDelegate(FrameThrottle(fps), onFrame)
            output.setSampleBufferDelegate(d, queue)
            if (!s.canAddOutput(output)) { s.commitConfiguration(); return }
            s.addOutput(output)
            output.connectionWithMediaType(AVMediaTypeVideo)?.let { c ->
                // Upright for a phone held in portrait; the far end shows it as sent.
                @Suppress("DEPRECATION")
                c.videoOrientation = AVCaptureVideoOrientationPortrait
            }
            s.commitConfiguration()
            session = s
            delegate = d
            // startRunning blocks while the camera spins up; keep it off the caller's thread.
            dispatch_async(queue) { s.startRunning() }
        } finally {
            lock.unlock()
        }
    }

    override fun stop() {
        lock.lock()
        try {
            val s = session ?: return
            session = null
            delegate?.stopped = true
            delegate = null
            dispatch_async(queue) { s.stopRunning() }
        } finally {
            lock.unlock()
        }
    }
}

private class CameraDelegate(
    private val throttle: FrameThrottle,
    private val onFrame: (VideoFrame) -> Unit,
) : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    var stopped = false

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        if (stopped) return
        val sb = didOutputSampleBuffer ?: return
        val now = nowUs()
        if (!throttle.take(now)) return
        val image = CMSampleBufferGetImageBuffer(sb) ?: return
        pixelBufferToI420(image, timestampUs = now)?.let(onFrame)
    }
}

private class IosScreenShareSource(private val fps: Int, private val maxDimension: Int) : VideoSource {
    override val kind = VideoKind.SCREEN
    private val lock = NSLock()
    private var running = false

    override fun start(onFrame: (VideoFrame) -> Unit) {
        lock.lock()
        try {
            if (running) return
            val recorder = RPScreenRecorder.sharedRecorder()
            if (!recorder.available) return
            running = true
            val throttle = FrameThrottle(fps)
            recorder.startCaptureWithHandler(
                { sampleBuffer, type, error ->
                    if (error != null || type != RPSampleBufferTypeVideo || sampleBuffer == null) return@startCaptureWithHandler
                    val now = nowUs()
                    if (!throttle.take(now)) return@startCaptureWithHandler
                    val image = CMSampleBufferGetImageBuffer(sampleBuffer) ?: return@startCaptureWithHandler
                    pixelBufferToI420(image, maxDimension, now)?.let(onFrame)
                },
                { error ->
                    // Refused (the person said no, or another capture holds the recorder).
                    if (error != null) {
                        lock.lock()
                        running = false
                        lock.unlock()
                    }
                },
            )
        } finally {
            lock.unlock()
        }
    }

    override fun stop() {
        lock.lock()
        try {
            if (!running) return
            running = false
            RPScreenRecorder.sharedRecorder().stopCaptureWithHandler { _ -> }
        } finally {
            lock.unlock()
        }
    }
}
