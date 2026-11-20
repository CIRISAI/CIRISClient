package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.DeferralData
import ai.ciris.mobile.shared.approvals.ApproveBlocked
import ai.ciris.mobile.shared.approvals.ConscienceObjection
import ai.ciris.mobile.shared.approvals.DeferralListMeta
import ai.ciris.mobile.shared.approvals.IDENTITY_CHANGE_RENDERED_KEYS
import ai.ciris.mobile.shared.approvals.IdentityChange
import ai.ciris.mobile.shared.approvals.IdentityProfileStatus
import ai.ciris.mobile.shared.approvals.ResolveSubject
import ai.ciris.mobile.shared.approvals.identityApproval
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.screens.files.refusalText
import ai.ciris.mobile.shared.ui.theme.SemanticColors
import ai.ciris.mobile.shared.viewmodels.IdentityResolveOutcome
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * RESOLVE AN IDENTITY CHANGE (CIRISAgent v2.23.1-stable; Eric 2026-11-20: show it
 * accurately and robustly, as a request to change the agent's template, with the
 * justification clear).
 *
 * - The proposed text is shown EXACTLY as stored, in a monospace block that is
 *   never trimmed, reflowed or rendered as markdown: it is what will be signed,
 *   and Approve sends those bytes unchanged.
 * - The M-1 justification always has its section; a missing one says so.
 * - There is no edit-and-approve (Eric): "change the wording" is Reject with the
 *   reason, and the agent proposes again. So Modify is not offered.
 * - Approve is off, with the reason, whenever [identityApproval] says so.
 */
@Composable
internal fun IdentityChangeDialog(
    deferral: DeferralData,
    change: IdentityChange,
    meta: DeferralListMeta,
    isResolving: Boolean,
    onDismiss: () -> Unit,
    onResolve: (resolution: String, guidance: String, subject: ResolveSubject?) -> Unit,
) {
    var guidance by remember { mutableStateOf("") }
    val (subject, blocked) = remember(change, meta) { identityApproval(change, meta) }

    AlertDialog(
        onDismissRequest = { if (!isResolving) onDismiss() },
        title = { Text(identityTitle(change), modifier = Modifier.testable("txt_identity_change_title")) },
        text = {
            Column(
                modifier = Modifier.testableVerticalScroll().fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                change.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }

                when (change) {
                    is IdentityChange.Amendment -> {
                        change.objection?.let { ObjectionLine(it) }
                        ExactText(change.proposedText, change.waEdited)
                        Justification(change.m1Justification)
                    }
                    is IdentityChange.Reapproval -> {
                        change.objection?.let { ObjectionLine(it) }
                        Text(localizedString("mobile.wa_identity_reapproval_note"), style = MaterialTheme.typography.bodySmall)
                        ExactText(change.proposedText, change.waEdited)
                        Justification(change.m1Justification)
                    }
                    is IdentityChange.BaseLink -> {
                        // A shipped template is anchored at first boot (Eric 2026-11-20: "the
                        // template ships pre-approved"), so a base-link request means a custom one.
                        Text(localizedString("mobile.wa_identity_base_link_custom_note"), style = MaterialTheme.typography.bodySmall)
                        TemplateBlock(change)
                    }
                    is IdentityChange.Unreadable ->
                        Text(localizedString("mobile.wa_identity_unreadable_note"), style = MaterialTheme.typography.bodySmall)
                }

                // Whatever else the deferral carries, as the plain view shows it.
                val rest = deferral.context.orEmpty().filterKeys { it !in IDENTITY_CHANGE_RENDERED_KEYS }
                if (rest.isNotEmpty() || change is IdentityChange.Unreadable) {
                    val shown = if (change is IdentityChange.Unreadable) deferral.context.orEmpty() else rest
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(localizedString("wa_context"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            shown.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    localizedString("mobile.wa_identity_guidance_optional"),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = guidance,
                    onValueChange = { guidance = it },
                    placeholder = { Text(localizedString("mobile.wa_identity_reject_why")) },
                    modifier = Modifier.fillMaxWidth().testable("input_identity_guidance"),
                    minLines = 2, maxLines = 4, enabled = !isResolving,
                )

                blocked?.let {
                    Text(
                        blockedText(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testable("note_identity_approve_blocked"),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val canApprove = subject != null && !isResolving
                    Button(
                        onClick = { if (subject != null) onResolve("approve", guidance, subject) },
                        enabled = canApprove,
                        colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.Default.success),
                        modifier = Modifier.weight(1f).testableClickable("btn_identity_approve") {
                            if (subject != null) onResolve("approve", guidance, subject)
                        },
                    ) {
                        Icon(CIRISIcons.check, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(localizedString("wa_approve"))
                    }
                    // Reject needs the "why": it is what the agent re-proposes from.
                    val canReject = guidance.isNotBlank() && !isResolving
                    Button(
                        onClick = { if (guidance.isNotBlank()) onResolve("reject", guidance, null) },
                        enabled = canReject,
                        colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.Default.error),
                        modifier = Modifier.weight(1f).testableClickable("btn_identity_reject") {
                            if (guidance.isNotBlank()) onResolve("reject", guidance, null)
                        },
                    ) {
                        Icon(CIRISIcons.close, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(localizedString("wa_reject"))
                    }
                }

                if (isResolving) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss, enabled = !isResolving,
                modifier = Modifier.testableClickable("btn_identity_cancel") { onDismiss() },
            ) { Text(localizedString("mobile.common_cancel")) }
        },
    )
}

@Composable
private fun identityTitle(change: IdentityChange): String = localizedString(
    when (change) {
        is IdentityChange.Amendment -> "mobile.wa_identity_amendment_title"
        is IdentityChange.Reapproval -> "mobile.wa_identity_reapproval_title"
        is IdentityChange.BaseLink -> "mobile.wa_identity_base_link_title"
        is IdentityChange.Unreadable -> "mobile.wa_identity_unreadable_title"
    },
)

@Composable
private fun blockedText(b: ApproveBlocked): String = localizedString(
    when (b) {
        ApproveBlocked.BASE_LINK_FIRST -> "mobile.wa_identity_blocked_base_link_first"
        ApproveBlocked.OUTER_WHITESPACE -> "mobile.wa_identity_blocked_outer_whitespace"
        ApproveBlocked.NO_DIGEST -> "mobile.wa_identity_blocked_no_digest"
        ApproveBlocked.AGENT_CANNOT_BIND -> "mobile.wa_identity_blocked_agent_cannot_bind"
        ApproveBlocked.UNREADABLE -> "mobile.wa_identity_unreadable_note"
    },
)

/** The exact bytes to be signed: monospace, selectable, untouched. */
@Composable
private fun ExactText(text: String, waEdited: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localizedString("mobile.wa_identity_exact_text"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        if (waEdited) {
            Text(
                localizedString("mobile.wa_identity_edited_by_wa"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testable("note_identity_wa_edited"),
            )
        }
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            SelectionContainer {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth().padding(10.dp).testable("code_identity_proposed"),
                )
            }
        }
    }
}

@Composable
private fun Justification(m1: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localizedString("mobile.wa_identity_why"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(
            m1 ?: localizedString("mobile.wa_identity_no_justification"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testable("txt_identity_m1"),
        )
    }
}

@Composable
private fun ObjectionLine(o: ConscienceObjection) {
    Text(
        localizedString(
            "mobile.wa_identity_objection",
            mapOf("conscience" to (o.conscience ?: "?"), "type" to (o.type ?: "?"), "reason" to (o.reason ?: "")),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testable("note_identity_objection"),
    )
}

@Composable
private fun TemplateBlock(change: IdentityChange.BaseLink) {
    val clipboard = LocalClipboardManager.current
    val t = change.template
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${localizedString("mobile.wa_identity_template_name")}: ${t.name ?: "—"}", style = MaterialTheme.typography.bodySmall)
        Text("${localizedString("mobile.wa_identity_template_version")}: ${t.version ?: "—"}", style = MaterialTheme.typography.bodySmall)
        Text(localizedString("mobile.wa_identity_manifest_digest"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(
                t.manifestDigest ?: "—",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testable("code_identity_manifest_digest"),
            )
        }
        t.manifestDigest?.let { d ->
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(d)) },
                modifier = Modifier.testableClickable("btn_identity_copy_digest") { clipboard.setText(AnnotatedString(d)) },
            ) { Text(localizedString("mobile.wa_identity_copy_digest")) }
        }
    }
}

/** The agent's identity status, above the deferrals, when it is not anchored. */
@Composable
internal fun IdentityProfileBanner(meta: DeferralListMeta, onOpenBaseLink: (() -> Unit)?) {
    val status = meta.profile?.status ?: return
    if (!meta.capabilities.identityChangeReview || status == IdentityProfileStatus.ANCHORED) return
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().testable("note_identity_profile"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                localizedString(
                    when (status) {
                        IdentityProfileStatus.UNANCHORED -> "mobile.wa_identity_profile_unanchored"
                        IdentityProfileStatus.MISMATCH -> "mobile.wa_identity_profile_mismatch"
                        IdentityProfileStatus.CONTESTED -> "mobile.wa_identity_profile_contested"
                        else -> "mobile.wa_identity_profile_unknown"
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            onOpenBaseLink?.let { open ->
                TextButton(
                    onClick = open,
                    modifier = Modifier.testableClickable("btn_identity_open_base_link") { open() },
                ) { Text(localizedString("mobile.wa_identity_open_base_link")) }
            }
        }
    }
}

/** What the last identity resolve did, in the reader's language. */
@Composable
internal fun IdentityOutcomeNotice(outcome: IdentityResolveOutcome, onDismiss: () -> Unit) {
    val (text, isError) = when (outcome) {
        is IdentityResolveOutcome.Approved -> localizedString("mobile.wa_identity_approved") to false
        IdentityResolveOutcome.Rejected -> localizedString("mobile.wa_identity_rejected") to false
        IdentityResolveOutcome.NotResolved -> localizedString("mobile.wa_identity_not_resolved") to true
        is IdentityResolveOutcome.Refused -> refusalText(outcome.refusal) to true
        is IdentityResolveOutcome.Failed ->
            localizedString("mobile.wa_identity_failed", mapOf("detail" to (outcome.message ?: "?"))) to true
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().testable("note_identity_outcome"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (outcome is IdentityResolveOutcome.Approved) {
                SelectionContainer {
                    Column {
                        outcome.requestAttestationId?.let {
                            Text("${localizedString("mobile.wa_identity_receipt_request")}: $it", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                        outcome.responseAttestationId?.let {
                            Text("${localizedString("mobile.wa_identity_receipt_response")}: $it", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End).testableClickable("btn_identity_outcome_dismiss") { onDismiss() },
            ) { Text(localizedString("mobile.common_close")) }
        }
    }
}
