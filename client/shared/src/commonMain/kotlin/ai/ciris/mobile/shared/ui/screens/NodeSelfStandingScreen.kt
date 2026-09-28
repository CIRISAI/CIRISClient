package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * This node › Own standing (CSD-045) — tier S of `CIRISServer/src/admin_ops.rs`.
 *
 * The owner says, on the record, what this node has done to itself: shed load,
 * stopped accepting, or come under legal compulsion. Three axes, never folded,
 * and the one admin rung that still works while the node is partitioned. It
 * calls the NODE's address ([CIRISApiClient.getSelfStanding] and the six acts
 * default to the local node, never `$baseUrl`), so it is on every build.
 *
 * It was a section at the bottom of Network until CSD-045 gave it a surface: a
 * warrant-canary declaration is not a row under the disk budget.
 */
@Composable
fun NodeSelfStandingScreen(
    apiClient: CIRISApiClient,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .testableVerticalScroll()
                .testable("screen_node_self"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(36.dp))
            Text(
                text = localizedString("nav.surface.node_self").let {
                    if (it.isBlank() || it == "nav.surface.node_self") "Own standing" else it
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            SelfStandingSection(apiClient = apiClient)
        }
    }
}
