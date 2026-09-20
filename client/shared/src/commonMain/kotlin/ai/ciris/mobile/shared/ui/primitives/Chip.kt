package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.glyphs.Glyph
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.ui.theme.tone
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

/**
 * PRIMITIVE 6 · Chip — filter, sort, segmented choice. One component, three
 * uses, and a fourth that is not a use: a static badge (a trust state on a
 * row) is a chip with no click.
 *
 * Hairline outline, 5dp radius. Selected = `brand` hairline and `brand` text.
 * A toned chip (trust, canonical) fills tone@12% over the surface with the
 * tone as text — the design's "colour re-resolves, never inverts" applied to
 * a badge.
 */
enum class ChipKind { FILTER, SORT, CHOICE }

@Immutable
data class ChipSpec(
    val label: String,
    val tag: String? = null,
    val kind: ChipKind = ChipKind.FILTER,
    val selected: Boolean = false,
    val tone: Tone = Tone.INK,
    val glyph: GlyphName? = null,
    val onClick: (() -> Unit)? = null,
)

@Composable
fun Chip(spec: ChipSpec, modifier: Modifier = Modifier) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val toned = spec.tone != Tone.INK
    val fg = when {
        spec.selected -> t.brand
        toned -> t.tone(spec.tone)
        else -> t.dim
    }
    val outline = when {
        spec.selected -> t.brand
        toned -> t.tone(spec.tone).copy(alpha = 0.35f).compositeOver(t.raised)
        else -> t.hairlineStrong
    }
    val fill = when {
        spec.selected -> t.brand.copy(alpha = 0.10f).compositeOver(t.raised)
        toned -> t.tone(spec.tone).copy(alpha = 0.12f).compositeOver(t.raised)
        else -> t.raised
    }
    val base = modifier
        .clip(CirisShape.chip)
        .background(fill)
        .border(CirisShape.hairlineWidth, outline, CirisShape.chip)
    val gesture = when {
        spec.onClick != null && spec.tag != null -> base.testableClickable(spec.tag, spec.label) { spec.onClick.invoke() }
        spec.tag != null -> base.testable(spec.tag, spec.label)
        else -> base
    }
    Row(
        modifier = gesture.padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spec.glyph != null) Glyph(spec.glyph, tint = fg, size = 13.dp)
        Text(spec.label, style = type.label, color = fg)
    }
}
