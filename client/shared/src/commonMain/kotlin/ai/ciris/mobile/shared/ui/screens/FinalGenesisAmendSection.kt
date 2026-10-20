package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.FinalGenesisAmendStatusDto
import ai.ciris.mobile.shared.models.federation.TransitFetch
import ai.ciris.mobile.shared.models.federation.TransitRows
import ai.ciris.mobile.shared.models.federation.pinTriesWarning
import ai.ciris.mobile.shared.platform.FilePickerDialog
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextField
import ai.ciris.mobile.shared.ui.primitives.FieldRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.ui.theme.tone
import ai.ciris.mobile.shared.viewmodels.AmendPhase
import ai.ciris.mobile.shared.viewmodels.FinalGenesisViewModel
import ai.ciris.mobile.shared.viewmodels.HolderSignNote
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * **RE-SCRUB** — the 2-of-3 trust-root AMEND, the amend mode of
 * [FinalGenesisSheet] (CC 5.3.4; the steward's ruling on CIRISConstitution#172;
 * CIRISServer#798). PLAN: mode `amend`, the canonical's transit row — fetched
 * from the canonical (expected) or pasted/attached — and the canonicals seeded,
 * "2 of 3 needed". SIGN: any holder, once, until complete; no recovery keys, no
 * second round. FINISH: the final-genesis finish section, version 4.
 */

// ── re-scrub: the 2-of-3 amend (CC 5.3.4; CIRISServer#798) ───────────────────

@Composable
internal fun AmendBody(vm: FinalGenesisViewModel, phase: AmendPhase) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val replanning by vm.amendReplanning.collectAsState()
    when (phase) {
        AmendPhase.Probing -> StateBlock(ListState.Loading, tag = "final_genesis_amend_loading")
        AmendPhase.TooOld -> StateBlock(
            ListState.Error(
                title = localizedString("mobile.final_genesis_amend_too_old"),
                body = localizedString("mobile.final_genesis_amend_too_old_body"),
            ),
            tag = "final_genesis_amend_too_old",
        )
        is AmendPhase.Unavailable -> {
            RefusalBlock(phase.refusal, tag = "final_genesis_amend_unavailable")
            Spacer(Modifier.height(8.dp))
            CirisTextButton(localizedString("mobile.final_genesis_retry"), tag = "btn_final_genesis_amend_retry", onClick = { vm.openAmend() })
        }
        AmendPhase.NotPlanned -> AmendPlanSection(vm)
        is AmendPhase.Planned -> if (replanning) {
            AmendPlanSection(vm)
            CirisTextButton(
                localizedString("mobile.final_genesis_replan_cancel"),
                tag = "btn_final_genesis_amend_replan_cancel",
                onClick = { vm.cancelAmendReplan() },
            )
        } else {
            AmendSignSection(vm, phase.status)
        }
        is AmendPhase.Finished -> FinishedSection(phase.result)
    }
    Spacer(Modifier.height(4.dp))
    Text(localizedString("mobile.final_genesis_amend_limits"), style = type.label, color = t.mute)
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun AmendPlanSection(vm: FinalGenesisViewModel) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val busy by vm.busy.collectAsState()
    val rowsText by vm.amendRowsText.collectAsState()
    val canonicalUrl by vm.canonicalUrl.collectAsState()
    val fetch by vm.transitFetch.collectAsState()
    var pickFile by remember { mutableStateOf(false) }
    var pickRefused by remember { mutableStateOf<ai.ciris.mobile.shared.platform.PickTooLarge?>(null) }
    var attachFailed by remember { mutableStateOf(false) }

    FieldRow(
        label = localizedString("mobile.final_genesis_amend_mode_label"),
        value = localizedString("mobile.final_genesis_amend_mode_value"),
        tag = "final_genesis_amend_mode",
    )
    Spacer(Modifier.height(10.dp))

    // 1. The expected path: fetch the canonical's signed transit row from it.
    SectionTitle(localizedString("mobile.final_genesis_amend_fetch_title"))
    Text(localizedString("mobile.final_genesis_amend_fetch_desc"), style = type.body, color = t.dim)
    Spacer(Modifier.height(6.dp))
    CirisTextField(
        tag = "input_final_genesis_amend_canonical_url",
        value = canonicalUrl,
        onValueChange = { vm.setCanonicalUrl(it) },
        placeholder = localizedString("mobile.final_genesis_amend_fetch_hint"),
        mono = true,
    )
    CirisTextButton(
        localizedString(if (fetch == TransitFetch.Fetching) "mobile.final_genesis_amend_fetching" else "mobile.final_genesis_amend_fetch"),
        tag = "btn_final_genesis_amend_fetch",
        onClick = { vm.fetchTransitRow() },
        enabled = canonicalUrl.isNotBlank() && fetch != TransitFetch.Fetching && !busy,
    )
    when (val f = fetch) {
        is TransitFetch.Fetched -> {
            FieldRow(
                label = localizedString("mobile.final_genesis_amend_fetched_key"),
                value = f.row.keyId ?: "?",
                mono = true,
                tone = Tone.OK,
                tag = "final_genesis_amend_fetched_key",
            )
            FieldRow(
                label = localizedString("mobile.final_genesis_amend_fetched_pair"),
                value = f.row.pairFingerprint ?: localizedString("mobile.final_genesis_amend_fetched_no_pair"),
                mono = true,
                tone = if (f.row.pairFingerprint != null) Tone.INK else Tone.DANGER,
                gloss = localizedString("mobile.final_genesis_amend_fetched_pair_gloss"),
                tag = "final_genesis_amend_fetched_pair",
            )
            CirisTextButton(localizedString("mobile.final_genesis_amend_fetch_clear"), tag = "btn_final_genesis_amend_fetch_clear", onClick = { vm.clearTransitFetch() })
        }
        TransitFetch.TooOld -> StateBlock(
            ListState.Error(localizedString("mobile.final_genesis_amend_fetch_too_old")),
            tag = "final_genesis_amend_fetch_too_old",
        )
        is TransitFetch.Refused -> RefusalBlock(f.refusal, tag = "final_genesis_amend_fetch_error")
        TransitFetch.Idle, TransitFetch.Fetching -> Unit
    }

    // 2. The alternative: paste or attach the canonical's transit_destination row.
    Spacer(Modifier.height(12.dp))
    SectionTitle(localizedString("mobile.final_genesis_amend_rows_title"))
    Text(localizedString("mobile.final_genesis_amend_rows_desc"), style = type.body, color = t.dim)
    Spacer(Modifier.height(6.dp))
    CirisTextField(
        tag = "input_final_genesis_amend_rows",
        value = rowsText,
        onValueChange = { vm.setAmendRowsText(it) },
        placeholder = localizedString("mobile.final_genesis_amend_rows_hint"),
        singleLine = false,
        mono = true,
    )
    CirisTextButton(
        localizedString("mobile.final_genesis_amend_rows_attach"),
        tag = "btn_final_genesis_amend_rows_attach",
        onClick = { pickFile = true },
    )
    pickRefused?.let { r ->
        ai.ciris.mobile.shared.ui.components.PickTooLargeNotice(r, tag = "final_genesis_amend_pick_too_large", onDismiss = { pickRefused = null })
    }
    if (attachFailed) {
        Text(
            localizedString("mobile.final_genesis_amend_rows_invalid"),
            style = type.body,
            color = t.danger,
            modifier = Modifier.testable("final_genesis_amend_attach_failed"),
        )
    }
    FilePickerDialog(
        show = pickFile,
        mimeTypes = listOf("application/json"),
        onFilePicked = { file ->
            pickFile = false
            attachFailed = try {
                vm.attachAmendRows(Base64.decode(file.dataBase64).decodeToString())
                false
            } catch (_: Exception) {
                true
            }
        },
        onDismiss = { pickFile = false },
        onTooLarge = { r -> pickFile = false; pickRefused = r },
    )
    when (val rows = vm.amendRows()) {
        TransitRows.Empty -> if (fetch !is TransitFetch.Fetched) Text(
            localizedString("mobile.final_genesis_amend_rows_none"),
            style = type.body,
            color = t.dim,
            modifier = Modifier.testable("final_genesis_amend_rows_state", "empty"),
        )
        is TransitRows.Rows -> Text(
            localizedString("mobile.final_genesis_amend_rows_read", mapOf("count" to rows.rows.size.toString(), "keys" to rows.subjects.joinToString(", "))),
            style = type.body,
            color = t.ok,
            modifier = Modifier.testable("final_genesis_amend_rows_state", "rows"),
        )
        TransitRows.Invalid -> Text(
            localizedString("mobile.final_genesis_amend_rows_invalid"),
            style = type.body,
            color = t.danger,
            modifier = Modifier.testable("final_genesis_amend_rows_state", "invalid"),
        )
    }
    Spacer(Modifier.height(12.dp))
    val canPlan = !busy && vm.amendRows() != TransitRows.Invalid && fetch != TransitFetch.Fetching
    CirisButton(
        localizedString(if (busy) "mobile.final_genesis_plan_busy" else "mobile.final_genesis_amend_plan"),
        tag = "btn_final_genesis_amend_plan",
        onClick = { vm.planAmend() },
        enabled = canPlan,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AmendSignSection(vm: FinalGenesisViewModel, status: FinalGenesisAmendStatusDto) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val busy by vm.busy.collectAsState()
    val notes by vm.amendNotes.collectAsState()
    val holders = vm.amendHolders()

    FieldRow(
        label = localizedString("mobile.final_genesis_amend_mode_label"),
        value = status.mode.ifBlank { "amend" },
        mono = true,
        tag = "final_genesis_amend_mode",
    )
    FieldRow(
        label = localizedString("mobile.final_genesis_amend_seeded"),
        value = status.serveTransportDestinations.joinToString(", "),
        mono = true,
        tag = "final_genesis_amend_seeded",
    )
    FieldRow(
        label = localizedString("mobile.final_genesis_amend_needed_label"),
        value = if (holders.isNotEmpty()) {
            localizedString("mobile.final_genesis_amend_needed", mapOf("needed" to status.needed.toString(), "holders" to holders.size.toString()))
        } else {
            localizedString("mobile.final_genesis_amend_needed_only", "needed", status.needed.toString())
        },
        tag = "final_genesis_amend_needed",
    )
    FieldRow(
        label = localizedString("mobile.final_genesis_amend_status_label"),
        value = status.status,
        mono = true,
        tone = if (status.complete) Tone.OK else Tone.INK,
        tag = "final_genesis_amend_status",
        divider = false,
    )
    Spacer(Modifier.height(10.dp))
    if (holders.isEmpty()) {
        AmendHolderCard(vm, null, status, notes, busy)
    } else {
        for (h in holders) {
            AmendHolderCard(vm, h, status, notes, busy)
            Spacer(Modifier.height(10.dp))
        }
    }
    if (status.complete) {
        Text(localizedString("mobile.final_genesis_amend_complete"), style = type.body, color = t.ok, modifier = Modifier.testable("final_genesis_amend_complete"))
        Spacer(Modifier.height(6.dp))
    }
    CirisButton(
        localizedString(if (busy) "mobile.final_genesis_finish_busy" else "mobile.final_genesis_finish"),
        tag = "btn_final_genesis_amend_finish",
        onClick = { vm.finishAmend() },
        enabled = status.complete && !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    CirisTextButton(
        localizedString("mobile.final_genesis_replan"),
        tag = "btn_final_genesis_amend_replan",
        onClick = { vm.startAmendReplan() },
        enabled = !busy,
    )
}

/** One holder's single authorization. [fixed] null: the roster is unknown, so the key id is typed. */
@Composable
private fun AmendHolderCard(
    vm: FinalGenesisViewModel,
    fixed: String?,
    status: FinalGenesisAmendStatusDto,
    notes: Map<String, HolderSignNote>,
    busy: Boolean,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    var typed by remember { mutableStateOf("") }
    val holder = fixed ?: typed.trim()
    val slug = if (fixed != null) genesisSlug(fixed) else "holder"
    val inputs = remember(slug) { TokenInputs() }
    val signed = holder.isNotEmpty() && holder in status.signed
    CardShell(tag = "final_genesis_amend_holder_$slug") {
        val stateText = localizedString(
            when {
                signed -> "mobile.final_genesis_amend_holder_signed"
                status.complete -> "mobile.final_genesis_amend_holder_not_needed"
                else -> "mobile.final_genesis_amend_holder_may_sign"
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fixed ?: localizedString("mobile.final_genesis_amend_holder_any"), style = type.title, color = t.ink, modifier = Modifier.weight(1f))
            Text(
                stateText,
                style = type.label,
                color = t.tone(if (signed) Tone.OK else if (status.complete) Tone.MUTE else Tone.BRAND),
                modifier = Modifier.testable("final_genesis_amend_holder_state_$slug", stateText),
            )
        }
        if (fixed == null) {
            Spacer(Modifier.height(6.dp))
            CirisTextField(
                tag = "input_final_genesis_amend_holder",
                value = typed,
                onValueChange = { typed = it },
                placeholder = localizedString("mobile.final_genesis_amend_holder_hint"),
                mono = true,
            )
        }
        when (val note = notes[holder]) {
            HolderSignNote.Signing -> NoteText(localizedString("mobile.final_genesis_note_signing"), Tone.DIM, "amend_$slug")
            is HolderSignNote.Signed -> NoteText(localizedString("mobile.final_genesis_amend_note_signed"), Tone.OK, "amend_$slug")
            is HolderSignNote.Refused -> {
                Spacer(Modifier.height(6.dp))
                RefusalBlock(note.refusal, tag = "final_genesis_note_amend_$slug")
            }
            else -> Unit
        }
        if (!signed && !status.complete) {
            Spacer(Modifier.height(8.dp))
            TokenForm(
                holder = holder.ifEmpty { "?" },
                inputs = inputs,
                usbTag = "input_final_genesis_amend_usb_$slug",
                pinTag = "input_final_genesis_amend_pin_$slug",
                slotTag = "input_final_genesis_amend_slot_$slug",
                moduleTag = "input_final_genesis_amend_module_$slug",
                browseTag = "btn_final_genesis_amend_browse_$slug",
                tagSuffix = "amend_$slug",
                pinWarning = (notes[holder] as? HolderSignNote.Refused)?.refusal?.detail?.let { pinTriesWarning(it) },
            )
            Spacer(Modifier.height(8.dp))
            val ready = holder.isNotEmpty() && inputs.usb.isNotBlank() && !busy
            CirisButton(
                localizedString("mobile.final_genesis_amend_sign", "holder", holder.ifEmpty { "?" }),
                tag = "btn_final_genesis_amend_sign_$slug",
                onClick = {
                    vm.signAmend(holder, inputs.usb, inputs.pin.ifBlank { null }, inputs.module.ifBlank { null }, inputs.slot.ifBlank { null })
                    inputs.pin = ""
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
