package ai.ciris.mobile.shared.calls.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** iOS: ReplayKit asks for consent itself, and captures this app only. */
@Composable
actual fun rememberSharePicker(): SharePicker = remember {
    object : SharePicker {
        override fun choices() = emptyList<ShareChoice>()
        override val unavailable: String? = null
        override fun pick(choice: ShareChoice?, onSource: (VideoSource?) -> Unit) =
            onSource(runCatching { iosScreenShareSource() }.getOrNull())
    }
}
