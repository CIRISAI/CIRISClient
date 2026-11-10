package ai.ciris.mobile.shared.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android: RECORD_AUDIO and CAMERA, both declared in this library's manifest and
 * asked at runtime when the person starts a call.
 */
@Composable
actual fun rememberCallMediaPlatform(): CallMediaPlatform {
    val context = LocalContext.current
    // The launcher's callback is fixed at registration; the pending handler is not.
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.invoke(granted)
        pending[0] = null
    }
    return remember(context, launcher) {
        object : CallMediaPlatform {
            override fun access(source: MediaSource): MediaAccess {
                val (feature, permission) = when (source) {
                    MediaSource.MICROPHONE -> PackageManager.FEATURE_MICROPHONE to Manifest.permission.RECORD_AUDIO
                    MediaSource.CAMERA -> PackageManager.FEATURE_CAMERA_ANY to Manifest.permission.CAMERA
                }
                return when {
                    !context.packageManager.hasSystemFeature(feature) ->
                        MediaAccess.Unavailable(MediaUnavailable.NO_DEVICE)
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED ->
                        MediaAccess.Granted
                    // Android re-asks until "don't ask again"; callPermissionStep's
                    // asked set turns a second refusal into Denied.
                    else -> MediaAccess.Askable
                }
            }

            override fun requestAccess(source: MediaSource, onResult: (granted: Boolean) -> Unit) {
                pending[0] = onResult
                launcher.launch(
                    when (source) {
                        MediaSource.MICROPHONE -> Manifest.permission.RECORD_AUDIO
                        MediaSource.CAMERA -> Manifest.permission.CAMERA
                    }
                )
            }
        }
    }
}
