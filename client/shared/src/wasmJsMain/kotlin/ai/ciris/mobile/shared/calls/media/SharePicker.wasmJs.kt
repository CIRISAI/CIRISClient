package ai.ciris.mobile.shared.calls.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * The browser's own picker (getDisplayMedia). It must start inside the click, so
 * the coroutine runs undispatched: getDisplayMedia is its first suspension.
 */
@Composable
actual fun rememberSharePicker(): SharePicker {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        object : SharePicker {
            override fun choices() = emptyList<ShareChoice>()
            override val unavailable: String? = null
            override fun pick(choice: ShareChoice?, onSource: (VideoSource?) -> Unit) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    onSource(runCatching { webDisplaySource() }.getOrNull())
                }
            }
        }
    }
}
