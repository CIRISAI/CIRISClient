package ai.ciris.mobile.shared.ui.screens.commons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.rememberTestableScrollState
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.platform.testableWithHandler
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.CohortScope
import ai.ciris.mobile.shared.ui.theme.CIRISColors

/**
 * The Rules hub of four circles (CSD-050): Just me, Family, Neighbours and
 * Communities and Businesses. Everyone's Rules tab is NOT this screen — it
 * renders the transport hub (CSD-051).
 *
 * What each circle's hub reads is that circle's own group, and only that:
 *
 * - **Family** — the household (CSD-100, [familyContent]): `GET /v1/families`
 *   and the acts that change it, at the node URL.
 * - **Neighbours / Communities and Businesses** — the community section
 *   (CSD-102, [communities]): `GET /v1/communities` at that tier and the acts
 *   that change a room, at the node URL.
 * - **Just me** — nothing. No route lists identities at a cohort scope
 *   (CIRISServer#662) and no registry family names a trust policy
 *   (CIRISConstitution#109), so its three sections are sentences describing
 *   rows nobody asked for. They are drawn ONLY where the circle has no group
 *   of its own to show; a hub with real rows never sits them under its data.
 */
@Composable
fun LayerHubScreen(
    scope: CohortScope,
    hasAgent: Boolean = false,
    onOpenEnvironment: (() -> Unit)? = null,
    onOpenDelegations: (() -> Unit)? = null,
    onIssueClick: (String) -> Unit = {},
    /**
     * Households (CSD-100): the Family hub's own content — the household you
     * are in, how it decides, and the acts that change it. When present it
     * comes first and replaces the three description-only sections for the
     * Family scope, which describe data this hub never fetched (CSD-050 §1).
     */
    familyContent: (@Composable () -> Unit)? = null,
    // Communities and affiliations (CSD-102): the circle's own rooms, their
    // rules, roles and pending changes. Neighbours and Communities and
    // Businesses pass their tier's view model; every other circle passes null.
    communities: ai.ciris.mobile.shared.viewmodels.CommunitiesViewModel? = null,
    onOpenModeration: ((communityId: String) -> Unit)? = null,
) {
    val scrollState = rememberTestableScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testable("layer_hub_${scope.id.replace('-', '_')}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LayerHeader(scope = scope, icon = scopeIcon(scope))

            // ── Households (CSD-100): the Family hub IS the household ──
            if (scope == CohortScope.FAMILY && familyContent != null) {
                familyContent()
                if (onOpenDelegations != null) FamilyDelegationsCard(onOpenDelegations = onOpenDelegations)
                return@Column
            }

            // ── Scope-specific feature cards ──
            if (scope == CohortScope.LOCAL_COMMUNITY && hasAgent && onOpenEnvironment != null) {
                LocalCommunityEnvironmentCard(onOpenEnvironment = onOpenEnvironment)
            } else if (scope == CohortScope.FAMILY && onOpenDelegations != null) {
                FamilyDelegationsCard(onOpenDelegations = onOpenDelegations)
            }

            // ── Communities and affiliations (CSD-102) — the community itself,
            // on the hub that already stands for this circle, not a card beside it.
            // Like the household, it replaces the description-only sections: they
            // describe rows this hub never fetched (CSD-050 §1), and under the
            // real roster they read as a second, empty list.
            if (communities != null) {
                ai.ciris.mobile.shared.ui.screens.CommunityGovernanceSection(
                    viewModel = communities,
                    onOpenModeration = onOpenModeration,
                )
                return@Column
            }

            LayerSection(
                testTag = "layer_section_identities_${scope.id.replace('-', '_')}",
                icon = CIRISIcons.person,
                titleKey = "commons.layer.section.identities",
                descriptionKey = identitiesDescriptionKey(scope),
                onIssueClick = onIssueClick,
            )

            LayerSection(
                testTag = "layer_section_trust_${scope.id.replace('-', '_')}",
                icon = CIRISIcons.shield,
                titleKey = "commons.layer.section.trust",
                descriptionKey = trustDescriptionKey(scope),
                onIssueClick = onIssueClick,
            )

            LayerSection(
                testTag = "layer_section_policies_${scope.id.replace('-', '_')}",
                icon = CIRISIcons.lock,
                titleKey = "commons.layer.section.policies",
                descriptionKey = policiesDescriptionKey(scope),
                onIssueClick = onIssueClick,
            )
        }
    }
}

@Composable
private fun LayerHeader(scope: CohortScope, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = localizedString(scopeTitleKey(scope)),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = localizedString(scopeSubtitleKey(scope)),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun LocalCommunityEnvironmentCard(onOpenEnvironment: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testable("card_local_community_environment"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = CIRISIcons.snapshot,
                    contentDescription = null,
                    tint = CIRISColors.AccentCyan,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = localizedString("commons.federation.environment_graph.title"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = localizedString("commons.federation.environment_graph.description"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
            Button(
                onClick = onOpenEnvironment,
                modifier = Modifier
                    .fillMaxWidth()
                    .testableWithHandler("btn_open_environment") { onOpenEnvironment() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = CIRISIcons.snapshot,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = localizedString("commons.federation.environment_graph.title"),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun FamilyDelegationsCard(onOpenDelegations: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testable("card_family_delegations"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = CIRISIcons.send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = localizedString("commons.federation.delegation.title"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = localizedString("commons.federation.delegation.description"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
            Button(
                onClick = onOpenDelegations,
                modifier = Modifier
                    .fillMaxWidth()
                    .testableWithHandler("btn_open_delegations") { onOpenDelegations() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = CIRISIcons.keySecure,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = localizedString("nav.surface.delegations"),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LayerSection(
    testTag: String,
    icon: ImageVector,
    titleKey: String,
    descriptionKey: String,
    onIssueClick: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testable(testTag),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = localizedString(titleKey),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = localizedString(descriptionKey),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
    }
}





// ─── Per-scope metadata ──────────────────────────────────────────────────────

private fun scopeIcon(scope: CohortScope): ImageVector = when (scope) {
    CohortScope.AGENT -> CIRISIcons.person
    CohortScope.FAMILY -> CIRISIcons.home
    CohortScope.LOCAL_COMMUNITY -> CIRISIcons.location
    CohortScope.GLOBAL_COMMUNITIES -> CIRISIcons.shield
    CohortScope.GLOBAL_COMMONS -> CIRISIcons.globe
}


private fun scopeTitleKey(scope: CohortScope): String = "commons.layer.${scope.id.replace('-', '_')}.title"

private fun scopeSubtitleKey(scope: CohortScope): String = "commons.layer.${scope.id.replace('-', '_')}.subtitle"

private fun identitiesDescriptionKey(scope: CohortScope): String =
    "commons.layer.${scope.id.replace('-', '_')}.identities_description"

private fun trustDescriptionKey(scope: CohortScope): String =
    "commons.layer.${scope.id.replace('-', '_')}.trust_description"

private fun policiesDescriptionKey(scope: CohortScope): String =
    "commons.layer.${scope.id.replace('-', '_')}.policies_description"
