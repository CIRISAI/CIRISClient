package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.FinalGenesisFinishDto
import ai.ciris.mobile.shared.models.federation.FinalGenesisGrid
import ai.ciris.mobile.shared.models.federation.FinalGenesisItems
import ai.ciris.mobile.shared.models.federation.FinalGenesisRefusal
import ai.ciris.mobile.shared.models.federation.GenesisCell
import ai.ciris.mobile.shared.models.federation.HolderGenesisState
import ai.ciris.mobile.shared.models.federation.PlanConfirm
import ai.ciris.mobile.shared.models.federation.DEFAULT_PIV_SLOT
import ai.ciris.mobile.shared.models.federation.genesisItemLabel
import ai.ciris.mobile.shared.models.federation.isDialHint
import ai.ciris.mobile.shared.models.federation.pinTriesWarning
import ai.ciris.mobile.shared.models.federation.seedBlobName
import ai.ciris.mobile.shared.models.federation.seedBlobPath
import ai.ciris.mobile.shared.platform.DirectoryPickerDialog
import ai.ciris.mobile.shared.platform.DirectoryPickerPurpose
import ai.ciris.mobile.shared.platform.getFileSize
import ai.ciris.mobile.shared.viewmodels.NodeWait
import ai.ciris.mobile.shared.models.federation.shortCommitment
import ai.ciris.mobile.shared.viewmodels.recoveryCommitmentShown
import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.platform.saveFileCopy
import ai.ciris.mobile.shared.platform.rememberInputSinks
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.Chip
import ai.ciris.mobile.shared.ui.primitives.ChipKind
import ai.ciris.mobile.shared.ui.primitives.ChipSpec
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextField
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.FieldRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.ui.theme.tone
import ai.ciris.mobile.shared.viewmodels.FinalGenesisPhase
import ai.ciris.mobile.shared.viewmodels.FinalGenesisViewModel
import ai.ciris.mobile.shared.viewmodels.HolderSignNote
import ai.ciris.mobile.shared.viewmodels.RecoveryRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * **FINAL GENESIS** — the 3-of-3 re-mint of CIRISServer 0.5.220
 * (`FSD/FINAL_GENESIS.md`, `src/final_genesis.rs`), replacing the 2-of-3
 * propose/cosign sheet on a node that serves it ([FinalGenesisViewModel] decides
 * by the route). One sheet, driven by `GET /v1/accord/final-genesis`:
 *
 *  1. **Recovery keys** — each holder's spare (A2 for A1, …) is ON RECORD on the
 *     node; each row offers an OPTIONAL "Verify with token". Plan is never gated
 *     on it.
 *  2. **Plan** — the canonicals to seat; `clock_checked` only after a ConfirmSheet
 *     on the host's NTP; `replace` only after a ConfirmSheet when one is planned.
 *  3. **The grid** — three holders × the node's items, two rounds; one Sign per
 *     holder per round, one YubiKey session each.
 *  4. **Finish** — the bundle's sha256, its path, and what the node verified.
 *
 * Every refusal is rendered by its id. PIN fields are `/input`-drivable and are
 * never echoed to `/tree`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinalGenesisSheet(vm: FinalGenesisViewModel, onDismiss: () -> Unit) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val phase by vm.phase.collectAsState()
    val replanning by vm.replanning.collectAsState()
    val refusal by vm.refusal.collectAsState()
    val confirm by vm.confirm.collectAsState()
    val nodeWait by vm.nodeWait.collectAsState()

    // The node drives the grid: re-read while a ceremony is planned, so the
    // other holders' signatures appear here without anyone pressing anything.
    val polling = phase is FinalGenesisPhase.Planned
    LaunchedEffect(polling) {
        while (polling) {
            delay(5_000)
            // Awaited: the next poll starts only after this one has landed.
            vm.refresh().join()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.raised,
        contentColor = t.ink,
        shape = CirisShape.sheet,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().testable("sheet_final_genesis")
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp).navigationBarsPadding(),
        ) {
            Text(localizedString("mobile.final_genesis_title"), style = type.title, color = t.ink)
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                    .testableVerticalScroll(name = "sheet_final_genesis"),
            ) {
                Text(localizedString("mobile.final_genesis_desc"), style = type.body, color = t.dim)
                Spacer(Modifier.height(10.dp))
                refusal?.let { r ->
                    RefusalBlock(r, tag = "final_genesis_error")
                    Spacer(Modifier.height(10.dp))
                }
                when (val p = phase) {
                    FinalGenesisPhase.Probing, FinalGenesisPhase.Legacy -> {
                        StateBlock(ListState.Loading, tag = "final_genesis_loading")
                        (nodeWait as? NodeWait.Waiting)?.let { w ->
                            val line = localizedString("mobile.final_genesis_waiting_node", "seconds", w.elapsedSeconds.toString())
                            Text(line, style = type.body, color = t.dim, modifier = Modifier.testable("final_genesis_waiting_node", line))
                        }
                    }
                    is FinalGenesisPhase.Unavailable -> {
                        RefusalBlock(p.refusal, tag = "final_genesis_unavailable")
                        Spacer(Modifier.height(8.dp))
                        CirisTextButton(
                            localizedString("mobile.final_genesis_retry"),
                            tag = "btn_final_genesis_retry",
                            onClick = { vm.open() },
                        )
                    }
                    FinalGenesisPhase.NotPlanned -> PlanSection(vm)
                    is FinalGenesisPhase.Planned -> if (replanning) {
                        PlanSection(vm)
                        CirisTextButton(
                            localizedString("mobile.final_genesis_replan_cancel"),
                            tag = "btn_final_genesis_replan_cancel",
                            onClick = { vm.cancelReplan() },
                        )
                    } else {
                        vm.grid()?.let { GridSection(vm, it) }
                    }
                    is FinalGenesisPhase.Finished -> FinishedSection(p.result)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CirisTextButton(localizedString("mobile.final_genesis_close"), tag = "btn_final_genesis_close", onClick = onDismiss)
            }
        }
    }

    when (confirm) {
        PlanConfirm.CLOCK -> ConfirmSheet(
            title = localizedString("mobile.final_genesis_clock_title"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.final_genesis_clock_fact_host"), localizedString("mobile.final_genesis_clock_fact_host_value")),
                ConfirmFact(localizedString("mobile.final_genesis_clock_fact_stamped"), localizedString("mobile.final_genesis_clock_fact_stamped_value")),
                ConfirmFact(localizedString("mobile.final_genesis_clock_fact_window"), localizedString("mobile.final_genesis_clock_fact_window_value")),
            ),
            confirmLabel = localizedString("mobile.final_genesis_clock_confirm"),
            onConfirm = { vm.confirmClock() },
            onDismiss = { vm.dismissConfirm() },
            tagPrefix = "final_genesis_clock",
            note = localizedString("mobile.final_genesis_clock_note"),
        )
        PlanConfirm.REPLACE -> ConfirmSheet(
            title = localizedString("mobile.final_genesis_replace_title"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.final_genesis_replace_fact_discards"), localizedString("mobile.final_genesis_replace_fact_discards_value")),
                ConfirmFact(localizedString("mobile.final_genesis_replace_fact_restamps"), localizedString("mobile.final_genesis_replace_fact_restamps_value")),
                ConfirmFact(localizedString("mobile.final_genesis_replace_fact_resign"), localizedString("mobile.final_genesis_replace_fact_resign_value")),
            ),
            confirmLabel = localizedString("mobile.final_genesis_replace_confirm"),
            onConfirm = { vm.confirmReplace() },
            onDismiss = { vm.dismissConfirm() },
            destructive = true,
            tagPrefix = "final_genesis_replace",
        )
        null -> Unit
    }
}

/** A tag-safe suffix: `A1` → `a1`, `record:ciris-canonical-1` → `record_ciris_canonical_1`. */
internal fun genesisSlug(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

/** A refusal in the reader's language: the node's id when the bundle has it, else the node's own English. */
@Composable
internal fun finalGenesisRefusalText(r: FinalGenesisRefusal): String {
    r.reasonId?.let { id ->
        val text = localizedString(id)
        if (text != id) return text
    }
    return r.detail ?: r.reasonId ?: localizedString("mobile.final_genesis_refused_status", "status", r.statusCode.toString())
}

@Composable
private fun RefusalBlock(r: FinalGenesisRefusal, tag: String) {
    val title = finalGenesisRefusalText(r)
    StateBlock(
        ListState.Error(
            title = title,
            // The node's own sentence under a localized title — it names what is owed.
            detail = r.detail?.takeIf { it.isNotBlank() && it != title },
        ),
        tag = tag,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = CirisTheme.type.label, color = CirisTheme.tokens.mute)
    Spacer(Modifier.height(6.dp))
}

/**
 * A PKCS#11 PIN field: `/input`-drivable (it declares a SENSITIVE sink and
 * applies the request addressed to it) and NEVER reported — the automation
 * layer neither stores nor echoes it ([ai.ciris.mobile.shared.platform.SensitiveInputs]),
 * no text on its `/tree` element, masked on screen.
 */
@Composable
private fun PinField(tag: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean = true) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    if (enabled) rememberInputSinks(tag, sensitive = true)
    val request by TestAutomation.textInputRequests.collectAsState()
    LaunchedEffect(request, enabled) {
        val r = request ?: return@LaunchedEffect
        if (enabled && r.testTag == tag) {
            onValueChange(if (r.clearFirst) r.text else value + r.text)
            TestAutomation.clearTextInputRequest()
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = type.body,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        placeholder = { Text(localizedString("mobile.final_genesis_pin_hint"), style = type.body, color = t.mute) },
        shape = CirisShape.input,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = t.ink,
            unfocusedTextColor = t.ink,
            disabledTextColor = t.mute,
            cursorColor = t.brand,
            focusedBorderColor = t.brand,
            unfocusedBorderColor = t.hairlineStrong,
            disabledBorderColor = t.hairline,
            focusedContainerColor = t.sunken,
            unfocusedContainerColor = t.sunken,
            disabledContainerColor = t.sunken,
            focusedPlaceholderColor = t.mute,
            unfocusedPlaceholderColor = t.mute,
        ),
        modifier = Modifier.fillMaxWidth().testable(tag),
    )
}

/**
 * The PKCS#11 module path, optional — the same advanced override the
 * hardware-scrub sheet offers (`AttestationSheets.kt`, its keys reused): blank
 * is omitted and the node picks the OS default; a holder whose `ykcs11` lives
 * elsewhere (macOS, Windows) sets it here. Drivable by construction.
 */
@Composable
private fun ModulePathField(tag: String, value: String, onValueChange: (String) -> Unit) {
    Text(localizedString("mobile.accord_scrub_module_label"), style = CirisTheme.type.label, color = CirisTheme.tokens.mute)
    Spacer(Modifier.height(4.dp))
    CirisTextField(
        tag = tag,
        value = value,
        onValueChange = onValueChange,
        placeholder = localizedString("mobile.accord_scrub_module_hint"),
        mono = true,
    )
}

/** What a holder types for one token session: the USB directory, the PIN, the PIV slot, the module. */
private class TokenInputs(usb: String = "", pin: String = "", slot: String = DEFAULT_PIV_SLOT, module: String = "") {
    var usb by mutableStateOf(usb)
    var pin by mutableStateOf(pin)
    var slot by mutableStateOf(slot)
    var module by mutableStateOf(module)
}

/**
 * The inputs one YubiKey + USB session needs, with what a person at the
 * console needs to know (Eric, live, 0.5.227): which holder's token to insert,
 * a folder picker for the USB key — and whether `<holder>.mldsa65.seed.blob`
 * is in the folder picked — the PIN named as the YubiKey PIV PIN, the PIV slot
 * (usually 9c), and a PIN-tries warning from the node's last refusal, large and
 * next to the PIN, not in the refusal's small print.
 */
@Composable
private fun TokenForm(
    holder: String,
    inputs: TokenInputs,
    usbTag: String,
    pinTag: String,
    slotTag: String,
    moduleTag: String,
    browseTag: String,
    tagSuffix: String,
    pinWarning: String?,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    var picker by remember(usbTag) { mutableStateOf(false) }
    Text(
        localizedString("mobile.final_genesis_insert_tokens", "holder", holder),
        style = type.title,
        color = t.ink,
        modifier = Modifier.testable("final_genesis_insert_$tagSuffix"),
    )
    Spacer(Modifier.height(6.dp))
    Text(localizedString("mobile.final_genesis_usb_label", "file", seedBlobName(holder)), style = type.label, color = t.mute)
    Spacer(Modifier.height(4.dp))
    CirisTextField(
        tag = usbTag,
        value = inputs.usb,
        onValueChange = { inputs.usb = it },
        placeholder = localizedString("mobile.final_genesis_usb_hint"),
        mono = true,
    )
    CirisTextButton(
        localizedString("mobile.final_genesis_usb_browse"),
        tag = browseTag,
        onClick = { picker = true },
    )
    DirectoryPickerDialog(
        show = picker,
        purpose = DirectoryPickerPurpose.UsbCustody,
        onDirectoryPicked = {
            inputs.usb = it
            picker = false
        },
        onDismiss = { picker = false },
    )
    // A cheap look for the holder's blob in the folder (a size read; no file is opened).
    if (inputs.usb.isNotBlank()) {
        val found = remember(inputs.usb, holder) { getFileSize(seedBlobPath(inputs.usb, holder)) > 0L }
        Text(
            localizedString(
                if (found) "mobile.final_genesis_blob_found" else "mobile.final_genesis_blob_missing",
                "file",
                seedBlobName(holder),
            ),
            style = type.body,
            color = if (found) t.ok else t.danger,
            modifier = Modifier.testable("final_genesis_blob_$tagSuffix", if (found) "found" else "missing"),
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(localizedString("mobile.final_genesis_pin_label"), style = type.label, color = t.mute)
    Spacer(Modifier.height(4.dp))
    PinField(tag = pinTag, value = inputs.pin, onValueChange = { inputs.pin = it })
    pinWarning?.let { w ->
        Spacer(Modifier.height(4.dp))
        Text(w, style = type.title, color = t.danger, modifier = Modifier.testable("final_genesis_pin_warning_$tagSuffix", w))
    }
    Spacer(Modifier.height(6.dp))
    Text(localizedString("mobile.final_genesis_slot_label"), style = type.label, color = t.mute)
    Spacer(Modifier.height(4.dp))
    CirisTextField(
        tag = slotTag,
        value = inputs.slot,
        onValueChange = { inputs.slot = it },
        placeholder = localizedString("mobile.final_genesis_slot_hint"),
        mono = true,
    )
    Text(localizedString("mobile.final_genesis_slot_hint"), style = type.body, color = t.dim)
    Spacer(Modifier.height(6.dp))
    ModulePathField(tag = moduleTag, value = inputs.module, onValueChange = { inputs.module = it })
}

// ── 1 + 2: recovery keys and the plan ───────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanSection(vm: FinalGenesisViewModel) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val source by vm.source.collectAsState()
    val sourceRefusal by vm.sourceRefusal.collectAsState()
    val recovery by vm.recovery.collectAsState()
    val serveNodes by vm.serveNodes.collectAsState()
    val busy by vm.busy.collectAsState()
    val blockedBy by vm.planBlockedBy.collectAsState()
    val dialHints by vm.dialHints.collectAsState()
    val recoveryRefusal by vm.recoveryRefusal.collectAsState()
    val recoveryUnavailable by vm.recoveryUnavailable.collectAsState()
    val failures by vm.recoveryFailures.collectAsState()
    val holders = source?.holders.orEmpty()

    sourceRefusal?.let {
        RefusalBlock(it, tag = "final_genesis_source_error")
        CirisTextButton(
            localizedString("mobile.final_genesis_retry"),
            tag = "btn_final_genesis_source_retry",
            onClick = { vm.retrySource() },
            enabled = !busy,
        )
        Spacer(Modifier.height(8.dp))
    }

    SectionTitle(localizedString("mobile.final_genesis_holders_title"))
    Text(localizedString("mobile.final_genesis_recovery_desc"), style = type.body, color = t.dim)
    Spacer(Modifier.height(6.dp))
    if (holders.isEmpty() && sourceRefusal == null) StateBlock(ListState.Loading, tag = "final_genesis_holders_loading")
    recoveryRefusal?.let {
        RefusalBlock(it, tag = "final_genesis_recovery_error")
        CirisTextButton(
            localizedString("mobile.final_genesis_retry"),
            tag = "btn_final_genesis_recovery_retry",
            onClick = { vm.retryRecoveryKeys() },
            enabled = !busy,
        )
        Spacer(Modifier.height(6.dp))
    }
    for (h in holders) {
        RecoveryRowView(vm, h.keyId, recovery[h.keyId], failures[h.keyId], busy)
    }

    Spacer(Modifier.height(14.dp))
    SectionTitle(localizedString("mobile.final_genesis_serve_title"))
    Text(localizedString("mobile.final_genesis_serve_desc"), style = type.body, color = t.dim)
    Spacer(Modifier.height(6.dp))
    val canonicals = source?.canonicals.orEmpty()
    if (source != null && canonicals.isEmpty()) {
        StateBlock(ListState.Empty(localizedString("mobile.final_genesis_serve_none")), tag = "final_genesis_serve_none")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in canonicals) {
            Chip(
                ChipSpec(
                    label = c.keyId,
                    tag = "chip_final_genesis_serve_${genesisSlug(c.keyId)}",
                    kind = ChipKind.CHOICE,
                    selected = c.keyId in serveNodes,
                    onClick = { vm.toggleServeNode(c.keyId) },
                ),
            )
        }
    }
    // Where peers dial each seated canonical (CIRISServer e4cbedeb: a bare id is
    // serve_node_no_dial_hint). Prefilled from the node's record, else — for the
    // July bake's canonical-1 only — its baked address; editable either way.
    for (keyId in serveNodes.sorted()) {
        val slug = genesisSlug(keyId)
        val value = dialHints[keyId].orEmpty()
        Spacer(Modifier.height(8.dp))
        Text(
            localizedString("mobile.final_genesis_dial_label", "key", keyId),
            style = type.label,
            color = t.mute,
        )
        Spacer(Modifier.height(4.dp))
        CirisTextField(
            tag = "input_final_genesis_dial_$slug",
            value = value,
            onValueChange = { vm.setDialHint(keyId, it) },
            placeholder = localizedString("mobile.final_genesis_dial_hint"),
            mono = true,
        )
        if (!isDialHint(value)) {
            Text(
                localizedString(if (value.isBlank()) "mobile.final_genesis_dial_missing" else "mobile.final_genesis_dial_invalid"),
                style = type.body,
                color = t.danger,
                modifier = Modifier.testable("final_genesis_dial_invalid_$slug"),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    // Gated on the holders the node has NO recovery key for — and only those.
    if (blockedBy.isNotEmpty()) {
        Text(
            localizedString("mobile.final_genesis_plan_blocked", "who", blockedBy.joinToString(", ")),
            style = type.body,
            color = t.danger,
            modifier = Modifier.testable("final_genesis_plan_blocked"),
        )
        Spacer(Modifier.height(6.dp))
    }
    if (recoveryUnavailable) {
        Text(
            localizedString("mobile.final_genesis_plan_blocked_unread"),
            style = type.body,
            color = t.danger,
            modifier = Modifier.testable("final_genesis_plan_blocked_unread"),
        )
        Spacer(Modifier.height(6.dp))
    }
    val canPlan = serveNodes.isNotEmpty() && !busy && blockedBy.isEmpty() && !recoveryUnavailable &&
        serveNodes.all { isDialHint(dialHints[it].orEmpty()) } &&
        sourceRefusal == null && source != null
    CirisButton(
        localizedString(if (busy) "mobile.final_genesis_plan_busy" else "mobile.final_genesis_plan"),
        tag = "btn_final_genesis_plan",
        onClick = { vm.plan() },
        enabled = canPlan,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RecoveryRowView(vm: FinalGenesisViewModel, holder: String, row: RecoveryRow?, failure: FinalGenesisRefusal?, busy: Boolean) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val slug = genesisSlug(holder)
    var open by remember(holder) { mutableStateOf(false) }
    val inputs = remember(holder) { TokenInputs() }

    // The fingerprint is the charter's own commitment (`GET …/recovery-keys`);
    // on a node from before that route there is none until a token is read.
    val (value, tone) = when (row) {
        is RecoveryRow.OnRecord -> (
            shortCommitment(row.commitment)?.let { c ->
                localizedString("mobile.final_genesis_recovery_record", mapOf("key" to row.recoveryKeyId, "fingerprint" to c))
            } ?: localizedString("mobile.final_genesis_recovery_on_record", "key", row.recoveryKeyId)
            ) to Tone.INK
        is RecoveryRow.Verifying -> localizedString("mobile.final_genesis_recovery_verifying", "key", row.recoveryKeyId) to Tone.DIM
        is RecoveryRow.Verified -> (
            recoveryCommitmentShown(row)?.let { c ->
                localizedString("mobile.final_genesis_recovery_verified", mapOf("key" to row.recoveryKeyId, "fingerprint" to c))
            } ?: localizedString("mobile.final_genesis_recovery_verified_pending", "key", row.recoveryKeyId)
            ) to Tone.OK
        RecoveryRow.Unread -> localizedString("mobile.final_genesis_recovery_unread") to Tone.DANGER
        is RecoveryRow.Missing -> localizedString(
            if (row.spare != null) "mobile.final_genesis_recovery_missing" else "mobile.final_genesis_recovery_missing_unknown",
            "key",
            row.spare.orEmpty(),
        ) to Tone.DANGER
        RecoveryRow.NoneRecorded, null -> localizedString("mobile.final_genesis_recovery_none") to Tone.DANGER
    }
    FieldRow(
        label = holder,
        value = value,
        mono = true,
        tone = tone,
        tag = "final_genesis_recovery_$slug",
    )
    // A failed read sits beside the row; the row keeps what the node said.
    failure?.let { RefusalBlock(it, tag = "final_genesis_recovery_refusal_$slug") }
    val canVerify = row is RecoveryRow.OnRecord || row is RecoveryRow.Verified ||
        (row is RecoveryRow.Missing && row.spare != null)
    if (canVerify && !open) {
        CirisTextButton(
            localizedString("mobile.final_genesis_recovery_verify"),
            tag = "btn_final_genesis_verify_$slug",
            onClick = { open = true },
        )
    }
    if (canVerify && open) {
        Text(localizedString("mobile.final_genesis_recovery_verify_desc"), style = type.body, color = t.dim)
        // The node accepts only the holder's own spare; the form names it, and the
        // read sends it — there is nothing to choose.
        vm.spareFor(holder)?.let { spare ->
            Text(
                localizedString("mobile.final_genesis_recovery_spare", "key", spare),
                style = type.body,
                color = t.ink,
                modifier = Modifier.testable("final_genesis_recovery_spare_$slug", spare),
            )
        }
        Spacer(Modifier.height(6.dp))
        // The spare is read as its own token: insert the spare's YubiKey and USB.
        TokenForm(
            holder = vm.spareFor(holder) ?: holder,
            inputs = inputs,
            usbTag = "input_final_genesis_recovery_usb_$slug",
            pinTag = "input_final_genesis_recovery_pin_$slug",
            slotTag = "input_final_genesis_recovery_slot_$slug",
            moduleTag = "input_final_genesis_recovery_module_$slug",
            browseTag = "btn_final_genesis_recovery_browse_$slug",
            tagSuffix = "recovery_$slug",
            pinWarning = pinTriesWarning(failure?.detail),
        )
        Spacer(Modifier.height(6.dp))
        val ready = inputs.usb.isNotBlank() && !busy && row !is RecoveryRow.Verifying
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CirisButton(
                localizedString("mobile.final_genesis_recovery_read"),
                tag = "btn_final_genesis_verify_go_$slug",
                onClick = {
                    vm.verifyRecovery(holder, inputs.usb, inputs.pin.ifBlank { null }, inputs.module.ifBlank { null }, inputs.slot.ifBlank { null })
                    inputs.pin = ""
                    open = false
                },
                enabled = ready,
            )
            CirisTextButton(
                localizedString("mobile.confirm_cancel"),
                tag = "btn_final_genesis_verify_cancel_$slug",
                onClick = { open = false; inputs.pin = "" },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

// ── 3: the grid ─────────────────────────────────────────────────────────────

@Composable
private fun GridSection(vm: FinalGenesisViewModel, grid: FinalGenesisGrid) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val busy by vm.busy.collectAsState()
    val notes by vm.notes.collectAsState()

    val round = if (grid.roundTwoOpen) 2 else 1
    Text(
        localizedString(if (round == 1) "mobile.final_genesis_round_one" else "mobile.final_genesis_round_two"),
        style = type.title,
        color = t.ink,
        modifier = Modifier.testable("final_genesis_round", round.toString()),
    )
    if (!grid.roundTwoOpen) {
        Text(
            localizedString("mobile.final_genesis_round_two_waits", "who", grid.charterOwedBy.joinToString(", ")),
            style = type.body,
            color = t.dim,
            modifier = Modifier.testable("final_genesis_round_two_waits"),
        )
    }
    Spacer(Modifier.height(10.dp))

    for (holder in grid.holders) {
        HolderCard(vm, grid, holder, notes[holder], busy)
        Spacer(Modifier.height(10.dp))
    }

    if (grid.complete) {
        Text(localizedString("mobile.final_genesis_all_signed"), style = type.body, color = t.ok,
            modifier = Modifier.testable("final_genesis_all_signed"))
        Spacer(Modifier.height(6.dp))
    }
    CirisButton(
        localizedString(if (busy) "mobile.final_genesis_finish_busy" else "mobile.final_genesis_finish"),
        tag = "btn_final_genesis_finish",
        onClick = { vm.finish() },
        enabled = grid.complete && !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    CirisTextButton(
        localizedString("mobile.final_genesis_replan"),
        tag = "btn_final_genesis_replan",
        onClick = { vm.startReplan() },
        enabled = !busy,
    )
}

@Composable
private fun HolderCard(vm: FinalGenesisViewModel, grid: FinalGenesisGrid, holder: String, note: HolderSignNote?, busy: Boolean) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val slug = genesisSlug(holder)
    val inputs = remember(holder) { TokenInputs() }
    val state = grid.holderState(holder)

    CardShell(tag = "final_genesis_holder_$slug") {
        val stateText = when (state) {
            HolderGenesisState.SignNow -> localizedString("mobile.final_genesis_holder_sign_now")
            is HolderGenesisState.Waiting -> localizedString("mobile.final_genesis_holder_waiting", "who", state.on.joinToString(", "))
            HolderGenesisState.Done -> localizedString("mobile.final_genesis_holder_done")
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(holder, style = type.title, color = t.ink, modifier = Modifier.weight(1f))
            Text(
                stateText,
                style = type.label,
                color = t.tone(if (state == HolderGenesisState.Done) Tone.OK else if (state == HolderGenesisState.SignNow) Tone.BRAND else Tone.MUTE),
                modifier = Modifier.testable("final_genesis_holder_state_$slug", stateText),
            )
        }
        Spacer(Modifier.height(6.dp))
        for (item in grid.items) {
            val cell = grid.cell(holder, item)
            val cellText = when (cell) {
                GenesisCell.SIGNED -> localizedString("mobile.final_genesis_cell_signed")
                GenesisCell.SIGN_NOW -> localizedString("mobile.final_genesis_cell_sign_now")
                GenesisCell.WAITING -> localizedString("mobile.final_genesis_cell_waiting")
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val plain = genesisItemLabel(item)?.let { (key, params) -> localizedString(key, params) } ?: item
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        localizedString("mobile.final_genesis_cell_round", mapOf("round" to FinalGenesisItems.round(item).toString(), "item" to plain)),
                        style = type.body,
                        color = t.ink,
                        modifier = Modifier.testable("final_genesis_item_${slug}_${genesisSlug(item)}", plain),
                    )
                    Text(item, style = type.signed, color = t.mute)
                }
                Chip(
                    ChipSpec(
                        label = cellText,
                        tag = "final_genesis_cell_${slug}_${genesisSlug(item)}",
                        tone = when (cell) {
                            GenesisCell.SIGNED -> Tone.OK
                            GenesisCell.SIGN_NOW -> Tone.BRAND
                            GenesisCell.WAITING -> Tone.MUTE
                        },
                    ),
                )
            }
        }
        when (note) {
            HolderSignNote.Signing -> NoteText(localizedString("mobile.final_genesis_note_signing"), Tone.DIM, slug)
            is HolderSignNote.Signed -> NoteText(
                localizedString("mobile.final_genesis_note_signed", "count", note.items.size.toString()), Tone.OK, slug,
            )
            is HolderSignNote.NothingYet -> NoteText(
                if (note.on.isEmpty()) localizedString("mobile.final_genesis_note_nothing_left")
                else localizedString("mobile.final_genesis_note_nothing_yet", "who", note.on.joinToString(", ")),
                Tone.DIM,
                slug,
            )
            is HolderSignNote.Refused -> {
                Spacer(Modifier.height(6.dp))
                RefusalBlock(note.refusal, tag = "final_genesis_note_$slug")
            }
            null -> Unit
        }
        if (state != HolderGenesisState.Done) {
            Spacer(Modifier.height(8.dp))
            TokenForm(
                holder = holder,
                inputs = inputs,
                usbTag = "input_final_genesis_usb_$slug",
                pinTag = "input_final_genesis_pin_$slug",
                slotTag = "input_final_genesis_slot_$slug",
                moduleTag = "input_final_genesis_module_$slug",
                browseTag = "btn_final_genesis_browse_$slug",
                tagSuffix = slug,
                pinWarning = (note as? HolderSignNote.Refused)?.refusal?.detail?.let { pinTriesWarning(it) },
            )
            Spacer(Modifier.height(8.dp))
            val ready = state == HolderGenesisState.SignNow && inputs.usb.isNotBlank() && !busy
            CirisButton(
                localizedString("mobile.final_genesis_sign", "holder", holder),
                tag = "btn_final_genesis_sign_$slug",
                onClick = {
                    vm.sign(holder, inputs.usb, inputs.pin.ifBlank { null }, inputs.module.ifBlank { null }, inputs.slot.ifBlank { null })
                    inputs.pin = ""
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NoteText(text: String, tone: Tone, slug: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, style = CirisTheme.type.body, color = CirisTheme.tokens.tone(tone), modifier = Modifier.testable("final_genesis_note_$slug", text))
}

// ── 4: finished ─────────────────────────────────────────────────────────────

@Composable
private fun FinishedSection(r: FinalGenesisFinishDto) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val clipboard = LocalClipboardManager.current
    Text(localizedString("mobile.final_genesis_done_title"), style = type.title, color = t.ok,
        modifier = Modifier.testable("final_genesis_done_title"))
    Spacer(Modifier.height(6.dp))
    FieldRow(
        label = localizedString("mobile.final_genesis_done_sha"),
        value = r.bundleSha256,
        mono = true,
        gloss = localizedString("mobile.final_genesis_done_sha_gloss"),
        tag = "final_genesis_bundle_sha256",
    )
    CirisTextButton(
        localizedString("mobile.final_genesis_done_copy_sha"),
        tag = "btn_final_genesis_copy_sha",
        onClick = { clipboard.setText(AnnotatedString(r.bundleSha256)) },
    )
    FieldRow(label = localizedString("mobile.final_genesis_done_path"), value = r.bundlePath, mono = true, tag = "final_genesis_bundle_path")
    // The bundle itself, as the node sent it (never re-serialized here). Whether
    // its bytes are the ones the fingerprint names is MEASURED, and said.
    r.bundleText?.let { bundle ->
        var saved by remember(bundle) { mutableStateOf<String?>(null) }
        var saveFailed by remember(bundle) { mutableStateOf(false) }
        val matches = r.bundleMatchesFingerprint == true
        val note = if (matches) {
            localizedString("mobile.final_genesis_done_bundle_matches")
        } else {
            localizedString("mobile.final_genesis_done_bundle_differs", "path", r.bundlePath)
        }
        Text(
            note,
            style = type.body,
            color = if (matches) t.ok else t.dim,
            modifier = Modifier.testable("final_genesis_bundle_match", if (matches) "matches" else "differs"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CirisTextButton(
                localizedString("mobile.final_genesis_done_copy_bundle"),
                tag = "btn_final_genesis_copy_bundle",
                onClick = { clipboard.setText(AnnotatedString(bundle)) },
            )
            // Save where the platform can write a file the person can find
            // (`saveFileCopy`); where it cannot (web), it answers null and the
            // sheet says so — Copy is then the way out.
            CirisTextButton(
                localizedString("mobile.final_genesis_done_save_bundle"),
                tag = "btn_final_genesis_save_bundle",
                onClick = {
                    val at = saveFileCopy("canonical_seed.json", "application/json", bundle.encodeToByteArray())
                    saved = at
                    saveFailed = at == null
                },
            )
        }
        saved?.let {
            Text(localizedString("mobile.final_genesis_done_saved_at", "path", it), style = type.body, color = t.dim,
                modifier = Modifier.testable("final_genesis_bundle_saved", it))
        }
        if (saveFailed) {
            Text(localizedString("mobile.final_genesis_done_save_unavailable"), style = type.body, color = t.danger,
                modifier = Modifier.testable("final_genesis_bundle_save_unavailable"))
        }
    }
    val v = r.verified
    FieldRow(label = localizedString("mobile.final_genesis_done_quorum"), value = v.quorumVerified.toString(), tag = "final_genesis_verified_quorum")
    FieldRow(
        label = localizedString("mobile.final_genesis_done_serve_nodes"),
        value = v.serveNodes.joinToString(", "),
        mono = true,
        tag = "final_genesis_verified_serve_nodes",
    )
    FieldRow(
        label = localizedString("mobile.final_genesis_done_attestations"),
        value = v.attestations.size.toString(),
        gloss = v.attestations.joinToString(", "),
        tag = "final_genesis_verified_attestations",
    )
    FieldRow(label = localizedString("mobile.final_genesis_done_community"), value = v.communityKeyId, mono = true, tag = "final_genesis_verified_community")
    FieldRow(
        label = localizedString("mobile.final_genesis_done_founders"),
        value = v.founders.toString(),
        tag = "final_genesis_verified_founders",
        divider = false,
    )
    Spacer(Modifier.height(8.dp))
    Text(localizedString("mobile.final_genesis_done_next"), style = type.body, color = t.dim)
}
