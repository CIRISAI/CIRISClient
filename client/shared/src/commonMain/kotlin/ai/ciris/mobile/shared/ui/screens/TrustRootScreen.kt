package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.DrillBand
import ai.ciris.mobile.shared.models.federation.GenesisState
import ai.ciris.mobile.shared.models.federation.RootKindView
import ai.ciris.mobile.shared.models.federation.TrustPosture
import ai.ciris.mobile.shared.models.federation.TrustRootFailure
import ai.ciris.mobile.shared.models.federation.ServedBundleRead
import ai.ciris.mobile.shared.models.federation.ServedBundleView
import ai.ciris.mobile.shared.models.federation.TrustRootView
import ai.ciris.mobile.shared.models.federation.importNotYetAccepted
import ai.ciris.mobile.shared.models.federation.UntrustConsequence
import ai.ciris.mobile.shared.models.federation.TRUST_ROOT_BUNDLE_UNLABELLED
import ai.ciris.mobile.shared.models.federation.trustRootRefusalBodyKey
import ai.ciris.mobile.shared.models.federation.trustRootRefusalKey
import ai.ciris.mobile.shared.platform.FilePickerDialog
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.platform.testableWithHandler
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.glyphs.Glyph
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextField
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.FieldRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.ui.theme.tone
import ai.ciris.mobile.shared.viewmodels.ImportStage
import ai.ciris.mobile.shared.viewmodels.TrustRootRead
import ai.ciris.mobile.shared.viewmodels.TrustRootViewModel
import ai.ciris.mobile.shared.viewmodels.UntrustStage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Tag-safe form of a key id. */
internal fun trustTagSlug(id: String): String =
    id.lowercase().map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

/**
 * **This node's trust root** — a detail of the Accord card (Everyone › Safety).
 *
 * The accord family is the default trust root; this is the node's own side of
 * it (CC 3.2 "Default trust, not forced root"): which roots this node's
 * `trust:accepts` edge reaches, whether persist finds each sound, the genesis
 * state, and the two levers a conformant consumer MUST have — adopt a portable
 * seed and un-trust a root (T3: one row). Every call goes to the NODE and is
 * loopback-only (CIRISServer#652): off the node's machine the answer is
 * "only a device on this node's own machine can see or change this", never an
 * empty list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun TrustRootScreen(
    viewModel: TrustRootViewModel,
    onBack: () -> Unit,
    /** The node these calls go to — named in the confirm's "which node" fact. */
    nodeUrl: String,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val read by viewModel.read.collectAsState()
    val importStage by viewModel.importStage.collectAsState()
    val untrustStage by viewModel.untrustStage.collectAsState()
    val servedBundle by viewModel.servedBundle.collectAsState()

    var seedText by remember { mutableStateOf("") }
    var allegianceFrom by remember { mutableStateOf("") }
    var pickFile by remember { mutableStateOf(false) }
    var pickRefused by remember { mutableStateOf<ai.ciris.mobile.shared.platform.PickTooLarge?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        containerColor = t.ground,
        topBar = {
            ScreenTopBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = t.ground,
                    scrolledContainerColor = t.ground,
                    titleContentColor = t.ink,
                    navigationIconContentColor = t.dim,
                    actionIconContentColor = t.dim,
                ),
                title = { Text(localizedString("mobile.trust_root_title"), style = type.title) },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testableWithHandler("btn_trust_root_back") { onBack() },
                        ) {
                            Icon(CIRISIcons.arrowBack, contentDescription = localizedString("common_back"), tint = t.dim)
                        }
                    } else {
                        Spacer(Modifier.width(56.dp))
                    }
                },
                actions = {
                    val busy = read is TrustRootRead.Loading
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !busy,
                        modifier = Modifier.testableWithHandler("btn_trust_root_refresh", enabled = !busy) { if (!busy) viewModel.refresh() },
                    ) {
                        Glyph(GlyphName.REFRESH, tint = if (busy) t.mute else t.dim, contentDescription = localizedString("common_refresh"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).testableVerticalScroll("trust_root")
                .padding(horizontal = 16.dp, vertical = 8.dp).testable("screen_trust_root"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(localizedString("mobile.trust_root_intro"), style = type.body, color = t.dim)

            when (val r = read) {
                TrustRootRead.Loading -> StateBlock(ListState.Loading, tag = "trust_root_loading")
                is TrustRootRead.Failed -> TrustRootFailureBlock(r.failure, tagPrefix = "trust_root", readFailure = true)
                is TrustRootRead.Loaded -> {
                    PostureCard(r.posture)
                    ServedBundleBlock(servedBundle)
                    Text(localizedString("mobile.trust_root_roots_title"), style = type.title, color = t.ink)
                    if (r.roots.isEmpty()) {
                        StateBlock(ListState.Empty(localizedString("mobile.trust_root_empty"), glyph = GlyphName.ROOT), tag = "trust_root_empty", inline = true)
                    }
                    r.roots.forEach { root -> RootCard(root, onUntrust = { viewModel.beginUntrust(root) }) }
                }
            }

            UntrustResult(untrustStage, onDismiss = { viewModel.dismissResults() })

            // ── Adopt a seed ───────────────────────────────────────────────
            CardShell(tag = "card_trust_root_import") {
                Text(localizedString("mobile.trust_root_import_title"), style = type.title, color = t.ink)
                Spacer(Modifier.height(4.dp))
                Text(localizedString("mobile.trust_root_import_body"), style = type.body, color = t.dim)
                Spacer(Modifier.height(8.dp))
                CirisTextField(
                    tag = "input_trust_root_seed",
                    value = seedText,
                    onValueChange = { seedText = it },
                    placeholder = localizedString("mobile.trust_root_import_seed_hint"),
                    singleLine = false,
                    mono = true,
                )
                Spacer(Modifier.height(6.dp))
                CirisTextField(
                    tag = "input_trust_root_allegiance_from",
                    value = allegianceFrom,
                    onValueChange = { allegianceFrom = it },
                    placeholder = localizedString("mobile.trust_root_import_allegiance_hint"),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CirisTextButton(
                        localizedString("mobile.trust_root_import_file_btn"),
                        tag = "btn_trust_root_seed_file",
                        onClick = { pickFile = true },
                    )
                    val canReview = seedText.isNotBlank() && importStage !is ImportStage.Sending
                    CirisButton(
                        localizedString("mobile.trust_root_import_review_btn"),
                        tag = "btn_trust_root_import_review",
                        enabled = canReview,
                        onClick = { viewModel.beginImport(seedText) },
                    )
                }
                pickRefused?.let { r ->
                    ai.ciris.mobile.shared.ui.components.PickTooLargeNotice(r, tag = "trust_root_pick_too_large", onDismiss = { pickRefused = null })
                }
                ImportResult(importStage)
            }
        }
    }

    FilePickerDialog(
        show = pickFile,
        mimeTypes = listOf("application/json"),
        onFilePicked = { file ->
            pickFile = false
            seedText = try {
                Base64.decode(file.dataBase64).decodeToString()
            } catch (_: Exception) {
                ""
            }
        },
        onDismiss = { pickFile = false },
        onTooLarge = { r -> pickFile = false; pickRefused = r },
    )

    // ── The two confirms: three facts each ──────────────────────────────────
    (importStage as? ImportStage.Confirming)?.let { c ->
        ConfirmSheet(
            title = localizedString("mobile.trust_root_import_confirm_title"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.trust_root_confirm_fact_who"), localizedString("mobile.trust_root_confirm_this_node", "node", nodeUrl), mono = true),
                ConfirmFact(
                    localizedString("mobile.trust_root_confirm_fact_what"),
                    c.charterRoot?.let { localizedString("mobile.trust_root_import_what", "root", it) }
                        ?: localizedString("mobile.trust_root_import_what_unknown"),
                ),
                ConfirmFact(localizedString("mobile.trust_root_confirm_fact_signs"), localizedString("mobile.trust_root_import_signs")),
            ),
            confirmLabel = localizedString("mobile.trust_root_import_confirm_btn"),
            onConfirm = { viewModel.confirmImport(allegianceFrom.ifBlank { null }) },
            onDismiss = { viewModel.cancelImport() },
            tagPrefix = "trust_root_import",
            // CIRISServer#404: no preview/fingerprint route yet, so the out-of-band
            // comparison CC 3.2 T5 requires cannot be shown here. Say so.
            note = localizedString("mobile.trust_root_import_note"),
        )
    }
    (untrustStage as? UntrustStage.Confirming)?.let { c ->
        ConfirmSheet(
            title = localizedString("mobile.trust_root_untrust_confirm_title", "root", c.root.rootKeyId),
            facts = listOf(
                ConfirmFact(localizedString("mobile.trust_root_untrust_fact_root"), c.root.rootKeyId, mono = true),
                ConfirmFact(
                    localizedString("mobile.trust_root_confirm_fact_what"),
                    when (c.consequence) {
                        UntrustConsequence.LAST_ROOT -> localizedString("mobile.trust_root_untrust_what_last")
                        UntrustConsequence.OTHERS_REMAIN -> localizedString("mobile.trust_root_untrust_what_others")
                    },
                ),
                ConfirmFact(localizedString("mobile.trust_root_confirm_fact_signs"), localizedString("mobile.trust_root_untrust_signs")),
            ),
            confirmLabel = localizedString("mobile.trust_root_untrust_confirm_btn"),
            onConfirm = { viewModel.confirmUntrust() },
            onDismiss = { viewModel.cancelUntrust() },
            destructive = true,
            tagPrefix = "trust_root_untrust",
        )
    }
}

/**
 * A trust-root call that produced no answer. The loopback refusal is its own
 * sentence — a fact about where this device is, not about the node's roots.
 */
@Composable
internal fun TrustRootFailureBlock(failure: TrustRootFailure, tagPrefix: String, readFailure: Boolean) {
    when (failure) {
        is TrustRootFailure.LoopbackOnly -> StateBlock(
            ListState.Error(
                title = localizedString("mobile.trust_root_loopback_only"),
                body = localizedString("mobile.trust_root_loopback_only_body"),
                detail = failure.reasonId,
            ),
            tag = "${tagPrefix}_loopback_only",
            inline = true,
        )
        TrustRootFailure.NotOnThisNode -> StateBlock(
            ListState.Empty(localizedString("mobile.trust_root_not_on_this_node"), glyph = GlyphName.INFO),
            tag = "${tagPrefix}_not_on_this_node",
            inline = true,
        )
        is TrustRootFailure.Refused -> {
            val key = trustRootRefusalKey(failure.reasonId)
            val bodyKey = trustRootRefusalBodyKey(failure.reasonId)
            StateBlock(
                ListState.Error(
                    title = key?.let { localizedString(it) }
                        ?: localizedString("mobile.trust_root_refused_other", "id", failure.reasonId ?: "—"),
                    body = bodyKey?.let { localizedString(it) } ?: failure.detail,
                    // With guidance in the body, the node's own words go beneath it:
                    // since CIRISServer#726 the unlabelled-bundle detail names the row.
                    detail = if (bodyKey != null) failure.detail ?: failure.reasonId else failure.reasonId,
                ),
                // A refusal with its own guidance is its own tag, so a flow can
                // assert the guidance and not just "an error".
                tag = if (failure.reasonId == TRUST_ROOT_BUNDLE_UNLABELLED) "${tagPrefix}_bundle_unlabelled" else "${tagPrefix}_error",
                inline = true,
            )
        }
        is TrustRootFailure.Failed -> StateBlock(
            ListState.Error(
                title = if (readFailure) localizedString("mobile.trust_root_read_failed") else localizedString("mobile.state_read_failed"),
                body = if (readFailure) localizedString("mobile.trust_root_read_failed_body") else null,
                detail = failure.detail,
            ),
            tag = "${tagPrefix}_error",
            inline = true,
        )
    }
}

/**
 * The bundle this node runs on (0.5.220 `GET /v1/trust-root/bundle`): what a
 * person compares out of band with the people who made it. Read-only; absent
 * on a node without the route.
 */
@Composable
private fun ServedBundleBlock(read: ServedBundleRead) {
    when (read) {
        ServedBundleRead.Absent -> Unit
        is ServedBundleRead.Shown -> ServedBundleCard(read.view)
        // 409 `trust_root.bundle_not_in_force` (CIRISServer#726): a fact about
        // this node's posture, not a failure — named, in the neutral tone.
        is ServedBundleRead.NotInForce -> StateBlock(
            ListState.Empty(localizedString("mobile.trust_root_bundle_not_in_force"), glyph = GlyphName.ROOT),
            tag = "trust_root_bundle_not_in_force",
            inline = true,
        )
    }
}

@Composable
private fun ServedBundleCard(b: ServedBundleView) {
    CardShell(tag = "card_trust_root_served_bundle") {
        Text(localizedString("mobile.trust_root_bundle_title"), style = CirisTheme.type.title, color = CirisTheme.tokens.ink)
        FieldRow(
            label = localizedString("mobile.trust_root_bundle_fingerprint_label"),
            value = b.fingerprint,
            mono = true,
            tag = "txt_trust_root_bundle_fingerprint",
        )
        FieldRow(
            label = localizedString("mobile.trust_root_bundle_charter_root_label"),
            value = b.charterRootKeyId ?: localizedString("mobile.trust_root_unknown"),
            mono = b.charterRootKeyId != null,
            tag = "txt_trust_root_bundle_charter_root",
            divider = false,
        )
    }
}

@Composable
private fun PostureCard(p: TrustPosture) {
    val t = CirisTheme.tokens
    val leg = p.leg ?: "—"
    // UNREADABLE is the error treatment: the node could not answer about itself.
    if (p.state == GenesisState.UNREADABLE) {
        StateBlock(
            ListState.Error(
                title = localizedString("mobile.trust_root_state_unreadable", "leg", leg),
                body = p.banner,
                detail = p.detail,
            ),
            tag = "trust_root_posture_unreadable",
            inline = true,
        )
        return
    }
    val (text, tone) = when (p.state) {
        GenesisState.ENTRENCHED -> localizedString("mobile.trust_root_state_entrenched") to Tone.OK
        // persist v53 (#973): a newer root this binary carries was not adopted.
        // With the previous root still in force the node is NOT unrooted — the
        // headline says so, and the node's own banner ("ROOT NOT ADOPTED …")
        // rides beneath it as given.
        GenesisState.PRE_GENESIS -> when {
            p.heldRootInForce -> localizedString("mobile.trust_root_state_not_adopted_held", "leg", leg) to Tone.BRAND
            p.bakeNotAdopted -> localizedString("mobile.trust_root_state_not_adopted", "leg", leg) to Tone.BRAND
            else -> localizedString("mobile.trust_root_state_pre_genesis", "leg", leg) to Tone.BRAND
        }
        GenesisState.DIVERGENT -> localizedString("mobile.trust_root_state_divergent", "leg", leg) to Tone.DANGER
        else -> localizedString("mobile.trust_root_state_unknown", "state", p.token.ifBlank { "—" }) to Tone.DIM
    }
    CardShell(tag = "card_trust_posture", accent = t.tone(tone)) {
        FieldRow(
            label = localizedString("mobile.trust_root_posture_label"),
            value = text,
            tone = tone,
            gloss = p.detail,
            tag = "txt_trust_posture_state",
            divider = p.banner != null,
        )
        p.banner?.let {
            FieldRow(label = localizedString("mobile.trust_root_banner_label"), value = it, tag = "txt_trust_posture_banner", divider = false)
        }
    }
}

@Composable
private fun RootCard(root: TrustRootView, onUntrust: () -> Unit) {
    val type = CirisTheme.type
    val t = CirisTheme.tokens
    val s = trustTagSlug(root.rootKeyId)
    CardShell(tag = "card_trust_root_$s") {
        Text(root.rootKeyId, style = type.signed, color = t.ink)
        Text(
            when (root.kind) {
                RootKindView.FAMILY -> localizedString("mobile.trust_root_kind_family")
                RootKindView.KEY -> localizedString("mobile.trust_root_kind_key")
                RootKindView.UNREADABLE -> localizedString("mobile.trust_root_kind_unreadable")
                RootKindView.OTHER -> root.kindToken.ifBlank { "—" }
            },
            style = type.body,
            color = t.dim,
        )
        root.evaluationError?.let {
            FieldRow(
                label = localizedString("mobile.trust_root_valid_label"),
                value = localizedString("mobile.trust_root_eval_error", "error", it),
                tone = Tone.DANGER,
                tag = "row_trust_root_eval_error_$s",
            )
        }
        FieldRow(
            label = localizedString("mobile.trust_root_accepted_label"),
            value = when (root.acceptedByThisNode) {
                true -> localizedString("mobile.trust_root_accepted_yes")
                false -> localizedString("mobile.trust_root_accepted_no")
                null -> localizedString("mobile.trust_root_unknown")
            },
            tone = if (root.acceptedByThisNode == true) Tone.OK else Tone.DIM,
            tag = "row_trust_root_accepted_$s",
        )
        if (root.evaluationError == null) {
            FieldRow(
                label = localizedString("mobile.trust_root_valid_label"),
                value = when (root.valid) {
                    true -> localizedString("mobile.trust_root_valid_yes")
                    false -> localizedString("mobile.trust_root_valid_no")
                    null -> localizedString("mobile.trust_root_unknown")
                },
                tone = when (root.valid) { true -> Tone.OK; false -> Tone.DANGER; null -> Tone.DIM },
                tag = "row_trust_root_valid_$s",
            )
        }
        if (root.evaluationError == null) {
            // The charter legs of T3 (CC 3.2): the root's self-charter, and the
            // pre-rotation recovery commitment it must carry. persist reports both
            // and `valid` folds them in; a person un-trusting on that verdict
            // should see which leg failed, not only that one did.
            val yes = localizedString("mobile.trust_root_yes")
            val no = localizedString("mobile.trust_root_no")
            val unknown = localizedString("mobile.trust_root_unknown")
            val word = { b: Boolean? -> when (b) { true -> yes; false -> no; null -> unknown } }
            FieldRow(
                label = localizedString("mobile.trust_root_charter_label"),
                value = localizedString(
                    "mobile.trust_root_charter_value",
                    mapOf("declares" to word(root.rootSelfDeclares), "recovery" to word(root.charterHasRecovery)),
                ),
                tone = if (root.rootSelfDeclares == false || root.charterHasRecovery == false) Tone.DANGER else Tone.INK,
                tag = "row_trust_root_charter_$s",
            )
        }
        root.quorum?.let { q ->
            FieldRow(
                label = localizedString("mobile.trust_root_quorum_label"),
                value = localizedString(
                    "mobile.trust_root_quorum_value",
                    mapOf("have" to q.distinctHolders.toString(), "required" to q.required.toString(), "roster" to q.rosterSize.toString()),
                ),
                tone = if (q.met) Tone.INK else Tone.DANGER,
                tag = "row_trust_root_quorum_$s",
            )
        }
        root.drillBand?.let { band ->
            val at = root.lastDrillAt
            FieldRow(
                label = localizedString("mobile.trust_root_drill_label"),
                value = when {
                    at == null -> localizedString("mobile.trust_root_drill_never")
                    band == DrillBand.GREEN -> localizedString("mobile.trust_root_drill_green", "at", at)
                    band == DrillBand.YELLOW -> localizedString("mobile.trust_root_drill_yellow", "at", at)
                    else -> localizedString("mobile.trust_root_drill_red", "at", at)
                },
                // A signal, never a gate (CC 3.2 T4): no band here is ever DANGER.
                tone = when (band) { DrillBand.GREEN -> Tone.OK; DrillBand.YELLOW -> Tone.BRAND; DrillBand.RED -> Tone.DIM },
                gloss = localizedString("mobile.trust_root_drill_signal_note"),
                tag = "row_trust_root_drill_$s",
            )
        }
        if (root.evaluationError == null) {
            FieldRow(
                label = localizedString("mobile.trust_root_halt_label"),
                value = when (root.haltLatched) {
                    true -> localizedString("mobile.trust_root_halt_latched")
                    false -> localizedString("mobile.trust_root_halt_clear")
                    null -> localizedString("mobile.trust_root_unknown")
                },
                tone = if (root.haltLatched == true) Tone.DANGER else Tone.INK,
                tag = "row_trust_root_halt_$s",
            )
            FieldRow(
                label = localizedString("mobile.trust_root_bounded_label"),
                value = root.boundedUntil ?: localizedString("mobile.trust_root_bounded_none"),
                mono = root.boundedUntil != null,
                tag = "row_trust_root_bounded_$s",
            )
        }
        val pass = localizedString("mobile.trust_root_layer_pass")
        val fail = localizedString("mobile.trust_root_layer_fail")
        val unchecked = localizedString("mobile.trust_root_layer_unchecked")
        root.holders.forEach { h ->
            val layer = { v: Boolean? -> when (v) { true -> pass; false -> fail; null -> unchecked } }
            FieldRow(
                label = localizedString("mobile.trust_root_holder_label", "key", h.keyId),
                value = localizedString(
                    "mobile.trust_root_holder_value",
                    mapOf(
                        "class" to (h.hardwareClass ?: localizedString("mobile.trust_root_holder_no_class")),
                        "a" to layer(h.layerA),
                        "b" to layer(h.layerB),
                    ),
                ),
                tone = if (!h.layerA || h.layerB == false) Tone.DANGER else Tone.INK,
                gloss = h.refusal,
                tag = "row_trust_root_holder_${s}_${trustTagSlug(h.keyId)}",
            )
        }
        // Un-trust is offered unless the node says this root is NOT accepted —
        // an unknown acceptance is still withdrawable (the node answers `withdrawn: false` if not).
        if (root.acceptedByThisNode != false) {
            Spacer(Modifier.height(6.dp))
            CirisTextButton(
                localizedString("mobile.trust_root_untrust_btn"),
                tag = "btn_trust_root_untrust_$s",
                onClick = onUntrust,
                danger = true,
            )
        }
    }
}

@Composable
private fun ImportResult(stage: ImportStage) {
    val type = CirisTheme.type
    val t = CirisTheme.tokens
    when (stage) {
        ImportStage.NotJson -> {
            Spacer(Modifier.height(8.dp))
            StateBlock(
                ListState.Error(title = localizedString("mobile.trust_root_import_not_json")),
                tag = "trust_root_import_not_json",
                inline = true,
            )
        }
        ImportStage.Sending -> {
            Spacer(Modifier.height(8.dp))
            StateBlock(ListState.Loading, tag = "trust_root_import_sending", inline = true)
        }
        is ImportStage.Failed -> {
            Spacer(Modifier.height(8.dp))
            TrustRootFailureBlock(stage.failure, tagPrefix = "trust_root_import", readFailure = false)
        }
        is ImportStage.Done -> {
            Spacer(Modifier.height(8.dp))
            val yes = localizedString("mobile.trust_root_yes")
            val no = localizedString("mobile.trust_root_no")
            // Two acts, reported apart (trust_root_api.rs:281-291): installed ≠ trusted.
            FieldRow(
                label = localizedString("mobile.trust_root_import_installed_label"),
                value = if (stage.result.installed) yes else no,
                tone = if (stage.result.installed) Tone.OK else Tone.DANGER,
                tag = "txt_trust_root_import_installed",
            )
            FieldRow(
                label = localizedString("mobile.trust_root_import_accepted_label"),
                value = if (stage.result.accepted) yes else no,
                tone = if (stage.result.accepted) Tone.OK else Tone.DANGER,
                tag = "txt_trust_root_import_accepted",
                divider = false,
            )
            if (importNotYetAccepted(stage.result)) {
                // Imported, NOT trusted — a named state with its why, never a
                // success. On 0.5.220 this is also what a deferred acceptance
                // reports (the node does not hold the root's head yet; persist
                // retries at the next boot or import), so it no longer claims
                // that adopting again cannot help.
                Spacer(Modifier.height(8.dp))
                StateBlock(
                    ListState.Error(
                        title = localizedString("mobile.trust_root_import_not_yet_accepted"),
                        body = localizedString("mobile.trust_root_import_not_yet_accepted_why"),
                        detail = stage.result.banner,
                    ),
                    tag = "trust_root_import_not_yet_accepted",
                    inline = true,
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun UntrustResult(stage: UntrustStage, onDismiss: () -> Unit) {
    when (stage) {
        is UntrustStage.Sending -> StateBlock(ListState.Loading, tag = "trust_root_untrust_sending", inline = true)
        is UntrustStage.Failed -> TrustRootFailureBlock(stage.failure, tagPrefix = "trust_root_untrust", readFailure = false)
        is UntrustStage.Done -> {
            val yes = localizedString("mobile.trust_root_yes")
            val no = localizedString("mobile.trust_root_no")
            val r = stage.result
            CardShell(tag = "card_trust_root_untrust_result") {
                FieldRow(
                    label = localizedString("mobile.trust_root_untrust_withdrawn_label"),
                    value = if (r.withdrawn) r.rootKeyId else localizedString("mobile.trust_root_untrust_withdrawn_none"),
                    mono = r.withdrawn,
                    tag = "txt_trust_root_untrust_withdrawn",
                )
                FieldRow(
                    label = localizedString("mobile.trust_root_untrust_records_label"),
                    value = if (r.recordsRetained) yes else no,
                    tag = "txt_trust_root_untrust_records_retained",
                )
                FieldRow(
                    label = localizedString("mobile.trust_root_untrust_entrenched_label"),
                    value = if (r.entrenched) yes else no,
                    tone = if (r.entrenched) Tone.OK else Tone.DANGER,
                    gloss = r.banner,
                    tag = "txt_trust_root_untrust_entrenched",
                    divider = false,
                )
                CirisTextButton(localizedString("mobile.trust_root_dismiss"), tag = "btn_trust_root_untrust_dismiss", onClick = onDismiss)
            }
        }
        else -> Unit
    }
}
