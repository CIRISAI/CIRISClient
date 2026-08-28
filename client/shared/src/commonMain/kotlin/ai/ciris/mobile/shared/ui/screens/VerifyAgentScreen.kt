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
import androidx.compose.runtime.Composable
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
                        else -> "mobile.verify_absent_body"
                    }
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
