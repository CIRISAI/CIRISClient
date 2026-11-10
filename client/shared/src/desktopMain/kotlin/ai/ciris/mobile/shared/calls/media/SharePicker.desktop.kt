package ai.ciris.mobile.shared.calls.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktop lists its own screens and windows (x11grab, gdigrab, avfoundation); none on Wayland. */
@Composable
actual fun rememberSharePicker(): SharePicker = remember {
    object : SharePicker {
        override fun choices() = runCatching { desktopShareTargets() }.getOrDefault(emptyList())
            .map { ShareChoice(it.id, it.title, it.kind) }

        override val unavailable: String? = runCatching { desktopShareUnavailableReason() }.getOrElse { it.message }

        override fun pick(choice: ShareChoice?, onSource: (VideoSource?) -> Unit) {
            val target = desktopShareTargets().firstOrNull { it.id == choice?.id }
            onSource(target?.let { runCatching { desktopShareSource(it) }.getOrNull() })
        }
    }
}
