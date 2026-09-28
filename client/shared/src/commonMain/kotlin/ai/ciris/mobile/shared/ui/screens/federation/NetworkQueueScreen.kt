package ai.ciris.mobile.shared.ui.screens.federation

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.viewmodels.federation.NetworkQueueViewModel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar

/**
 * Network → Queue sub-screen.
 *
 * Three sections, each rendered as a row of [ElevatedCard]s:
 *  1. Top stats — queue depth + envelopes sent + envelopes received
 *  2. Failures — send + verify failure totals (red when > 0)
 *  3. Throughput — session totals + sent-vs-received horizontal bar
 *
 * Auto-refreshes every 5 s; the queue can shift between thoughts so the
 * tight cadence is intentional.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkQueueScreen(
    apiClient: CIRISApiClient,
    onIssueClick: (String) -> Unit = {},
) {
    val vm = remember { NetworkQueueViewModel(apiClient) }
    val metrics by vm.metrics.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val readFailure by vm.readFailure.collectAsState()

    DisposableEffect(vm) {
        vm.refreshNow()
        vm.startAutoRefresh(5_000L)
        onDispose { vm.stopAutoRefresh() }
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("network.tiles.queue")) },
                actions = {
                    IconButton(
                        onClick = { vm.refreshNow() },
                        modifier = Modifier.testableClickable("btn_federation_queue_refresh") { vm.refreshNow() },
                    ) {
                        Icon(
                            imageVector = CIRISIcons.refresh,
                            contentDescription = localizedString("network.queue.refresh"),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (metrics == null && loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        // No snapshot was ever read: which failure, never a row of zeros
        // (CSD-049). A later poll that fails keeps the last reading beneath
        // the transient error card instead.
        val unread = metrics == null && readFailure != null
        if (unread) {
            Box(modifier = Modifier.fillMaxSize().padding(padding).testable("screen_federation_queue")) {
                ai.ciris.mobile.shared.ui.screens.ReadFailureBlock(
                    failure = readFailure!!,
                    tagPrefix = "federation_queue",
                    modifier = Modifier.padding(16.dp),
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testableVerticalScroll()
                .padding(16.dp)
                .testable("screen_federation_queue"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            error?.let { msg ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ── Section 1: top stats ────────────────────────────────────────
            SectionLabel(localizedString("network.queue.stats.title"))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BigStatCard(
                    label = localizedString("network.queue.stats.depth"),
                    value = vm.queueDepth?.toString() ?: ai.ciris.mobile.shared.ui.screens.NOT_READ,
                    modifier = Modifier.weight(1f).testable("text_queue_depth"),
                )
                BigStatCard(
                    label = localizedString("network.queue.stats.sent"),
                    value = vm.envelopesSent?.toString() ?: ai.ciris.mobile.shared.ui.screens.NOT_READ,
                    modifier = Modifier.weight(1f).testable("text_envelopes_sent"),
                )
                BigStatCard(
                    label = localizedString("network.queue.stats.received"),
                    value = vm.envelopesReceived?.toString() ?: ai.ciris.mobile.shared.ui.screens.NOT_READ,
                    modifier = Modifier.weight(1f).testable("text_envelopes_received"),
                )
            }

            // ── Section 1b: the replication plane (CSD-049) ─────────────────
            // The counters above are the APPLICATION plane; replication moves
            // records without touching them (the node's own plane_note), so a
            // node carrying records can show 0 sent there. These say whether
            // records are actually moving.
            ReplicationPlaneCard(metrics)

            // ── Section 2: failures ─────────────────────────────────────────
            SectionLabel(localizedString("network.queue.failures.title"))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FailureCard(
                    label = localizedString("network.queue.failures.send"),
                    count = vm.sendFailures,
                    modifier = Modifier.weight(1f).testable("text_send_failures"),
                )
                FailureCard(
                    label = localizedString("network.queue.failures.verify"),
                    count = vm.verifyFailures,
                    modifier = Modifier.weight(1f).testable("text_verify_failures"),
                )
            }

            // ── Section 3: throughput ───────────────────────────────────────
            SectionLabel(localizedString("network.queue.throughput.title"))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = localizedString("network.queue.throughput.session"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = localizedString("network.queue.throughput.bytes_in"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = vm.bytesIn?.let { formatBytes(it) } ?: ai.ciris.mobile.shared.ui.screens.NOT_READ,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = localizedString("network.queue.throughput.bytes_out"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = vm.bytesOut?.let { formatBytes(it) } ?: ai.ciris.mobile.shared.ui.screens.NOT_READ,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SentReceivedBar(sent = vm.envelopesSent ?: 0L, received = vm.envelopesReceived ?: 0L)
                }
            }
        }
    }
}

/** The replication plane's two standings and totals, or a line saying the node does not report them. */
@Composable
private fun ReplicationPlaneCard(m: ai.ciris.mobile.shared.models.federation.FederationMetricsResponse?) {
    SectionLabel(localizedString("network.queue.replication.title"))
    ElevatedCard(modifier = Modifier.fillMaxWidth().testable("card_queue_replication")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (m == null || (m.carriageStanding == null && m.receiveStanding == null)) {
                Text(
                    text = localizedString("network.queue.replication.not_reported"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable("text_replication_not_reported"),
                )
                return@Column
            }
            PlaneRow(
                localizedString("network.queue.replication.carriage"),
                standingLabel(m.carriageStanding),
                "text_carriage_standing",
                m.carriageStanding,
            )
            PlaneRow(
                localizedString("network.queue.replication.receive"),
                standingLabel(m.receiveStanding),
                "text_receive_standing",
                m.receiveStanding,
            )
            PlaneRow(
                localizedString("network.queue.replication.served"),
                m.replicationServed()?.toString() ?: ai.ciris.mobile.shared.ui.screens.NOT_READ,
                "text_replication_served",
            )
            PlaneRow(
                localizedString("network.queue.replication.applied"),
                m.replicationApplied()?.toString() ?: ai.ciris.mobile.shared.ui.screens.NOT_READ,
                "text_replication_applied",
            )
            Text(
                text = localizedString("network.queue.replication.note"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A standing token in words; an unknown token is shown as sent, never as a known one. */
@Composable
private fun standingLabel(token: String?): String {
    if (token == null) return ai.ciris.mobile.shared.ui.screens.NOT_READ
    val known = setOf(
        "unreadable", "not_exercised", "idle", "moving", "withholding", "converged", "applying", "refusing",
    )
    return if (token in known) localizedString("network.queue.standing.$token") else token
}

@Composable
private fun PlaneRow(label: String, value: String, tag: String, published: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().testable(tag, published ?: value),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BigStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailureCard(label: String, count: Long?, modifier: Modifier = Modifier) {
    val warn = (count ?: 0L) > 0L
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (warn) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count?.toString() ?: ai.ciris.mobile.shared.ui.screens.NOT_READ,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (warn) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (warn) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Horizontal sent-vs-received bar. When both counters are 0 the bar
 * renders as a single muted track; otherwise the split is proportional.
 */
@Composable
private fun SentReceivedBar(sent: Long, received: Long) {
    val total = sent + received
    val sentFraction = if (total == 0L) 0f else sent.toFloat() / total.toFloat()
    val receivedFraction = if (total == 0L) 0f else received.toFloat() / total.toFloat()
    val sentColor = Color(0xFF1976D2)
    val recvColor = Color(0xFF388E3C)
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
        ) {
            if (total == 0L) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color.Gray.copy(alpha = 0.3f))
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(sentFraction.coerceAtLeast(0.001f))
                            .fillMaxSize()
                            .background(sentColor),
                    )
                    Box(
                        modifier = Modifier
                            .weight(receivedFraction.coerceAtLeast(0.001f))
                            .fillMaxSize()
                            .background(recvColor),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            LegendDot(sentColor)
            Spacer(Modifier.size(4.dp))
            Text(
                text = "${localizedString("network.queue.throughput.sent")}: $sent",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.size(12.dp))
            LegendDot(recvColor)
            Spacer(Modifier.size(4.dp))
            Text(
                text = "${localizedString("network.queue.throughput.received")}: $received",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color),
    )
}
