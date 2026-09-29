package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.primitives.CirisTextField
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.FieldRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.viewmodels.DataManagementViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import ai.ciris.mobile.shared.ui.icons.*
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar

/**
 * Data — My things › Everything I shared › Data (CSD-039).
 *
 * The person's data rights, self-served:
 *  1. Sharing outward (the accord traces opt-in, `PUT /v1/my-data/accord-settings`)
 *     — THIS card owns that write; Manage Consent and Add Federation ID point here.
 *  2. Ask CIRISLens to delete the traces already sent — which this card reports
 *     as REQUESTED and never as done: the agent returns no SLA and no completion
 *     (CIRISAgent#1212).
 *  3. Erase an agent's traces on this node, and check a deletion receipt
 *     (`DataErasureSections.kt`).
 *  4. Reset the account (key preserved) and wipe the signing key (wallet lost).
 *
 * Every irreversible act here is behind a three-fact confirm (who, what
 * changes, who authorises) — and the third fact is honest about who signs:
 * for the node's own acts it is the owner session, not a signature of yours.
 *
 * A read that failed is said, persistently and tagged (`data_error`), never as
 * a snackbar with a timer on it; and on a build without an agent the sharing
 * block says the agent is not here (`data_accord_not_on_this_node`) rather
 * than offering an "Enable" that has no host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(
    viewModel: DataManagementViewModel,
    onNavigateBack: () -> Unit,
    onResetSetup: () -> Unit,
    modifier: Modifier = Modifier,
    /** An agent is attached: the sharing and receipt sections show, and the trace id pre-fills (CSD-039 §2). */
    hasAgent: Boolean = false,
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val lensIdentifier by viewModel.lensIdentifier.collectAsState()
    val accordSettings by viewModel.accordSettings.collectAsState()
    val accordFailure by viewModel.accordFailure.collectAsState()
    val communityPeer by viewModel.communityPeer.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isDeletingLensTraces by viewModel.isDeletingLensTraces.collectAsState()
    val lensDeletionResult by viewModel.lensDeletionResult.collectAsState()
    val isResetting by viewModel.isResetting.collectAsState()
    val resetSuccess by viewModel.resetSuccess.collectAsState()
    val isWipingSigningKey by viewModel.isWipingSigningKey.collectAsState()
    val wipeSigningKeySuccess by viewModel.wipeSigningKeySuccess.collectAsState()
    val isLoadingAdapter by viewModel.isLoadingAdapter.collectAsState()

    var showResetDialog by remember { mutableStateOf(false) }
    var showWipeSigningKeyDialog by remember { mutableStateOf(false) }
    var showDeleteTracesDialog by remember { mutableStateOf(false) }
    var deletionReason by remember { mutableStateOf("") }

    // Load data when screen is first shown
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    // Handle reset success - trigger app restart (signing key preserved)
    LaunchedEffect(resetSuccess) {
        if (resetSuccess) {
            viewModel.clearFactoryResetSuccess()
            onResetSetup()
        }
    }

    // Handle wipe signing key success - trigger app restart (wallet access destroyed)
    LaunchedEffect(wipeSigningKeySuccess) {
        if (wipeSigningKeySuccess) {
            viewModel.clearWipeSigningKeySuccess()
            onResetSetup()
        }
    }

    // ── Reset account: three facts, two buttons (tagPrefix "reset" keeps
    // btn_reset_confirm / btn_reset_cancel). The node does it on the owner
    // session; nothing is signed as the person.
    if (showResetDialog) {
        ConfirmSheet(
            title = localizedString("mobile.data_reset_confirm"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.data_fact_who"), localizedString("mobile.data_reset_fact_who")),
                ConfirmFact(
                    localizedString("mobile.data_fact_changes"),
                    localizedString("mobile.data_reset_confirm_body") + " " + localizedString("mobile.data_reset_wallet_preserved"),
                ),
                ConfirmFact(localizedString("mobile.data_fact_authorised"), localizedString("mobile.data_fact_owner_session")),
            ),
            confirmLabel = localizedString("mobile.data_reset_account"),
            onConfirm = {
                showResetDialog = false
                viewModel.factoryReset()
            },
            onDismiss = { showResetDialog = false },
            destructive = true,
            tagPrefix = "reset",
        )
    }

    // ── Wipe the signing key: the loudest one. tagPrefix "wipe_key" keeps
    // btn_wipe_key_confirm / btn_wipe_key_cancel.
    if (showWipeSigningKeyDialog) {
        ConfirmSheet(
            title = localizedString("mobile.data_wipe_key_confirm"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.data_fact_who"), localizedString("mobile.data_wipe_key_fact_who")),
                ConfirmFact(
                    localizedString("mobile.data_fact_changes"),
                    localizedString("mobile.data_wipe_key_warning") + " " + localizedString("mobile.data_wipe_key_funds_lost"),
                ),
                ConfirmFact(localizedString("mobile.data_fact_authorised"), localizedString("mobile.data_fact_owner_session")),
            ),
            confirmLabel = localizedString("mobile.data_wipe_key_button"),
            onConfirm = {
                showWipeSigningKeyDialog = false
                viewModel.wipeSigningKey()
            },
            onDismiss = { showWipeSigningKeyDialog = false },
            destructive = true,
            tagPrefix = "wipe_key",
        )
    }

    // ── Ask CIRISLens to delete the traces already sent. tagPrefix
    // "delete_traces" keeps btn_delete_traces_confirm / btn_delete_traces_cancel.
    // The reason is entered on the card (input_delete_traces_reason), not in
    // the confirm: a confirm names facts, it does not collect them.
    if (showDeleteTracesDialog) {
        ConfirmSheet(
            title = localizedString("mobile.data_delete_traces"),
            facts = listOf(
                ConfirmFact(
                    localizedString("mobile.data_fact_who"),
                    localizedString("mobile.data_delete_traces_fact_who", "hash", accordSettings?.agentIdHash ?: NOT_READ),
                    mono = true,
                ),
                ConfirmFact(localizedString("mobile.data_fact_changes"), localizedString("mobile.data_delete_traces_fact_changes")),
                ConfirmFact(localizedString("mobile.data_fact_signs"), localizedString("mobile.data_delete_traces_fact_signs")),
            ),
            confirmLabel = localizedString("mobile.data_delete_traces_button"),
            onConfirm = {
                showDeleteTracesDialog = false
                viewModel.deleteLensTraces(deletionReason.takeIf { it.isNotBlank() })
                deletionReason = ""
            },
            onDismiss = { showDeleteTracesDialog = false },
            destructive = true,
            tagPrefix = "delete_traces",
        )
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("mobile.nav_data_management")) },
                navigationIcon = {
                    // Suppressed on compact viewports — the global 3-state
                    // overlay button in CIRISApp handles back navigation
                    // there to avoid the prior "back arrow + signet stacked"
                    // bug. Wider viewports (tablet/desktop) keep this arrow.
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testableClickable("btn_back") { onNavigateBack() }
                        ) {
                            Icon(
                                imageVector = CIRISIcons.arrowBack,
                                contentDescription = localizedString("mobile.common_back")
                            )
                        }
                    } else {
                        // Reserve the global signet/back overlay's footprint so the
                        // TopAppBar title doesn't slide underneath it on compact.
                        Spacer(Modifier.width(56.dp))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.testableClickable("btn_refresh") { viewModel.refresh() }
                    ) {
                        Icon(
                            imageVector = CIRISIcons.refresh,
                            contentDescription = localizedString("mobile.common_refresh")
                        )
                    }
                }
            )
        },
    ) { paddingValues ->

        if (isLoading) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .testable("data_loading"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(localizedString("mobile.data_loading"))
                }
            }
        } else {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .testable("data_loaded")
                    .testableVerticalScroll()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // A read or an act that failed, said where it stays until the
                // next refresh — an error with a timer on it is not a state.
                errorMessage?.let {
                    StateBlock(
                        ListState.Error(title = localizedString("mobile.state_read_failed"), detail = it),
                        tag = "data_error",
                        inline = true,
                    )
                }

                // Header
                Text(
                    text = localizedString("mobile.data_rights_title"),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = localizedString("mobile.data_rights_desc_full"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Privacy & Data Practices summary
                PrivacyInfoCard()

                // Section 1: Delete Opt-In Traces
                Text(
                    text = localizedString("mobile.data_lens_title"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                DeleteTracesCard(
                    hasAgent = hasAgent,
                    accordSettings = accordSettings,
                    accordFailure = accordFailure,
                    communityPeer = communityPeer,
                    isDeleting = isDeletingLensTraces,
                    isLoadingAdapter = isLoadingAdapter,
                    deletionReason = deletionReason,
                    onDeletionReasonChange = { deletionReason = it },
                    deletionResult = lensDeletionResult,
                    onDeleteClick = { showDeleteTracesDialog = true },
                    onConsentChanged = { consent -> viewModel.updateAccordConsent(consent) },
                    onEnableAdapter = { viewModel.enableAccordMetrics() }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Erasure on this node, and the receipt that proves an erasure
                // (CSD-039 §3). The node section shows on every build; the
                // receipt routes are the agent's. A node-only build pre-fills
                // nothing: the node's own id files no traces.
                NodeTraceErasureSection(
                    controller = viewModel.erasure,
                    prefillAgentIdHash = if (hasAgent) lensIdentifier?.agentIdHash else null,
                )
                if (hasAgent) {
                    DeletionReceiptSection(controller = viewModel.erasure)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Section 2: Reset Account (preserves signing key for wallet access)
                Text(
                    text = localizedString("mobile.data_reset_title"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                ResetAccountCard(
                    isResetting = isResetting,
                    onResetClick = { showResetDialog = true }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Section 3: DANGER - Wipe Signing Key (destroys wallet access)
                Text(
                    text = localizedString("mobile.data_wipe_key_title"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )

                WipeSigningKeyCard(
                    isWiping = isWipingSigningKey,
                    onWipeClick = { showWipeSigningKeyDialog = true }
                )
            }
        }
    }
}

/**
 * Card for managing CIRISLens trace collection and deletion.
 * Uses accordSettings as the source of truth (matches adapter state shown in Adapters screen).
 *
 * The accord routes are the AGENT's. Without an agent the card says so and
 * offers nothing; with one, a failed read that is not "adapter not loaded"
 * is said as a failure rather than drawn as the Enable button.
 */
@Composable
private fun DeleteTracesCard(
    hasAgent: Boolean,
    accordSettings: ai.ciris.mobile.shared.api.AccordSettingsData?,
    accordFailure: ReadFailure?,
    communityPeer: ai.ciris.mobile.shared.models.federation.LocalPeerState?,
    isDeleting: Boolean,
    isLoadingAdapter: Boolean,
    deletionReason: String,
    onDeletionReasonChange: (String) -> Unit,
    deletionResult: ai.ciris.mobile.shared.api.LensDeletionResult?,
    onDeleteClick: () -> Unit,
    onConsentChanged: (Boolean) -> Unit,
    onEnableAdapter: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    // accordSettings is the source of truth for consent (matches adapter state)
    val isConsentActive = accordSettings?.consentGiven == true
    // Adapter is loaded if we have accord settings
    val adapterLoaded = accordSettings != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConsentActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = localizedString("mobile.data_accord_title"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // Info text - always show
            Text(
                text = localizedString("mobile.data_accord_desc_full"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = localizedString("mobile.data_accord_learn"),
                style = MaterialTheme.typography.bodySmall.copy(
                    textDecoration = TextDecoration.Underline
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    uriHandler.openUri("https://ciris.ai/ciris-scoring")
                }
            )

            // No agent: the sharing routes have no host here. Said, not "Enable".
            if (!hasAgent) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                StateBlock(
                    ListState.Empty(localizedString("mobile.data_accord_not_on_this_node")),
                    tag = "data_accord_not_on_this_node",
                    inline = true,
                )
                return@Column
            }

            // Adapter-specific controls - only show when adapter is loaded
            accordSettings?.let { settings ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Status info
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow(localizedString("mobile.data_agent_hash_label"), settings.agentIdHash, "data_row_lens_identifier")
                    InfoRow(localizedString("mobile.data_events_sent"), settings.eventsSent.toString(), "data_row_events_sent")
                    if (settings.eventsReceived > 0 || settings.eventsQueued > 0) {
                        InfoRow(localizedString("mobile.data_events_captured"), settings.eventsReceived.toString())
                        if (settings.eventsQueued > 0) {
                            InfoRow(localizedString("mobile.data_events_queued"), settings.eventsQueued.toString(), "data_row_events_queued")
                        }
                    }
                    settings.traceLevel?.let { level ->
                        InfoRow(localizedString("mobile.data_detail_level"), level)
                    }
                    settings.endpointUrl?.let { url ->
                        InfoRow(localizedString("mobile.data_endpoint"), url.take(40) + if (url.length > 40) "..." else "")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Community & Trust — the directed CEG consent object: who the
                // traces go to (the canonical CIRIS community peer), its trust
                // state, and the live consent state. Renders organically as the
                // mesh comes up (lenscore 1.0); graceful "pending" until then.
                CommunityTrustSection(communityPeer = communityPeer, consentActive = isConsentActive)

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Consent toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = localizedString("mobile.data_trace_collection"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isConsentActive)
                                localizedString("mobile.data_traces_active")
                            else
                                localizedString("mobile.data_traces_disabled"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isConsentActive,
                        onCheckedChange = { onConsentChanged(it) },
                        modifier = Modifier.testableClickable("switch_consent") {
                            onConsentChanged(!isConsentActive)
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Delete/Revoke section - always show deletion request option
                val traceCount = settings.eventsSent

                Text(
                    text = localizedString("mobile.data_delete_lens"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = if (traceCount > 0) {
                        localizedString("mobile.data_delete_lens_desc_count")
                            .replace("{count}", traceCount.toString())
                    } else {
                        localizedString("mobile.data_delete_lens_desc_zero")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CirisTextField(
                    tag = "input_delete_traces_reason",
                    value = deletionReason,
                    onValueChange = onDeletionReasonChange,
                    placeholder = localizedString("mobile.data_reason_placeholder"),
                    enabled = !isDeleting,
                )

                Button(
                    onClick = onDeleteClick,
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth().testableClickable("btn_delete_traces") {
                        if (!isDeleting) onDeleteClick()
                    }
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onError,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (isDeleting) localizedString("mobile.data_processing")
                        else localizedString("mobile.data_delete_traces_revoke")
                    )
                }

                // What the agent said to the deletion request. REQUESTED, never
                // done: no SLA and no completion come back (CIRISAgent#1212), so
                // "requested" is the most this card may say, and it stays on
                // the card rather than vanishing from a snackbar.
                deletionResult?.let { LensDeletionOutcome(it) }
            }

            // Adapter not loaded - show enable button; a read that failed for
            // another reason is said instead of drawn as "Enable".
            if (!adapterLoaded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                val f = accordFailure
                if (f is ReadFailure.Failed) {
                    ReadFailureBlock(failure = f, tagPrefix = "data_accord", inline = true)
                } else {
                    Button(
                        onClick = onEnableAdapter,
                        enabled = !isLoadingAdapter,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth().testableClickable("btn_enable_accord") {
                            if (!isLoadingAdapter) onEnableAdapter()
                        }
                    ) {
                        if (isLoadingAdapter) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isLoadingAdapter) localizedString("mobile.data_enabling") else localizedString("mobile.data_enable_accord"))
                    }
                }
            }
        }
    }
}

/** The agent's answer to a lens-deletion request, as rows: requested (never done), accepted by the lens, local consent revoked. */
@Composable
private fun LensDeletionOutcome(r: ai.ciris.mobile.shared.api.LensDeletionResult) {
    if (!r.success) {
        StateBlock(
            ListState.Error(title = localizedString("mobile.data_deletion_failed"), detail = r.message),
            tag = "data_lens_deletion_refused",
            inline = true,
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth().testable("data_lens_deletion_requested")) {
        FieldRow(
            label = localizedString("mobile.data_lens_deletion_label"),
            value = localizedString("mobile.data_lens_deletion_requested"),
            tone = Tone.BRAND,
            tag = "data_lens_deletion_status",
        )
        FieldRow(
            label = localizedString("mobile.data_lens_deletion_lens_label"),
            value = localizedString(if (r.lensRequestAccepted) "mobile.data_lens_deletion_lens_accepted" else "mobile.data_lens_deletion_lens_not_accepted"),
            tone = if (r.lensRequestAccepted) Tone.OK else Tone.DANGER,
            protocol = "lens_request_accepted",
            tag = "data_lens_deletion_lens",
        )
        FieldRow(
            label = localizedString("mobile.data_lens_deletion_local_label"),
            value = localizedString(if (r.localConsentRevoked) "mobile.data_lens_deletion_local_revoked" else "mobile.data_lens_deletion_local_kept"),
            tone = if (r.localConsentRevoked) Tone.OK else Tone.DANGER,
            protocol = "local_consent_revoked",
            tag = "data_lens_deletion_local",
        )
        FieldRow(
            label = localizedString("mobile.data_lens_deletion_sla_label"),
            value = localizedString("mobile.data_lens_deletion_sla_none"),
            tone = Tone.DIM,
            divider = false,
            tag = "data_row_deletion_sla",
        )
    }
}

/**
 * The directed CEG consent object, shown organically: which community the
 * traces go to (the canonical CIRIS community peer), its trust state, and the
 * live consent state. Renders a graceful "federation pending" line until the
 * lens registers as a federation peer (lenscore 1.0).
 */
@Composable
private fun CommunityTrustSection(
    communityPeer: ai.ciris.mobile.shared.models.federation.LocalPeerState?,
    consentActive: Boolean,
) {
    val trusted = ai.ciris.mobile.shared.models.federation.PeerTrustState.TRUSTED
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testable("community_trust_section"),
    ) {
        Text(
            text = localizedString("mobile.data_community_trust_title"),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        // Community (the directed counterparty) — always known
        InfoRow(localizedString("mobile.data_community_label"), localizedString("mobile.data_community_canonical"))

        if (communityPeer != null) {
            // Live peer: show trust state organically
            val isTrusted = communityPeer.trust == trusted
            Row(
                modifier = Modifier.fillMaxWidth().testable("community_peer_state"),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = localizedString("mobile.data_community_peer_label"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (isTrusted)
                        localizedString("mobile.data_community_trusted")
                    else
                        localizedString("mobile.data_community_untrusted"),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isTrusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        } else {
            // Pre-lenscore-1.0: graceful pending state
            Text(
                text = localizedString("mobile.data_community_pending"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testable("community_pending"),
            )
        }

        // The consent object's live state
        Text(
            text = if (consentActive)
                localizedString("mobile.data_community_consent_active")
            else
                localizedString("mobile.data_community_consent_paused"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testable("community_consent_state"),
        )
    }
}

@Composable
private fun ResetAccountCard(
    isResetting: Boolean,
    onResetClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = localizedString("mobile.data_reset_account"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            Text(
                text = localizedString("mobile.data_reset_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )

            Text(
                text = localizedString("mobile.data_reset_preserves"),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Button(
                onClick = onResetClick,
                enabled = !isResetting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                ),
                modifier = Modifier.fillMaxWidth().testableClickable("btn_reset_account") {
                    if (!isResetting) onResetClick()
                }
            ) {
                if (isResetting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onTertiary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isResetting) localizedString("mobile.data_resetting") else localizedString("mobile.data_reset_account"))
            }
        }
    }
}

/**
 * Card for DANGER zone - wiping the agent signing key.
 * WARNING: This destroys wallet access permanently!
 */
@Composable
private fun WipeSigningKeyCard(
    isWiping: Boolean,
    onWipeClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = localizedString("mobile.data_wipe_key_danger"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            Text(
                text = localizedString("mobile.data_wipe_key_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )

            Text(
                text = localizedString("mobile.data_wipe_key_warning_short"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            Text(
                text = localizedString("mobile.data_wipe_key_deletes"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )

            Button(
                onClick = onWipeClick,
                enabled = !isWiping,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth().testableClickable("btn_wipe_signing_key") {
                    if (!isWiping) onWipeClick()
                }
            ) {
                if (isWiping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isWiping) localizedString("mobile.data_wiping") else localizedString("mobile.data_wipe_key_button"))
            }
        }
    }
}

/**
 * Card summarizing privacy practices, data retention, user rights, and contact info.
 * Ensures compliance with GDPR Arts. 13-14, CCPA, and EU AI Act transparency requirements.
 */
@Composable
private fun PrivacyInfoCard() {
    val uriHandler = LocalUriHandler.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = localizedString("mobile.data_how_title"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // LLM zero data retention
            Text(
                text = localizedString("mobile.data_llm_title"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = localizedString("mobile.data_llm_desc_full"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // Local data
            Text(
                text = localizedString("mobile.data_local_title"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = localizedString("mobile.data_local_desc_full"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // Billing records
            Text(
                text = localizedString("mobile.data_billing_title"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = localizedString("mobile.data_billing_desc_full"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // Your rights
            Text(
                text = localizedString("mobile.data_your_rights"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = localizedString("mobile.data_your_rights_desc_full"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // Contact & links
            Text(
                text = localizedString("mobile.data_contact"),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    uriHandler.openUri("mailto:privacy@ciris.ai")
                }
            )

            Text(
                text = localizedString("mobile.data_privacy_policy"),
                style = MaterialTheme.typography.bodySmall.copy(
                    textDecoration = TextDecoration.Underline
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    uriHandler.openUri("https://ciris.ai/privacy")
                }
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, tag: String? = null) {
    Row(
        modifier = if (tag != null) Modifier.fillMaxWidth().testable(tag, value) else Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}
