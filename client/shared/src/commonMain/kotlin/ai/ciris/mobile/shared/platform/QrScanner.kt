package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * "Scan one QR code and give me its text." The platform half.
 *
 * The UI half is `ui/primitives/QrScanAction.kt`, which a card places BESIDE
 * a paste field — never instead of one. Scanning is a convenience over
 * pasting, and the scanned text goes through the same handler a paste does,
 * so the test server drives the paste path and a device with no camera loses
 * nothing but a shortcut.
 *
 *  - Android: CameraX preview + zxing-core decoding frames on-device. No
 *    Google Play Services, so it works on de-Googled devices.
 *  - iOS: AVFoundation's own QR metadata output. No third-party code.
 *  - Desktop and web: no camera scanning. [QrCameraAccess.Unavailable] says
 *    so as DATA the UI renders as a sentence — never an exception, never a
 *    button that does nothing.
 */

/** Why scanning cannot happen here at all. Each has its own sentence. */
enum class QrScanUnavailable {
    /** Desktop and web: this platform has no camera scanning. */
    PLATFORM,

    /** A phone or tablet with no camera. */
    NO_CAMERA,

    /**
     * The scanning libraries are not in this build. The Android AAR is
     * consumed as a bare file with no POM, so a host app must add CameraX and
     * zxing-core itself; one that did not gets this sentence, not a crash.
     */
    NOT_IN_THIS_BUILD,
}

/** Where camera access stands, as the OS reports it right now. */
sealed interface QrCameraAccess {
    data object Granted : QrCameraAccess

    /** Not yet granted, and the OS will still ask the person. */
    data object Askable : QrCameraAccess

    /** Refused, and the OS will not ask again (iOS after one "Don't Allow"). */
    data object Denied : QrCameraAccess

    data class Unavailable(val reason: QrScanUnavailable) : QrCameraAccess
}

/** The OS-facing half of scanning. Obtain with [rememberQrScanPlatform]. */
interface QrScanPlatform {
    /** Read fresh each time: the person can change it in Settings between taps. */
    fun access(): QrCameraAccess

    /** Ask the OS for the camera; [onResult] runs on the main thread. */
    fun requestAccess(onResult: (granted: Boolean) -> Unit)
}

@Composable
expect fun rememberQrScanPlatform(): QrScanPlatform

/**
 * A live camera preview that reports the FIRST QR code it reads through
 * [onScanned], once, on the main thread; [onFailed] if the camera will not
 * start. Only composed after access is [QrCameraAccess.Granted]. Desktop and
 * web never compose it (their access is always Unavailable).
 */
@Composable
expect fun QrCameraPreview(
    onScanned: (String) -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier,
)

// ─── The state machine, pure so it is testable without a camera ─────────────

sealed interface QrScanState {
    /** A scan button. */
    data object Idle : QrScanState

    /** The OS permission prompt is up. */
    data object Asking : QrScanState

    /** The camera preview is up. */
    data object Scanning : QrScanState

    /** The person said no. Its own sentence, and a retry for after Settings. */
    data object PermissionDenied : QrScanState

    /** Access was granted and the camera still would not start. */
    data object CameraFailed : QrScanState

    /** No scanning here at all: a sentence, no button. */
    data class Unavailable(val reason: QrScanUnavailable) : QrScanState
}

sealed interface QrScanEvent {
    /** The scan (or retry) button, with access as it stands at the tap. */
    data class Tap(val access: QrCameraAccess) : QrScanEvent
    data class PermissionResult(val granted: Boolean) : QrScanEvent
    data object Scanned : QrScanEvent
    data object Cancel : QrScanEvent
    data object CameraFailed : QrScanEvent
}

/** Where a scan control starts, given access at first composition. */
fun qrScanInitial(access: QrCameraAccess): QrScanState =
    if (access is QrCameraAccess.Unavailable) QrScanState.Unavailable(access.reason) else QrScanState.Idle

/** The whole of the scan control's behaviour. Events a state does not expect leave it unchanged. */
fun QrScanState.on(event: QrScanEvent): QrScanState = when (this) {
    is QrScanState.Unavailable -> this
    QrScanState.Idle, QrScanState.PermissionDenied, QrScanState.CameraFailed -> when (event) {
        is QrScanEvent.Tap -> when (val a = event.access) {
            QrCameraAccess.Granted -> QrScanState.Scanning
            QrCameraAccess.Askable -> QrScanState.Asking
            QrCameraAccess.Denied -> QrScanState.PermissionDenied
            is QrCameraAccess.Unavailable -> QrScanState.Unavailable(a.reason)
        }
        else -> this
    }
    QrScanState.Asking -> when (event) {
        is QrScanEvent.PermissionResult ->
            if (event.granted) QrScanState.Scanning else QrScanState.PermissionDenied
        else -> this
    }
    QrScanState.Scanning -> when (event) {
        QrScanEvent.Scanned, QrScanEvent.Cancel -> QrScanState.Idle
        QrScanEvent.CameraFailed -> QrScanState.CameraFailed
        else -> this
    }
}

/**
 * The sentence a state shows, as a localization key; null for the two states
 * whose controls speak for themselves (Idle's button, Scanning's preview).
 */
fun QrScanState.messageKey(): String? = when (this) {
    QrScanState.Idle -> null
    QrScanState.Scanning -> "mobile.qr_scan_hint"
    QrScanState.Asking -> "mobile.qr_scan_asking"
    QrScanState.PermissionDenied -> "mobile.qr_scan_denied"
    QrScanState.CameraFailed -> "mobile.qr_scan_failed"
    is QrScanState.Unavailable -> when (reason) {
        QrScanUnavailable.PLATFORM -> "mobile.qr_scan_unavailable_platform"
        QrScanUnavailable.NO_CAMERA -> "mobile.qr_scan_unavailable_no_camera"
        QrScanUnavailable.NOT_IN_THIS_BUILD -> "mobile.qr_scan_unavailable_build"
    }
}

/** The platform every non-camera target returns: scanning is not here, and says so. */
internal object NoCameraQrScanPlatform : QrScanPlatform {
    override fun access(): QrCameraAccess = QrCameraAccess.Unavailable(QrScanUnavailable.PLATFORM)
    override fun requestAccess(onResult: (granted: Boolean) -> Unit) = onResult(false)
}
