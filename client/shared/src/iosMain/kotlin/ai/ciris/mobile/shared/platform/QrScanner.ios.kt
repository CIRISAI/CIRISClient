package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
// Wildcards on purpose: several AVCaptureDevice class methods come from
// Objective-C categories, which Kotlin/Native may surface as extensions that
// need their own import. A wildcard resolves either shape.
import platform.AVFoundation.*
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.darwin.*

/**
 * iOS scanning: AVFoundation's metadata output does the QR decoding itself
 * (AVMetadataObjectTypeQRCode). No third-party code. The Info.plist carries
 * NSCameraUsageDescription; without it iOS kills the app on first camera use.
 */
@Composable
actual fun rememberQrScanPlatform(): QrScanPlatform = remember { IosQrScanPlatform }

private object IosQrScanPlatform : QrScanPlatform {
    override fun access(): QrCameraAccess {
        // The simulator, and any device without a camera, has no video device.
        if (AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) == null) {
            return QrCameraAccess.Unavailable(QrScanUnavailable.NO_CAMERA)
        }
        return when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> QrCameraAccess.Granted
            AVAuthorizationStatusNotDetermined -> QrCameraAccess.Askable
            // Denied or Restricted: iOS asks once, ever. Settings is the way back.
            else -> QrCameraAccess.Denied
        }
    }

    override fun requestAccess(onResult: (granted: Boolean) -> Unit) {
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
            dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QrCameraPreview(
    onScanned: (String) -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier,
) {
    val latestScanned by rememberUpdatedState(onScanned)
    val latestFailed by rememberUpdatedState(onFailed)
    val session = remember { AVCaptureSession() }
    val delegate = remember { QrMetadataDelegate { text -> latestScanned(text) } }

    DisposableEffect(session) {
        if (configure(session, delegate)) {
            // startRunning blocks while the camera spins up; keep it off the main thread.
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                session.startRunning()
            }
        } else {
            latestFailed()
        }
        onDispose { session.stopRunning() }
    }

    UIKitView(
        factory = { QrPreviewView(session) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun configure(session: AVCaptureSession, delegate: QrMetadataDelegate): Boolean {
    val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return false
    val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: return false
    if (!session.canAddInput(input)) return false
    session.addInput(input)
    val output = AVCaptureMetadataOutput()
    if (!session.canAddOutput(output)) return false
    session.addOutput(output)
    output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
    // Only after addOutput: the available types are empty until then.
    output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
    return true
}

/** Delivers the first QR string AVFoundation reads, once. */
private class QrMetadataDelegate(
    private val onText: (String) -> Unit,
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    private var delivered = false

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        if (delivered) return
        val text = didOutputMetadataObjects
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue?.takeIf { s -> s.isNotEmpty() } }
            ?: return
        delivered = true
        onText(text)
    }
}

/** A UIView whose preview layer follows its bounds. */
@OptIn(ExperimentalForeignApi::class)
private class QrPreviewView(session: AVCaptureSession) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    init {
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.setFrame(bounds)
    }
}
