package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableCombinedClickable
import ai.ciris.mobile.shared.platform.testableWithHandler
import ai.ciris.mobile.shared.ui.glyphs.Glyph
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.ui.theme.tone
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * PRIMITIVE 3 · ItemRow — icon, title, meta, hamburger. Every list in the
 * product.
 *
 * THE HAMBURGER IS THE TEST. If a row came off the wire as a signed claim it
 * carries a [Receipt], and the receipt puts a hamburger on the row and opens
 * the same five facts on long-press. If a row has no receipt it has no
 * hamburger — it is app chrome, and a person can tell by looking. That is
 * enforced by construction: there is no way to draw the hamburger without a
 * receipt.
 *
 * The hamburger has its own drivable tag (`btn_receipt_<id>`) because a
 * long-press is not something `/click` can do.
 */
@Immutable
data class RowFlag(val text: String, val tag: String? = null, val tone: Tone = Tone.DANGER)

@Composable
fun ItemRow(
    glyph: GlyphName,
    title: String,
    tag: String,
    modifier: Modifier = Modifier,
    glyphTint: Color = CirisTheme.tokens.dim,
    meta: String? = null,
    metaMono: Boolean = true,
    secondary: String? = null,
    chips: List<ChipSpec> = emptyList(),
    flags: List<RowFlag> = emptyList(),
    receipt: Receipt? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onOpenReceipt: ((Receipt) -> Unit)? = null,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val openReceipt: (() -> Unit)? = if (receipt != null && onOpenReceipt != null) ({ onOpenReceipt(receipt) }) else null

    val shell = modifier
        .fillMaxWidth()
        .clip(CirisShape.card)
        .background(t.raised)
        .border(CirisShape.hairlineWidth, t.hairline, CirisShape.card)
    val gesture = when {
        onClick != null -> shell.testableCombinedClickable(tag, title, onLongClick = openReceipt) { onClick() }
        openReceipt != null -> shell.testable(tag, title).pointerInput(receipt) {
            detectTapGestures(onLongPress = { openReceipt() })
        }
        else -> shell.testable(tag, title)
    }

    Row(
        modifier = gesture.padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CirisShape.card)
                .background(glyphTint.copy(alpha = 0.12f).compositeOver(t.raised)),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(glyph, tint = glyphTint, size = 20.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, style = type.body, color = t.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                for (chip in chips) Chip(chip)
            }
            if (meta != null) Text(meta, style = if (metaMono) type.signed else type.body, color = t.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (secondary != null) Text(secondary, style = type.body, color = t.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            for (flag in flags) {
                val m = if (flag.tag != null) Modifier.testable(flag.tag, flag.text) else Modifier
                Text(flag.text, style = type.body, color = t.tone(flag.tone), modifier = m)
            }
        }
        if (trailing != null) {
            Box(Modifier.wrapContentWidth()) { trailing() }
        }
        if (openReceipt != null) {
            IconButton(
                onClick = openReceipt,
                modifier = Modifier.size(36.dp).testableWithHandler("btn_receipt_${receipt!!.id}") { openReceipt() },
            ) {
                Glyph(GlyphName.RECEIPT, tint = t.mute, size = 18.dp, contentDescription = localizedString("mobile.receipt_open"))
            }
        }
    }
}
