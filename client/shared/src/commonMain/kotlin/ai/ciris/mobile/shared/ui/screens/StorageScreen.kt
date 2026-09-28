package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.MemoryStatsApiData
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.AgentModeStatus
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
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
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar

/**
 * Storage — CIRISPersist operator view (CSD-040).
 *
 * Surfaces the persist substrate's local facts: the graph store (total nodes,
 * by type/scope, recent activity) and the on-disk storage location. Read-only;
 * the agent surfaces what persist produces, it does not mutate it here.
 *
 * Four states, four renderings (CSD-040 §2): the counters; an empty graph said
 * as "not holding anything yet" rather than as `Total nodes — 0`; a spinner;
 * and the error card. The "On disk" card is the AGENT's (`/v1/system/agent-mode`,
 * no node route): when that read fails the card says so in its place rather
 * than disappearing, because a card that is missing and a card that was never
 * asked for look identical (CSD-040 §6).
 *
 * `oldest` / `newest` are labelled approximate: the node computes them from
 * the first 1000-row page per scope, not a full scan (`memory_api.rs`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    apiClient: CIRISApiClient,
    modifier: Modifier = Modifier,
) {
    var stats by remember { mutableStateOf<MemoryStatsApiData?>(null) }
    var mode by remember { mutableStateOf<AgentModeStatus?>(null) }
    var diskFailure by remember { mutableStateOf<ReadFailure?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        try {
            stats = apiClient.getMemoryStats()
        } catch (e: Exception) {
            error = e.message ?: "failed to load graph stats"
            PlatformLogger.d("StorageScreen", "getMemoryStats failed: ${e.message}")
        }
        try {
            mode = apiClient.getAgentMode()
            diskFailure = null
        } catch (e: Exception) {
            diskFailure = ReadFailure.of(e)
            PlatformLogger.d("StorageScreen", "getAgentMode failed: ${e.message}")
        }
        loading = false
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ScreenTopBar(title = { Text(localizedString("nav.surface.storage").ifEmpty { "Storage" }) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .testableVerticalScroll()
                .testable("screen_storage"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "CIRISPersist · graph store",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (loading && stats == null) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp).testable("storage_loading"))
            }

            error?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.testable("storage_error", it),
                ) {
                    Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            stats?.let { s ->
                when (storageGraphState(s)) {
                    // A number where there is nothing is a reading nobody took.
                    StorageGraphState.EMPTY -> StateBlock(
                        ListState.Empty(localizedString("mobile.storage_empty")),
                        tag = "storage_empty",
                        inline = true,
                    )
                    StorageGraphState.POPULATED -> StorageCard(title = "Graph store", testTag = "card_storage_graph") {
                        StatRow("Total nodes", s.totalNodes.toString(), "row_storage_total_nodes")
                        StatRow("New (24h)", s.recentNodes24h.toString(), "row_storage_recent_nodes")
                        s.oldestNodeDate?.let { StatRow(localizedString("mobile.storage_oldest_approx"), it, "row_storage_oldest") }
                        s.newestNodeDate?.let { StatRow(localizedString("mobile.storage_newest_approx"), it, "row_storage_newest") }
                        Text(
                            localizedString("mobile.storage_dates_heuristic"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testable("row_storage_dates_heuristic"),
                        )
                    }
                }

                if (s.nodesByType.isNotEmpty()) {
                    StorageCard(title = "Nodes by type", testTag = "card_storage_by_type") {
                        s.nodesByType.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                            StatRow(k, v.toString(), "row_storage_type_$k")
                        }
                    }
                }

                if (s.nodesByScope.isNotEmpty()) {
                    StorageCard(title = "Nodes by scope", testTag = "card_storage_by_scope") {
                        s.nodesByScope.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                            StatRow(k, v.toString(), "row_storage_scope_$k")
                        }
                    }
                }
            }

            mode?.let { m ->
                StorageCard(title = "On disk", testTag = "card_storage_disk") {
                    StatRow("Data directory", m.dataDir, "row_storage_data_dir", mono = true)
                    StatRow("Available", formatBytes(m.availableDiskBytes), "row_storage_available")
                }
            }
            // The disk facts are the agent's. A read that failed is said in its
            // place — `storage_disk_not_on_this_node` on a host without the
            // route, `storage_disk_error` otherwise — never a card that is
            // simply not there.
            if (mode == null && !loading) {
                diskFailure?.let {
                    ReadFailureBlock(
                        failure = it,
                        tagPrefix = "storage_disk",
                        notOnThisNode = localizedString("mobile.storage_disk_not_on_this_node"),
                        inline = true,
                    )
                }
            }
        }
    }
}

/** Whether the graph-store card has anything to count. Pure, so the empty state is tested without Compose. */
enum class StorageGraphState { POPULATED, EMPTY }

fun storageGraphState(stats: MemoryStatsApiData): StorageGraphState =
    if (stats.totalNodes <= 0 && stats.nodesByType.isEmpty() && stats.nodesByScope.isEmpty()) StorageGraphState.EMPTY
    else StorageGraphState.POPULATED

@Composable
private fun StorageCard(title: String, testTag: String, content: @Composable ColumnScope.() -> Unit) {
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

@Composable
private fun StatRow(label: String, value: String, testTag: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().testable(testTag),
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

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val gb = bytes.toDouble() / (1024 * 1024 * 1024)
    if (gb >= 1.0) return "${(gb * 10).toLong() / 10.0} GB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return "${(mb * 10).toLong() / 10.0} MB"
}
