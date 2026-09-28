package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.AdminMessage
import ai.ciris.mobile.shared.models.AdminOpResponse
import ai.ciris.mobile.shared.models.AdminOpTargetResult
import ai.ciris.mobile.shared.models.AdminPreviewResponse
import ai.ciris.mobile.shared.models.AdminRefusal
import ai.ciris.mobile.shared.models.AdminStandingDto
import ai.ciris.mobile.shared.models.CatalogueSource
import ai.ciris.mobile.shared.models.LadderRung
import ai.ciris.mobile.shared.models.safety.ModerationDuty
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.viewmodels.AdminLadderState
import ai.ciris.mobile.shared.viewmodels.AdminLadderViewModel
import ai.ciris.mobile.shared.viewmodels.NodeDelegations
import ai.ciris.mobile.shared.viewmodels.SafetyViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar

/**
 * **Moderation card** — moderation as a delegable DUTY, not a role (CC §4.5.x).
 *
 * Three things, all driving the local node's `/v1/safety/` routes:
 *  1. **File a report** — pick the duty + allegation type + target + note, then
 *     `POST /v1/safety/moderation`. The node admits IFF the signer holds the
 *     duty or sits on a live delegated chain (the §11.10 gate); a non-holder
 *     gets a 403 surfaced here ("the duty is held or delegated, never assumed").
 *  2. **Named moderator** — `GET /v1/safety/named-moderator/{community}` shows
 *     the CC 4.5.4 existence verdict: operate / auto-promote / quiesce. The
 *     invariant FAILS SECURE — better no group than an unmoderated one.
 *  3. **Delegable duty** — an explainer that you can moderate as yourself OR
 *     delegate the `moderate` duty to your agent / a trusted party (the
 *     delegation flow lives on the Family → Delegation surface).
 *
 * The app holds NO keys: every action is a plain localhost call; the node signs.
 *
 * ## 4. The graded enforcement ladder — the `/v1/admin` routes
 *
 * The three cards above are the *duty* surface: reports, existence invariant,
 * delegation. The fourth is the **recourse** surface — the owner-gated tiers 0–4
 * of `src/admin_ops.rs`, which are what an operator actually has against an
 * admitted-but-hostile peer. Until CIRISServer#375 there was no route at all
 * that stopped the next write; `refuse-writes` is that door.
 *
 * Two things this card is built around, both of them load-bearing:
 *
 *  - **Preview → confirm-with-hash.** Nothing commits without a preview, and the
 *    commit submits the hash it displayed over the selection it displayed. Any
 *    edit to a hash-covered field drops the preview.
 *  - **Each op's stated limits are in the confirmation, not a tooltip.** The
 *    server names what an op reaches AND what it does not; the same message ids
 *    are resolved from the bundle BEFORE the act, so the operator reads
 *    "quarantine deletes nothing" while they can still decline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationScreen(
    viewModel: SafetyViewModel,
    /** Drives the owner-gated `/v1/admin` ladder (preview → commit-with-hash). */
    ladderViewModel: AdminLadderViewModel,
    onBack: () -> Unit,
    /** Navigate to the delegation flow (Family → Delegation), if present. */
    onOpenDelegation: (() -> Unit)? = null,
    /**
     * A picker of the person's communities, drawn under the community field.
     * It hands back a community key, which fills both the lookup here and the
     * quarantine rung's `community_id`. Null (today) leaves the typed field as
     * the only way in. This is the entry point the Communities card plugs into.
     */
    communityPicker: (@Composable (onPick: (String) -> Unit) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val ladder by ladderViewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.probeIdentityAndStatus()
        // The ladder as the node states it, and the owner's own delegation ids.
        ladderViewModel.load()
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("mobile.moderation_title")) },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(onClick = onBack, modifier = Modifier.testable("btn_moderation_back")) {
                            Icon(CIRISIcons.arrowBack, contentDescription = localizedString("mobile.back"))
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
                .testableVerticalScroll()
                .testable("screen_moderation"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = localizedString("mobile.moderation_intro"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )

            // ── Community scope (shared by report + named-moderator) ──
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(CIRISIcons.handler, localizedString("mobile.moderation_community_section"))
                    OutlinedTextField(
                        value = state.communityKeyId,
                        onValueChange = viewModel::setCommunityKeyId,
                        label = { Text(localizedString("mobile.moderation_community_label")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testable("input_moderation_community"),
                    )
                    communityPicker?.let { picker ->
                        Spacer(Modifier.height(6.dp))
                        picker { key ->
                            viewModel.setCommunityKeyId(key)
                            ladderViewModel.setCommunityId(key)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.loadNamedModerator() },
                        modifier = Modifier.testable("btn_load_named_moderator"),
                    ) {
                        if (state.namedModeratorLoading) {
                            CircularProgressIndicator(
                                Modifier.width(16.dp).height(16.dp).testable("spinner_named_moderator"),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(localizedString("mobile.moderation_check_moderator"))
                    }

                    // Existence-invariant verdict: four facts, four renderings.
                    // Never-asked, could-not-ask and quiesce are three different
                    // things on a fail-secure invariant, and a blank drew all three.
                    Spacer(Modifier.height(10.dp))
                    NamedModeratorVerdictBlock(state)
                }
            }

            // ── File a report ──
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(CIRISIcons.warning, localizedString("mobile.moderation_report_section"))

                    Text(localizedString("mobile.moderation_duty_label"),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp))
                    val duties = listOf(
                        ModerationDuty.MODERATE to localizedString("mobile.moderation_duty_moderate"),
                        ModerationDuty.TAKEDOWN to localizedString("mobile.moderation_duty_takedown"),
                        ModerationDuty.REVIEW to localizedString("mobile.moderation_duty_review"),
                    )
                    duties.forEach { (duty, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = state.selectedDuty == duty,
                                onClick = { viewModel.setDuty(duty) },
                                modifier = Modifier.testable("duty_${duty.name.lowercase()}"),
                            )
                            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.allegationType,
                        onValueChange = viewModel::setAllegationType,
                        label = { Text(localizedString("mobile.moderation_allegation_label")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testable("input_allegation_type"),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.targetKeyIdsRaw,
                        onValueChange = viewModel::setTargetKeyIdsRaw,
                        label = { Text(localizedString("mobile.moderation_targets_label")) },
                        modifier = Modifier.fillMaxWidth().testable("input_moderation_targets"),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.reportNote,
                        onValueChange = viewModel::setReportNote,
                        label = { Text(localizedString("mobile.moderation_note_label")) },
                        modifier = Modifier.fillMaxWidth().testable("input_moderation_note"),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.fileModeration() },
                        enabled = !state.filing,
                        modifier = Modifier.testable("btn_file_moderation"),
                    ) {
                        if (state.filing) {
                            CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(localizedString("mobile.moderation_file_button"))
                    }
                    state.lastModerationAttestationId?.let {
                        Text(localizedString("mobile.moderation_filed_id", "id", it),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp).testable("txt_moderation_filed_id", it))
                    }
                }
            }

            // ── The graded enforcement ladder (/v1/admin/*) ──
            EnforcementLadderCard(ladder, ladderViewModel)

            // ── Delegable duty explainer ──
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(CIRISIcons.send, localizedString("mobile.moderation_delegate_section"))
                    Text(localizedString("mobile.moderation_delegate_body"),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onOpenDelegation != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onOpenDelegation,
                            modifier = Modifier.testable("btn_open_delegation"),
                        ) { Text(localizedString("mobile.moderation_open_delegation")) }
                    }
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    modifier = Modifier.testable("txt_moderation_error"))
            }
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    modifier = Modifier.testable("txt_moderation_message"))
            }
        }

        // The ratification surface. Rendered LAST and over everything, because
        // it is the only place a signature is authorised.
        if (ladder.confirmOpen) {
            LadderConfirmSheet(ladder, ladderViewModel)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  The graded enforcement ladder — /v1/admin/* (CIRISServer #346 / #361 / #375)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Resolve a message id through the localization bundle.
 *
 * Returns `null` when the id is NOT in the bundle. `localizedString` answers the
 * key itself in that case, and rendering a raw dotted key at an operator is
 * worse than saying nothing — so every caller has to decide what the absence
 * means rather than getting a string that looks like a sentence and is not one.
 */
@Composable
private fun ladderBundleText(id: String): String? {
    val resolved = localizedString(id)
    return if (resolved == id) null else resolved
}

/**
 * Render one server `{id, text}` pair. **The id is tried FIRST** — all of this
 * module's ids ship in 29 languages — and the English `text` the response
 * carried is the fallback, never the primary.
 */
@Composable
private fun serverMessage(msg: AdminMessage?): String? {
    if (msg == null) return null
    return ladderBundleText(msg.id) ?: msg.text
}

/**
 * The ladder card: pick a rung, name a selection, PREVIEW it, then ratify.
 *
 * The commit button does not exist until a preview does — not disabled, absent —
 * because "sign" and "sign what?" are one question here.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EnforcementLadderCard(
    state: AdminLadderState,
    viewModel: AdminLadderViewModel,
) {
    val op = state.selectedOp
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testable("card_enforcement_ladder"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(CIRISIcons.shield, localizedString("moderation.ladder.section"))
            Text(
                localizedString("moderation.ladder.intro"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Where the rungs below came from. A compiled ladder shown as if the
            // node had served it is the drift GET /v1/operations exists to end.
            LadderCatalogueSourceLine(state.catalogueSource)

            Spacer(Modifier.height(12.dp))

            // ── Which rung ──
            Text(
                localizedString("moderation.ladder.op_label"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.rungs.forEach { candidate ->
                    FilterChip(
                        selected = candidate.op == op.op,
                        onClick = { viewModel.selectOp(candidate) },
                        label = {
                            Text(
                                rungLabel(candidate),
                                fontSize = 12.sp,
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testable("chip_ladder_${candidate.tagStem}"),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                op.scope?.let {
                    localizedString(
                        "moderation.ladder.tier_and_scope",
                        mapOf("tier" to op.tier.toString(), "scope" to it),
                    )
                } ?: localizedString("moderation.ladder.tier_no_scope", "tier", op.tier.toString()),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testable("txt_ladder_scope", op.scope ?: ""),
            )
            ReachesSubstrateLine(op)
            if (op.irreversible) {
                Text(
                    localizedString("moderation.ladder.irreversible_flag"),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            // ── The selection. Every field here changes the hash. ──
            Text(
                localizedString("moderation.ladder.selection_label"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = state.attestingKeyId,
                onValueChange = viewModel::setAttestingKeyId,
                label = { Text(localizedString("moderation.ladder.attesting_key_label")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testable("input_ladder_attesting_key"),
            )
            Spacer(Modifier.height(8.dp))
            // BULK: paste a whole population. One act, one hash, one reason —
            // not one preview→commit pair per key. The count is shown because a
            // pasted list is exactly the input an operator cannot eyeball, and
            // the number they are about to act on is the number they should see
            // before they sign.
            val bulkKeys = remember(state.attestingKeyIdsRaw) {
                state.attestingKeyIdsRaw
                    .split('\n', ',', ';', ' ', '\t')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            }
            OutlinedTextField(
                value = state.attestingKeyIdsRaw,
                onValueChange = viewModel::setAttestingKeyIdsRaw,
                label = { Text(localizedString("moderation.ladder.attesting_keys_label")) },
                placeholder = { Text(localizedString("moderation.ladder.attesting_keys_hint")) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().testable("input_ladder_attesting_keys"),
            )
            if (bulkKeys.isNotEmpty()) {
                Text(
                    localizedString("moderation.ladder.attesting_keys_count", "count", bulkKeys.size.toString()),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp).testable("txt_ladder_keys_count"),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.attestationType,
                onValueChange = viewModel::setAttestationType,
                label = { Text(localizedString("moderation.ladder.attestation_type_label")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testable("input_ladder_attestation_type"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.dimensionPrefix,
                onValueChange = viewModel::setDimensionPrefix,
                label = { Text(localizedString("moderation.ladder.dimension_prefix_label")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testable("input_ladder_dimension_prefix"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.selectionAfter,
                onValueChange = viewModel::setSelectionAfter,
                label = { Text(localizedString("moderation.ladder.selection_after_label")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testable("input_ladder_selection_after"),
            )
            Text(
                localizedString("moderation.ladder.selection_after_hint"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { viewModel.runPreview() },
                enabled = !state.previewing,
                modifier = Modifier.testable("btn_ladder_preview"),
            ) {
                if (state.previewing) {
                    CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(localizedString("moderation.ladder.preview_button"))
            }

            Spacer(Modifier.height(10.dp))
            LadderPreviewBlock(state)

            Spacer(Modifier.height(14.dp))

            // ── Attribution. MANDATORY, and it rides in the tombstone. ──
            Text(
                localizedString("moderation.ladder.attribution_label"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                localizedString("moderation.ladder.attribution_hint"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            NodeDelegationPicker(state, viewModel)
            OutlinedTextField(
                value = state.delegationId,
                onValueChange = viewModel::setDelegationId,
                label = { Text(localizedString("moderation.ladder.delegation_id_label")) },
                singleLine = true,
                isError = state.delegationId.isBlank(),
                modifier = Modifier.fillMaxWidth().testable("input_ladder_delegation_id"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.reason,
                onValueChange = viewModel::setReason,
                label = { Text(localizedString("moderation.ladder.reason_label")) },
                isError = state.reason.isBlank(),
                modifier = Modifier.fillMaxWidth().testable("input_ladder_reason"),
            )

            // ── Per-op extras, each shown ONLY for the rung that takes it ──
            if (op.requiresCommunityId) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.communityId,
                    onValueChange = viewModel::setCommunityId,
                    label = { Text(localizedString("moderation.ladder.community_id_label")) },
                    singleLine = true,
                    isError = state.communityId.isBlank(),
                    modifier = Modifier.fillMaxWidth().testable("input_ladder_community_id"),
                )
                Text(
                    localizedString("moderation.ladder.community_id_hint"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (op.requiresQuorum) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.quorumDelegationIdsRaw,
                    onValueChange = viewModel::setQuorumDelegationIds,
                    label = { Text(localizedString("moderation.ladder.quorum_ids_label")) },
                    modifier = Modifier.fillMaxWidth().testable("input_ladder_quorum_ids"),
                )
                Text(
                    localizedString(
                        "moderation.ladder.quorum_hint",
                        "min",
                        op.quorum.toString(),
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (op.acceptsRevokedAfter) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.revokedAfter,
                    onValueChange = viewModel::setRevokedAfter,
                    label = { Text(localizedString("moderation.ladder.revoked_after_label")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testable("input_ladder_revoked_after"),
                )
                Text(
                    localizedString("moderation.ladder.revoked_after_hint"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            // The ratify button EXISTS only with a preview to ratify.
            if (state.preview == null) {
                Text(
                    localizedString("moderation.ladder.no_preview_no_commit"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(
                    onClick = { viewModel.openConfirm() },
                    enabled = state.canCommit,
                    colors = if (op.irreversible) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.testable("btn_ladder_review"),
                ) {
                    Text(localizedString("moderation.ladder.review_button"))
                }
                if (!state.canCommit) {
                    Text(
                        localizedString("moderation.ladder.commit_blocked"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // ── The outcome of the last commit ──
            state.commitError?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    localizedString("moderation.ladder.transport_error", "detail", it),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.commitRefusal?.let {
                Spacer(Modifier.height(10.dp))
                LadderRefusalBlock(it)
            }
            state.result?.let {
                Spacer(Modifier.height(10.dp))
                LadderResultBlock(it)
            }
        }
    }
}

/**
 * The preview, and its **three distinct zeroes**: no preview has been run, the
 * preview ran and matched nothing, the preview could not be read. Those are
 * three different facts about the mesh and never one dash.
 */
@Composable
private fun LadderPreviewBlock(state: AdminLadderState) {
    val preview = state.preview
    when {
        state.previewError != null -> Text(
            localizedString("moderation.ladder.preview_unreadable", "detail", state.previewError),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testable("txt_ladder_preview_unreadable"),
        )

        state.previewRefusal != null -> LadderRefusalBlock(state.previewRefusal)

        preview == null -> Text(
            localizedString("moderation.ladder.preview_none"),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testable("txt_ladder_preview_none"),
        )

        preview.counts.rows == 0 -> Text(
            localizedString("moderation.ladder.preview_empty"),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testable("txt_ladder_preview_empty"),
        )

        else -> LadderPreviewSummary(preview)
    }
}

/** The blast radius, in the shape an operator ratifies. */
@Composable
private fun LadderPreviewSummary(preview: AdminPreviewResponse) {
    Column(modifier = Modifier.testable("block_ladder_preview")) {
        Text(
            localizedString(
                "moderation.ladder.preview_counts",
                mapOf(
                    "rows" to preview.counts.rows.toString(),
                    "targets" to preview.counts.targets.toString(),
                ),
            ),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testable("txt_ladder_preview_counts", preview.counts.rows.toString()),
        )
        Text(
            localizedString("moderation.ladder.selection_hash", "hash", preview.selectionHash),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).testable("txt_ladder_selection_hash"),
        )
        if (preview.counts.truncated) {
            Text(
                localizedString("moderation.ladder.preview_truncated"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // Per-key blast radius: "this touches 3 keys and 9,811 rows are one
        // key's liveness" is the sentence that stops a wrong call.
        if (preview.counts.perAttester.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            preview.counts.perAttester.entries.take(8).forEach { (key, rows) ->
                Text(
                    localizedString(
                        "moderation.ladder.per_attester_row",
                        mapOf("key" to key, "rows" to rows.toString()),
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preview.counts.perAttester.size > 8) {
                Text(
                    localizedString(
                        "moderation.ladder.per_attester_more",
                        "count",
                        (preview.counts.perAttester.size - 8).toString(),
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Where the time window was enforced — MEASURED by the node, so a
        // window that narrowed in this process is said out loud.
        when (preview.windowEnforced) {
            AdminPreviewResponse.WINDOW_SUBSTRATE -> Text(
                localizedString("moderation.ladder.window_substrate"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            AdminPreviewResponse.WINDOW_APPLICATION -> Text(
                serverMessage(preview.windowNote)
                    ?: localizedString("moderation.ladder.window_application"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            else -> Unit
        }
        serverMessage(preview.note)?.let {
            Text(
                it,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * **What you are about to sign.** The hash, the blast radius, what the op
 * reaches, what it does NOT reach, and how (or whether) it is undone — all of it
 * before the signature, none of it behind a tooltip.
 *
 * The limits are resolved from the SERVER'S OWN message ids, so the sentence
 * read here is the sentence the response will carry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LadderConfirmSheet(
    state: AdminLadderState,
    viewModel: AdminLadderViewModel,
) {
    val op = state.selectedOp
    val preview = state.preview ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ackWord = localizedString("moderation.ladder.descend_ack_word")
    val ackSatisfied = !op.irreversible || state.descendAck.trim().equals(ackWord, ignoreCase = true)

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeConfirm() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .heightIn(max = 620.dp)
                .testableVerticalScroll()
                .testable("sheet_ladder_confirm"),
        ) {
            Text(
                localizedString("moderation.ladder.confirm_title"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                localizedString(
                    "moderation.ladder.confirm_op",
                    mapOf(
                        "op" to rungLabel(op),
                        "tier" to op.tier.toString(),
                    ),
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            // Tier 3 leads with the irreversibility. It is not a footnote.
            if (op.irreversible) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                CIRISIcons.warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.width(18.dp).height(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                localizedString("moderation.ladder.irreversible_banner"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.testable("txt_ladder_irreversible"),
                            )
                        }
                        Text(
                            op.local?.let { ladderBundleText(it.enforcementMessageId) }
                                ?: localizedString("moderation.ladder.limits_unavailable"),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            // ── The exact thing being signed ──
            Spacer(Modifier.height(14.dp))
            Text(
                localizedString("moderation.ladder.confirm_signing"),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            LadderPreviewSummary(preview)

            // A few of the rows themselves — the hash covers this exact set.
            if (preview.rows.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                preview.rows.take(6).forEach { row ->
                    Text(
                        localizedString(
                            "moderation.ladder.row_line",
                            mapOf(
                                "type" to row.attestationType,
                                "dimension" to (row.dimension
                                    ?: localizedString("moderation.ladder.row_no_dimension")),
                                "at" to row.assertedAt,
                            ),
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview.rows.size > 6) {
                    Text(
                        localizedString(
                            "moderation.ladder.row_more",
                            "count",
                            (preview.rows.size - 6).toString(),
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── The limits. Each block is the server's own sentence. ──
            Spacer(Modifier.height(14.dp))
            LadderLimitBlock(
                titleId = "moderation.ladder.reaches_title",
                body = op.local?.let { ladderBundleText(it.enforcementMessageId) },
                emphasis = false,
                tag = "txt_ladder_reaches",
            )
            Spacer(Modifier.height(10.dp))
            // The not-reached block is ALWAYS on the sheet. Where the node states
            // no separate limit for this rung, the block says that, rather than
            // vanishing: a limit's absence is itself something to read before
            // signing.
            LadderLimitBlock(
                titleId = "moderation.ladder.not_reached_title",
                body = op.local?.notReachedMessageId?.let { ladderBundleText(it) }
                    ?: if (op.local != null && op.local.notReachedMessageId == null) {
                        localizedString("moderation.ladder.not_reached_unstated")
                    } else {
                        null
                    },
                emphasis = false,
                tag = "txt_ladder_not_reached",
            )
            Spacer(Modifier.height(6.dp))
            ReachesSubstrateLine(op)
            Spacer(Modifier.height(10.dp))
            LadderLimitBlock(
                titleId = "moderation.ladder.undone_title",
                body = undoneSentence(op, state.rungs),
                emphasis = op.irreversible,
                tag = "txt_ladder_undone",
            )

            // ── Attribution, restated: this is what lands in the tombstone ──
            Spacer(Modifier.height(14.dp))
            Text(
                localizedString(
                    "moderation.ladder.confirm_attribution",
                    mapOf(
                        "delegation" to state.delegationId.trim(),
                        "reason" to state.reason.trim(),
                    ),
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (op.requiresQuorum) {
                Text(
                    localizedString(
                        "moderation.ladder.confirm_quorum",
                        mapOf(
                            "count" to (state.quorumDelegationIds.size + 1).toString(),
                            "min" to op.quorum.toString(),
                        ),
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (op.acceptsRevokedAfter && state.revokedAfter.isNotBlank()) {
                Text(
                    localizedString(
                        "moderation.ladder.confirm_revoked_after",
                        "at",
                        state.revokedAfter.trim(),
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ── Tier 3's materially heavier gate: type the word ──
            if (op.irreversible) {
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = state.descendAck,
                    onValueChange = viewModel::setDescendAck,
                    label = {
                        Text(
                            localizedString(
                                "moderation.ladder.descend_ack_label",
                                "word",
                                ackWord,
                            ),
                        )
                    },
                    singleLine = true,
                    isError = !ackSatisfied,
                    modifier = Modifier.fillMaxWidth().testable("input_ladder_descend_ack"),
                )
            }

            state.commitError?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    localizedString("moderation.ladder.transport_error", "detail", it),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.commitRefusal?.let {
                Spacer(Modifier.height(10.dp))
                LadderRefusalBlock(it)
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = { viewModel.closeConfirm() },
                    modifier = Modifier.testable("btn_ladder_cancel"),
                ) { Text(localizedString("moderation.ladder.cancel_button")) }
                Button(
                    onClick = { viewModel.commit() },
                    enabled = state.canCommit && ackSatisfied,
                    colors = if (op.irreversible) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.testable("btn_ladder_commit"),
                ) {
                    if (state.committing) {
                        CircularProgressIndicator(
                            Modifier.width(16.dp).height(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        localizedString(
                            "moderation.ladder.commit_button",
                            "hash",
                            preview.selectionHash.take(12),
                        ),
                    )
                }
            }
        }
    }
}

/** One limit statement: a heading, and the sentence the node itself writes. */
@Composable
private fun LadderLimitBlock(titleId: String, body: String?, emphasis: Boolean, tag: String) {
    Column(modifier = Modifier.fillMaxWidth().testable(tag)) {
        Text(
            localizedString(titleId),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (emphasis) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            // A missing bundle entry is its own fact, not an empty block: the
            // limit still applies, this app just cannot state it.
            body ?: localizedString("moderation.ladder.limits_unavailable"),
            fontSize = 12.sp,
            color = if (emphasis) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp).let {
                if (body == null) it.testable("txt_ladder_limits_unavailable") else it
            },
        )
    }
}

/** A refusal: the stable token, plus the localized sentence that explains it. */
@Composable
private fun LadderRefusalBlock(refusal: AdminRefusal) {
    Column(modifier = Modifier.fillMaxWidth().testable("block_ladder_refusal")) {
        Text(
            localizedString("moderation.ladder.refused_title", "token", refusal.refusal),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        serverMessage(refusal.message)?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        if (refusal.quorumRequired != null) {
            Text(
                localizedString(
                    "moderation.ladder.quorum_shortfall",
                    mapOf(
                        "have" to (refusal.quorumDistinctRoots ?: 0).toString(),
                        "need" to refusal.quorumRequired.toString(),
                    ),
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (refusal.refusal == AdminRefusal.PREVIEW_HASH_MISMATCH) {
            Text(
                localizedString("moderation.ladder.hash_mismatch_hint"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** What a committed op actually did, per target, with the node's own wording. */
@Composable
private fun LadderResultBlock(result: AdminOpResponse) {
    Column(modifier = Modifier.fillMaxWidth().testable("block_ladder_result")) {
        Text(
            localizedString(
                "moderation.ladder.result_title",
                mapOf("op" to result.op, "tier" to result.tier.toString()),
            ),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        // The AV-77 axis: is the substrate reading this node's de-admissions at
        // all? Separate from "is this key de-admitted" on purpose.
        result.deadmissionGate?.let { gate ->
            val id = when (gate) {
                AdminOpResponse.GATE_ARMED -> "moderation.ladder.gate_armed"
                AdminOpResponse.GATE_DORMANT -> "moderation.ladder.gate_dormant"
                AdminOpResponse.GATE_FOREIGN_IDENTITY -> "moderation.ladder.gate_foreign_identity"
                else -> "moderation.ladder.gate_unknown"
            }
            Text(
                localizedString(id),
                fontSize = 12.sp,
                color = if (gate == AdminOpResponse.GATE_ARMED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        result.quorum?.let { q ->
            Text(
                localizedString(
                    "moderation.ladder.result_quorum",
                    mapOf(
                        "roots" to q.distinctRoots.toString(),
                        "required" to q.required.toString(),
                    ),
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.bounded == true) {
            Text(
                localizedString("moderation.ladder.result_bounded"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        result.revokedAfter?.let {
            Text(
                localizedString("moderation.ladder.result_revoked_after", "at", it),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        (result.recorded + result.results + result.failed).forEach { target ->
            Spacer(Modifier.height(6.dp))
            LadderTargetResult(target)
        }

        // The response's own limits, restated after the act in the same words
        // the confirmation used.
        serverMessage(result.enforcement)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        serverMessage(result.irreversible)?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        serverMessage(result.notReached)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // `reversal` arrives in two shapes; both are rendered, neither guessed.
        serverMessage(result.reversalReach?.note)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        serverMessage(result.reversalMessage)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One target's outcome, including the three-valued admission standing. */
@Composable
private fun LadderTargetResult(target: AdminOpTargetResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            localizedString(
                "moderation.ladder.result_target",
                mapOf(
                    "key" to target.targetKeyId,
                    "outcome" to (target.outcome
                        ?: localizedString("moderation.ladder.outcome_recorded")),
                ),
            ),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        serverMessage(target.message)?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        target.standingBefore?.let {
            LadderStandingLine("moderation.ladder.standing_before", it)
        }
        target.standingAfter?.let {
            LadderStandingLine("moderation.ladder.standing_after", it)
        }
        target.payloadDescent?.let { descent ->
            val line = if (descent.performed) {
                localizedString("moderation.ladder.descent_performed")
            } else {
                serverMessage(descent.message)
                    ?: descent.error
                    ?: localizedString(
                        "moderation.ladder.descent_refused",
                        "token",
                        descent.refusal ?: "",
                    )
            }
            Text(line, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        target.error?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
        target.eventError?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
        target.withdrew.forEach { w ->
            Text(
                localizedString(
                    "moderation.ladder.withdrew_row",
                    "id",
                    w.withdrawsId ?: w.deadmissionId ?: "",
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        target.errors.forEach { w ->
            Text(
                w.error ?: "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The admission standing, in **three** values. `unreadable` is rendered as its
 * own fact and coloured like the warning it is — folding it into "admitted" is
 * the false clean that cost a dead trace plane 71 hours.
 */
@Composable
private fun LadderStandingLine(titleId: String, standing: AdminStandingDto) {
    val labelId = when (standing.standing) {
        AdminStandingDto.REFUSED -> "moderation.ladder.standing_refused"
        AdminStandingDto.ADMITTED -> "moderation.ladder.standing_admitted"
        AdminStandingDto.UNREADABLE -> "moderation.ladder.standing_unreadable"
        else -> "moderation.ladder.standing_unknown"
    }
    Text(
        localizedString(
            titleId,
            "standing",
            localizedString(labelId),
        ),
        fontSize = 11.sp,
        color = if (standing.standing == AdminStandingDto.UNREADABLE) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** A rung's name: this app's label where it has one, the node's token where it does not. */
@Composable
private fun rungLabel(rung: LadderRung): String =
    rung.local?.let { localizedString(it.labelMessageId) } ?: rung.op

/**
 * How the act is undone, from the served reversal pair (`reverses`) where the
 * node stated it, and this app's own sentence where it has one.
 */
@Composable
private fun undoneSentence(op: LadderRung, rungs: List<LadderRung>): String? {
    fun labelOf(token: String): String? = rungs.firstOrNull { it.op == token }?.let { r ->
        r.local?.labelMessageId
    }
    return when {
        op.irreversible -> localizedString("moderation.ladder.undone_never")
        op.local?.reversalMessageId != null -> ladderBundleText(op.local.reversalMessageId)
        op.reverses != null -> localizedString(
            "moderation.ladder.undone_by_op",
            "op",
            labelOf(op.reverses)?.let { localizedString(it) } ?: op.reverses,
        )
        op.reversedBy != null -> localizedString(
            "moderation.ladder.undone_is_reversal",
            "op",
            labelOf(op.reversedBy)?.let { localizedString(it) } ?: op.reversedBy,
        )
        // The compiled fallback never carried the reversal pair.
        op.reachesSubstrate == null -> localizedString("moderation.ladder.undone_by_reversal_op")
        else -> localizedString("moderation.ladder.undone_no_inverse")
    }
}

/**
 * The catalogue's `reaches_substrate`: "we noted it" versus "the door is
 * shut". Drawn only when the node stated it; the fallback has no such column.
 */
@Composable
private fun ReachesSubstrateLine(op: LadderRung) {
    val reaches = op.reachesSubstrate ?: return
    Text(
        localizedString(
            if (reaches) "moderation.ladder.reaches_substrate_yes" else "moderation.ladder.reaches_substrate_no",
        ),
        fontSize = 12.sp,
        fontWeight = if (reaches) FontWeight.Bold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp).testable("txt_ladder_reaches_substrate", reaches.toString()),
    )
}

/** Where the ladder came from, said only when it is NOT the node's own table. */
@Composable
private fun LadderCatalogueSourceLine(source: CatalogueSource) {
    val text = when (source) {
        is CatalogueSource.NotOnThisNode -> localizedString("moderation.ladder.catalogue_not_on_this_node")
        is CatalogueSource.Unreadable -> localizedString(
            "moderation.ladder.catalogue_unreadable",
            "detail",
            source.detail ?: "",
        )
        CatalogueSource.Node, CatalogueSource.NotLoaded -> return
    }
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp).testable("txt_ladder_catalogue_fallback"),
    )
}

/**
 * **The node's own delegation ids** (`GET /v1/admin/self` → `owner_delegations`,
 * CIRISServer#676). One chip per row carrying the scope this rung needs; when
 * none does, the sentence says which scopes the node's rows DO carry, because
 * an id the node would refuse (`authority_scope_absent`) is not offered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeDelegationPicker(state: AdminLadderState, viewModel: AdminLadderViewModel) {
    val op = state.selectedOp
    when (val read = state.nodeDelegations) {
        NodeDelegations.NotLoaded -> Unit
        NodeDelegations.NotReturned -> Text(
            localizedString("moderation.ladder.delegations_not_returned"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp).testable("txt_ladder_delegations_not_returned"),
        )
        is NodeDelegations.Unreadable -> Text(
            localizedString("moderation.ladder.delegations_unreadable", "detail", read.detail ?: ""),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 6.dp).testable("txt_ladder_delegations_error"),
        )
        is NodeDelegations.Read -> {
            val usable = state.usableDelegations
            if (usable.isEmpty()) {
                val carried = read.rows.map { it.scope }.filter { it.isNotBlank() }.distinct()
                Text(
                    if (read.rows.isEmpty()) {
                        localizedString("moderation.ladder.delegations_none", "scope", op.scope ?: "")
                    } else {
                        localizedString(
                            "moderation.ladder.delegations_wrong_scope",
                            mapOf(
                                "count" to read.rows.size.toString(),
                                "carried" to carried.joinToString(", "),
                                "scope" to (op.scope ?: ""),
                            ),
                        )
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp).testable("txt_ladder_delegations_none_usable"),
                )
            } else {
                Text(
                    localizedString("moderation.ladder.delegations_pick", "scope", op.scope ?: ""),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    usable.forEachIndexed { i, row ->
                        FilterChip(
                            selected = state.delegationId.trim() == row.delegationId,
                            onClick = { viewModel.useNodeDelegation(row.delegationId) },
                            label = { Text(row.delegationId.take(16), fontSize = 11.sp) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testable("chip_ladder_delegation_$i", row.delegationId),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The CC 4.5.4 verdict area. Four states, each its own tag: never asked,
 * loading (blank, the button carries the spinner), could not ask (or the node
 * has no such route), and the verdict itself with its closed set.
 */
@Composable
private fun NamedModeratorVerdictBlock(state: ai.ciris.mobile.shared.viewmodels.SafetyState) {
    val v = state.namedModeratorVerdict
    val failure = state.namedModeratorFailure
    when {
        state.namedModeratorLoading -> Unit
        failure is ai.ciris.mobile.shared.ui.screens.ReadFailure.NotOnThisNode -> Text(
            localizedString("mobile.moderation_named_not_on_this_node"),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testable("txt_named_moderator_not_on_this_node"),
        )
        failure != null -> Text(
            localizedString("mobile.moderation_named_error", "detail", failure.detail ?: ""),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testable("txt_named_moderator_error"),
        )
        v == null -> Text(
            localizedString("mobile.moderation_named_none"),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testable("txt_named_moderator_none"),
        )
        else -> {
            val (label, detail) = when (v.verdict) {
                "operate" -> localizedString("mobile.moderation_verdict_operate") to
                    localizedString("mobile.moderation_verdict_operate_detail")
                "auto_promote" -> localizedString("mobile.moderation_verdict_autopromote") to
                    localizedString("mobile.moderation_verdict_autopromote_detail",
                        "candidate", v.candidateKeyId ?: "?")
                "quiesce" -> localizedString("mobile.moderation_verdict_quiesce") to
                    localizedString("mobile.moderation_verdict_quiesce_detail")
                // A verdict outside the closed set is not rendered as itself:
                // the community is not cleared to operate until this app knows.
                else -> localizedString("mobile.moderation_verdict_unknown", "verdict", v.verdict) to ""
            }
            Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testable("txt_named_moderator_verdict", v.verdict))
            if (detail.isNotEmpty()) {
                Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            v.candidateKeyId?.let { candidate ->
                // Who, without why: the track record CC 4.5.4 rule 2 selects on
                // is not in the verdict body yet, so none is claimed here.
                Text(
                    localizedString("mobile.moderation_candidate_row", "candidate", candidate),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).testable("row_named_moderator_candidate", candidate),
                )
            }
            if (state.namedModeratorFailsSecure) {
                Text(localizedString("mobile.moderation_fails_secure"),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp).testable("txt_named_moderator_fails_secure"))
            }
        }
    }
}

@Composable
internal fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp).height(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
