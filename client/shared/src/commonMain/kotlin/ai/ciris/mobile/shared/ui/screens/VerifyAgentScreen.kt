package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.capability.AgentStatus
import ai.ciris.mobile.shared.models.capability.Capability
import ai.ciris.mobile.shared.models.capability.CapabilityState
import ai.ciris.mobile.shared.models.capability.LookupResult
import ai.ciris.mobile.shared.models.capability.NodeCapabilities
import ai.ciris.mobile.shared.platform.testable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ai.ciris.mobile.shared.platform.testableClickable
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The gate, rendered.
 *
 * Ported from CIRISPortal's `/verify`: look an agent build up by hash and say
 * whether the registry has it and whether it is still good. This is the first
 * surface shipped AHEAD of its API — no node released today can serve it — so
 * what the screen does when the capability is missing is the actual feature.
 *
 * FOUR OUTCOMES, AND NONE OF THEM IS A BLANK SCREEN OR AN ERROR:
 *
 *   UNDECLARED  the node predates the declaration (CIRISServer#499). Say that.
 *               Not "unavailable", because a newer node will serve it and the
 *               operator should know an upgrade is the fix.
 *   ABSENT      the node declared its capabilities and this was not among them.
 *               Say THAT — it is a different sentence with a different remedy,
 *               and conflating it with the above is what the three-state model
 *               exists to prevent.
 *   PRESENT     render the lookup.
 *   UNKNOWN     a status this client cannot read. Show the raw string rather
 *               than guessing; on a revocation check a guess is a lie.
 */
@Composable
fun VerifyAgentCapabilityNotice(
    capabilities: NodeCapabilities,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val state = capabilities.state(Capability.REGISTRY_LOOKUP)
    if (state == CapabilityState.PRESENT) return

    Card(
        modifier = modifier.fillMaxWidth().testable("card_verify_capability"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = localizedString(
                    when (state) {
                        CapabilityState.UNDECLARED -> "mobile.verify_undeclared_title"
                        CapabilityState.UNREACHABLE -> "mobile.verify_unreachable_title"
                        CapabilityState.UNDETERMINED -> "mobile.verify_undetermined_title"
                        else -> "mobile.verify_absent_title"
                    }
                ),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // The two remedies are different, so the two sentences are.
                text = localizedString(
                    when (state) {
                        CapabilityState.UNDECLARED -> "mobile.verify_undeclared_body"
                        // COULD NOT ASK. Distinct copy, because the remedy is
                        // neither "upgrade" nor "use another node" — it is "try
                        // again", and telling someone their node is old because
                        // a request timed out is a false diagnosis.
                        CapabilityState.UNREACHABLE -> "mobile.verify_unreachable_body"
                        // The NODE said it does not know — its answer, not our
                        // failure to ask. Remedy is retry, not upgrade.
                        CapabilityState.UNDETERMINED -> "mobile.verify_undetermined_body"
                        else -> "mobile.verify_absent_body"
                    }
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // EVERY non-PRESENT state gets a retry. I first withheld it from
            // ABSENT and UNDECLARED, reasoning that pressing again cannot change
            // a settled answer — but an operator can upgrade the node or install
            // registry support AT THE SAME URL, and the probe result survives
            // leaving and reopening the screen. So those answers are not
            // permanent either, and withholding the control left the form
            // unavailable for the rest of the session (Codex, PR #20).
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testableClickable("btn_verify_retry_probe") { onRetry() },
                ) {
                    Text(localizedString("mobile.verify_retry"))
                }
            }
        }
    }
}

/** The lookup outcome, once the capability is [CapabilityState.PRESENT]. */
@Composable
fun VerifyAgentResult(
    result: LookupResult,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().testable("card_verify_result"),
        colors = CardDefaults.cardColors(
            containerColor = when (result) {
                is LookupResult.Found ->
                    if (result.record.status.isDiscouraged) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (result) {
                is LookupResult.Found -> {
                    val r = result.record
                    Text(
                        text = when (r.status) {
                            AgentStatus.REGISTERED -> localizedString("mobile.verify_status_registered")
                            AgentStatus.DEPRECATED -> localizedString("mobile.verify_status_deprecated")
                            AgentStatus.REVOKED -> localizedString("mobile.verify_status_revoked")
                            // The registry said something this build does not
                            // know. Show it verbatim rather than deciding — the
                            // raw string is not translated because it is the
                            // registry's own token, not our prose.
                            AgentStatus.UNKNOWN ->
                                r.rawStatus.ifBlank { localizedString("mobile.verify_status_unreadable") }
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    if (r.agentType.isNotBlank()) Text("${r.agentType} ${r.version}".trim(), fontSize = 13.sp)
                    Text(r.agentHash, fontSize = 12.sp)
                    if (r.registeredAt.isNotBlank()) Text("registered ${r.registeredAt}", fontSize = 12.sp)
                    if (r.status == AgentStatus.REVOKED) {
                        Text(
                            localizedString("mobile.verify_revoked_warning"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                // ANSWERED, and the answer is no record. Actionable.
                LookupResult.NotFound -> {
                    Text(localizedString("mobile.verify_not_found_title"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(localizedString("mobile.verify_not_found_body"), fontSize = 13.sp)
                }
                // NOT ANSWERED. Never rendered as "no record": that would tell
                // someone an unverified build was checked and cleared.
                is LookupResult.Unavailable -> {
                    Text(localizedString("mobile.verify_unavailable_title"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        localizedString("mobile.verify_unavailable_body") + " " + result.reason,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}


/**
 * The whole surface: a hash field, a check button, and whichever of the four
 * outcomes applies.
 *
 * The lookup is INJECTED rather than reached for, so this composable has no
 * opinion about which node answers — `CIRISApp` supplies the attached node's
 * URL, which is the bug that made `LOCAL_NODE_URL` a bad default here and in
 * the reset home resolution.
 *
 * The form only renders when the capability is PRESENT. That is not cosmetic:
 * offering a field that cannot be submitted teaches the operator the feature is
 * broken, when in fact this node simply does not serve it.
 */
@Composable
fun VerifyAgentScreen(
    capabilities: NodeCapabilities,
    /** Which node answers. Also the identity the result belongs to — see below. */
    nodeUrl: String,
    onLookup: suspend (String) -> LookupResult,
    /** Re-probe the node's declaration — only offered for the transient states. */
    onRetryProbe: (() -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val usable = capabilities.has(Capability.REGISTRY_LOOKUP)
    // KEYED ON THE NODE. Without this the state survives a node switch, so an
    // operator who verifies a hash against node A, switches to node B, and looks
    // at the screen sees A's verdict presented as B's answer — a revocation
    // result attributed to a registry that never gave it. Self-review, after
    // eight review findings on this file all of the same shape: a path that
    // shows an answer it does not have.
    var hash by remember(nodeUrl) { mutableStateOf("") }
    var result by remember(nodeUrl) { mutableStateOf<LookupResult?>(null) }
    var inFlight by remember(nodeUrl) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // THE RESULT MUST BELONG TO THE HASH THAT WAS SUBMITTED. The field stays
    // editable while a lookup runs, so submitting A and then typing B left A's
    // coroutine to write its answer underneath a field showing B — and the
    // API's returned-hash check cannot catch it, because the response correctly
    // matches A (Codex, PR #20). The submitted value is captured and the
    // completion is discarded if the field has moved on.
    val submit: (String) -> Unit = { candidate ->
        val submitted = candidate.trim()
        if (!inFlight && submitted.isNotBlank()) {
            inFlight = true
            scope.launch {
                val r = onLookup(submitted)
                if (hash.trim() == submitted) result = r
                inFlight = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = localizedString("mobile.verify_title"),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            modifier = Modifier.testable("txt_verify_title"),
        )

        // Says which of UNDECLARED / ABSENT / UNREACHABLE applies, and returns
        // nothing at all when the capability is PRESENT.
        VerifyAgentCapabilityNotice(capabilities, onRetry = onRetryProbe)

        if (usable) {
            OutlinedTextField(
                value = hash,
                onValueChange = { hash = it; result = null },
                label = { Text(localizedString("mobile.verify_hash_label")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().testable("input_verify_hash"),
            )
            Button(
                onClick = { submit(hash) },
                enabled = !inFlight && hash.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testableClickable("btn_verify_submit") { submit(hash) },
            ) {
                Text(localizedString("mobile.verify_button"))
            }
            result?.let { VerifyAgentResult(it) }
        }

        Button(
            onClick = onBack,
            modifier = Modifier.testableClickable("btn_verify_back") { onBack() },
        ) {
            Text(localizedString("mobile.claim_node_back"))
        }
    }
}
