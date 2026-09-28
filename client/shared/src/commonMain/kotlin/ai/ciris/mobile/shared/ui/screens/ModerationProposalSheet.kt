package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.viewmodels.ModerationAction
import ai.ciris.mobile.shared.viewmodels.ModerationViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reverse-quorum **moderation proposal** sheet (CIRIS Constitution 0.5.1
 * §4.5.13 — "presence is authority, absence forfeits it").
 *
 * Surfaced from any displayed piece of content in InteractScreen. It lets
 * the user pick one of three open-labeling actions — **Report**, **Request
 * takedown**, or a neutral **Keep-or-remove question** — add an optional
 * reason, and submit. Submitting POSTs the report→`scores` Contribution
 * (via [ModerationViewModel.submit]) and OPENS THE 48-HOUR COMMUNITY
 * WINDOW: a present moderator/steward may act sooner, otherwise the live
 * community (reverse-quorum) decides.
 *
 * NOTE: this is purely the PROPOSE affordance — anyone MAY raise it. The
 * authoritative duty-holder action lives elsewhere. A person the node records
 * as a minor is refused before any request, with the steward path named
 * ([ModerationViewModel]). A node without the route (every node today,
 * CIRISServer#665) is said to be one, in words, never as a failed submit.
 *
 * @param targetId the content/contributor id the proposal names.
 * @param viewModel holds the submit state + performs the POST.
 * @param onDismiss close the sheet (also called after a successful submit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationProposalSheet(
    targetId: String,
    viewModel: ModerationViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedAction by remember { mutableStateOf(ModerationAction.REPORT) }
    var reason by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .testable("sheet_moderation_proposal"),
        ) {
            Text(
                text = localizedString("moderation.proposal.title"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // The load-bearing reverse-quorum framing — stated up front so the
            // user knows exactly what submitting does. (CC 0.5.1 §4.5.13.)
            Text(
                text = localizedString("moderation.proposal.framing"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = localizedString("moderation.proposal.action_label"),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Action chips. Tags: btn_moderation_action_{report,takedown,question}.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModerationActionChip(
                    label = localizedString("moderation.proposal.action_report"),
                    testTag = "btn_moderation_action_report",
                    selected = selectedAction == ModerationAction.REPORT,
                    onSelect = { selectedAction = ModerationAction.REPORT },
                )
                ModerationActionChip(
                    label = localizedString("moderation.proposal.action_takedown"),
                    testTag = "btn_moderation_action_takedown",
                    selected = selectedAction == ModerationAction.TAKEDOWN,
                    onSelect = { selectedAction = ModerationAction.TAKEDOWN },
                )
                ModerationActionChip(
                    label = localizedString("moderation.proposal.action_question"),
                    testTag = "btn_moderation_action_question",
                    selected = selectedAction == ModerationAction.QUESTION,
                    onSelect = { selectedAction = ModerationAction.QUESTION },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(localizedString("moderation.proposal.reason_label")) },
                placeholder = { Text(localizedString("moderation.proposal.reason_placeholder")) },
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp)
                    .testable("input_moderation_reason"),
            )

            // Four outcomes, four tags: failed, no such route, refused as a
            // minor, raised. None of them shares pixels with another.
            state.error?.let { err ->
                Text(
                    text = localizedString("moderation.proposal.error", "detail", err),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp).testable("txt_moderation_proposal_error"),
                )
            }
            if (state.notOnThisNode) {
                Text(
                    text = localizedString("moderation.proposal.not_on_this_node"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp).testable("txt_moderation_proposal_not_on_this_node"),
                )
            }
            if (state.refusedAsMinor) {
                Text(
                    text = localizedString("moderation.proposal.refused_minor"),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp).testable("txt_moderation_proposal_refused_minor"),
                )
            }
            if (state.isDone) {
                Text(
                    text = localizedString("moderation.proposal.raised"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp).testable("txt_moderation_proposal_raised"),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.submit(
                        targetId = targetId,
                        action = selectedAction,
                        reason = reason,
                        onComplete = onDismiss,
                    )
                },
                enabled = !state.isSubmitting && !state.notOnThisNode && !state.refusedAsMinor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testableClickable(
                        "btn_moderation_submit",
                        enabled = !state.isSubmitting && !state.notOnThisNode && !state.refusedAsMinor,
                    ) {
                        viewModel.submit(
                            targetId = targetId,
                            action = selectedAction,
                            reason = reason,
                            onComplete = onDismiss,
                        )
                    },
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = localizedString(
                        if (state.isSubmitting) "moderation.proposal.submitting" else "moderation.proposal.submit",
                    ),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModerationActionChip(
    label: String,
    testTag: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.testableClickable(testTag) { onSelect() },
    )
}
