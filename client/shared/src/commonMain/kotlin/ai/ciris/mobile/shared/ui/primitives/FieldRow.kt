package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.LocalLayoutBand
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.ui.theme.tone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * PRIMITIVE 2 · FieldRow — label column + value or input. Replaces every
 * stacked input card.
 *
 * Labels stack above values: that is the DEFAULT, not the fallback. At the
 * ≥560 band the label moves into a fixed left column so the eye reads down a
 * rule — the only layout that differs between phone and desktop, and it is
 * this one primitive. A hairline under each row; the label in mono uppercase
 * `mute`; a `protocol` line (the wire field name) in mono `mute` under the
 * label when a receipt shows it.
 */
@Composable
fun FieldRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    mono: Boolean = false,
    tone: Tone = Tone.INK,
    protocol: String? = null,
    gloss: String? = null,
    tag: String? = null,
    divider: Boolean = true,
    input: (@Composable () -> Unit)? = null,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val twoColumn = LocalLayoutBand.current.twoColumnFields
    val tagged = if (tag != null) modifier.testable(tag, value) else modifier

    val labelBlock: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(), style = type.label, color = t.mute)
            if (protocol != null) Text(protocol, style = type.signed, color = t.mute)
        }
    }
    val valueBlock: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (input != null) {
                input()
            } else if (value != null) {
                Text(value, style = if (mono) type.signed else type.body, color = t.tone(tone))
            }
            if (gloss != null) Text(gloss, style = type.body, color = t.dim)
        }
    }

    Column(modifier = tagged.fillMaxWidth()) {
        if (twoColumn) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.width(FIELD_LABEL_COLUMN)) { labelBlock() }
                Column(Modifier.weight(1f)) { valueBlock() }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                labelBlock()
                valueBlock()
            }
        }
        if (divider) HorizontalDivider(thickness = CirisShape.hairlineWidth, color = t.hairline)
    }
}

/** The fixed label column at the two-column band. */
val FIELD_LABEL_COLUMN = 160.dp
