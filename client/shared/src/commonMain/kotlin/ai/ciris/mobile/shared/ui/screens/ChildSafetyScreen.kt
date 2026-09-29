package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.safety.AgeBand
import ai.ciris.mobile.shared.models.safety.WatchlistClass
import ai.ciris.mobile.shared.models.safety.WatchlistMode
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.ui.primitives.CirisTextField
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.viewmodels.AgeRestate
import ai.ciris.mobile.shared.viewmodels.SafetyViewModel
import ai.ciris.mobile.shared.viewmodels.WatchlistRead
import ai.ciris.mobile.shared.viewmodels.WatchlistWriteRefusal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar

/**
 * **Child-safety / watchlist card** (CC 4.5.7, CSD-066).
 *
 * Two things, all driving the local node's `/v1/safety/` routes:
 *  1. **Protective posture** — `GET /v1/safety/status/{key_id}` for the PERSON
 *     (the bound owner's fed-ID; a node's signer key has no age), with the
 *     honest framing (self-declared is unfalsifiable; misdeclaration routes to
 *     adjudication, never slashing). The band can be (re)stated from here via
 *     `POST /v1/self/age`, the owner-session route: the node signs as the owner.
 *     The node serves the status read to any caller; this card asks about the
 *     resolved subject only and offers no key field.
 *  2. **Per-group watchlist** — for a group you hold `moderate` over, opt into a
 *     content watchlist. `GET /v1/safety/watchlist/{group}` lists current
 *     enables; `POST /v1/safety/watchlist` enables/disables one, behind a
 *     three-fact confirm. The node takes that POST only in a request signed by
 *     the moderate-holder's key, which this app does not produce: its 401 is
 *     rendered as that fact, not as a wrong password, and a 403 as "not a holder".
 *
 * **Load-bearing honest framing (prominent, kept TRUE from the server's wire):**
 *  - default OFF, opt-in, **per-group, NEVER global** (no fabric-wide watchlist).
 *  - **we do not scan private (self/family) content** — detection runs only at
 *    the share/publish seam.
 *  - CSAM hashes are operator-provisioned (never shipped); the NCMEC report is
 *    the operator's duty, not the fabric's.
 *
 * Four watchlist states, four renderings: never asked, loading, the list or the
 * true empty, and a failed read — which is NOT a report that nothing is watched.
 *
 * The app holds NO keys: every action is a localhost call; the node signs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildSafetyScreen(
    viewModel: SafetyViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    // Which confirm is open, if any: an enable, a disable, or a band restate.
    var confirmEnable by remember { mutableStateOf<Boolean?>(null) }
    var confirmBand by remember { mutableStateOf<AgeBand?>(null) }

    LaunchedEffect(Unit) { viewModel.probeIdentityAndStatus() }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("mobile.child_safety_title")) },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(onClick = onBack, modifier = Modifier.testableClickable("btn_child_safety_back") { onBack() }) {
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
                .testableVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── The load-bearing honesty banner (always visible, never buried) ──
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().testable("banner_child_safety_honesty"),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(CIRISIcons.shield, localizedString("mobile.child_safety_honesty_title"))
                    Text(localizedString("mobile.child_safety_honesty_pergroup"),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(6.dp))
                    Text(localizedString("mobile.child_safety_honesty_noprivate"),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(6.dp))
                    Text(localizedString("mobile.child_safety_honesty_hashes"),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            // ── Protective posture (age assurance + honesty) ──
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(CIRISIcons.person, localizedString("mobile.child_safety_posture_section"))
                    PostureSubject(state.subjectKeyId, state.subjectIsOwner, state.identityProbed)
                    Spacer(Modifier.height(8.dp))
                    when {
                        // Whose posture this is could not be resolved: its own
                        // state, never the node key's posture drawn as the owner's.
                        state.subjectFailure != null -> Column {
                            Text(
                                localizedString("mobile.child_safety_subject_unresolved"),
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testable("txt_posture_subject_unresolved"),
                            )
                            ReadFailureBlock(
                                failure = state.subjectFailure!!,
                                tagPrefix = "posture_subject",
                                inline = true,
                            )
                        }
                        state.statusLoading -> CircularProgressIndicator(
                            Modifier.width(20.dp).height(20.dp).testable("spinner_posture"),
                            strokeWidth = 2.dp,
                        )
                        state.statusFailure != null -> ReadFailureBlock(
                            failure = state.statusFailure!!,
                            tagPrefix = "posture",
                            notOnThisNode = localizedString("mobile.child_safety_posture_not_on_this_node"),
                            inline = true,
                        )
                        state.subjectKeyId != null -> {
                            val band = state.ageAssurance?.band
                            val posture = when (band) {
                                AgeBand.ADULT -> localizedString("mobile.child_safety_posture_adult")
                                AgeBand.MINOR -> localizedString("mobile.child_safety_posture_minor")
                                null -> localizedString("mobile.child_safety_posture_unknown")
                            }
                            Text(posture, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testable("txt_posture_band", band?.name?.lowercase() ?: "unknown"))
                            state.statusHonesty?.let {
                                if (it.selfLevelUnfalsifiable) {
                                    Text(localizedString("mobile.child_safety_posture_self_unfalsifiable"),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 6.dp).testable("txt_posture_self_unfalsifiable"))
                                }
                            }
                        }
                        else -> Text(localizedString("mobile.child_safety_posture_no_identity"),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testable("txt_posture_no_identity"))
                    }

                    // (Re)state the band — the owner-session route, for the owner.
                    Spacer(Modifier.height(12.dp))
                    Text(localizedString("mobile.child_safety_restate_title"),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.subjectIsOwner) {
                        val working = state.ageRestate is AgeRestate.Working
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            val stateAdult = { if (!working) confirmBand = AgeBand.ADULT }
                            OutlinedButton(
                                onClick = stateAdult,
                                enabled = !working,
                                modifier = Modifier.testableClickable("btn_age_state_adult") { stateAdult() },
                            ) { Text(localizedString("mobile.age_range_adult")) }
                            val stateMinor = { if (!working) confirmBand = AgeBand.MINOR }
                            OutlinedButton(
                                onClick = stateMinor,
                                enabled = !working,
                                modifier = Modifier.testableClickable("btn_age_state_minor") { stateMinor() },
                            ) { Text(localizedString("mobile.age_range_minor")) }
                        }
                        when (val r = state.ageRestate) {
                            AgeRestate.Idle -> Unit
                            is AgeRestate.Working -> StateBlock(ListState.Loading, tag = "spinner_age_state", inline = true)
                            is AgeRestate.Recorded -> Text(
                                localizedString("mobile.age_range_saved"),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp).testable("txt_age_state_recorded"),
                            )
                            is AgeRestate.Failed -> StateBlock(
                                ListState.Error(
                                    title = localizedString("mobile.child_safety_restate_failed"),
                                    detail = r.detail,
                                ),
                                tag = "txt_age_state_failed",
                                inline = true,
                            )
                        }
                    } else {
                        Text(localizedString("mobile.child_safety_restate_needs_owner"),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp).testable("txt_age_state_needs_owner"))
                    }
                }
            }

            // ── Per-group watchlist (opt-in) ──
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionHeader(CIRISIcons.lock, localizedString("mobile.child_safety_watchlist_section"))
                    Text(localizedString("mobile.child_safety_watchlist_intro"),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp))

                    CirisTextField(
                        tag = "input_watchlist_group",
                        value = state.watchlistGroupKeyId,
                        onValueChange = viewModel::setWatchlistGroupKeyId,
                        placeholder = localizedString("mobile.child_safety_group_label"),
                        mono = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    CirisTextField(
                        tag = "input_watchlist_id",
                        value = state.watchlistId,
                        onValueChange = viewModel::setWatchlistId,
                        placeholder = localizedString("mobile.child_safety_watchlist_id_label"),
                    )

                    // Class (CSAM vs other-content).
                    Spacer(Modifier.height(8.dp))
                    Text(localizedString("mobile.child_safety_class_label"),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChoiceRow(
                        selected = state.watchlistClass == WatchlistClass.OTHER_CONTENT,
                        label = localizedString("mobile.child_safety_class_other"),
                        tag = "class_other",
                    ) { viewModel.setWatchlistClass(WatchlistClass.OTHER_CONTENT) }
                    ChoiceRow(
                        selected = state.watchlistClass == WatchlistClass.CSAM,
                        label = localizedString("mobile.child_safety_class_csam"),
                        tag = "class_csam",
                    ) { viewModel.setWatchlistClass(WatchlistClass.CSAM) }

                    // Mode (alert-only vs enforce).
                    Spacer(Modifier.height(8.dp))
                    Text(localizedString("mobile.child_safety_mode_label"),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChoiceRow(
                        selected = state.watchlistMode == WatchlistMode.ALERT_ONLY,
                        label = localizedString("mobile.child_safety_mode_alert"),
                        tag = "mode_alert",
                    ) { viewModel.setWatchlistMode(WatchlistMode.ALERT_ONLY) }
                    ChoiceRow(
                        selected = state.watchlistMode == WatchlistMode.ENFORCE,
                        label = localizedString("mobile.child_safety_mode_enforce"),
                        tag = "mode_enforce",
                    ) { viewModel.setWatchlistMode(WatchlistMode.ENFORCE) }

                    Spacer(Modifier.height(12.dp))
                    val canWrite = !state.watchlistMutating
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val onEnable = { if (canWrite) confirmEnable = true }
                        Button(
                            onClick = onEnable,
                            enabled = canWrite,
                            modifier = Modifier.testableClickable("btn_watchlist_enable") { onEnable() },
                        ) {
                            if (state.watchlistMutating) {
                                CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(localizedString("mobile.child_safety_enable"))
                        }
                        val onDisable = { if (canWrite) confirmEnable = false }
                        OutlinedButton(
                            onClick = onDisable,
                            enabled = canWrite,
                            modifier = Modifier.testableClickable("btn_watchlist_disable") { onDisable() },
                        ) { Text(localizedString("mobile.child_safety_disable")) }
                        OutlinedButton(
                            onClick = { viewModel.loadWatchlist() },
                            modifier = Modifier.testableClickable("btn_watchlist_refresh") { viewModel.loadWatchlist() },
                        ) { Text(localizedString("mobile.child_safety_refresh")) }
                    }

                    // What the node did with the last write, when it did not take it.
                    state.watchlistWriteRefusal?.let { WriteRefusalBlock(it) }

                    // The read: four states, none of them blank.
                    Spacer(Modifier.height(12.dp))
                    WatchlistReadBlock(state.watchlistRead)
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    modifier = Modifier.testable("txt_child_safety_error", it))
            }
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    modifier = Modifier.testable("txt_child_safety_message", it))
            }
        }
    }

    // ── Confirms: three facts, two buttons ──
    confirmEnable?.let { enabling ->
        val cls = if (state.watchlistClass == WatchlistClass.CSAM) "CSAM" else localizedString("mobile.child_safety_class_other_short")
        val mode = if (state.watchlistMode == WatchlistMode.ENFORCE) localizedString("mobile.child_safety_mode_enforce_short")
        else localizedString("mobile.child_safety_mode_alert_short")
        ConfirmSheet(
            title = localizedString(if (enabling) "mobile.child_safety_enable_confirm_title" else "mobile.child_safety_disable_confirm_title"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.child_safety_fact_group"), state.watchlistGroupKeyId.trim(), mono = true),
                ConfirmFact(
                    localizedString("mobile.child_safety_fact_changes"),
                    localizedString(
                        if (enabling) "mobile.child_safety_fact_changes_enable" else "mobile.child_safety_fact_changes_disable",
                        mapOf("id" to state.watchlistId.trim(), "class" to cls, "mode" to mode),
                    ),
                ),
                ConfirmFact(
                    localizedString("mobile.child_safety_fact_signs"),
                    localizedString("mobile.child_safety_fact_signs_value"),
                ),
            ),
            confirmLabel = localizedString(if (enabling) "mobile.child_safety_enable" else "mobile.child_safety_disable"),
            onConfirm = { confirmEnable = null; viewModel.setWatchlistEnabled(enabling) },
            onDismiss = { confirmEnable = null },
            destructive = !enabling,
            tagPrefix = if (enabling) "watchlist_enable" else "watchlist_disable",
        )
    }
    confirmBand?.let { band ->
        ConfirmSheet(
            title = localizedString("mobile.child_safety_restate_confirm_title"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.child_safety_fact_who"), state.subjectKeyId.orEmpty(), mono = true),
                ConfirmFact(
                    localizedString("mobile.child_safety_fact_changes"),
                    localizedString(
                        if (band == AgeBand.ADULT) "mobile.child_safety_restate_adult" else "mobile.child_safety_restate_minor",
                    ),
                ),
                ConfirmFact(
                    localizedString("mobile.child_safety_fact_signs"),
                    localizedString("mobile.child_safety_restate_signs_value"),
                ),
            ),
            confirmLabel = localizedString("mobile.child_safety_restate_confirm"),
            onConfirm = { confirmBand = null; viewModel.restateAgeBand(band) },
            onDismiss = { confirmBand = null },
            tagPrefix = "age_state",
        )
    }
}

/** Whose posture this is: the owner's fed-ID, or — on an unclaimed node — the node's own key, said so. */
@Composable
private fun PostureSubject(subjectKeyId: String?, subjectIsOwner: Boolean, probed: Boolean) {
    if (subjectKeyId == null) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            localizedString(if (subjectIsOwner) "mobile.child_safety_subject_owner" else "mobile.child_safety_subject_node"),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            subjectKeyId, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testable("txt_posture_subject", subjectKeyId),
        )
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, label: String, tag: String, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            modifier = Modifier.testableClickable(tag) { onSelect() },
        )
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The list of enables as the node serves it, or one of the three kinds of
 * silence — each its own tag, so "off" and "we could not ask" are never one
 * rendering (CSD-066 §2). Who enabled each entry, and when, is not served
 * (`WatchlistEnable` carries no attester and no timestamp), and the card says so.
 */
@Composable
private fun WatchlistReadBlock(read: WatchlistRead) {
    when (read) {
        WatchlistRead.NotAsked -> Text(
            localizedString("mobile.child_safety_watchlist_not_asked"),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testable("txt_watchlist_not_asked"),
        )
        WatchlistRead.Loading -> StateBlock(ListState.Loading, tag = "spinner_watchlist", inline = true)
        is WatchlistRead.Failed -> Column {
            ReadFailureBlock(
                failure = read.failure,
                tagPrefix = "watchlist",
                notOnThisNode = localizedString("mobile.child_safety_watchlist_not_on_this_node"),
                inline = true,
            )
            Text(
                localizedString("mobile.child_safety_watchlist_read_failed_note"),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp).testable("txt_watchlist_error_note"),
            )
        }
        is WatchlistRead.Loaded -> if (read.enables.isEmpty()) {
            StateBlock(
                ListState.Empty(localizedString("mobile.child_safety_watchlist_none")),
                tag = "txt_watchlist_none",
                inline = true,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth().testable("list_watchlist_enables")) {
                Text(localizedString("mobile.child_safety_current_enables"),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                read.enables.forEachIndexed { i, e ->
                    Text("• ${e.watchlistId} (${e.watchlistClass}, ${e.mode})",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp).testable("row_watchlist_enable_$i", e.watchlistId))
                }
                Text(localizedString("mobile.child_safety_audit_missing"),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp).testable("txt_watchlist_audit_missing"))
            }
        }
    }
}

/** Why the node did not take the write — four different facts, four tags. */
@Composable
private fun WriteRefusalBlock(r: WatchlistWriteRefusal) {
    Spacer(Modifier.height(10.dp))
    when (r) {
        WatchlistWriteRefusal.Unsigned -> StateBlock(
            ListState.Error(
                title = localizedString("mobile.child_safety_write_unsigned_title"),
                body = localizedString("mobile.child_safety_write_unsigned"),
            ),
            tag = "watchlist_write_unsigned",
            inline = true,
        )
        WatchlistWriteRefusal.NotAHolder -> StateBlock(
            ListState.Error(
                title = localizedString("mobile.child_safety_write_not_holder_title"),
                body = localizedString("mobile.child_safety_write_not_holder"),
            ),
            tag = "watchlist_write_not_holder",
            inline = true,
        )
        is WatchlistWriteRefusal.Refused -> StateBlock(
            ListState.Error(
                title = localizedString("mobile.child_safety_write_refused", "status", r.status.toString()),
                body = r.reasonId?.let { id -> localizedString(id).takeIf { it != id } },
                detail = r.detail,
            ),
            tag = "watchlist_write_refused",
            inline = true,
        )
        is WatchlistWriteRefusal.Failed -> StateBlock(
            ListState.Error(title = localizedString("mobile.state_read_failed"), detail = r.detail),
            tag = "watchlist_write_error",
            inline = true,
        )
    }
}
