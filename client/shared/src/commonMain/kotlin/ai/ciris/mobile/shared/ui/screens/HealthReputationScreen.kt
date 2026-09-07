package ai.ciris.mobile.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.components.ComingSoonPlaceholder
import ai.ciris.mobile.shared.ui.nav.NavSurface
import ai.ciris.mobile.shared.ui.nav.SubstrateGate
import ai.ciris.mobile.shared.ui.screens.graph.CellVizState
import ai.ciris.mobile.shared.ui.theme.CIRISColors
import ai.ciris.mobile.shared.platform.rememberTestableScrollState

/**
 * "Health & Reputation" — the agent's CIRIS Capacity Score, surfaced as a
 * proper card in the new Epistemic Commons nav (2.9.4 promotion: moved out
 * of the InteractScreen badge popup).
 *
 * Data source: `InteractViewModel.cellVizState: StateFlow<CellVizState>`,
 * which already polls `/v1/my-data/capacity?scope=both`. The caller (the
 * app shell) hoists that flow and passes the current value here — no new
 * fetch path. See `graph/CellVizState.kt` for the data model and the
 * §5a TODO in CELL_VIZ_REDESIGN for the local-vs-fleet split rationale.
 *
 * **Anti-Goodhart constraint** (FSD-002 §4.7): capacity scores are an
 * **operator-facing render** only. The agent itself never reads its own
 * capacity. This screen surfaces local + fleet to the user; the underlying
 * StateFlow is similarly excluded from the agent's prompt context.
 *
 * Federation-signed capacity attestations (the full `capacity:*` namespace
 * per FSD-002 §3.5.4 — cohort-conformity, manifold-conformity, distributive
 * access, etc.) ship in a later 2.9.X patch when CIRISLensCore#25 closes.
 * The card pins that as a "Federation attestations" sub-section gated by
 * the substrate issue so users see the architecture even though local+fleet
 * gives them the score number today.
 */
@Composable
fun HealthReputationScreen(
    state: CellVizState,
    onIssueClick: (String) -> Unit = {},
) {
    val scroll = rememberTestableScrollState()
    val uriHandler = LocalUriHandler.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CIRISColors.BackgroundDark)
            .testTag("screen_health_reputation"),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .padding(24.dp)
                .verticalScroll(scroll)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Title + category pill
            HealthHeader(state)

            // Composite score hero
            CompositeScoreHero(state)

            // Five-factor breakdown
            Text(
                text = "Five-factor breakdown",
                color = CIRISColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.0.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            FactorRow("C", "Core identity", state.c, "Consistency — no contradictions, identity stable.")
            FactorRow("I_int", "Integrity", state.iInt, "All traces signed and chain-verified.")
            FactorRow("R", "Resilience", state.r, "No drift from baseline behavior.")
            FactorRow("I_inc", "Incompleteness awareness", state.iInc, "Calibrated and defers when unsure.")
            FactorRow("S", "Sustained coherence", state.s, "Ethical faculties passing; σ-maturity climbing with use.")

            // The σ-maturity explainer (lifted from the old popup)
            CapacityMaturityNote(state)

            // Federation-attestations sub-section (LensCore merged in-tree)
            Spacer(Modifier.height(8.dp))
            FederationAttestationsSection(state = state)

            // Spec link
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        uriHandler.openUri("https://ciris.ai/ciris-scoring/")
                    }
                    .testable("btn_capacity_full_spec")
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Read the full spec",
                    color = CIRISColors.AccentCyan,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun HealthHeader(state: CellVizState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = NavSurface.HealthReputation.label,
            color = CIRISColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        CategoryPill(category = if (state.isPreFetch) "pending" else state.category)
    }
}

@Composable
private fun CategoryPill(category: String) {
    val (bg, fg, label) = when (category.lowercase()) {
        "high_capacity" -> Triple(CIRISColors.SignetTeal.copy(alpha = 0.15f), CIRISColors.SignetTeal, "HIGH CAPACITY")
        "healthy" -> Triple(CIRISColors.SignetTeal.copy(alpha = 0.15f), CIRISColors.SignetTeal, "HEALTHY")
        "moderate" -> Triple(CIRISColors.BusTool.copy(alpha = 0.15f), CIRISColors.BusTool, "MODERATE")
        "high_fragility" -> Triple(CIRISColors.StatusWarn.copy(alpha = 0.15f), CIRISColors.StatusWarn, "HIGH FRAGILITY")
        "pending" -> Triple(CIRISColors.BusTool.copy(alpha = 0.15f), CIRISColors.BusTool, "WARMING UP")
        else -> Triple(CIRISColors.TextDim.copy(alpha = 0.15f), CIRISColors.TextDim, category.uppercase())
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
private fun CompositeScoreHero(state: CellVizState) {
    val hasLocal = state.localScore != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testable("card_capacity_composite"),
        color = CIRISColors.BackgroundDarker,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "COMPOSITE SCORE",
                        color = CIRISColors.TextDim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (state.isPreFetch) "—" else fmt(state.compositeScore),
                        color = CIRISColors.TextPrimary,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (!state.isPreFetch && hasLocal) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Local: ${fmt(state.localScore!!)} · Fleet: ${fmt(state.compositeScore)}",
                            color = CIRISColors.TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Surface(
                    color = CIRISColors.SignetTeal.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = if (hasLocal) "LOCAL + FLEET" else "FLEET ONLY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = CIRISColors.SignetTeal,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (!state.isPreFetch) {
                LinearProgressIndicator(
                    progress = { state.compositeScore.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = CIRISColors.AccentCyan,
                    trackColor = Color.White.copy(alpha = 0.08f),
                )
            }
        }
    }
}

@Composable
private fun FactorRow(
    symbol: String,
    title: String,
    score: Float,
    description: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testable("factor_row_${symbol.lowercase()}"),
        color = CIRISColors.BackgroundDarker,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = symbol,
                    color = CIRISColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = CIRISColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = CIRISColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            Text(
                text = fmt(score),
                color = CIRISColors.AccentCyan,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun CapacityMaturityNote(state: CellVizState) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testable("card_capacity_maturity"),
        color = CIRISColors.BackgroundDarker.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.04f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "ℹ",
                color = CIRISColors.AccentCyan,
                fontSize = 14.sp,
            )
            Text(
                text = "σ-maturity tracks behavioral stability over time. As the agent completes interactions, sustained coherence confidence climbs.",
                color = CIRISColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun FederationAttestationsSection(state: CellVizState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CIRISColors.BackgroundDarker.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .padding(14.dp)
            .testable("card_federation_capacity_attestations"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Federation attestations",
                color = CIRISColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (state.isPreFetch) {
                Surface(
                    color = CIRISColors.BusTool.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = "WARMING UP",
                        color = CIRISColors.BusTool,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.0.sp,
                        modifier = Modifier
                            .testable("federation_capacity_warming_up")
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            } else {
                Surface(
                    color = CIRISColors.SignetTeal.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = "LIVE",
                        color = CIRISColors.SignetTeal,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.0.sp,
                        modifier = Modifier
                            .testable("federation_capacity_live")
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            text = if (state.isPreFetch) {
                "Node capacity detectors warming up. Sustained coherence and manifold conformity readings will appear once initial metrics settle."
            } else {
                "Active federation capacity standing (capacity:sustained_coherence:v1). Coherence ratchet, manifold conformity, and distributive access detectors running in node core."
            },
            color = CIRISColors.TextDim,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

// Integer-math formatter — String.format is JVM-only in commonMain.
private fun fmt(v: Float): String {
    val hundredths = (v.coerceIn(0f, 1f) * 100f + 0.5f).toInt()
    val whole = hundredths / 100
    val frac = hundredths % 100
    val fracStr = if (frac < 10) "0$frac" else "$frac"
    return "$whole.$fracStr"
}

// Same formatter for unbounded values like fragility (capped at 9.99 for display).
private fun fmt2(v: Float): String {
    val capped = v.coerceIn(0f, 9.99f)
    val hundredths = (capped * 100f + 0.5f).toInt()
    val whole = hundredths / 100
    val frac = hundredths % 100
    val fracStr = if (frac < 10) "0$frac" else "$frac"
    return "$whole.$fracStr"
}

/**
 * Deprecated overload — kept as the no-arg path for callers wired up before
 * the state-hoisted version landed. Renders a Coming Soon placeholder; new
 * callers should use the [state]-taking overload above.
 *
 * Once the CIRISApp.kt rewire wires every callsite through the state-hoisted
 * shape, delete this overload.
 */
/**
 * Deprecated overload — kept as the no-arg path for callers wired up before
 * the state-hoisted version landed. Renders the live card with default state.
 */
@Deprecated(
    message = "Use the overload that takes CellVizState — the score now ships as a real card.",
    replaceWith = ReplaceWith("HealthReputationScreen(state, onIssueClick)"),
)
@Composable
fun HealthReputationScreen(onIssueClick: (String) -> Unit = {}) {
    HealthReputationScreen(
        state = CellVizState(),
        onIssueClick = onIssueClick,
    )
}
