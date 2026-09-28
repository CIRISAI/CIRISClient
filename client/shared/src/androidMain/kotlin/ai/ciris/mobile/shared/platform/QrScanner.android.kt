package ai.ciris.mobile.shared.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android scanning: CameraX for the preview and frames, zxing-core to read
 * them. zxing rather than ML Kit because ML Kit's barcode model arrives
 * through Google Play Services, and this app runs on devices without them.
 */
@Composable
actual fun rememberQrScanPlatform(): QrScanPlatform {
    val context = LocalContext.current
    // The launcher's callback is fixed at registration; the pending result
    // handler is not, so it lives in a holder the callback reads.
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.invoke(granted)
        pending[0] = null
    }
    return remember(context, launcher) {
        object : QrScanPlatform {
            override fun access(): QrCameraAccess = when {
                !scanningLibrariesPresent() -> QrCameraAccess.Unavailable(QrScanUnavailable.NOT_IN_THIS_BUILD)
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ->
                    QrCameraAccess.Unavailable(QrScanUnavailable.NO_CAMERA)
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> QrCameraAccess.Granted
                // Android re-asks until "don't ask again"; after that the
                // request returns false at once, which lands in PermissionDenied.
                else -> QrCameraAccess.Askable
            }

            override fun requestAccess(onResult: (granted: Boolean) -> Unit) {
                pending[0] = onResult
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

/**
 * The AAR ships as a bare file with no POM, so a host app must declare
 * CameraX and zxing-core itself. One that did not gets a sentence, not a
 * NoClassDefFoundError on the first tap.
 */
private fun scanningLibrariesPresent(): Boolean = try {
    Class.forName("androidx.camera.lifecycle.ProcessCameraProvider")
    Class.forName("androidx.camera.view.PreviewView")
    Class.forName("com.google.zxing.qrcode.QRCodeReader")
    true
} catch (_: ClassNotFoundException) {
    false
} catch (_: LinkageError) {
    false
}

@Composable
actual fun QrCameraPreview(
    onScanned: (String) -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestScanned by rememberUpdatedState(onScanned)
    val latestFailed by rememberUpdatedState(onFailed)
    val previewView = remember(context) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val delivered = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(context)
        var bound: Pair<ProcessCameraProvider, Array<androidx.camera.core.UseCase>>? = null
        var disposed = false

        future.addListener({
            if (disposed) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, QrFrameAnalyzer { text ->
                    // First read wins; later frames of the same code are dropped.
                    if (delivered.compareAndSet(false, true)) mainExecutor.execute { latestScanned(text) }
                })
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> null
                }
                if (selector == null) {
                    latestFailed()
                    return@addListener
                }
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                bound = provider to arrayOf(preview, analysis)
            } catch (e: Exception) {
                PlatformLogger.e("QrScanner", "camera did not start: ${e.message}")
                latestFailed()
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            bound?.let { (provider, useCases) -> provider.unbind(*useCases) }
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Reads the luminance (Y) plane of each frame; QR decoding needs nothing else. */
private class QrFrameAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        // Light-on-dark codes: other apps draw them in dark mode.
        DecodeHintType.ALSO_INVERTED to true,
    )

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val w = image.width
            val h = image.height
            val rowStride = plane.rowStride
            val luma = ByteArray(w * h)
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                buffer.get(luma, row * w, w)
            }
            val source = PlanarYUVLuminanceSource(luma, w, h, 0, 0, w, h, false)
            val text = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (_: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (!text.isNullOrEmpty()) onText(text)
        } catch (e: Exception) {
            PlatformLogger.w("QrScanner", "frame skipped: ${e.message}")
        } finally {
            image.close()
        }
    }
}
