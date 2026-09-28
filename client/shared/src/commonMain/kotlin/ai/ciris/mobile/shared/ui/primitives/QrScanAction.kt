package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.QrCameraPreview
import ai.ciris.mobile.shared.platform.QrScanEvent
import ai.ciris.mobile.shared.platform.QrScanState
import ai.ciris.mobile.shared.platform.messageKey
import ai.ciris.mobile.shared.platform.on
import ai.ciris.mobile.shared.platform.qrScanInitial
import ai.ciris.mobile.shared.platform.rememberQrScanPlatform
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * PRIMITIVE · QrScanAction — "scan it instead of pasting it", placed BESIDE a
 * [CirisTextField] paste field, never in place of one.
 *
 * [onScanned] should be the SAME handler the paste field feeds, so a scan and
 * a paste are one path: the test server drives that path by `/input` on the
 * field, and a device that cannot scan loses a shortcut, not the flow.
 *
 * Every state is visible and says what it is (platform/QrScanner.kt holds the
 * machine): a scan button; the permission prompt; a live preview with a stop
 * button; permission refused — its own sentence, and a retry for after the
 * person changes it in Settings; the camera failing; and, on desktop and web,
 * one sentence saying to paste instead, with no button to press.
 *
 * Tags, all under [tag]: `[tag]` the scan / retry button, `[tag]_cancel`,
 * `[tag]_status` the sentence (its text is the sentence, for `/tree`),
 * `[tag]_preview` the camera.
 */
@Composable
fun QrScanAction(
    onScanned: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val platform = rememberQrScanPlatform()
    var state by remember(platform) { mutableStateOf(qrScanInitial(platform.access())) }
    val latestOnScanned by rememberUpdatedState(onScanned)
    val t = CirisTheme.tokens
    val type = CirisTheme.type

    if (state == QrScanState.Asking) {
        LaunchedEffect(Unit) {
            platform.requestAccess { granted -> state = state.on(QrScanEvent.PermissionResult(granted)) }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val s = state
        s.messageKey()?.let { key ->
            val sentence = localizedString(key)
            val warns = s == QrScanState.PermissionDenied || s == QrScanState.CameraFailed
            Text(
                sentence,
                style = type.body,
                color = if (warns) t.danger else t.dim,
                modifier = Modifier.testable("${tag}_status", sentence),
            )
        }
        when (s) {
            QrScanState.Idle, QrScanState.PermissionDenied, QrScanState.CameraFailed -> CirisTextButton(
                label = localizedString(if (s == QrScanState.Idle) "mobile.qr_scan_button" else "mobile.qr_scan_retry"),
                tag = tag,
                onClick = { state = state.on(QrScanEvent.Tap(platform.access())) },
            )
            QrScanState.Asking -> Unit
            QrScanState.Scanning -> {
                QrCameraPreview(
                    onScanned = { text ->
                        state = state.on(QrScanEvent.Scanned)
                        latestOnScanned(text)
                    },
                    onFailed = { state = state.on(QrScanEvent.CameraFailed) },
                    modifier = Modifier
                        .size(PREVIEW_SIDE)
                        .clip(CirisShape.card)
                        .border(CirisShape.hairlineWidth, t.hairlineStrong, CirisShape.card)
                        .testable("${tag}_preview"),
                )
                CirisTextButton(
                    label = localizedString("mobile.qr_scan_cancel"),
                    tag = "${tag}_cancel",
                    onClick = { state = state.on(QrScanEvent.Cancel) },
                )
            }
            is QrScanState.Unavailable -> Unit // the sentence is the whole of it
        }
    }
}

private val PREVIEW_SIDE = 240.dp
