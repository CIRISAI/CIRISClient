package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.viewmodels.ConsentObjectsViewModel
import ai.ciris.mobile.shared.viewmodels.GrantDirectionState
import ai.ciris.mobile.shared.viewmodels.RevokeRoute
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar

/**
 * Manage Consent — replication between the owner's own nodes (CSD-053).
 *
 * The fabric exposes one consent object through a node-driven API: the
 * **bilateral `consent:replication`** peering between two nodes A↔B, driven by
 * [ConsentObjectsViewModel] (`POST /v1/federation/peering` in each direction —
 * ratified iff both grants present). This screen renders the current grant
 * state for the two selected nodes and lets the user (re)run the set-up.
 *
 * **Revoke** drives `POST /v1/federation/peering/revoke` on node A
 * (CIRISServer#657, ciris-server 0.5.218) behind a ConfirmSheet. The node signs
 * the `withdraws` with the PERSON's key; the app does no crypto. Whether node A
 * has the route is asked of node A at runtime ([RevokeRoute]); an older node
 * keeps the control disabled and says why. A grant the node wrote before the
 * person re-signed it cannot be withdrawn and renders as still active
 * (`consent_remaining_grants`) — never as done.
 *
 * **Two consents this screen does NOT own, and points at instead.** The
 * user-data consent stream (`consent:state` — TEMPORARY / PARTNERED /
 * ANONYMOUS) lives on the Consent surface ([onOpenUserConsent], CSD-054). The
 * reasoning-traces opt-in (`PUT /v1/my-data/accord-settings`) lives on the Data
 * card ([onOpenDataSharing], CSD-039): it used to be written from here as well,
 * one act with three doors, and this one closed — the switch here documented
 * a `consent:community_trust:v1` leaf that does not exist (CC 3.3.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageConsentScreen(
    viewModel: ConsentObjectsViewModel,
    onBack: () -> Unit,
    onOpenUserConsent: () -> Unit = {},
    /** Navigate to the Data card (CSD-039), which owns the reasoning-traces opt-in. */
    onOpenDataSharing: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    // The confirm is open. Withdrawing consent is an outward act: three facts, two buttons.
    var confirmRevoke by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("mobile.manage_consent_title")) },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testableClickable("btn_manage_consent_back") { onBack() },
                        ) {
                            Icon(
                                imageVector = CIRISIcons.arrowBack,
                                contentDescription = localizedString("mobile.common_back"),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .testableVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = localizedString("mobile.manage_consent_subtitle"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.error?.let { msg ->
                MessageBar(msg, isError = true, tag = "bar_consent_error") { viewModel.clearMessages() }
            }
            state.message?.let { key ->
                MessageBar(localizedString(key, state.messageParams), isError = false, tag = "bar_consent_message") {
                    viewModel.clearMessages()
                }
            }

            // ── consent:replication peering ──────────────────────────────────
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        localizedString("mobile.manage_consent_replication_title"),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    Text(
                        localizedString("mobile.manage_consent_replication_desc"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (state.nodeA == null || state.nodeB == null) {
                        // The EMPTY state: fewer than two owned nodes, so there is
                        // no pair to peer. An ordinary fact, in the ordinary tone —
                        // it was drawn in the danger colour (CSD-053 §2).
                        Text(
                            localizedString("mobile.manage_consent_need_two_nodes"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testable("text_consent_need_two_nodes"),
                        )
                    } else {
                        // Node pair + direction states.
                        DirectionRow(
                            label = "${state.nodeA?.name}  →  ${state.nodeB?.name}",
                            grant = state.aToB,
                            tag = "row_consent_a_to_b",
                        )
                        DirectionRow(
                            label = "${state.nodeB?.name}  →  ${state.nodeA?.name}",
                            grant = state.bToA,
                            tag = "row_consent_b_to_a",
                        )

                        val ratifiedText = if (state.isRatified) {
                            localizedString("mobile.manage_consent_ratified")
                        } else {
                            localizedString("mobile.manage_consent_not_ratified")
                        }
                        Surface(
                            color = if (state.isRatified) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(4.dp),
                            // `txt_`, not `chip_`: it is a label, not a control (check_ui_drivable).
                            modifier = Modifier.testable("txt_consent_ratified", ratifiedText),
                        ) {
                            Text(
                                text = ratifiedText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val onSetup = { if (state.canRun) viewModel.runBilateralPeering() }
                            Button(
                                onClick = onSetup,
                                enabled = state.canRun,
                                modifier = Modifier.testableClickable("btn_consent_setup_peering") { onSetup() },
                            ) {
                                if (state.isRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp).testable("consent_peering_running"),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(
                                        if (state.isRatified)
                                            localizedString("mobile.manage_consent_resetup")
                                        else localizedString("mobile.manage_consent_setup"),
                                    )
                                }
                            }

                            // Revoke — withdraws node A's grant to B (CIRISServer#657).
                            // Whether the node can is asked of the node, at runtime.
                            val onRevoke = { if (state.canRevoke) confirmRevoke = true }
                            OutlinedButton(
                                onClick = onRevoke,
                                enabled = state.canRevoke,
                                modifier = Modifier.testableClickable("btn_consent_revoke_peering") { onRevoke() },
                            ) {
                                if (state.isRevoking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp).testable("consent_revoking"),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(localizedString("mobile.manage_consent_revoke"))
                                }
                            }
                        }
                        RevokeStatus(state)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── user-data consent pointer ────────────────────────────────────
            PointerCard(
                title = localizedString("mobile.manage_consent_userdata_title"),
                desc = localizedString("mobile.manage_consent_userdata_desc"),
                button = localizedString("mobile.manage_consent_open_userdata"),
                tag = "btn_open_user_consent",
                onClick = onOpenUserConsent,
            )

            // ── reasoning-traces opt-in pointer (CSD-039 owns the write) ─────
            PointerCard(
                title = localizedString("mobile.announce_decision_trace_title"),
                desc = localizedString("mobile.manage_consent_traces_elsewhere"),
                button = localizedString("mobile.manage_consent_open_data"),
                tag = "btn_open_data_sharing",
                onClick = onOpenDataSharing,
            )
        }
    }

    if (confirmRevoke) {
        val from = state.nodeA?.name.orEmpty()
        val to = state.nodeB?.name.orEmpty()
        ConfirmSheet(
            title = localizedString("mobile.manage_consent_revoke_confirm_title"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.consent_withdraw_fact_who"), "$from  →  $to"),
                ConfirmFact(
                    localizedString("mobile.consent_withdraw_fact_stops"),
                    localizedString(
                        "mobile.manage_consent_revoke_fact_stops_value",
                        mapOf("from" to from, "to" to to),
                    ),
                ),
                ConfirmFact(
                    localizedString("mobile.consent_withdraw_fact_signs"),
                    localizedString("mobile.consent_withdraw_signs_value"),
                ),
            ),
            confirmLabel = localizedString("mobile.manage_consent_revoke_confirm"),
            onConfirm = { confirmRevoke = false; viewModel.revokeAToB() },
            onDismiss = { confirmRevoke = false },
            destructive = true,
            tagPrefix = "consent_revoke",
        )
    }
}

/**
 * Why the revoke control is disabled, as a (bundle key, test tag), or null
 * when it is not. Pure, so the one sentence an older node earns is tested
 * without Compose. A missing route outranks everything: no grant id changes
 * what a node without the route can do.
 */
internal fun revokeNote(state: ai.ciris.mobile.shared.viewmodels.ConsentObjectsState): Pair<String, String>? = when {
    state.revokeRoute == RevokeRoute.MISSING ->
        "mobile.manage_consent_revoke_unsupported" to "text_consent_revoke_unsupported"
    state.aToBGrantId.isNullOrBlank() && state.remainingGrants.isEmpty() && state.withdrawnBy == null ->
        "mobile.manage_consent_revoke_needs_grant" to "text_consent_revoke_needs_grant"
    else -> null
}

/**
 * What the revoke control can do and what it last did, in that order. Every
 * line here is something the NODE said: the route is missing (a bare 404 or the
 * probe), the grant is still active (node-authored, so not the person's to
 * withdraw), the node refused, or the node signed the withdrawal.
 */
@Composable
private fun RevokeStatus(state: ai.ciris.mobile.shared.viewmodels.ConsentObjectsState) {
    revokeNote(state)?.let { (key, tag) ->
        Text(
            localizedString(key),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testable(tag),
        )
    }

    // Still active: in the NORMAL tone, never struck through or greyed as if gone.
    if (state.remainingGrants.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().testable("consent_remaining_grants"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                localizedString("mobile.consent_remaining_title"),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            state.remainingGrants.forEach { grant ->
                Text(
                    localizedString("mobile.consent_remaining_row"),
                    fontSize = 12.sp,
                )
                Text(grant, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (state.revokeRefusalId != null || state.revokeRefusalDetail != null) {
        Text(
            state.revokeRefusalId?.let { localizedString(it) } ?: state.revokeRefusalDetail.orEmpty(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testable("consent_revoke_refusal"),
        )
    }

    // The success line appears only with the node's `withdraws` id and nothing remaining.
    if (state.withdrawnBy != null && state.remainingGrants.isEmpty()) {
        Text(
            localizedString("mobile.manage_consent_withdrawn", "id", state.withdrawnBy.take(16)),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testable("text_consent_withdrawn"),
        )
    }
}

/** A card that names another card as the owner of a consent and opens it. */
@Composable
private fun PointerCard(title: String, desc: String, button: String, tag: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.testableClickable(tag) { onClick() },
            ) {
                Icon(CIRISIcons.lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(button)
            }
        }
    }
}

@Composable
private fun DirectionRow(label: String, grant: GrantDirectionState, tag: String) {
    val (text, color) = when (grant) {
        GrantDirectionState.GRANTED ->
            localizedString("mobile.manage_consent_granted") to MaterialTheme.colorScheme.primary
        GrantDirectionState.IN_PROGRESS ->
            localizedString("mobile.manage_consent_in_progress") to MaterialTheme.colorScheme.onSurfaceVariant
        GrantDirectionState.FAILED ->
            localizedString("mobile.manage_consent_failed") to MaterialTheme.colorScheme.error
        GrantDirectionState.IDLE ->
            localizedString("mobile.manage_consent_idle") to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testable(tag, text),
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun MessageBar(msg: String, isError: Boolean, tag: String, onDismiss: () -> Unit) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().testable(tag, msg),
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = msg,
                fontSize = 12.sp,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(localizedString("mobile.common_close")) }
        }
    }
}
