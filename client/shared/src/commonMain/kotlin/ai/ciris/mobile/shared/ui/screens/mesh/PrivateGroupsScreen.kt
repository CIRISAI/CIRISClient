package ai.ciris.mobile.shared.ui.screens.mesh

import androidx.compose.runtime.Composable
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.ui.components.ComingSoonPlaceholder
import ai.ciris.mobile.shared.ui.nav.NavSurface
import ai.ciris.mobile.shared.ui.nav.SubstrateGate

/**
 * Gated placeholder for the edge v18 adoption (CIRISServer#451) — see the
 * SubstrateGate entry for the prefix family and the FSD section. The wait
 * itself teaches the architecture; the screen goes live when the node-side
 * surface lands and the gate flips.
 */
@Composable
fun PrivateGroupsScreen(onIssueClick: (String) -> Unit = {}) {
    ComingSoonPlaceholder(
        title = localizedString("mesh.private_groups.title").ifEmpty { NavSurface.PrivateGroups.label },
        icon = NavSurface.PrivateGroups.icon,
        description = localizedString("mesh.private_groups.description"),
        gate = SubstrateGate.EDGE_V18_PRIVATE_GROUPS,
        onIssueClick = onIssueClick,
    )
}
