package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar
import ai.ciris.mobile.shared.viewmodels.RemoteFailure
import ai.ciris.mobile.shared.viewmodels.RemoteNodeState
import ai.ciris.mobile.shared.viewmodels.RemoteNodeViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Another node the person owns, read through their own node (CSD-133 entry,
 * `POST /v1/mesh/relay`). A SNAPSHOT: the relay carries one request and one
 * answer, never a stream, so the screen says when it was read and offers Refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteNodeScreen(
    viewModel: RemoteNodeViewModel,
    targetKeyId: String,
    label: String?,
    onBack: () -> Unit,
) {
    LaunchedEffect(targetKeyId) { viewModel.load(targetKeyId) }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(label ?: localizedString("mobile.remote_node_title")) },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(onClick = onBack, modifier = Modifier.testableClickable("btn_remote_node_back") { onBack() }) {
                            Icon(CIRISIcons.arrowBack, contentDescription = localizedString("common_back"))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .testableVerticalScroll().testable("screen_remote_node"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(targetKeyId, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            when (val s = state) {
                RemoteNodeState.Loading ->
                    CircularProgressIndicator(modifier = Modifier.testable("remote_node_loading"))
                is RemoteNodeState.Failed -> Text(
                    remoteFailureText(s.failure),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testable("remote_node_error", s.failure.name.lowercase()),
                )
                is RemoteNodeState.Loaded -> {
                    val time = s.at.toLocalDateTime(TimeZone.currentSystemDefault())
                    Text(
                        localizedString(
                            "mobile.remote_node_snapshot_at",
                            "time",
                            "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testable("txt_remote_node_snapshot"),
                    )
                    Text(localizedString("mobile.remote_node_metrics"), fontWeight = FontWeight.SemiBold)
                    s.metricsFailure?.let {
                        Text(remoteFailureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testable("remote_node_metrics_error"))
                    }
                    Column(modifier = Modifier.testable("remote_node_metrics", s.metrics.size.toString())) {
                        s.metrics.forEach { (k, v) ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(k, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(v, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Text(localizedString("mobile.remote_node_logs"), fontWeight = FontWeight.SemiBold)
                    s.logsFailure?.let {
                        Text(remoteFailureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testable("remote_node_logs_error"))
                    }
                    Column(modifier = Modifier.testable("remote_node_logs", s.logs.size.toString())) {
                        s.logs.forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
            OutlinedButton(
                onClick = { viewModel.load(targetKeyId) },
                enabled = state !is RemoteNodeState.Loading,
                modifier = Modifier.testableClickable("btn_remote_node_refresh") { viewModel.load(targetKeyId) },
            ) { Text(localizedString("mobile.remote_node_refresh")) }
        }
    }
}

@Composable
private fun remoteFailureText(f: RemoteFailure): String = localizedString(
    when (f) {
        RemoteFailure.UNREACHABLE -> "mobile.remote_node_unreachable"
        RemoteFailure.TIMEOUT -> "mobile.remote_node_timeout"
        RemoteFailure.SEND_FAILED -> "mobile.remote_node_send_failed"
        RemoteFailure.NO_TRANSPORT -> "mobile.remote_node_no_transport"
        RemoteFailure.NEEDS_UPDATE -> "mobile.remote_node_needs_update"
        RemoteFailure.NOT_OWNER -> "mobile.remote_node_not_owner"
        RemoteFailure.OTHER -> "mobile.remote_node_failed"
    }
)
