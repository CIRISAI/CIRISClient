package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * PRIMITIVE 8 · ConfirmSheet — three facts, two buttons. Sharing out, and
 * every other outward act.
 *
 * "Sharing outward always confirms; pulling inward never does. The confirm
 * names three facts about what changes — never a generic 'are you sure'."
 * Exactly three: [confirmFacts] refuses any other number, so a screen cannot
 * ship a vague confirm.
 */
@Immutable
data class ConfirmFact(val label: String, val value: String, val mono: Boolean = false)

/** Pure validation, testable without Compose. */
fun confirmFacts(facts: List<ConfirmFact>): List<ConfirmFact> {
    require(facts.size == 3) { "a confirm names exactly three facts, got ${facts.size}" }
    return facts
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    facts: List<ConfirmFact>,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = localizedString("mobile.confirm_cancel"),
    destructive: Boolean = false,
    tagPrefix: String = "confirm",
) {
    val checked = confirmFacts(facts)
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.raised,
        contentColor = t.ink,
        shape = CirisShape.sheet,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().testable("sheet_$tagPrefix", title)
                .padding(horizontal = 18.dp, vertical = 14.dp).navigationBarsPadding(),
        ) {
            Text(title, style = type.title, color = t.ink)
            Spacer(Modifier.height(8.dp))
            checked.forEachIndexed { i, f ->
                FieldRow(label = f.label, value = f.value, mono = f.mono, tag = "${tagPrefix}_fact_${i + 1}", divider = i < 2)
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = androidx.compose.ui.Alignment.End)) {
                CirisTextButton(dismissLabel, tag = "btn_${tagPrefix}_cancel", onClick = onDismiss)
                CirisButton(confirmLabel, tag = "btn_${tagPrefix}_confirm", onClick = onConfirm, danger = destructive)
            }
        }
    }
}
