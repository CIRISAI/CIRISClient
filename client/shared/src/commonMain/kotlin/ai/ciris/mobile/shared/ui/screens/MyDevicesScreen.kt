package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.ownedDevices
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.platform.testableVerticalScroll
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **My devices** (CSD-133) — My things › Devices & keys.
 *
 * The owned nodes that run no agent, each with its release (held for the
 * roster's quorum, after which the device erases itself — CSD-037). Your keys
 * and what revokes them are My keys; a node that runs an agent is on Agents
 * you own. One roster, one kind of remove, per card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyDevicesScreen(
    viewModel: IdentityManagementViewModel,
    /** Approving a new device lives on My keys, because it adds a key to that roster. */
    onAddDevice: () -> Unit,
    onClaim: () -> Unit,
    /** Open another owned node as a relayed snapshot; null where the local node cannot relay. */
    onViewNode: ((keyId: String, label: String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val nodes by viewModel.ownedNodes.collectAsState()
    val release by viewModel.release.collectAsState()
    val devicesUnsupported by viewModel.devicesUnsupported.collectAsState()
    val rows = ownedDevices(nodes)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .testableVerticalScroll()
                .testable("screen_my_devices"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(36.dp))
            Text(
                localizedString("nav.surface.my_devices"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(localizedString("mobile.identity_nodes_desc"), fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onAddDevice,
                    modifier = Modifier.testableClickable("btn_my_devices_add") { onAddDevice() },
                ) { Text(localizedString("mobile.identity_add_title")) }
                OutlinedButton(
                    onClick = onClaim,
                    modifier = Modifier.testableClickable("btn_my_devices_claim") { onClaim() },
                ) { Text(localizedString("mobile.my_devices_claim")) }
            }

            if (rows.isEmpty()) {
                Text(
                    localizedString("mobile.identity_nodes_empty"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable("identity_nodes_empty"),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().testable("identity_node_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rows.forEach { node ->
                        Card(modifier = Modifier.fillMaxWidth().testable("identity_node_row_${node.keyId}")) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                node.label?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                                Text(truncMid(node.keyId), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                if (node.isSelf) {
                                    Text(
                                        localizedString("mobile.identity_node_this"),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!node.isSelf && onViewNode != null) {
                                    OutlinedButton(
                                        onClick = { onViewNode(node.keyId, node.label) },
                                        modifier = Modifier.testableClickable("btn_identity_view_${node.keyId}") {
                                            onViewNode(node.keyId, node.label)
                                        },
                                    ) { Text(localizedString("mobile.remote_node_view")) }
                                }
                                // A node too old for the device routes has no release to offer.
                                if (!devicesUnsupported) {
                                    OutlinedButton(
                                        onClick = { viewModel.askRelease(node.keyId) },
                                        enabled = release !is ReleaseState.Working,
                                        modifier = Modifier.testableClickable("btn_identity_release_${node.keyId}") {
                                            viewModel.askRelease(node.keyId)
                                        },
                                    ) { Text(localizedString("mobile.identity_release_button")) }
                                }
                            }
                        }
                    }
                }
            }
            ReleaseOutcome(
                state = release,
                onForce = { viewModel.askForceRelease() },
                onRetry = { viewModel.retryRelease() },
                onDismiss = { viewModel.cancelRelease() },
            )
        }
    }

    NodeReleaseSheets(viewModel, release)
}
