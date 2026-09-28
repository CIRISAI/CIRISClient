package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.theme.CIRISColors
import ai.ciris.mobile.shared.ui.components.FederationIdCard
import ai.ciris.mobile.shared.viewmodels.NetworkViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import ai.ciris.mobile.shared.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciris.mobile.shared.platform.testableVerticalScroll

/**
 * Network — federation transport substrate operator hub (2.9.4).
 *
 * Hub-and-spoke replacement for the prior 5-tab scaffold. The hub itself
 * exposes:
 *   1. Identity card — federation signer_key_id with copy-to-clipboard + QR
 *      placeholder (Edge 1.0 ratchet/rotation lands in a sibling release).
 *   2. A link to Settings for the agent's network mode, with an agent attached.
 *      The mode itself (`PUT /v1/system/agent-mode`, CIRISAgent-only) is set in
 *      Settings, its one door (CSD-022 §2.0.1, CSD-051 §3): this hub is on every
 *      build and a bare node 404s that route, so it no longer reads or writes it.
 *   3. Live stats strip — 4 inline metrics (placeholders until Edge 1.0).
 *   4. 10 navigation tiles — Identity / Map / Trust Graph / Peers /
 *      Interfaces / Paths / Announces / Queue / Diagnostics / Content.
 *
 * All ten sub-screens are live (T-E / T-E-D) — tiles navigate directly.
 * capability is *visible* to operators today, not deferred to "Edge 1.0 ships."
 */
@Composable
fun NetworkScreen(
    viewModel: NetworkViewModel,
    onTileClick: (NetworkTile) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * An agent is attached. The Federation ID card reads the agent's persist
     * aggregate (`GET /v1/system/peers/federation-identity`, CIRISAgent-only):
     * on a bare node that read can only fail, and the card would say
     * "Identity initializing…" for ever. So it is drawn only with an agent.
     */
    hasAgent: Boolean = false,
    /**
     * Opens Settings, where the agent's network mode is set. Drawn only with
     * an agent: a bare node has no mode to set.
     */
    onOpenModeSettings: (() -> Unit)? = null,
) {
    val federationAddress by viewModel.federationAddress.collectAsState()
    val federationId by viewModel.federationId.collectAsState()
    val identityFailure by viewModel.identityFailure.collectAsState()

    LaunchedEffect(Unit) {
        // Fetch the real federation identity (signer_key_id) from Edge. If Edge
        // is degraded/unavailable the address stays null and the card shows "—"
        // — never a fabricated key — and the failure is said under it.
        viewModel.loadFederationIdentity()
    }

    // Bounded hub content (identity cards + mode link + stats strip + ~10
    // tiles) — uses `Column.verticalScroll` instead of `LazyColumn` so every
    // section composes eagerly regardless of viewport width. T-T2 / T-T3
    // wrapped the rows in a single `LazyColumn item { … }` for desktop, but
    // T-Q5 surfaced that the wrapper itself falls below the fold on Android's
    // narrower viewport (1080×2400 with sidebar consuming the left half), so
    // the LazyColumn skips composing the stats strip + tiles grid and 14
    // testTags don't appear in the test-automation tree. A bounded Column +
    // verticalScroll has the same UX (scrollable, padded, spaced) but
    // composes everything eagerly — testTags reach `/tree` on Android and
    // desktop alike.
    // Surface paints the theme background — without it the hub bleeds the
    // host's default (white) behind the cards.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testable("screen_network_hub")
            .testableVerticalScroll()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle()

        // ── Identity card ────────────────────────────────────────────────────
        IdentityCard(address = federationAddress)

        // ── Federation ID (persist identity aggregate) — top-level, not
        //    buried in the Identity sub-screen ─────────────────────────────────
        if (hasAgent) FederationIdCard(federationId = federationId)

        // Why the key reads "—": the node was asked and did not answer, or has
        // no such route. Never a blank that reads as "no identity".
        identityFailure?.let { ReadFailureBlock(it, tagPrefix = "network_identity", inline = true) }

        // ── Network mode: set in Settings (CSD-051 §3) ───────────────────────
        if (hasAgent) onOpenModeSettings?.let { ModeSettingsLink(onOpen = it) }

        // ── Live stats strip ─────────────────────────────────────────────────
        StatsStrip()

        // ── 10 navigation tiles in a 2-column grid ───────────────────────────
        tilesGrid(onTileClick)
    }
    }
}

/**
 * Navigation tile identity — used by [NetworkScreen] callers to route to the
 * matching sub-screen via the existing `screenToSurface` bridge.
 */
enum class NetworkTile(val route: String) {
    IDENTITY("federation/identity"),
    TRUST_GRAPH("federation/trust_graph"),
    PEERS("federation/peers"),
    INTERFACES("federation/interfaces"),
    PATHS("federation/paths"),
    ANNOUNCES("federation/announces"),
    QUEUE("federation/queue"),
    DIAGNOSTICS("federation/diagnostics"),
    CONTENT("federation/content"),
}

// ═══════════════════════════════════════════════════════════════════════════
// Header
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ScreenTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = CIRISIcons.globe,
            contentDescription = null,
            tint = CIRISColors.AccentCyan,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = localizedString("network.title"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Identity card
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun IdentityCard(address: String?) {
    val clipboardManager = LocalClipboardManager.current
    // Always compose AddressRow so its `testable("text_network_identity_key")`
    // + `testableClickable("btn_network_identity_copy")` modifiers fire
    // `onGloballyPositioned` on first paint. When no real signer_key_id is
    // available yet (Edge 1.0 wires it), render the honest "—" placeholder —
    // the row still composes so the walk-test contract holds, but we never
    // show a fabricated key.
    val rendered: String = address?.takeIf { it.isNotBlank() } ?: "—"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testable("card_network_identity"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = localizedString("network.identity_card.title"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            AddressRow(address = rendered, clipboard = clipboardManager)
        }
    }
}

@Composable
private fun AddressRow(
    address: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
) {
    // Reticulum cribsheet: render full 32-char hex inside <...>, truncate to
    // <…last10> when tight. We render full + provide copy; truncation happens
    // in narrow column constraints downstream.
    val rendered = "<$address>"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = rendered,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                modifier = Modifier.testable("text_network_identity_key"),
            )
        }
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(address)) },
            modifier = Modifier.testableClickable("btn_network_identity_copy") {
                clipboard.setText(AnnotatedString(address))
            },
        ) {
            Icon(
                imageVector = CIRISMaterialIcons.Filled.ContentCopy,
                contentDescription = localizedString("network.identity_card.copy_address"),
                tint = CIRISColors.AccentCyan,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SelectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.text.selection.SelectionContainer(modifier = modifier) {
        content()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Mode selector card
// ═══════════════════════════════════════════════════════════════════════════

/**
 * The agent's network mode lives in Settings, with the rest of the agent's
 * configuration — one door for `PUT /v1/system/agent-mode`. This card only
 * says where, so a person who looked for it on the hub is not left guessing.
 */
@Composable
private fun ModeSettingsLink(onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testable("card_network_mode_link"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = localizedString("network.mode_card.title"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = localizedString("network.mode_card.set_in_settings"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onOpen,
                modifier = Modifier.testableClickable("btn_network_mode_open_settings") { onOpen() },
            ) { Text(localizedString("network.mode_card.open_settings")) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Live stats strip (placeholders until Edge 1.0)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun StatsStrip() {
    val cells = listOf(
        "text_stat_peers" to localizedString("network.stats_strip.peers"),
        "text_stat_transports" to localizedString("network.stats_strip.transports"),
        "text_stat_queue" to localizedString("network.stats_strip.queue"),
        "text_stat_errors" to localizedString("network.stats_strip.errors"),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
        ) {
            cells.forEachIndexed { idx, (tag, label) ->
                StatCell(tag = tag, label = label, modifier = Modifier.weight(1f))
                if (idx != cells.lastIndex) StatDivider()
            }
        }
    }
}

@Composable
private fun StatCell(tag: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp).testable(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "—",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(48.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// Navigation tiles — 2-column grid (LazyColumn rows of 2)
// ═══════════════════════════════════════════════════════════════════════════

private data class TileSpec(
    val tile: NetworkTile,
    val labelKey: String,
    val icon: ImageVector,
)

private val TILE_ROW_1 = listOf(
    TileSpec(NetworkTile.IDENTITY, "network.tiles.identity", CIRISIcons.identity),
    // The Map tile was retired into the Trust graph (CSD-046): both drew the
    // same peer list in the same three tiers.
    TileSpec(NetworkTile.TRUST_GRAPH, "network.tiles.trust_graph", CIRISIcons.welcome),
)
private val TILE_ROW_2 = listOf(
    TileSpec(NetworkTile.PEERS, "network.tiles.peers", CIRISIcons.person),
)
private val TILE_ROW_3 = listOf(
    TileSpec(NetworkTile.INTERFACES, "network.tiles.interfaces", CIRISIcons.adapter),
    TileSpec(NetworkTile.PATHS, "network.tiles.paths", CIRISIcons.send),
)
private val TILE_ROW_4 = listOf(
    TileSpec(NetworkTile.ANNOUNCES, "network.tiles.announces", CIRISIcons.bus),
    TileSpec(NetworkTile.QUEUE, "network.tiles.queue", CIRISIcons.pkg),
)
private val TILE_ROW_5 = listOf(
    TileSpec(NetworkTile.DIAGNOSTICS, "network.tiles.diagnostics", CIRISIcons.telemetry),
    TileSpec(NetworkTile.CONTENT, "network.tiles.content", CIRISIcons.pkg),
)

@Composable
private fun tilesGrid(onTileClick: (NetworkTile) -> Unit) {
    val rows = listOf(TILE_ROW_1, TILE_ROW_2, TILE_ROW_3, TILE_ROW_4, TILE_ROW_5)
    // Post-T-T4: hub is a Column + verticalScroll (was LazyColumn), so the
    // tiles grid is a bare Composable rather than a LazyListScope extension.
    // Every row + tile composes eagerly regardless of viewport width — see
    // the call-site comment in `NetworkScreen` for the T-Q5 Android viewport
    // diagnostic that motivated the LazyColumn → Column conversion.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (spec in row) {
                    NavTile(
                        spec = spec,
                        onClick = { onTileClick(spec.tile) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavTile(
    spec: TileSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .height(120.dp)
            .clickable { onClick() }
            .testableClickable("tile_federation_${spec.tile.name.lowercase()}") { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CIRISColors.AccentCyan.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = null,
                    tint = CIRISColors.AccentCyan,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column {
                Text(
                    text = localizedString(spec.labelKey),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════════

// MOCK_NETWORK_SNAPSHOT removed 2.9.6 — the identity card no longer seeds a
// fabricated federation key. The real signer_key_id arrives via Edge 1.0; until
// then the card renders the honest "—" placeholder.
