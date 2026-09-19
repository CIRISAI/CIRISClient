package ai.ciris.mobile.shared.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * THE THREE BREAKPOINTS, AND NOTHING ELSE MOVES.
 *
 * "The phone is the design; the desktop is the phone with room." On a large
 * screen three things move and nothing is added (locked spec §1, "unfolded"):
 *
 *   ≥ 560 · field rows go two-column (label in a fixed left column)
 *   ≥ 700 · the seven tabs stop scrolling — all fit at once
 *   ≥ 900 · the bottom bar becomes the left rail, gaining counts and subtitles
 *
 * Measured ONCE, at the root, from the window — not per row. A `FieldRow`
 * asks `LocalLayoutBand.current`; it never subcomposes to find out. Set by
 * [CirisTheme]; `LocalIsCompactWindow` (< 600, the drawer/overlay decision)
 * stays where it is — a different question with a different answer.
 */
enum class LayoutBand {
    PHONE, TWO_COLUMN, TABS_FIT, RAIL;

    val twoColumnFields: Boolean get() = this >= TWO_COLUMN
    val tabsFit: Boolean get() = this >= TABS_FIT
    val rail: Boolean get() = this >= RAIL

    companion object {
        val TWO_COLUMN_AT: Dp = 560.dp
        val TABS_FIT_AT: Dp = 700.dp
        val RAIL_AT: Dp = 900.dp

        fun forWidth(width: Dp): LayoutBand = when {
            width >= RAIL_AT -> RAIL
            width >= TABS_FIT_AT -> TABS_FIT
            width >= TWO_COLUMN_AT -> TWO_COLUMN
            else -> PHONE
        }
    }
}

val LocalLayoutBand = staticCompositionLocalOf<LayoutBand> { LayoutBand.PHONE }

/** One measurement at the root; everything below reads the local. */
@Composable
fun LayoutBandProvider(content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val band = LayoutBand.forWidth(maxWidth)
        CompositionLocalProvider(LocalLayoutBand provides band, content = content)
    }
}
