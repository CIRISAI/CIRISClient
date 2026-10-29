package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.OwnedAgentKind
import ai.ciris.mobile.shared.models.federation.ownedAgents
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.primitives.CirisTextField
import ai.ciris.mobile.shared.viewmodels.IdentityManagementViewModel
import ai.ciris.mobile.shared.viewmodels.ReleaseState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **Agents you own** (CSD-132) — My things › Devices & keys.
 *
 * The owned nodes that run an agent, each with what can be done to it: rename,
 * announce at `federation`, verify its build, and release it (held for the
 * roster's quorum, after which the device erases itself — CSD-037). Claiming a
 * new one is CSD-085. Stopping or retiring an agent is the agent's own ladder,
 * not a node act, so it is not offered here.
 *
 * What the owner's node does not know is shown as unknown, never as false: a
 * row whose agent pairing is unsigned reads "may run an agent", and nothing is
 * said about version or presence, which no node carries yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OwnedAgentsScreen(
    viewModel: IdentityManagementViewModel,
    onClaim: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val nodes by viewModel.ownedNodes.collectAsState()
    val release by viewModel.release.collectAsState()
    val labelling by viewModel.labelling.collectAsState()
    val labelRefusal by viewModel.labelRefusal.collectAsState()
    val announcing by viewModel.announcing.collectAsState()
    val announceRefusal by viewModel.announceRefusal.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val rows = ownedAgents(nodes)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .testableVerticalScroll()
                .testable("screen_owned_agents"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(36.dp))
            Text(
                localizedString("nav.surface.owned_agents"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(localizedString("mobile.owned_agents_intro"), fontSize = 13.sp)
            OutlinedButton(
                onClick = onClaim,
                modifier = Modifier.testableClickable("btn_owned_agents_claim") { onClaim() },
            ) { Text(localizedString("mobile.owned_agents_claim")) }

            if (rows.isEmpty()) {
                Text(
                    localizedString("mobile.owned_agents_empty"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable("owned_agents_empty"),
                )
            }

            rows.forEach { (node, kind) ->
                Card(modifier = Modifier.fillMaxWidth().testable("row_owned_agent_${node.keyId}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            node.label ?: localizedString("mobile.owned_agents_unnamed"),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(node.keyId, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text(
                            localizedString(
                                if (kind == OwnedAgentKind.AGENT) "mobile.owned_agents_kind_agent"
                                else "mobile.owned_agents_kind_maybe",
                            ),
                            fontSize = 12.sp,
                        )
                        Text(
                            localizedString(
                                when (node.announced) {
                                    true -> "mobile.owned_agents_announced"
                                    false -> "mobile.owned_agents_not_announced"
                                    null -> "mobile.owned_agents_announced_unknown"
                                },
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { viewModel.startLabel(node.keyId) },
                                modifier = Modifier.testableClickable("btn_owned_agent_rename_${node.keyId}") {
                                    viewModel.startLabel(node.keyId)
                                },
                            ) { Text(localizedString("mobile.owned_agents_rename")) }
                            if (node.announced != true) {
                                TextButton(
                                    onClick = { viewModel.announceNode(node.keyId) },
                                    enabled = announcing == null,
                                    modifier = Modifier.testableClickable("btn_owned_agent_announce_${node.keyId}") {
                                        if (announcing == null) viewModel.announceNode(node.keyId)
                                    },
                                ) { Text(localizedString("mobile.owned_agents_announce")) }
                            }
                            TextButton(
                                onClick = onVerify,
                                modifier = Modifier.testableClickable("btn_owned_agent_verify_${node.keyId}") { onVerify() },
                            ) { Text(localizedString("mobile.owned_agents_verify")) }
                            TextButton(
                                onClick = { viewModel.askRelease(node.keyId) },
                                enabled = release !is ReleaseState.Working,
                                modifier = Modifier.testableClickable("btn_owned_agent_release_${node.keyId}") {
                                    viewModel.askRelease(node.keyId)
                                },
                            ) {
                                Text(
                                    localizedString("mobile.identity_release_button"),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            announceRefusal?.let {
                Text(
                    localizedString("mobile.owned_agents_announce_refused", mapOf("why" to (it.reasonId ?: it.detail ?: "HTTP ${it.statusCode}"))),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.testable("owned_agents_announce_error"),
                )
            }
            ReleaseOutcome(
                state = release,
                onForce = { viewModel.askForceRelease() },
                onRetry = { viewModel.retryRelease() },
                onDismiss = { viewModel.cancelRelease() },
            )
            Text(
                localizedString("mobile.owned_agents_retire_note"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    NodeReleaseSheets(viewModel, release)

    labelling?.let { key ->
        if (nodes.any { it.keyId == key }) {
            var name by remember(key) { mutableStateOf(nodes.firstOrNull { it.keyId == key }?.label ?: "") }
            AlertDialog(
                onDismissRequest = { viewModel.cancelLabel() },
                modifier = Modifier.testable("dialog_owned_agent_rename"),
                title = { Text(localizedString("mobile.owned_agents_rename")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CirisTextField(
                            tag = "input_owned_agent_name",
                            value = name,
                            onValueChange = { name = it },
                            placeholder = localizedString("mobile.owned_agents_name_label"),
                        )
                        labelRefusal?.let {
                            Text(
                                it.reasonId ?: it.detail ?: "HTTP ${it.statusCode}",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.saveLabel(name.trim()) },
                        enabled = name.isNotBlank() && !busy,
                        modifier = Modifier.testableClickable("btn_owned_agent_rename_save") {
                            if (name.isNotBlank() && !busy) viewModel.saveLabel(name.trim())
                        },
                    ) { Text(localizedString("mobile.owned_agents_save")) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.cancelLabel() },
                        modifier = Modifier.testableClickable("btn_owned_agent_rename_cancel") { viewModel.cancelLabel() },
                    ) { Text(localizedString("mobile.confirm_cancel")) }
                },
            )
        }
    }
}
