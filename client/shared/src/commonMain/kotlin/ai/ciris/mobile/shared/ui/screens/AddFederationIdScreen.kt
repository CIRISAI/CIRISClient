package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.platform.rememberInputSinks
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.AnnounceDecisionCard
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.viewmodels.FederationIdentitySetupState
import ai.ciris.mobile.shared.viewmodels.NodeSwitcherViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar

/**
 * **Add Federation ID (catch-up flow)** — the guided path for an EXISTING logged-in
 * user whose node is owned the legacy way (a password/OAuth ROOT WA with NO fed-ID).
 *
 * These users must NOT redo account creation. They take the session-authed
 * `POST /v1/self/upgrade-owner` path (mint fed-ID + re-root the node on it, login
 * preserved) — NOT the first-run `claim-remote` path (which needs a one-time console
 * PIN that no longer exists on an already-claimed node). The whole flow is driven by
 * [NodeSwitcherViewModel.upgradeToFedId].
 *
 * Steps (single scrolling screen):
 *  1. A UNIQUE, non-generic fed-ID label (same validation as the first-run wizard —
 *     [FederationIdentitySetupState.REJECTED_GENERIC_LABELS] — to avoid the
 *     `ciris-client-user` identity collision). This names + keys the "one canonical
 *     you".
 *  2. The SAME first-class announce decision as first-run ([AnnounceDecisionCard]),
 *     WITHOUT the trace opt-in: that write is the Data card's (CSD-039), read back
 *     there, and this screen points at it rather than issuing a second write it
 *     cannot read back (CSD-086 §3).
 *  3. Confirm — three facts, two buttons (CC: re-rooting the node on a new
 *     fed-ID is a supersede, not an undo) — then run the upgrade (mint → re-root →
 *     optional announce). On success the screen leaves via [onDone]; the
 *     success/soft-failure notice is surfaced by the node-management surface.
 *
 * A device that already HAS a fed-ID ([NodeSwitcherViewModel.ownerHasFedId] true)
 * is told so and offered no form: a second mint would collide.
 *
 * The app performs NO crypto — the local node mints + signs everything.
 */
/**
 * Localized string with a hardcoded fallback for keys not yet in the manifest.
 * [localizedString] returns the KEY itself when a key is absent (not ""), so a
 * plain `.ifEmpty {}` wouldn't fall back — treat "blank OR equals the key" as
 * missing and render [fallback]. Lets this screen ship before en.json is updated.
 */
@Composable
private fun l10nOr(key: String, fallback: String): String {
    val v = localizedString(key)
    return if (v.isBlank() || v == key) fallback else v
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFederationIdScreen(
    viewModel: NodeSwitcherViewModel,
    onBack: () -> Unit,
    /** Navigate away after a successful upgrade (typically back to Manage Nodes). */
    onDone: () -> Unit,
) {
    val inProgress by viewModel.upgradeInProgress.collectAsState()
    val error by viewModel.error.collectAsState()
    val ownerHasFedId by viewModel.ownerHasFedId.collectAsState()

    var label by remember { mutableStateOf("") }
    var announce by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    // The confirm is open: three facts before a supersede nobody can undo here.
    var confirming by remember { mutableStateOf(false) }

    // Label validation mirrors the first-run wizard: a name is REQUIRED and must not
    // be a generic default (those collide identities across devices).
    val labelTrimmed = label.trim()
    val labelIsGeneric = labelTrimmed.lowercase() in
        FederationIdentitySetupState.REJECTED_GENERIC_LABELS
    val labelHasError = labelTrimmed.isEmpty() || labelIsGeneric
    val alreadyHasFedId = ownerHasFedId == true
    val canConfirm = !labelHasError && !inProgress && !alreadyHasFedId

    // Test automation: route /input requests into the label field (the pattern
    // SetupScreen/LoginScreen/InteractScreen use — without this, /input on
    // input_fed_label "succeeds" but the Compose state never updates).
    val textInputRequest by TestAutomation.textInputRequests.collectAsState()
    // The tags the dispatch below handles. Registration lives HERE, on the
    // line after the collector, so it cannot be forgotten separately —
    // check_ui_drivable.py fails the build if a dispatched tag is missing.
    rememberInputSinks("input_fed_label")
    LaunchedEffect(textInputRequest) {
        textInputRequest?.let { request ->
            if (request.testTag == "input_fed_label") {
                label = if (request.clearFirst) request.text else label + request.text
                TestAutomation.clearTextInputRequest()
            }
        }
    }

    // Leave on a clean completion; re-arm for retry if the upgrade errored.
    LaunchedEffect(submitted, inProgress, error) {
        if (submitted && !inProgress) {
            if (error == null) {
                onDone()
            } else {
                submitted = false
            }
        }
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = {
                    Text(l10nOr("mobile.add_fedid_title", "Add Federation ID"))
                },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testableClickable("btn_add_fedid_back") { onBack() },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── The EMPTY state: nothing to add ──────────────────────────────
            // Login hides the door when a fed-ID exists; the ManageNodes entry
            // and the catch-up effect do not, and a form here would mint a
            // second identity for a device that has one (CSD-086 §2).
            if (alreadyHasFedId) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testable("txt_fedid_absent"),
                ) {
                    Text(
                        text = localizedString("mobile.add_fedid_already"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                return@Column
            }

            Text(
                text = l10nOr(
                    "mobile.add_fedid_intro",
                    "You're signed in already — this adds a federation ID to your existing " +
                        "account without re-creating it. Your login is preserved.",
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Step 1: unique, non-generic fed-ID label ─────────────────────
            Text(
                text = l10nOr("mobile.add_fedid_label_heading", "Name your federation ID"),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = {
                    Text(localizedString("mobile.setup_fedid_label").ifEmpty { "Federation ID name" })
                },
                placeholder = {
                    Text(
                        localizedString("mobile.setup_fedid_label_hint")
                            .ifEmpty { "e.g. firstname-lastname-v1" }
                    )
                },
                singleLine = true,
                isError = labelHasError,
                enabled = !inProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .testable("input_fed_label"),
            )
            Text(
                text = when {
                    labelTrimmed.isEmpty() ->
                        localizedString("mobile.setup_fedid_label_required")
                            .ifEmpty { "A unique name is required." }
                    labelIsGeneric ->
                        localizedString("mobile.setup_fedid_label_generic")
                            .ifEmpty { "That name is too generic — choose a unique one." }
                    else ->
                        localizedString("mobile.setup_fedid_label_ok").ifEmpty { "Looks good." }
                },
                color = if (labelHasError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                fontSize = 12.sp,
            )

            // ── Step 2: the first-class announce decision (reused) ───────────
            // No trace opt-in here: that write belongs to the Data card, where
            // it is read back. Announcing is what makes it possible at all.
            AnnounceDecisionCard(
                announce = announce,
                onAnnounceChange = { on -> announce = on },
                traceOptIn = false,
                onTraceOptInChange = {},
                showTraceOptIn = false,
            )
            Text(
                text = localizedString("mobile.add_fedid_traces_elsewhere"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testable("txt_fedid_traces_elsewhere"),
            )

            // ── Errors: the node's own refusal, and the button re-armed ──────
            error?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testable("txt_fedid_error", msg),
                ) {
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            // ── Step 3: confirm ──────────────────────────────────────────────
            // The button opens the three-fact confirm; the guard lives inside
            // the lambda so a /click on a disabled button is a no-op.
            val onReview = { if (canConfirm) confirming = true }
            Button(
                onClick = onReview,
                enabled = canConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .testableClickable("btn_add_fedid_confirm") { onReview() },
            ) {
                if (inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).testable("txt_fedid_progress"),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(l10nOr("mobile.add_fedid_confirm", "Add Federation ID"))
            }
        }
    }

    if (confirming) {
        ConfirmSheet(
            title = localizedString("mobile.add_fedid_sheet_title"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.add_fedid_fact_who"), labelTrimmed, mono = true),
                ConfirmFact(
                    localizedString("mobile.add_fedid_fact_changes"),
                    localizedString(
                        if (announce) "mobile.add_fedid_fact_changes_announced" else "mobile.add_fedid_fact_changes_private",
                    ),
                ),
                ConfirmFact(
                    localizedString("mobile.add_fedid_fact_signs"),
                    localizedString("mobile.add_fedid_fact_signs_value"),
                ),
            ),
            confirmLabel = l10nOr("mobile.add_fedid_confirm", "Add Federation ID"),
            onConfirm = {
                confirming = false
                if (canConfirm) {
                    submitted = true
                    viewModel.upgradeToFedId(label = labelTrimmed, announce = announce)
                }
            },
            onDismiss = { confirming = false },
            tagPrefix = "fedid",
        )
    }
}
