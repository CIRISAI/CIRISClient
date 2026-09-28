package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.FederationIdCard
import ai.ciris.mobile.shared.viewmodels.NetworkViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.ciris.mobile.shared.platform.testableVerticalScroll

/**
 * Network — CIRISEdge operator view (Manage group, 2.9.6).
 *
 * THIS node's local edge facts: the federation signer_key_id, the current agent
 * mode, and the disk budget that gates SERVER mode. Read-only here — the full
 * federation experience (mode switching, peers, trust graph, transport tiles)
 * lives in the Commons → Global Commons hub, reached via the button below. This
 * is the operator-infra slice; the Commons hub is the social/federation view.
 *
 * It also hosts **tier R** ([ReaderPolicySection], `CIRISServer/src/admin_ops.rs`):
 * this node's own reader policy over other parties' judgements. It lives here
 * rather than on the runtime screen because it is a fact about THIS node's
 * local ledger — the same subject as the signer key and the agent mode above —
 * while `RuntimeScreen` is the agent's H3ERE pipeline debugger (pause /
 * single-step / queue depth), a different object entirely. **Tier S**, this
 * node's own three standings, has its own surface (This node › Own standing,
 * [NodeSelfStandingScreen], CSD-045). The enforcement ladder that acts on
 * OTHERS lives on the moderation surface, deliberately not here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkOpsScreen(
    viewModel: NetworkViewModel,
    onOpenFederationHub: () -> Unit,
    apiClient: ai.ciris.mobile.shared.api.CIRISApiClient,
    modifier: Modifier = Modifier,
) {
    val status by viewModel.status.collectAsState()
    // The mode as READ, never the selector's PROXY default (CSD-036 §6).
    val modeRead by viewModel.modeRead.collectAsState()
    val modeFailure by viewModel.modeFailure.collectAsState()
    val identityFailure by viewModel.identityFailure.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val federationAddress by viewModel.federationAddress.collectAsState()
    val federationId by viewModel.federationId.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadAgentMode()
        viewModel.loadFederationIdentity()
    }

    // In-content title (hub pattern): the app shell draws a floating logo at
    // the top-left, which clips a Scaffold TopAppBar title ("Network" → "ork").
    // Surface paints the theme background the removed Scaffold used to supply.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testableVerticalScroll()
            .testable("screen_network_ops"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(36.dp))
        Text(
            text = localizedString("nav.surface.network_ops").ifEmpty { "Network" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "CIRISEdge · this node",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OpsCard(title = "Federation identity", testTag = "card_netops_identity") {
            OpsRow(
                "Signer key",
                federationAddress?.takeIf { it.isNotBlank() } ?: "—",
                "row_netops_signer_key",
                mono = true,
            )
            identityFailure?.let { ReadFailureBlock(it, tagPrefix = "netops_identity", inline = true) }
        }

        FederationIdCard(federationId = federationId)

        OpsCard(title = "Agent mode", testTag = "card_netops_mode") {
            val failure = modeFailure
            when {
                modeRead != null -> {
                    OpsRow("Current", netopsModeValue(modeRead), "row_netops_mode")
                    status?.let {
                        OpsRow("SERVER-eligible", if (it.serverEligible) "yes" else "no", "row_netops_server_eligible")
                    }
                }
                // Not read is not a reading: no row, and the reason instead.
                failure != null -> ReadFailureBlock(
                    failure,
                    tagPrefix = "netops",
                    inline = true,
                    notOnThisNode = localizedString("network_ops.mode_not_on_this_node").let {
                        if (it.isBlank() || it == "network_ops.mode_not_on_this_node") {
                            "This node runs without an agent, and the mode is the agent's to report."
                        } else it
                    },
                )
                else -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().testable("progress_netops_mode", if (loading) "loading" else "waiting"),
                )
            }
        }

        status?.let { s ->
            OpsCard(title = "Disk budget", testTag = "card_netops_disk") {
                OpsRow("Available", formatBytes(s.availableDiskBytes), "row_netops_disk_available")
                OpsRow("SERVER minimum", formatBytes(s.serverMinimumDiskBytes), "row_netops_disk_minimum")
                OpsPathRow("Data directory", s.dataDir, "row_netops_data_dir")
            }
        }

        Button(
            onClick = onOpenFederationHub,
            modifier = Modifier.fillMaxWidth().testableClickable("btn_netops_open_hub") { onOpenFederationHub() },
        ) {
            Text(localizedString("network_ops.open_federation_hub").ifEmpty { "Open federation hub →" })
        }

        // Tier R, this node's own reader policy. Tier S (this node's own
        // standings) has its own surface, This node › Own standing (CSD-045).
        ReaderPolicySection(apiClient = apiClient)
    }
    }
}

/** The mode row's value: the mode as read, or [NOT_READ], never a default. Pure. */
internal fun netopsModeValue(modeRead: ai.ciris.mobile.shared.models.AgentMode?): String =
    modeRead?.wire?.uppercase() ?: NOT_READ

@Composable
private fun OpsCard(title: String, testTag: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testable(testTag),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** Long mono values (paths) — label on its own line, value wrapping below.
 *  A SpaceBetween row collides label and value when the value is wider than
 *  the remaining space (seen with the Android data dir path). */
@Composable
private fun OpsPathRow(label: String, value: String, testTag: String) {
    Column(modifier = Modifier.fillMaxWidth().testable(testTag)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun OpsRow(label: String, value: String, testTag: String, mono: Boolean = false) {
    Row(
        // The VALUE is published to the automation tree, not just the row's
        // existence: "a mode row rendered" is true whether it shows a real
        // reading or a ViewModel default, and telling those apart is the whole
        // point of walking this surface.
        modifier = Modifier.fillMaxWidth().testable(testTag, value),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}
