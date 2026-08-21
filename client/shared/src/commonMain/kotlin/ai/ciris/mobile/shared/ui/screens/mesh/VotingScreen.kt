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
fun VotingScreen(onIssueClick: (String) -> Unit = {}) {
    ComingSoonPlaceholder(
        title = localizedString("mesh.voting.title").ifEmpty { NavSurface.Voting.label },
        icon = NavSurface.Voting.icon,
        description = localizedString("mesh.voting.description"),
        gate = SubstrateGate.EDGE_V18_VOTING,
        onIssueClick = onIssueClick,
    )
}
