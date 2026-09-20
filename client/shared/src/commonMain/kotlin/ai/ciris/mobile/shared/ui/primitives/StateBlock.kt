package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.Glyph
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.CirisTokens
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.ui.theme.tone
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * PRIMITIVE 7 · StateBlock — the list states, including gone and
 * hidden-by-your-rules, plus the one the Constitution requires.
 *
 * `populated · empty · loading · error · file-is-gone · hidden-by-your-rules`
 * are mandatory on every list surface (CSD/3 §2.2). **Error must never look
 * like empty**: a safety surface failing silently is worse than one that is
 * absent, because absence is at least visible (CSD-003). And `UNREVIEWED`
 * must be visually distinct and must not be green (CC part 8, Accountability
 * Display) — it lives here, not in per-card copy.
 *
 * Distinctness is three-way and pure: [ListState.style] returns tone, glyph
 * and border, and the test pins that Error and Unreviewed never take `ok`
 * and that Error never shares a look with Empty.
 */
sealed interface ListState {
    data object Populated : ListState
    data object Loading : ListState
    data class Empty(val message: String, val glyph: GlyphName = GlyphName.CIRCLE) : ListState
    data class Error(val title: String, val body: String? = null, val detail: String? = null) : ListState
    data class FileGone(val message: String) : ListState
    data class HiddenByRules(val message: String) : ListState
    data class Unreviewed(val message: String) : ListState
}

data class StateStyle(val tone: Tone, val glyph: GlyphName, val bordered: Boolean, val labelled: Boolean)

/** Pure: how each state looks. Testable without Compose. */
fun ListState.style(): StateStyle = when (this) {
    ListState.Populated -> error("StateBlock does not render Populated — render the list")
    ListState.Loading -> StateStyle(Tone.DIM, GlyphName.SYNC, bordered = false, labelled = false)
    is ListState.Empty -> StateStyle(Tone.MUTE, glyph, bordered = false, labelled = false)
    is ListState.Error -> StateStyle(Tone.DANGER, GlyphName.ERROR, bordered = true, labelled = true)
    is ListState.FileGone -> StateStyle(Tone.DIM, GlyphName.FILE_GONE, bordered = false, labelled = true)
    is ListState.HiddenByRules -> StateStyle(Tone.DIM, GlyphName.BLURRED, bordered = false, labelled = true)
    // Brand, not ok, not danger: distinct from both the green it must never be
    // and the error it is not. The label is what says UNREVIEWED.
    is ListState.Unreviewed -> StateStyle(Tone.BRAND, GlyphName.HOLD, bordered = true, labelled = true)
}

/** The glyph tint for a state — never `ok` for an error or an unreviewed state; the test pins it. */
fun ListState.tint(t: CirisTokens) = t.tone(style().tone)

@Composable
fun StateBlock(
    state: ListState,
    tag: String,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    if (state == ListState.Populated) return
    val style = state.style()
    val tint = t.tone(style.tone)
    val message = when (state) {
        is ListState.Empty -> state.message
        is ListState.Error -> state.title
        is ListState.FileGone -> state.message
        is ListState.HiddenByRules -> state.message
        is ListState.Unreviewed -> state.message
        ListState.Loading -> null
        ListState.Populated -> null
    }
    val label = when (state) {
        is ListState.Error -> localizedString("mobile.state_error")
        is ListState.FileGone -> localizedString("mobile.state_gone")
        is ListState.HiddenByRules -> localizedString("mobile.state_hidden_by_rules")
        is ListState.Unreviewed -> localizedString("mobile.state_unreviewed")
        else -> null
    }
    val frame = modifier
        .fillMaxWidth()
        .testable(tag, message ?: label)
        .let { m ->
            if (style.bordered) {
                m.clip(CirisShape.card)
                    .background(tint.copy(alpha = 0.06f))
                    .border(CirisShape.hairlineWidth, tint.copy(alpha = 0.45f), CirisShape.card)
            } else m
        }
        .padding(horizontal = 16.dp, vertical = if (inline) 12.dp else 28.dp)

    Column(
        modifier = frame,
        horizontalAlignment = if (inline) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state == ListState.Loading) {
                CircularProgressIndicator(color = t.brand, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Glyph(style.glyph, tint = tint, size = if (inline) 18.dp else 28.dp)
            }
            if (style.labelled && label != null) {
                Text(label.uppercase(), style = type.label, color = tint)
            }
        }
        if (message != null) {
            Text(message, style = if (inline) type.body else type.title, color = if (state is ListState.Empty) t.dim else t.ink)
        }
        if (state is ListState.Error && state.body != null) {
            Text(state.body, style = type.body, color = t.dim)
        }
        if (state is ListState.Error && state.detail != null) {
            Text(state.detail, style = type.signed, color = t.mute)
        }
        if (action != null) action()
    }
}
