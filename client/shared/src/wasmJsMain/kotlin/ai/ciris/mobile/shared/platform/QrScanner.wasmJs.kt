package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/**
 * No camera scanning here. [NoCameraQrScanPlatform] reports it as data, and
 * QrScanAction renders it as one sentence telling the person to paste the
 * code instead — no button that could do nothing.
 */
@Composable
actual fun rememberQrScanPlatform(): QrScanPlatform = NoCameraQrScanPlatform

/** Never composed: access is always Unavailable on this target. */
@Composable
actual fun QrCameraPreview(
    onScanned: (String) -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier,
) {
    LaunchedEffect(Unit) { onFailed() }
}
