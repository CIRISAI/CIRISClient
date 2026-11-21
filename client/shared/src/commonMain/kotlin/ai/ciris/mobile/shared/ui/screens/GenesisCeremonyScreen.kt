package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.ceremony.CeremonyCommands
import ai.ciris.mobile.shared.ceremony.CeremonyOutput
import ai.ciris.mobile.shared.ceremony.CeremonyToolState
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.primitives.rememberTextInputDriver
import ai.ciris.mobile.shared.viewmodels.GenesisCeremonyViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE GENESIS CEREMONY (CSD-069): a screen over the offline `genesis-ceremony` tool.
 * It shows where the ceremony directory is, builds each step's command line, runs it,
 * shows what the tool prints, and relays only what each prompt expects. The PIN goes
 * to the tool's own terminal prompt and nowhere else.
 */
@Composable
fun GenesisCeremonyScreen(vm: GenesisCeremonyViewModel, onClose: () -> Unit) {
    val tool by vm.tool.collectAsState()
    val dir by vm.dir.collectAsState()
    val status by vm.status.collectAsState()
    val running by vm.running.collectAsState()
    val preflight by vm.preflight.collectAsState()
    val preflightDetail by vm.preflightDetail.collectAsState()
    LaunchedEffect(Unit) { vm.prepare() }
    val busy = running?.finished == false
    val enabled = tool is CeremonyToolState.Ready && !busy

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testable("screen_genesis_ceremony"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(localizedString("mobile.genesis_title"), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.testableClickable("btn_genesis_close") { if (!busy) onClose() }) {
                Text(localizedString("mobile.genesis_close"))
            }
        }
        Text(localizedString("mobile.genesis_intro"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ToolCard(tool)

        // ── The ceremony directory ─────────────────────────────────────────────
        var dirText by remember { mutableStateOf(dir) }
        Field("input_genesis_dir", "mobile.genesis_dir_label", dirText, !busy) { dirText = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.setDir(dirText) }, enabled = !busy, modifier = Modifier.testableClickable("btn_genesis_open_dir") { vm.setDir(dirText) }) {
                Text(localizedString("mobile.genesis_open_dir"))
            }
            OutlinedButton(onClick = { vm.refresh() }, enabled = dir.isNotBlank(), modifier = Modifier.testableClickable("btn_genesis_refresh") { vm.refresh() }) {
                Text(localizedString("mobile.genesis_refresh"))
            }
        }
        StatusCard(status, dir)
        preflight?.let { Text(localizedString(it), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testable("note_genesis_preflight")) }
        preflightDetail?.let { Mono("code_genesis_preflight_detail", it) }

        running?.let { RunCard(it, vm) }

        // ── Steps (CEREMONY.md §3) ─────────────────────────────────────────────
        StepCard("step_genesis_init", "mobile.genesis_step_init", "mobile.genesis_step_init_desc") {
            var t0 by remember { mutableStateOf("") }
            Field("input_genesis_t0", "mobile.genesis_t0_label", t0, enabled) { t0 = it }
            RunButton("btn_genesis_run_init", enabled) { vm.runInit(t0) }
        }

        StepCard("step_genesis_holder_prep", "mobile.genesis_step_prep", "mobile.genesis_step_prep_desc") {
            var name by remember { mutableStateOf("A1") }
            var seed by remember { mutableStateOf("") }
            var keygen by remember { mutableStateOf(false) }
            var inter by remember { mutableStateOf("") }
            var bseed by remember { mutableStateOf("") }
            var bkeygen by remember { mutableStateOf(false) }
            Field("input_genesis_prep_name", "mobile.genesis_holder_name", name, enabled) { name = it }
            Field("input_genesis_prep_mldsa_seed", "mobile.genesis_holder_seed", seed, enabled) { seed = it }
            Toggle("opt_genesis_prep_keygen", "mobile.genesis_keygen", keygen, enabled) { keygen = it }
            Field("input_genesis_prep_intermediate", "mobile.genesis_intermediate", inter, enabled, single = false) { inter = it }
            Field("input_genesis_prep_backup_mldsa_seed", "mobile.genesis_backup_seed", bseed, enabled) { bseed = it }
            Toggle("opt_genesis_prep_backup_keygen", "mobile.genesis_backup_keygen", bkeygen, enabled) { bkeygen = it }
            RunButton("btn_genesis_run_holder_prep", enabled) {
                vm.runHolderPrep(
                    CeremonyCommands.HolderPrep(
                        dir = dir, name = name.trim(), mldsaSeed = seed.trim(), keygen = keygen,
                        intermediates = inter.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        backupMldsaSeed = bseed.trim(), backupKeygen = bkeygen,
                    ),
                )
            }
        }

        StepCard("step_genesis_seat", "mobile.genesis_step_seat", "mobile.genesis_step_seat_desc") {
            var name by remember { mutableStateOf("canonical-1") }
            var seed by remember { mutableStateOf("") }
            var transport by remember { mutableStateOf("") }
            var dial by remember { mutableStateOf(CeremonyCommands.CANONICAL_1_DIAL.joinToString("\n")) }
            Field("input_genesis_seat_name", "mobile.genesis_seat_name", name, enabled) { name = it }
            Field("input_genesis_seat_seed", "mobile.genesis_seat_seed", seed, enabled) { seed = it }
            Field("input_genesis_seat_transport", "mobile.genesis_seat_transport", transport, enabled) { transport = it }
            Field("input_genesis_seat_dial", "mobile.genesis_seat_dial", dial, enabled, single = false) { dial = it }
            RunButton("btn_genesis_run_seat", enabled) { vm.runSeat(name, seed, transport, dial.lines()) }
        }

        StepCard("step_genesis_terms", "mobile.genesis_step_terms", "mobile.genesis_step_terms_desc") {
            RunButton("btn_genesis_run_terms", enabled) { vm.runTerms() }
        }

        StepCard("step_genesis_holder_sign", "mobile.genesis_step_sign", "mobile.genesis_step_sign_desc") {
            var name by remember { mutableStateOf("A1") }
            var seed by remember { mutableStateOf("") }
            var sealed by remember { mutableStateOf("") }
            var module by remember { mutableStateOf("") }
            Field("input_genesis_sign_name", "mobile.genesis_holder_name", name, enabled) { name = it }
            // The sealed halt goes on the holder's OWN medium: prefilled as a new folder beside the seed.
            Field("input_genesis_sign_mldsa_seed", "mobile.genesis_holder_seed", seed, enabled) {
                val before = sealedBeside(seed)
                seed = it
                if (sealed.isBlank() || sealed == before) sealed = sealedBeside(it)
            }
            Field("input_genesis_sign_sealed_dir", "mobile.genesis_sealed_dir", sealed, enabled) { sealed = it }
            Field("input_genesis_sign_module", "mobile.genesis_module", module, enabled) { module = it }
            RunButton("btn_genesis_run_holder_sign", enabled) { vm.runHolderSign(name, seed, sealed, module.takeIf { it.isNotBlank() }) }
        }

        StepCard("step_genesis_check", "mobile.genesis_step_check", "mobile.genesis_step_check_desc") {
            var halts by remember { mutableStateOf("") }
            Field("input_genesis_halts", "mobile.genesis_halts", halts, enabled, single = false) { halts = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RunButton("btn_genesis_run_check", enabled, "mobile.genesis_run_check") { vm.runCheck(halts.lines().filter { it.isNotBlank() }) }
                RunButton("btn_genesis_run_final_gate", enabled, "mobile.genesis_run_final_gate") { vm.runFinalGate(halts.lines().filter { it.isNotBlank() }) }
            }
        }
        Text(localizedString("mobile.genesis_after"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testable("note_genesis_after"))
    }
}

/** `<the seed's folder>/sealed`, or empty when there is no seed path yet. */
internal fun sealedBeside(seed: String): String {
    val s = seed.trim()
    val cut = s.lastIndexOf('/')
    return if (cut <= 0) "" else s.substring(0, cut) + "/sealed"
}

@Composable
private fun ToolCard(tool: CeremonyToolState?) {
    Card(Modifier.fillMaxWidth().testable("card_genesis_tool")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (tool) {
                null -> Text(localizedString("mobile.genesis_tool_checking"), modifier = Modifier.testable("txt_genesis_tool_checking"))
                is CeremonyToolState.Ready -> {
                    Text(localizedString("mobile.genesis_tool_ready"), fontWeight = FontWeight.Bold, modifier = Modifier.testable("txt_genesis_tool_ready"))
                    Mono("code_genesis_tool_version", localizedString("mobile.genesis_tool_version", "version", tool.info.version ?: "—"))
                    Mono("code_genesis_tool_commit", localizedString("mobile.genesis_tool_commit", "commit", tool.info.commit.ifBlank { "—" }))
                    Mono("code_genesis_tool_sha256", localizedString("mobile.genesis_tool_sha256", "sha256", tool.info.sha256))
                }
                CeremonyToolState.NotBundled -> Text(localizedString("mobile.genesis_tool_not_bundled"), modifier = Modifier.testable("note_genesis_tool_not_bundled"))
                is CeremonyToolState.Unsupported -> Text(localizedString("mobile.genesis_tool_unsupported", "detail", tool.detail), modifier = Modifier.testable("note_genesis_tool_unsupported"))
                is CeremonyToolState.Refused -> {
                    Text(localizedString(tool.key), color = MaterialTheme.colorScheme.error, modifier = Modifier.testable("note_genesis_tool_refused"))
                    Mono("code_genesis_tool_refused", tool.detail)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: ai.ciris.mobile.shared.ceremony.CeremonyStatus, dir: String) {
    Card(Modifier.fillMaxWidth().testable("card_genesis_status")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (dir.isBlank() || !status.initialised) {
                Text(localizedString(if (dir.isBlank()) "mobile.genesis_status_no_dir" else "mobile.genesis_status_not_initialised"), modifier = Modifier.testable("txt_genesis_status"))
                return@Column
            }
            Text(
                localizedString("mobile.genesis_status_initialised", mapOf("family" to (status.familyKeyId ?: "—"), "t0" to (status.t0Ms?.toString() ?: "—"))),
                modifier = Modifier.testable("txt_genesis_status"),
            )
            status.holders.forEach { h ->
                val state = when {
                    h.signed -> "mobile.genesis_holder_signed"
                    h.prepared -> "mobile.genesis_holder_prepared"
                    else -> "mobile.genesis_holder_started"
                }
                Text(
                    localizedString("mobile.genesis_holder_row", mapOf("name" to h.name, "state" to localizedString(state), "serial" to (h.serial ?: "—"))),
                    fontSize = 13.sp, modifier = Modifier.testable("row_genesis_holder_${h.name}"),
                )
            }
            status.seats.forEach { s ->
                Text(
                    localizedString(if (s.written) "mobile.genesis_seat_written" else "mobile.genesis_seat_started", "name", s.name),
                    fontSize = 13.sp, modifier = Modifier.testable("row_genesis_seat_${s.name}"),
                )
            }
            if (status.termsFixed) {
                Mono("code_genesis_bundle", localizedString("mobile.genesis_terms_fixed", mapOf("bundle" to (status.bundleObjectId ?: "—"), "voices" to "${status.voices}", "holders" to "${status.termsHolders.size}")))
            }
        }
    }
}

@Composable
private fun RunCard(r: GenesisCeremonyViewModel.Running, vm: GenesisCeremonyViewModel) {
    Card(Modifier.fillMaxWidth().testable("card_genesis_run")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localizedString("mobile.genesis_running", "step", r.step.subcommand), fontWeight = FontWeight.Bold)
            Mono("code_genesis_argv", r.line)
            // The touch banner: large and unmissable, n of 3.
            if (!r.finished) r.touch?.let { t ->
                Surface(color = Color(0xFFFFD54F), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().testable("state_genesis_touch")) {
                    Text(
                        localizedString("mobile.genesis_touch_now", mapOf("n" to "${t.n}", "of" to "${t.of}")),
                        fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.Black,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
            if (r.otpTyped) Text(localizedString("mobile.genesis_otp_notice"), color = MaterialTheme.colorScheme.error, modifier = Modifier.testable("note_genesis_otp"))
            SelectionContainer {
                Text(
                    r.transcript.ifEmpty { "…" },
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp).testable("code_genesis_output"),
                )
            }
            when (r.prompt) {
                CeremonyOutput.Prompt.CONFIRM -> AnswerRow(r, vm, "mobile.genesis_prompt_confirm")
                CeremonyOutput.Prompt.SWAP_TOKEN -> {
                    Text(localizedString("mobile.genesis_prompt_swap"), fontWeight = FontWeight.Bold, modifier = Modifier.testable("txt_genesis_prompt"))
                    Button(onClick = { vm.swapped() }, modifier = Modifier.testableClickable("btn_genesis_swapped") { vm.swapped() }) {
                        Text(localizedString("mobile.genesis_swapped"))
                    }
                }
                CeremonyOutput.Prompt.PIN -> GenesisCeremonyPinRow(vm)
                null -> Unit
            }
            if (r.inputRejected) Text(localizedString("mobile.genesis_input_rejected"), color = MaterialTheme.colorScheme.error, modifier = Modifier.testable("note_genesis_input_rejected"))
            if (!r.finished) {
                OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.testableClickable("btn_genesis_cancel") { vm.cancel() }) {
                    Text(localizedString("mobile.genesis_cancel"))
                }
            } else {
                Text(localizedString("mobile.genesis_exit", "code", "${r.exitCode}"), modifier = Modifier.testable("txt_genesis_exit"))
                if (r.finalGatePassed) Text(localizedString("mobile.genesis_final_gate_passed"), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.testable("txt_genesis_verdict"))
                r.error?.let { e ->
                    r.refusal?.takeIf { it != CeremonyOutput.Refusal.OTHER }?.let {
                        Text(localizedString(it.key), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, modifier = Modifier.testable("note_genesis_refusal"))
                    }
                    Mono("code_genesis_error", e)
                }
                TextButton(onClick = { vm.dismissRun() }, modifier = Modifier.testableClickable("btn_genesis_dismiss_run") { vm.dismissRun() }) {
                    Text(localizedString("mobile.genesis_dismiss_run"))
                }
            }
        }
    }
}

@Composable
private fun AnswerRow(r: GenesisCeremonyViewModel.Running, vm: GenesisCeremonyViewModel, promptKey: String) {
    var answer by remember(r.transcript.length / 4096) { mutableStateOf("") }
    Text(localizedString(promptKey), fontWeight = FontWeight.Bold, modifier = Modifier.testable("txt_genesis_prompt"))
    Field("input_genesis_answer", "mobile.genesis_answer_label", answer, true) { answer = it }
    Button(onClick = { vm.answerTyped(answer); answer = "" }, modifier = Modifier.testableClickable("btn_genesis_send_answer") { vm.answerTyped(answer); answer = "" }) {
        Text(localizedString("mobile.genesis_send_answer"))
    }
}

@Composable
private fun StepCard(tag: String, titleKey: String, descKey: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().testable(tag)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(localizedString(titleKey), fontWeight = FontWeight.Bold)
            Text(localizedString(descKey), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun Field(tag: String, labelKey: String, value: String, enabled: Boolean, single: Boolean = true, onChange: (String) -> Unit) {
    rememberTextInputDriver(tag, value, enabled) { onChange(it) }
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled, singleLine = single, minLines = if (single) 1 else 3,
        label = { Text(localizedString(labelKey)) },
        modifier = Modifier.fillMaxWidth().testable(tag, value),
    )
}

@Composable
private fun Toggle(tag: String, labelKey: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testableClickable(tag) { if (enabled) onChange(!value) },
    ) {
        Checkbox(checked = value, onCheckedChange = { onChange(it) }, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Text(localizedString(labelKey), fontSize = 13.sp)
    }
}

@Composable
private fun RunButton(tag: String, enabled: Boolean, labelKey: String = "mobile.genesis_run", onRun: () -> Unit) {
    Button(onClick = onRun, enabled = enabled, modifier = Modifier.testableClickable(tag) { if (enabled) onRun() }) {
        Text(localizedString(labelKey))
    }
}

@Composable
private fun Mono(tag: String, text: String) {
    SelectionContainer {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.testable(tag))
    }
}
