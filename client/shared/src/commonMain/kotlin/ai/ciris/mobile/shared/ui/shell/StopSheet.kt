package ai.ciris.mobile.shared.ui.shell

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * STOP EVERYTHING — top right, every circle, every tab, above the scroll.
 *
 * On an agent build it is a [ConfirmSheet] naming three facts (what stops,
 * what stays, how to start again) and then the emergency shutdown. On a bare
 * node nothing is thinking or acting for the person, so the sheet says so —
 * the button is still there, in the same place, because it always is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopSheet(hasAgent: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (hasAgent) {
        ConfirmSheet(
            title = localizedString("nav.stop.title"),
            facts = listOf(
                ConfirmFact(localizedString("nav.stop.fact_what_label"), localizedString("nav.stop.fact_what")),
                ConfirmFact(localizedString("nav.stop.fact_keeps_label"), localizedString("nav.stop.fact_keeps")),
                ConfirmFact(localizedString("nav.stop.fact_again_label"), localizedString("nav.stop.fact_again")),
            ),
            confirmLabel = localizedString("nav.stop.confirm"),
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            destructive = true,
            tagPrefix = "stop",
        )
        return
    }
    val t = CirisTheme.tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.raised,
        contentColor = t.ink,
        shape = CirisShape.sheet,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().testable("sheet_stop").padding(18.dp).navigationBarsPadding()) {
            StateBlock(
                ListState.Empty(localizedString("nav.stop.nothing_body")),
                tag = "stop_nothing_running",
                inline = true,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CirisTextButton(localizedString("common_close"), tag = "btn_stop_cancel", onClick = onDismiss)
            }
        }
    }
}
