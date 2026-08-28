package ai.ciris.mobile.shared.ui.screens

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
                text = when (state) {
                    CapabilityState.UNDECLARED -> "This node hasn't said whether it can verify builds"
                    else -> "This node doesn't verify builds"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // The two remedies are different, so the two sentences are.
                text = when (state) {
                    CapabilityState.UNDECLARED ->
                        "It's running a version from before nodes declared what they can do. " +
                            "A newer node will answer this, and the check will appear here when it does."
                    else ->
                        "Build verification is part of the registry, and this node doesn't carry it. " +
                            "A node that does can answer the same question."
                },
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
                            AgentStatus.REGISTERED -> "Registered"
                            AgentStatus.DEPRECATED -> "Deprecated"
                            AgentStatus.REVOKED -> "Revoked"
                            // The registry said something this build does not
                            // know. Show it verbatim rather than deciding.
                            AgentStatus.UNKNOWN -> r.rawStatus.ifBlank { "Status not recognised" }
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    if (r.agentType.isNotBlank()) Text("${r.agentType} ${r.version}".trim(), fontSize = 13.sp)
                    Text(r.agentHash, fontSize = 12.sp)
                    if (r.registeredAt.isNotBlank()) Text("registered ${r.registeredAt}", fontSize = 12.sp)
                    if (r.status == AgentStatus.REVOKED) {
                        Text(
                            "This build has been revoked. Do not run it.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                // ANSWERED, and the answer is no record. Actionable.
                LookupResult.NotFound -> {
                    Text("No record of this build", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "The registry answered and holds nothing for this hash.",
                        fontSize = 13.sp,
                    )
                }
                // NOT ANSWERED. Never rendered as "no record": that would tell
                // someone an unverified build was checked and cleared.
                is LookupResult.Unavailable -> {
                    Text("Couldn't check", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "This is not the same as 'not registered' — the registry did not answer. " +
                            result.reason,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
