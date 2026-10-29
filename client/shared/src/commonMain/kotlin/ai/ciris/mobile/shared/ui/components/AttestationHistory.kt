package ai.ciris.mobile.shared.ui.components

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **History is the row's lifecycle, not its details.**
 *
 * The History item used to open the same view as View details, so "history"
 * showed one row and nothing that happened to it. A history is the chain: the
 * original, then each later row that named it — a replacement, a withdrawal, a
 * recantation, or a new version — each with who signed it and when.
 *
 * Where a node serves no fuller chain, the dialog shows what the row itself
 * records (its own state and the id of the row that changed it) and says that
 * is all the node offers. It never presents a partial chain as the whole one.
 */
enum class HistoryStep { Original, Supersede, Withdraw, Recant, Version }

data class HistoryEntry(
    val attestationId: String,
    val step: HistoryStep,
    /** What signed this step. */
    val attesterKeyId: String? = null,
    /** Who it speaks for, where that differs from the signer (a chat message's author). */
    val authorKeyId: String? = null,
    /** RFC 3339, or null when the row that recorded this step does not say. */
    val at: String? = null,
    /** For a version: its number. */
    val version: Int? = null,
    /** A short line of what this step carries (a replacement's text, a version's members). */
    val note: String? = null,
)

sealed interface AttHistory {
    data object Loading : AttHistory

    /** [complete] is false when only the row's own record is shown. */
    data class Loaded(val entries: List<HistoryEntry>, val complete: Boolean) : AttHistory

    data class Failed(val reason: String) : AttHistory
}

/**
 * The history a row can tell about itself, without a chain from the node: the
 * original, and — when it is no longer active — the step that changed it,
 * whose own signer and time the row does not carry.
 */
fun rowOnlyHistory(att: Attestation): AttHistory.Loaded {
    val original = HistoryEntry(
        attestationId = att.id,
        step = HistoryStep.Original,
        attesterKeyId = att.attesterKeyId,
        authorKeyId = att.authorKeyId,
        at = att.timestamp,
    )
    val later = when (att.status) {
        AttStatus.Superseded -> HistoryStep.Supersede
        AttStatus.Withdrawn -> HistoryStep.Withdraw
        AttStatus.Recanted -> HistoryStep.Recant
        else -> null
    }
    val entries = listOfNotNull(
        original,
        later?.let { HistoryEntry(attestationId = att.supersededBy ?: "", step = it) },
    )
    return AttHistory.Loaded(entries, complete = false)
}

@Composable
private fun HistoryStep.label(entry: HistoryEntry): String = when (this) {
    HistoryStep.Original -> localizedString("mobile.att_history_step_original")
    HistoryStep.Supersede -> localizedString("mobile.att_history_step_supersede")
    HistoryStep.Withdraw -> localizedString("mobile.att_history_step_withdraw")
    HistoryStep.Recant -> localizedString("mobile.att_history_step_recant")
    HistoryStep.Version -> localizedString("mobile.att_history_step_version", mapOf("n" to (entry.version?.toString() ?: "?")))
}

/** The history of one row, oldest first. */
@Composable
fun AttestationHistoryDialog(title: String, history: AttHistory, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testable("dialog_att_history"),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (history) {
                    AttHistory.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.testable("att_history_loading"))
                    }
                    is AttHistory.Failed -> Text(
                        localizedString("mobile.att_history_failed", mapOf("why" to history.reason)),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testable("att_history_error"),
                    )
                    is AttHistory.Loaded -> {
                        history.entries.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider()
                            Column(
                                modifier = Modifier.fillMaxWidth().testable("att_history_entry_$i"),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(e.step.label(e), fontWeight = FontWeight.SemiBold)
                                if (e.attestationId.isNotBlank()) {
                                    Text(e.attestationId, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                e.authorKeyId?.let {
                                    Text(localizedString("mobile.att_history_written_by", mapOf("key" to it)), fontSize = 12.sp)
                                }
                                Text(
                                    e.attesterKeyId?.let { localizedString("mobile.att_history_signed_by", mapOf("key" to it)) }
                                        ?: localizedString("mobile.att_history_signer_unknown"),
                                    fontSize = 12.sp,
                                )
                                Text(e.at ?: localizedString("mobile.att_history_time_unknown"), fontSize = 12.sp)
                                e.note?.let { Text(it, fontSize = 12.sp) }
                            }
                        }
                        if (!history.complete) {
                            Text(
                                localizedString("mobile.att_history_partial"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp).testable("att_history_partial"),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testable("btn_att_history_close")) {
                Text(localizedString("mobile.common_close"))
            }
        },
    )
}
