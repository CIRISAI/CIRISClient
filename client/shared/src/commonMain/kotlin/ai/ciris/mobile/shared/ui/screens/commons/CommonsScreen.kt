package ai.ciris.mobile.shared.ui.screens.commons

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.surfaces.CommonsEscalationRecord
import ai.ciris.mobile.shared.models.surfaces.CommonsStanding
import ai.ciris.mobile.shared.models.surfaces.CommonsWriteResult
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.components.refusalText
import ai.ciris.mobile.shared.ui.components.surfaceText
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.ui.theme.SemanticColors
import ai.ciris.mobile.shared.viewmodels.CommonsViewModel
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.primitives.rememberTextInputDriver
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet

/**
 * **The Commons** — persist's reverse-quorum plane, rendered
 * (CIRISServer#367, `src/commons_surface.rs`).
 *
 * # Why this is its own surface and not a fifth tab of Mesh Configuration
 *
 * The issue guessed "probably a fifth tab". It should not be, and the server's
 * own module doc says why in one line: the `/v1/admin` routes and `/v1/mesh-config` are
 * *authority acting **on*** a node — an owner, or a trust root the owner
 * subscribed to, turning a knob. **This is the commons acting on itself.**
 * Nothing on this screen is an owner privilege: one member of a cohort raises a
 * brake, and the cohort's own threshold is what lifts it. Filing it under a
 * configuration screen would put a community's self-policing inside the
 * operator's settings, which is precisely the authority the mechanism exists to
 * not require. It is a Commons-group surface, next to the cohort layers whose
 * rosters supply its quorum.
 *
 * # The asymmetry, and how it is drawn
 *
 * > **1-of-N to protect, m-of-n to undo.**
 *
 * Raising is ONE control: a button, one mandatory field, no gathering, no
 * waiting, always available. Lifting is a THREE-STEP disclosure whose submit
 * stays shut until a dry run has produced the exact bytes co-signers must sign,
 * with the cohort's threshold printed before anything can be done. That is not
 * decoration: the m-of-n is literally unreachable without the dry run, so the
 * two controls have the shapes their costs have. They are never rendered as two
 * buttons side by side, because a vote count is exactly what this is not.
 *
 * # Five absences, none of them a zero
 *
 * `unreadable` / `action_unknown` / `cohort_unknown` / `not_governed` all send
 * `fold: null` and `escalation: null` — **no counts at all** — and this screen
 * shows no numbers on those arms, only what could not be established. `quiet`
 * is the fifth and is different in kind: the plane WAS read and nobody objected.
 * "We could not ask" and "nobody objected" are opposite facts and they do not
 * share a rendering here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonsScreen(
    viewModel: CommonsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cohort by viewModel.cohort.collectAsState()
    val cohortKeyId by viewModel.cohortKeyId.collectAsState()
    val actionId by viewModel.actionId.collectAsState()
    val standing by viewModel.standing.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val writeResult by viewModel.writeResult.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("surfaces.commons.title")) },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testableClickable("btn_commons_back") { onBack() },
                        ) {
                            Icon(CIRISIcons.arrowBack, contentDescription = localizedString("mobile.common_back"))
                        }
                    } else {
                        Spacer(Modifier.width(56.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testableVerticalScroll()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = localizedString("surfaces.commons.subtitle"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AsymmetryBanner(standing)

            QueryCard(
                cohort = cohort,
                cohortKeyId = cohortKeyId,
                actionId = actionId,
                loading = loading,
                onCohort = viewModel::setCohort,
                onCohortKeyId = viewModel::setCohortKeyId,
                onActionId = viewModel::setActionId,
                onRead = { viewModel.readStanding() },
            )

            // A read that did not arrive (transport failure, unparseable body) is
            // its own state, never the `unreadable` arm and never `quiet` (CSD-070).
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testable("txt_commons_error", it),
                )
            }
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testable("spinner_commons_read"))
            }
            writeResult?.let { WriteResultCard(it) { viewModel.clearWriteResult() } }

            val s = standing
            if (s == null) {
                Text(
                    text = localizedString("surfaces.commons.no_reading"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StandingCard(s)
                RaiseTheBrake(
                    threshold = s.objectionThreshold,
                    busy = busy,
                    subject = commonsSubject(actionId, cohortKeyId),
                    onRaise = { grounds -> viewModel.raiseObjection(grounds) },
                )
                ObjectionsBlock(standing = s, viewModel = viewModel, busy = busy)
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  The asymmetry, stated before anything can be done
// ═════════════════════════════════════════════════════════════════════════════

/**
 * The two prices, drawn with deliberately unequal weight. The raise side is a
 * solid block with one number in it; the lift side is a thin outline whose
 * number is the cohort's own and is often simply not known here — which is
 * itself the point, since nobody can lift a brake without asking the roster.
 */
@Composable
private fun AsymmetryBanner(standing: CommonsStanding?) {
    val raiseColor = SemanticColors.Default.warning
    val threshold = standing?.objectionThreshold
    val required = standing?.fold?.required
    val roster = standing?.fold?.rosterSize
    val floor = standing?.escalationRespondentFloor

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = localizedString("surfaces.commons.asymmetry_title"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        // RAISE — filled, loud, one number.
        Surface(
            color = raiseColor.copy(alpha = 0.16f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = localizedString("surfaces.commons.raise_label"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = raiseColor,
                )
                Text(
                    text = if (threshold != null) {
                        localizedString("surfaces.commons.raise_price", "threshold", threshold.toString())
                    } else {
                        localizedString("surfaces.commons.raise_price_unread")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testable("txt_commons_raise_price", threshold?.toString() ?: ""),
                )
            }
        }
        // LIFT — outlined, quiet, and its number is the cohort's, not ours.
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = localizedString("surfaces.commons.dismiss_label"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (required != null && roster != null) {
                        localizedString(
                            "surfaces.commons.dismiss_price",
                            mapOf("required" to required.toString(), "roster" to roster.toString()),
                        )
                    } else {
                        localizedString("surfaces.commons.dismiss_price_unknown")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable(
                        "txt_commons_lift_price",
                        if (required != null && roster != null) "$required/$roster" else "",
                    ),
                )
                if (floor != null) {
                    Text(
                        text = localizedString("surfaces.commons.floor", "floor", floor.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testable("txt_commons_floor", floor.toString()),
                    )
                }
            }
        }
        // The substrate's own sentence about the asymmetry, in the reader's language.
        surfaceText(standing?.asymmetryMessage).takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  Ask the fold
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun QueryCard(
    cohort: String,
    cohortKeyId: String,
    actionId: String,
    loading: Boolean,
    onCohort: (String) -> Unit,
    onCohortKeyId: (String) -> Unit,
    onActionId: (String) -> Unit,
    onRead: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = localizedString("surfaces.commons.cohort"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommonsViewModel.COHORTS.forEach { c ->
                    FilterChip(
                        selected = cohort == c,
                        onClick = { onCohort(c) },
                        label = { Text(c) },
                        modifier = Modifier.testableClickable("chip_commons_cohort_$c") { onCohort(c) },
                    )
                }
            }
            rememberTextInputDriver("input_commons_cohort_key", cohortKeyId, onValueChange = onCohortKeyId)
            rememberTextInputDriver("input_commons_action", actionId, onValueChange = onActionId)
            OutlinedTextField(
                value = cohortKeyId,
                onValueChange = onCohortKeyId,
                label = { Text(localizedString("surfaces.commons.cohort_key")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testable("input_commons_cohort_key", cohortKeyId),
            )
            OutlinedTextField(
                value = actionId,
                onValueChange = onActionId,
                label = { Text(localizedString("surfaces.commons.action")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testable("input_commons_action", actionId),
            )
            Button(
                onClick = onRead,
                enabled = !loading && cohortKeyId.isNotBlank() && actionId.isNotBlank(),
                modifier = Modifier.testableClickable(
                    "btn_commons_read",
                    enabled = !loading && cohortKeyId.isNotBlank() && actionId.isNotBlank(),
                ) { onRead() },
            ) {
                Text(localizedString("surfaces.commons.read"))
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  The standing — eight arms, five of them absences
// ═════════════════════════════════════════════════════════════════════════════

/** The four arms that carry NO counts. `quiet` is deliberately not among them. */
private val ABSENCE_ARMS = setOf("unreadable", "action_unknown", "cohort_unknown", "not_governed")

/** How one standing draws (CSD-070). Pure, so the eight-zeroes rule is testable. */
internal enum class StandingBody { REFUSED, NO_COUNTS, QUIET, COUNTED }

/**
 * A refusal returns before anything else; an absence arm, or any arm that
 * arrived without a fold, prints NO counts (a `0` there is a claim nobody
 * made); `quiet` is the plane read and clear, which is not an absence.
 */
internal fun standingBody(s: CommonsStanding): StandingBody = when {
    s.refused -> StandingBody.REFUSED
    s.standing in ABSENCE_ARMS || s.fold == null -> StandingBody.NO_COUNTS
    s.standing == "quiet" -> StandingBody.QUIET
    else -> StandingBody.COUNTED
}

@Composable
private fun standingColor(token: String): Color = when (token) {
    "reversed" -> SemanticColors.Default.error
    "objected" -> SemanticColors.Default.warning
    "stood" -> SemanticColors.Default.info
    "quiet" -> SemanticColors.Default.success
    // Every absence reads as an absence, not as a verdict.
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun StandingCard(s: CommonsStanding) {
    val body = standingBody(s)
    if (body == StandingBody.REFUSED) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().testable("txt_commons_refusal", s.refusal ?: "refused"),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = s.refusal ?: "refused",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = refusalText("commons_surface", s.refusal, s.message),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        return
    }

    val absent = body == StandingBody.NO_COUNTS
    val color = standingColor(s.standing)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = color.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.testable("txt_commons_standing", s.standing),
                ) {
                    Text(
                        text = s.standing.uppercase(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                }
                Text(
                    text = localizedString("surfaces.commons.standing"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            surfaceText(s.standingMessage).takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            s.error?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            s.actionAuthor?.let { Line(localizedString("surfaces.commons.action_author"), it, mono = true) }
            s.actionAssertedAt?.let { Line(localizedString("surfaces.commons.action_asserted"), it, mono = true) }

            if (absent) {
                // THE distinct zero. No counts are printed, because none exist:
                // a `0` here would be a claim nobody made.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testable("txt_commons_no_counts", s.standing),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = localizedString("surfaces.commons.unknown_title"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = localizedString("surfaces.commons.unknown_body"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                val f = s.fold!!
                if (body == StandingBody.QUIET) {
                    Surface(
                        color = SemanticColors.Default.success.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().testable("txt_commons_quiet"),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = localizedString("surfaces.commons.quiet_title"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = SemanticColors.Default.success,
                            )
                            Text(
                                text = localizedString("surfaces.commons.quiet_body"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Line(localizedString("surfaces.commons.objectors"), f.distinctObjectors.toString(), tag = "txt_commons_objectors")
                Line(localizedString("surfaces.commons.required"), f.required.toString())
                Line(localizedString("surfaces.commons.roster"), f.rosterSize.toString())
                f.policy?.let { Line(localizedString("surfaces.commons.policy"), it, mono = true) }
                Line(
                    localizedString("surfaces.commons.window"),
                    (if (f.windowOpen) {
                        localizedString("surfaces.commons.window_open")
                    } else {
                        localizedString("surfaces.commons.window_closed")
                    }) + " · ${f.windowOpensAt} → ${f.windowClosesAt}",
                )
                if (f.countedObjectionIds.isNotEmpty()) {
                    IdList(localizedString("surfaces.commons.counted"), f.countedObjectionIds)
                }
                // Two suppressions, two prices, two denominators — never merged.
                if (f.dismissedObjectionIds.isNotEmpty()) {
                    IdList(localizedString("surfaces.commons.dismissed"), f.dismissedObjectionIds)
                }
                if (f.escalatedDismissedObjectionIds.isNotEmpty()) {
                    IdList(
                        localizedString("surfaces.commons.escalated_dismissed"),
                        f.escalatedDismissedObjectionIds,
                    )
                }
            }

            // The escalation axis is SEPARATE from the standing, because
            // "did the duty-holders answer" and "does the action stand" are
            // two questions.
            s.escalation?.let { esc ->
                HorizontalDivider()
                Text(
                    text = localizedString("surfaces.commons.escalation") + " · " + esc.standing.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testable("txt_commons_escalation", esc.standing),
                )
                surfaceText(esc.standingMessage).takeIf { it.isNotBlank() }?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall)
                }
                esc.stewardDeadline?.let {
                    Line(localizedString("surfaces.commons.steward_deadline"), it, mono = true)
                }
            }
            s.dimensions?.let { d ->
                Line(
                    localizedString("surfaces.commons.dimensions"),
                    "${d.objection} · ${d.dismissal} · ${d.uphold} · ${d.overrule}",
                    mono = true,
                )
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String, mono: Boolean = false, tag: String? = null) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = if (tag != null) Modifier.testable(tag, value) else Modifier,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun IdList(label: String, ids: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "$label (${ids.size})",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ids.forEach {
            Text(text = it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  1-of-N — raising
// ═════════════════════════════════════════════════════════════════════════════

/**
 * One control, one field, always available. There is nothing to gather here
 * because there is nothing to reach: the threshold IS one.
 */
@Composable
private fun RaiseTheBrake(threshold: Int, busy: Boolean, subject: String, onRaise: (String) -> Unit) {
    var grounds by remember { mutableStateOf("") }
    rememberTextInputDriver("input_commons_objection_grounds", grounds) { grounds = it }
    // A raised brake cannot be taken back by the one who raised it — only the
    // cohort's own m-of-n lifts it — so the three facts come first.
    var confirming by remember { mutableStateOf(false) }
    val raise = { confirming = true }
    if (confirming) {
        CommonsConfirm(
            tagPrefix = "commons_raise",
            title = localizedString("surfaces.commons.raise_label"),
            subject = subject,
            what = localizedString("surfaces.commons.confirm_raise_what"),
            who = localizedString("surfaces.commons.confirm_signs_node"),
            confirmLabel = localizedString("surfaces.commons.raise_action"),
            onConfirm = {
                confirming = false
                onRaise(grounds)
                grounds = ""
            },
            onDismiss = { confirming = false },
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.Default.warning.copy(alpha = 0.10f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = localizedString("surfaces.commons.raise_label"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.Default.warning,
            )
            Text(
                text = localizedString("surfaces.commons.raise_price", "threshold", threshold.toString()),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = grounds,
                onValueChange = { grounds = it },
                label = { Text(localizedString("surfaces.commons.grounds")) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testable("input_commons_objection_grounds"),
            )
            Button(
                onClick = raise,
                enabled = !busy && grounds.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.Default.warning),
                // The automation click runs the SAME act as a tap; it used to be a no-op.
                modifier = Modifier.testableClickable("btn_commons_object", enabled = !busy && grounds.isNotBlank()) {
                    raise()
                },
            ) {
                Text(localizedString("surfaces.commons.raise_action"))
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  m-of-n — ballots and the dismissal ceremony
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun ObjectionsBlock(standing: CommonsStanding, viewModel: CommonsViewModel, busy: Boolean) {
    val records = standing.escalation?.objections ?: emptyList()
    val counted = standing.fold?.countedObjectionIds ?: emptyList()
    // Every objection the fold named, whether or not it has an escalation record.
    val ids = (counted + records.map { it.objectionId }).distinct()
    if (ids.isEmpty()) return

    val floor = standing.escalationRespondentFloor
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = localizedString("surfaces.commons.objections"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        ids.forEach { id ->
            ObjectionCard(
                objectionId = id,
                record = records.firstOrNull { it.objectionId == id },
                floor = floor,
                viewModel = viewModel,
                busy = busy,
            )
        }
    }
}

@Composable
private fun ObjectionCard(
    objectionId: String,
    record: CommonsEscalationRecord?,
    floor: Int,
    viewModel: CommonsViewModel,
    busy: Boolean,
) {
    var ballotGrounds by remember { mutableStateOf("") }
    rememberTextInputDriver("input_commons_ballot_grounds", ballotGrounds) { ballotGrounds = it }
    var lifting by remember { mutableStateOf(false) }
    // One ballot per respondent, and it is not taken back: confirmed first.
    var ballotUpholds by remember { mutableStateOf<Boolean?>(null) }
    // The dismissal is the m-of-n act itself; its grounds wait here for the confirm.
    var dismissGrounds by remember { mutableStateOf<String?>(null) }
    val cohortKeyId by viewModel.cohortKeyId.collectAsState()
    val actionId by viewModel.actionId.collectAsState()
    val subject = commonsSubject(actionId, cohortKeyId)
    val payloadSha by viewModel.dismissalPayloadSha256.collectAsState()
    val draftObjection by viewModel.dismissalObjectionId.collectAsState()
    val cosigners by viewModel.cosigners.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = objectionId, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)

            record?.let { r ->
                Line("steward", r.steward)
                surfaceText(r.stewardMessage).takeIf { it.isNotBlank() }?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Line("outcome", r.outcome)
                Line(localizedString("surfaces.commons.duty_holders"), r.dutyHolders.toString())
                if (r.escalationOpen) {
                    // THE property #591 exists for: the denominator changed.
                    Surface(
                        color = SemanticColors.Default.info.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = localizedString(
                                    "surfaces.commons.respondents_of_required",
                                    mapOf(
                                        "respondents" to r.respondents.toString(),
                                        "required" to r.required.toString(),
                                    ),
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = localizedString("surfaces.commons.respondents_note"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = localizedString("surfaces.commons.floor", "floor", floor.toString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Line(
                    localizedString("surfaces.commons.uphold") + " / " + localizedString("surfaces.commons.overrule"),
                    "${r.upholdBallots} / ${r.overruleBallots}",
                    tag = "txt_commons_ballot_tally",
                )
            }

            // ── One signature: a ballot ─────────────────────────────────────
            Text(
                text = localizedString("surfaces.commons.ballot_title"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = ballotGrounds,
                onValueChange = { ballotGrounds = it },
                label = { Text(localizedString("surfaces.commons.grounds")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testable("input_commons_ballot_grounds"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { ballotUpholds = true },
                    enabled = !busy && ballotGrounds.isNotBlank(),
                    modifier = Modifier.testableClickable("btn_commons_uphold", enabled = !busy && ballotGrounds.isNotBlank()) {
                        ballotUpholds = true
                    },
                ) {
                    Text(localizedString("surfaces.commons.uphold"))
                }
                OutlinedButton(
                    onClick = { ballotUpholds = false },
                    enabled = !busy && ballotGrounds.isNotBlank(),
                    modifier = Modifier.testableClickable("btn_commons_overrule", enabled = !busy && ballotGrounds.isNotBlank()) {
                        ballotUpholds = false
                    },
                ) {
                    Text(localizedString("surfaces.commons.overrule"))
                }
            }

            HorizontalDivider()

            // ── m-of-n: the dismissal ceremony, behind a disclosure ─────────
            TextButton(
                onClick = { lifting = !lifting },
                modifier = Modifier.testableClickable("btn_commons_lift_disclose") { lifting = !lifting },
            ) {
                Text(localizedString("surfaces.commons.dismiss_label"))
            }
            AnimatedVisibility(visible = lifting) {
                DismissalCeremony(
                    objectionId = objectionId,
                    payloadSha = payloadSha.takeIf { draftObjection == objectionId },
                    cosigners = cosigners.size,
                    busy = busy,
                    onDryRun = { grounds -> viewModel.dismissDryRun(objectionId, grounds) },
                    onAddCosigner = viewModel::addCosigner,
                    onRemoveCosigner = viewModel::removeCosigner,
                    onSubmit = { grounds -> dismissGrounds = grounds },
                    onAbandon = { viewModel.clearDismissalDraft() },
                )
            }
        }
    }

    ballotUpholds?.let { upholds ->
        CommonsConfirm(
            tagPrefix = "commons_ballot",
            title = localizedString("surfaces.commons.ballot_title"),
            subject = subject,
            what = localizedString(
                if (upholds) "surfaces.commons.confirm_ballot_uphold_what" else "surfaces.commons.confirm_ballot_overrule_what",
                "objection", objectionId,
            ),
            who = localizedString("surfaces.commons.confirm_signs_node"),
            confirmLabel = localizedString(if (upholds) "surfaces.commons.uphold" else "surfaces.commons.overrule"),
            onConfirm = {
                ballotUpholds = null
                viewModel.castBallot(objectionId, upholds, ballotGrounds)
            },
            onDismiss = { ballotUpholds = null },
        )
    }
    dismissGrounds?.let { grounds ->
        CommonsConfirm(
            tagPrefix = "commons_dismiss",
            title = localizedString("surfaces.commons.dismiss_label"),
            subject = subject,
            what = localizedString("surfaces.commons.confirm_dismiss_what", "objection", objectionId),
            who = localizedString("surfaces.commons.confirm_signs_cosigners", "count", cosigners.size.toString()),
            confirmLabel = localizedString("surfaces.commons.dismiss_submit"),
            onConfirm = {
                dismissGrounds = null
                viewModel.submitDismissal(objectionId, grounds)
            },
            onDismiss = { dismissGrounds = null },
        )
    }
}

/** "act-8812ab4f · in cohort-key" — the action a commons act is about, and whose. */
private fun commonsSubject(actionId: String, cohortKeyId: String): String =
    "${actionId.trim()} · ${cohortKeyId.trim()}"

/**
 * The three facts every commons write confirms (CSD-070 §2): which action in
 * which cohort, what changes, and who signs. Every write here is signed by
 * this node's key on its owner's behalf and replicates as a row; none is taken
 * back by the one who made it.
 */
@Composable
private fun CommonsConfirm(
    tagPrefix: String,
    title: String,
    subject: String,
    what: String,
    who: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmSheet(
        title = title,
        facts = listOf(
            ConfirmFact(localizedString("surfaces.commons.confirm_subject"), subject, mono = true),
            ConfirmFact(localizedString("surfaces.commons.confirm_what"), what),
            ConfirmFact(localizedString("surfaces.commons.confirm_who_signs"), who),
        ),
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        tagPrefix = tagPrefix,
    )
}


/**
 * **Three steps, and the third is shut until the first has happened.**
 *
 * Not a UI opinion: a co-signature only counts over the exact canonical bytes
 * the submission carries, and only the dry run knows them. The gate is the
 * substrate's rule wearing a UI.
 */
@Composable
private fun DismissalCeremony(
    objectionId: String,
    payloadSha: String?,
    cosigners: Int,
    busy: Boolean,
    onDryRun: (String) -> Unit,
    onAddCosigner: (String, String, String?) -> Unit,
    onRemoveCosigner: (Int) -> Unit,
    onSubmit: (String) -> Unit,
    onAbandon: () -> Unit,
) {
    var grounds by remember { mutableStateOf("") }
    var keyId by remember { mutableStateOf("") }
    var classical by remember { mutableStateOf("") }
    var pqc by remember { mutableStateOf("") }
    rememberTextInputDriver("input_commons_dismiss_grounds", grounds) { grounds = it }
    rememberTextInputDriver("input_commons_cosigner_key", keyId, enabled = payloadSha != null) { keyId = it }
    rememberTextInputDriver("input_commons_cosigner_classical", classical, enabled = payloadSha != null) { classical = it }
    rememberTextInputDriver("input_commons_cosigner_pqc", pqc, enabled = payloadSha != null) { pqc = it }
    val addCosigner = {
        onAddCosigner(keyId, classical, pqc)
        keyId = ""
        classical = ""
        pqc = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = localizedString("surfaces.commons.dismiss_gate"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Step 1
        Text(
            text = localizedString("surfaces.commons.dismiss_step1"),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = grounds,
            onValueChange = { grounds = it },
            label = { Text(localizedString("surfaces.commons.grounds")) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testable("input_commons_dismiss_grounds"),
        )
        OutlinedButton(
            onClick = { onDryRun(grounds) },
            enabled = !busy && grounds.isNotBlank(),
            modifier = Modifier.testableClickable("btn_commons_dismiss_dry_run", enabled = !busy && grounds.isNotBlank()) {
                onDryRun(grounds)
            },
        ) {
            Text(localizedString("surfaces.commons.dry_run"))
        }
        payloadSha?.let {
            Line("payload_sha256", it, mono = true, tag = "txt_commons_dry_run_hash")
        }

        // Step 2
        Text(
            text = localizedString("surfaces.commons.dismiss_step2"),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (payloadSha == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = keyId,
            onValueChange = { keyId = it },
            label = { Text(localizedString("surfaces.commons.cosigner_key")) },
            singleLine = true,
            enabled = payloadSha != null,
            modifier = Modifier.fillMaxWidth().testable("input_commons_cosigner_key"),
        )
        OutlinedTextField(
            value = classical,
            onValueChange = { classical = it },
            label = { Text(localizedString("surfaces.commons.cosigner_classical")) },
            singleLine = true,
            enabled = payloadSha != null,
            modifier = Modifier.fillMaxWidth().testable("input_commons_cosigner_classical"),
        )
        OutlinedTextField(
            value = pqc,
            onValueChange = { pqc = it },
            label = { Text(localizedString("surfaces.commons.cosigner_pqc")) },
            singleLine = true,
            enabled = payloadSha != null,
            modifier = Modifier.fillMaxWidth().testable("input_commons_cosigner_pqc"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = addCosigner,
                enabled = payloadSha != null && keyId.isNotBlank() && classical.isNotBlank(),
                modifier = Modifier.testableClickable(
                    "btn_commons_add_cosigner",
                    enabled = payloadSha != null && keyId.isNotBlank() && classical.isNotBlank(),
                ) { addCosigner() },
            ) {
                Text(localizedString("surfaces.commons.add_cosigner"))
            }
            Text(
                text = localizedString("surfaces.commons.cosigners", "n", cosigners.toString()),
                style = MaterialTheme.typography.labelSmall,
            )
            if (cosigners > 0) {
                TextButton(
                    onClick = { onRemoveCosigner(cosigners - 1) },
                    modifier = Modifier.testableClickable("btn_commons_remove_cosigner") { onRemoveCosigner(cosigners - 1) },
                ) {
                    Text(localizedString("mobile.common_delete"))
                }
            }
        }

        // Step 3 — shut until step 1 produced the bytes.
        Text(
            text = localizedString("surfaces.commons.dismiss_step3"),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (payloadSha == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSubmit(grounds) },
                enabled = !busy && payloadSha != null && grounds.isNotBlank(),
                modifier = Modifier.testableClickable(
                    "btn_commons_dismiss_submit",
                    enabled = !busy && payloadSha != null && grounds.isNotBlank(),
                ) { onSubmit(grounds) },
            ) {
                Text(localizedString("surfaces.commons.dismiss_submit"))
            }
            TextButton(
                onClick = onAbandon,
                modifier = Modifier.testableClickable("btn_commons_dismiss_abandon") { onAbandon() },
            ) {
                Text(localizedString("mobile.common_cancel"))
            }
        }
        // objectionId is echoed so a long ceremony cannot drift onto another row.
        Line(localizedString("surfaces.commons.objection_id"), objectionId, mono = true)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  What the substrate said
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun WriteResultCard(result: CommonsWriteResult, onDismiss: () -> Unit) {
    val color = when {
        result.refused -> MaterialTheme.colorScheme.error
        result.dryRun -> SemanticColors.Default.info
        else -> SemanticColors.Default.success
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        result.refused -> result.refusal ?: localizedString("surfaces.commons.refused")
                        result.dryRun -> localizedString("surfaces.commons.dry_run")
                        else -> localizedString("surfaces.commons.admitted")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testableClickable("btn_commons_clear_result") { onDismiss() },
                ) {
                    Text(localizedString("mobile.common_close"))
                }
            }
            Text(
                text = refusalText("commons_surface", result.refusal, result.message),
                style = MaterialTheme.typography.bodySmall,
            )
            result.objectionId?.let { Line("objection_id", it, mono = true) }
            result.ballotId?.let { Line("ballot_id", it, mono = true) }
            result.dismissalId?.let { Line("dismissal_id", it, mono = true) }
            result.payloadSha256?.let { Line("payload_sha256", it, mono = true) }
            // The m-of-n evidence, on BOTH arms: a refusal names its shortfall
            // and an admission names what it cleared.
            result.quorum?.let { q ->
                Text(
                    text = localizedString(
                        "surfaces.commons.counted_of_required",
                        mapOf(
                            "counted" to q.counted.toString(),
                            "required" to q.required.toString(),
                            "roster" to q.rosterSize.toString(),
                        ),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
