package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.ErasureFailure
import ai.ciris.mobile.shared.models.ErasureOutcome
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextField
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.FieldRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.viewmodels.DataErasureController
import ai.ciris.mobile.shared.viewmodels.ReceiptCheckState
import ai.ciris.mobile.shared.viewmodels.ReceiptKeyState
import ai.ciris.mobile.shared.viewmodels.TraceErasureState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Data card's erasure and receipt sections (FSD/CSD/CSD-039 §2, §3).
 *
 * Two rules drive every line here:
 *  1. **What the person reads after an erasure is what the host reported** —
 *     its counts, its time, its scope note. "Erased" is said only for
 *     [ErasureOutcome.Erased]; nothing-there, partial and refused each say
 *     what they are.
 *  2. **The card says what erasure cannot reach, before and after.** The
 *     node's erasure is TRACES ONLY; nothing it mints today is erasable
 *     (CIRISPersist#914); the person's own right to be forgotten is not
 *     reachable from this app (CIRISServer#677). Deleting traces must never
 *     read as deleting a person.
 */
@Composable
fun NodeTraceErasureSection(
    controller: DataErasureController,
    /** The attached agent's trace id, or null on a node-only build — the node's own id files no traces. */
    prefillAgentIdHash: String?,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val state by controller.traceErasure.collectAsState()
    var agentIdHash by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(prefillAgentIdHash) {
        if (agentIdHash.isBlank() && !prefillAgentIdHash.isNullOrBlank()) agentIdHash = prefillAgentIdHash
    }

    Text(localizedString("mobile.data_erase_title"), style = type.title, color = t.ink)
    CardShell(tag = "data_erase_traces_section") {
        Text(localizedString("mobile.data_erase_desc"), style = type.body, color = t.dim)
        Spacer(Modifier.height(8.dp))
        FieldRow(
            label = localizedString("mobile.data_erase_reaches_label"),
            value = localizedString("mobile.data_erase_reaches"),
            tag = "data_erase_reaches",
        )
        FieldRow(
            label = localizedString("mobile.data_erase_not_reached_label"),
            value = localizedString("mobile.data_erase_not_reached"),
            tone = Tone.DANGER,
            tag = "data_erase_does_not_reach",
        )
        FieldRow(
            label = localizedString("mobile.data_erase_self_label"),
            value = localizedString("mobile.data_erase_self_blocked"),
            tone = Tone.DIM,
            tag = "data_erase_self_blocked",
        )
        FieldRow(
            label = localizedString("mobile.data_erase_agent_id_label"),
            gloss = if (!prefillAgentIdHash.isNullOrBlank() && agentIdHash == prefillAgentIdHash) {
                localizedString("mobile.data_erase_agent_id_prefilled")
            } else {
                null
            },
            input = {
                CirisTextField(
                    tag = "input_erase_agent_id_hash",
                    value = agentIdHash,
                    onValueChange = { agentIdHash = it },
                    placeholder = localizedString("mobile.data_erase_agent_id_placeholder"),
                    mono = true,
                )
            },
        )
        FieldRow(
            label = localizedString("mobile.data_erase_reason_label"),
            divider = false,
            input = {
                CirisTextField(
                    tag = "input_erase_reason",
                    value = reason,
                    onValueChange = { reason = it },
                    placeholder = localizedString("mobile.data_erase_reason_placeholder"),
                )
            },
        )
        Spacer(Modifier.height(10.dp))
        CirisButton(
            label = localizedString("mobile.data_erase_button"),
            tag = "btn_erase_traces",
            onClick = { if (controller.canErase(agentIdHash, reason)) confirming = true },
            enabled = controller.canErase(agentIdHash, reason),
            danger = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TraceErasureOutcome(state)
    }

    if (confirming) {
        ConfirmSheet(
            title = localizedString("mobile.data_erase_confirm_title"),
            facts = listOf(
                ConfirmFact(
                    localizedString("mobile.data_erase_fact_erased_label"),
                    localizedString("mobile.data_erase_fact_erased").replace("{hash}", agentIdHash.trim()),
                ),
                ConfirmFact(
                    localizedString("mobile.data_erase_fact_kept_label"),
                    localizedString("mobile.data_erase_fact_kept"),
                ),
                ConfirmFact(
                    localizedString("mobile.data_erase_fact_signer_label"),
                    localizedString("mobile.data_erase_fact_signer"),
                ),
            ),
            confirmLabel = localizedString("mobile.data_erase_confirm_button"),
            onConfirm = {
                confirming = false
                controller.eraseAgentTraces(agentIdHash, reason)
            },
            onDismiss = { confirming = false },
            destructive = true,
            tagPrefix = "erase_traces",
        )
    }
}

@Composable
private fun TraceErasureOutcome(state: TraceErasureState) {
    when (state) {
        TraceErasureState.Idle -> Unit
        TraceErasureState.Working -> {
            Spacer(Modifier.height(10.dp))
            StateBlock(ListState.Loading, tag = "data_erase_working", inline = true)
        }
        is TraceErasureState.Failed -> {
            Spacer(Modifier.height(10.dp))
            ErasureFailureBlock(state.failure, tagPrefix = "data_erase", notOnThisHost = localizedString("mobile.data_erase_not_on_this_node"))
        }
        is TraceErasureState.Answered -> {
            Spacer(Modifier.height(10.dp))
            when (val o = state.outcome) {
                ErasureOutcome.NothingThere -> StateBlock(
                    ListState.Empty(localizedString("mobile.data_erase_nothing")),
                    tag = "data_erase_nothing",
                    inline = true,
                )
                is ErasureOutcome.Partial -> StateBlock(
                    ListState.Error(
                        title = localizedString("mobile.data_erase_partial"),
                        body = localizedString("mobile.data_erase_partial_body"),
                        detail = if (o.withdrawsFailed > 0) "withdraws_failed=${o.withdrawsFailed}" else null,
                    ),
                    tag = "data_erase_partial",
                    inline = true,
                )
                ErasureOutcome.Erased -> Unit
            }
            // The node's own report, whatever the outcome: the counts are the
            // answer, and zero is a count the node gave, not a default.
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                FieldRow(
                    label = localizedString("mobile.data_erase_result_outcome_label"),
                    value = when (state.outcome) {
                        ErasureOutcome.Erased -> localizedString("mobile.data_erase_result_erased")
                        ErasureOutcome.NothingThere -> localizedString("mobile.data_erase_result_nothing")
                        is ErasureOutcome.Partial -> localizedString("mobile.data_erase_result_not_done")
                    },
                    tone = if (state.outcome == ErasureOutcome.Erased) Tone.OK else Tone.DANGER,
                    tag = "data_erase_result_outcome",
                )
                FieldRow(
                    label = localizedString("mobile.data_erase_result_traces"),
                    value = state.result.traceEvents.toString(),
                    protocol = "trace_events",
                    tag = "data_erase_result_trace_events",
                )
                FieldRow(
                    label = localizedString("mobile.data_erase_result_llm_calls"),
                    value = state.result.traceLlmCalls.toString(),
                    protocol = "trace_llm_calls",
                    tag = "data_erase_result_llm_calls",
                )
                FieldRow(
                    label = localizedString("mobile.data_erase_result_detections"),
                    value = state.result.detectionEventsTombstoned.toString(),
                    protocol = "detection_events_tombstoned",
                    tag = "data_erase_result_detections",
                )
                FieldRow(
                    label = localizedString("mobile.data_erase_result_at"),
                    value = state.result.erasedAt ?: NOT_READ,
                    mono = true,
                    tag = "data_erase_result_erased_at",
                )
                state.result.scopeNote?.let {
                    FieldRow(
                        label = localizedString("mobile.data_erase_result_scope_label"),
                        value = it,
                        tone = Tone.DIM,
                        tag = "data_erase_result_scope_note",
                    )
                }
                FieldRow(
                    label = localizedString("mobile.data_erase_result_receipt_label"),
                    value = localizedString("mobile.data_erase_result_receipt_none"),
                    tone = Tone.DIM,
                    divider = false,
                    tag = "data_erase_result_receipt",
                )
            }
        }
    }
}

/**
 * Checking a deletion receipt the agent signed — the `/v1/verification` routes. Shown
 * only with an agent attached: the routes are the agent's.
 */
@Composable
fun DeletionReceiptSection(controller: DataErasureController) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val key by controller.receiptKey.collectAsState()
    val check by controller.receiptCheck.collectAsState()
    var pasted by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { controller.loadReceiptKey() }

    Text(localizedString("mobile.data_receipts_title"), style = type.title, color = t.ink)
    CardShell(tag = "data_receipts_section") {
        Text(localizedString("mobile.data_receipts_desc"), style = type.body, color = t.dim)
        Spacer(Modifier.height(8.dp))
        FieldRow(
            label = localizedString("mobile.data_receipts_issued_label"),
            value = localizedString("mobile.data_receipts_none_issued"),
            tone = Tone.DIM,
            tag = "data_receipts_none_issued",
        )
        when (val k = key) {
            ReceiptKeyState.Loading -> StateBlock(ListState.Loading, tag = "data_receipts_key_loading", inline = true)
            is ReceiptKeyState.Failed -> ErasureFailureBlock(
                k.failure,
                tagPrefix = "data_receipts_key",
                notOnThisHost = localizedString("mobile.data_receipts_not_on_this_agent"),
            )
            is ReceiptKeyState.Loaded -> {
                FieldRow(
                    label = localizedString("mobile.data_receipts_key_id"),
                    value = k.key.publicKeyId,
                    mono = true,
                    protocol = "public_key_id",
                    tag = "data_receipts_key_id",
                )
                FieldRow(
                    label = localizedString("mobile.data_receipts_algorithm"),
                    value = k.key.algorithm ?: NOT_READ,
                    tag = "data_receipts_key_algorithm",
                )
                FieldRow(
                    label = localizedString("mobile.data_receipts_public_key"),
                    value = k.publicKeyB64 ?: NOT_READ,
                    mono = true,
                    gloss = if (k.publicKeyB64 == null) localizedString("mobile.data_receipts_public_key_unread") else null,
                    tag = "data_receipts_public_key",
                )
            }
        }
        FieldRow(
            label = localizedString("mobile.data_receipts_paste_label"),
            divider = false,
            input = {
                CirisTextField(
                    tag = "input_deletion_proof",
                    value = pasted,
                    onValueChange = { pasted = it; controller.clearReceiptCheck() },
                    placeholder = localizedString("mobile.data_receipts_paste_placeholder"),
                    singleLine = false,
                    mono = true,
                )
            },
        )
        Spacer(Modifier.height(10.dp))
        CirisButton(
            label = localizedString("mobile.data_receipts_check_button"),
            tag = "btn_check_receipt",
            onClick = { controller.checkReceipt(pasted) },
            enabled = pasted.isNotBlank() && check !is ReceiptCheckState.Working,
            modifier = Modifier.fillMaxWidth(),
        )
        ReceiptCheckOutcome(check)
    }
}

@Composable
private fun ReceiptCheckOutcome(state: ReceiptCheckState) {
    when (state) {
        ReceiptCheckState.Idle -> Unit
        is ReceiptCheckState.Unreadable -> {
            Spacer(Modifier.height(10.dp))
            StateBlock(
                ListState.Error(title = localizedString("mobile.data_receipt_unreadable"), detail = state.why),
                tag = "data_receipt_unreadable",
                inline = true,
            )
        }
        is ReceiptCheckState.Working -> {
            Spacer(Modifier.height(10.dp))
            StateBlock(ListState.Loading, tag = "data_receipt_checking", inline = true)
        }
        is ReceiptCheckState.Failed -> {
            Spacer(Modifier.height(10.dp))
            ErasureFailureBlock(state.failure, tagPrefix = "data_receipt_check", notOnThisHost = localizedString("mobile.data_receipts_not_on_this_agent"))
        }
        is ReceiptCheckState.Checked -> {
            Spacer(Modifier.height(10.dp))
            val verdict = when {
                state.verdict.valid -> localizedString("mobile.data_receipt_valid")
                // The agent checks against its current key only; a proof naming
                // another key reads invalid whether or not it was forged.
                state.keyNotCurrent -> localizedString("mobile.data_receipt_uncheckable")
                else -> localizedString("mobile.data_receipt_invalid")
            }
            FieldRow(
                label = localizedString("mobile.data_receipt_verdict_label"),
                value = verdict,
                tone = if (state.verdict.valid) Tone.OK else Tone.DANGER,
                gloss = state.verdict.message.takeIf { it.isNotBlank() },
                tag = "data_receipt_verdict",
            )
            if (state.keyNotCurrent) {
                FieldRow(
                    label = localizedString("mobile.data_receipt_key_label"),
                    value = localizedString("mobile.data_receipt_key_not_current"),
                    tone = Tone.DANGER,
                    tag = "data_receipt_key_not_current",
                )
            }
            FieldRow(
                label = localizedString("mobile.data_receipt_deletion_id"),
                value = state.proof.deletionId,
                mono = true,
                protocol = "deletion_id",
                tag = "data_receipt_deletion_id",
            )
            FieldRow(
                label = localizedString("mobile.data_receipt_subject"),
                value = state.proof.userIdentifier,
                mono = true,
                protocol = "user_identifier",
                tag = "data_receipt_subject",
            )
            FieldRow(
                label = localizedString("mobile.data_receipt_deleted_at"),
                value = state.proof.deletedAt,
                mono = true,
                protocol = "deleted_at",
                tag = "data_receipt_deleted_at",
            )
            FieldRow(
                label = if (state.verdict.valid) {
                    localizedString("mobile.data_receipt_records_verified")
                } else {
                    localizedString("mobile.data_receipt_records_claimed")
                },
                // A count is shown as verified only when the agent verified it.
                value = if (state.verdict.valid) state.verdict.totalRecords.toString() else state.proof.claimedRecords.toString(),
                protocol = "total_records_deleted",
                tag = "data_receipt_records",
            )
            FieldRow(
                label = localizedString("mobile.data_receipt_signed_by"),
                value = state.proof.publicKeyId,
                mono = true,
                protocol = "public_key_id",
                tag = "data_receipt_signed_by",
            )
            FieldRow(
                label = localizedString("mobile.data_receipt_checked_at"),
                value = state.verdict.verifiedAt.ifBlank { NOT_READ },
                mono = true,
                divider = false,
                tag = "data_receipt_checked_at",
            )
        }
    }
}

/**
 * The three ways a call produces no answer, each with its own tag:
 * `<prefix>_not_on_this_host`, `<prefix>_refused`, `<prefix>_error`.
 */
@Composable
private fun ErasureFailureBlock(failure: ErasureFailure, tagPrefix: String, notOnThisHost: String) {
    when (failure) {
        is ErasureFailure.NotOnThisHost -> StateBlock(
            ListState.Empty(notOnThisHost),
            tag = "${tagPrefix}_not_on_this_host",
            inline = true,
        )
        is ErasureFailure.Refused -> StateBlock(
            ListState.Error(
                title = refusalTitle(failure),
                body = failure.reasonId?.let { id -> localizedString(id).takeIf { it != id } },
                detail = failure.detail,
            ),
            tag = "${tagPrefix}_refused",
            inline = true,
        )
        is ErasureFailure.Failed -> StateBlock(
            ListState.Error(
                title = localizedString("mobile.state_read_failed"),
                body = localizedString("mobile.data_erase_failed_body"),
                detail = failure.detail,
            ),
            tag = "${tagPrefix}_error",
            inline = true,
        )
    }
}

@Composable
private fun refusalTitle(r: ErasureFailure.Refused): String = when (r.status) {
    401 -> localizedString("mobile.data_erase_refused_signin")
    403 -> localizedString("mobile.data_erase_refused_owner")
    else -> localizedString("mobile.data_erase_refused").replace("{status}", r.status.toString())
}
