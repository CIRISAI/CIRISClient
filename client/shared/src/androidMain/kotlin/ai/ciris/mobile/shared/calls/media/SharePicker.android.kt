package ai.ciris.mobile.shared.calls.media

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import ai.ciris.mobile.shared.localization.localizedString

/**
 * Android: the system's MediaProjection consent prompt, then the screen as a
 * source under a foreground-service notification, worded in the person's language.
 */
@Composable
actual fun rememberSharePicker(): SharePicker {
    val context = LocalContext.current
    val title = localizedString("mobile.call_share_notification_title")
    val text = localizedString("mobile.call_share_notification_text")
    val pending = remember { arrayOfNulls<(VideoSource?) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val source = if (result.resultCode == Activity.RESULT_OK && data != null) {
            runCatching { androidScreenSource(context, result.resultCode, data, title, text) }.getOrNull()
        } else null
        pending[0]?.invoke(source)
        pending[0] = null
    }
    return remember(context, launcher) {
        object : SharePicker {
            override fun choices() = emptyList<ShareChoice>()
            override val unavailable: String? = null
            override fun pick(choice: ShareChoice?, onSource: (VideoSource?) -> Unit) {
                pending[0] = onSource
                launcher.launch(androidScreenShareIntent(context))
            }
        }
    }
}
