package ai.ciris.mobile.shared.calls.media

import androidx.compose.runtime.Composable

/** Something this device can share: a screen or one window (CSD-134). */
class ShareChoice(val id: String, val title: String, val kind: VideoKind)

/**
 * How this platform picks what to share. Desktop lists screens and windows in the
 * app; Android, iOS and the browser show their own consent or picker, so their
 * [choices] is empty and [pick] goes straight to it.
 */
interface SharePicker {
    /** Choices the app offers itself; empty when the system shows its own picker. */
    fun choices(): List<ShareChoice>

    /** Null when sharing can work here; otherwise why not, logged, with the screen saying it in words. */
    val unavailable: String?

    /** Start sharing [choice] (null for a system picker); [onSource] gets null when the person declines. */
    fun pick(choice: ShareChoice?, onSource: (VideoSource?) -> Unit)
}

@Composable
expect fun rememberSharePicker(): SharePicker
