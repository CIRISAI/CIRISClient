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
import ai.ciris.mobile.shared.ui.nav.SubstrateGate
import ai.ciris.mobile.shared.ui.theme.CIRISColors

/**
 * Generic layer hub for the 5 UX-facing cohort scopes. Renders three
 * sections that repeat at every scale (Recursive Golden Rule fractal —
 * the same shape applies at Self, Family, Local Community, Global
 * Communities, Global Commons):
 *
 * - **Identities** — list of identities visible at this scope, with
 *   friendly names where available, key_id otherwise.
 * - **Trust** — for each identity, are we trusting them, and if so how
 *   (via a trust policy or direct trust).
 * - **Policies** — trust policies that govern automatic trust at this
 *   scope.
 *
 * EDGE_PEERRESOLVER (CIRISEdge#22) has shipped; the cohort-aware views
 * are active. Local Community exposes the Environment & Resources surface
 * directly to allow sharing physical resources, tools, and inventory.
 */
@Composable
fun LayerHubScreen(
    scope: CohortScope,
    hasAgent: Boolean = false,
    onOpenEnvironment: (() -> Unit)? = null,
    onOpenDelegations: (() -> Unit)? = null,
    onIssueClick: (String) -> Unit = {},
) {
    val gate = scopeGate(scope)
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

            // ── Scope-specific feature cards ──
            if (scope == CohortScope.LOCAL_COMMUNITY && hasAgent && onOpenEnvironment != null) {
                LocalCommunityEnvironmentCard(onOpenEnvironment = onOpenEnvironment)
            } else if (scope == CohortScope.FAMILY && onOpenDelegations != null) {
                FamilyDelegationsCard(onOpenDelegations = onOpenDelegations)
            }

            LayerSection(
                testTag = "layer_section_identities_${scope.id.replace('-', '_')}",
                icon = CIRISIcons.person,
                titleKey = "commons.layer.section.identities",
                descriptionKey = identitiesDescriptionKey(scope),
                gate = gate,
                onIssueClick = onIssueClick,
            )

            LayerSection(
                testTag = "layer_section_trust_${scope.id.replace('-', '_')}",
                icon = CIRISIcons.shield,
                titleKey = "commons.layer.section.trust",
                descriptionKey = trustDescriptionKey(scope),
                gate = gate,
                onIssueClick = onIssueClick,
            )

            LayerSection(
                testTag = "layer_section_policies_${scope.id.replace('-', '_')}",
                icon = CIRISIcons.lock,
                titleKey = "commons.layer.section.policies",
                descriptionKey = policiesDescriptionKey(scope),
                gate = gate,
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
    gate: SubstrateGate? = null,
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
                if (gate != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    ComingSoonBadge()
                }
            }
            Text(
                text = localizedString(descriptionKey),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
            if (gate != null) {
                GateRow(gate = gate, onIssueClick = onIssueClick)
            }
        }
    }
}

@Composable
private fun ComingSoonBadge() {
    Surface(
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = localizedString("commons.layer.badge.coming_soon"),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}



@Composable
private fun GateRow(gate: SubstrateGate, onIssueClick: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testableClickable("layer_gate_${gate.name.lowercase()}") {
                onIssueClick(gate.url)
            },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "🔗",
                fontSize = 11.sp,
            )
            Text(
                text = gate.shortRef,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "·",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = gate.fsdSection,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun scopeGate(scope: CohortScope): SubstrateGate? = when (scope) {
    // EDGE_PEERRESOLVER (CIRISEdge#22) has shipped; all cohort scopes are live.
    CohortScope.AGENT,
    CohortScope.FAMILY,
    CohortScope.LOCAL_COMMUNITY,
    CohortScope.GLOBAL_COMMUNITIES,
    CohortScope.GLOBAL_COMMONS -> null
}

private fun scopeTitleKey(scope: CohortScope): String = "commons.layer.${scope.id.replace('-', '_')}.title"

private fun scopeSubtitleKey(scope: CohortScope): String = "commons.layer.${scope.id.replace('-', '_')}.subtitle"

private fun identitiesDescriptionKey(scope: CohortScope): String =
    "commons.layer.${scope.id.replace('-', '_')}.identities_description"

private fun trustDescriptionKey(scope: CohortScope): String =
    "commons.layer.${scope.id.replace('-', '_')}.trust_description"

private fun policiesDescriptionKey(scope: CohortScope): String =
    "commons.layer.${scope.id.replace('-', '_')}.policies_description"
