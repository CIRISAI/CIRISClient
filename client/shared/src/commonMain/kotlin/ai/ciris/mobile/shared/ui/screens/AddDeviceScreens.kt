package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.viewmodels.SetupViewModel
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.DeviceJoinRequest
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextField
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.QrScanAction
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.viewmodels.AddDeviceAvailability
import ai.ciris.mobile.shared.viewmodels.AddDevicePhase
import ai.ciris.mobile.shared.viewmodels.AddDeviceSetupState
import ai.ciris.mobile.shared.viewmodels.ApproveDeviceViewModel
import ai.ciris.mobile.shared.viewmodels.DeviceRequestOutcome
import ai.ciris.mobile.shared.viewmodels.DeviceRequestRefusal
import ai.ciris.mobile.shared.viewmodels.IdentityPath
import ai.ciris.mobile.shared.viewmodels.deviceClassDisplay
import ai.ciris.mobile.shared.viewmodels.formatCountdown
import ai.ciris.mobile.shared.viewmodels.requestExpiresInSecs
import ai.ciris.mobile.shared.viewmodels.windowSecsLeftAt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

/**
 * Test tags for "Add a device" on both devices (CSD-093 / CSD-094). One place,
 * so the CSDs, the flows and the screens cannot drift apart.
 */
object AddDeviceTags {
    // The wizard's first question.
    const val PATH_CHOICE = "setup_identity_path"
    const val PATH_NEW = "btn_setup_new_identity"
    const val PATH_EXISTING = "btn_setup_add_this_device"
    const val PATH_EXISTING_TOO_OLD = "txt_setup_add_device_too_old"
    const val PATH_LOCKED = "txt_setup_identity_path_locked"

    // The new device (Setup's YOU step on the add-device path, and COMPLETE).
    const val CARD = "card_add_this_device"
    const val NAME = "input_add_device_name"
    const val CONTACT = "input_add_device_contact"
    const val SCAN = "btn_add_device_scan"
    const val SEND = "btn_add_device_send"
    const val MATCH_CODE = "text_add_device_match_code"
    const val STATUS = "text_add_device_status"
    const val OWNER = "text_add_device_owner"
    const val REFUSAL = "add_device_refusal"
    const val NEW_REQUEST = "btn_add_device_new_request"
    const val CANCEL = "btn_add_device_cancel"
    const val CANCEL_CONFIRM_PREFIX = "confirm_add_device_cancel"
    const val CANCEL_OUTCOME = "text_add_device_cancel_outcome"
    const val CANCEL_ERROR = "add_device_cancel_error"
    const val WAITING = "setup_add_device_waiting"
    const val WAITING_RETRY = "btn_add_device_finish_retry"

    // The approving device (My Identity).
    const val APPROVE_CARD = "card_approve_device"
    const val APPROVE_OPEN = "btn_approve_device_open"
    const val APPROVE_WINDOW = "text_approve_device_window"
    const val APPROVE_EMPTY = "approve_device_empty"
    const val APPROVE_REFUSAL = "approve_device_refusal"
    const val APPROVE_OUTCOME = "text_approve_device_outcome"
    const val APPROVE_TOO_OLD = "approve_device_too_old"
    /** The held approval's line: another device must agree (CIRISServer#794). */
    const val APPROVE_HELD_WINDOW = "text_approve_device_held_window"
    /** From the held line to the roster card (CSD-095). */
    const val APPROVE_HELD_OPEN = "btn_approve_device_held_roster"
    /** The new device's note that a held addition can outlast its 15-minute poll. */
    const val WAITING_HELD_NOTE = "text_add_device_held_note"
    fun requestRow(id: String) = "approve_device_request_$id"
    fun requestCode(id: String) = "text_approve_device_code_$id"
    fun requestExpires(id: String) = "text_approve_device_expires_$id"
    fun requestClass(id: String) = "text_approve_device_class_$id"
    fun approve(id: String) = "btn_approve_device_$id"
    fun deny(id: String) = "btn_deny_device_$id"
    const val CONFIRM_PREFIX = "confirm_device_request"
}

/** A server id resolved in the bundle, or null when the bundle does not carry it (it resolves to itself). */
@Composable
private fun resolvedId(id: String?): String? = id?.let { localizedString(it).takeIf { s -> s != id && s.isNotBlank() } }

/** A refusal as one block: the localized id first, the node's English beneath; the English alone when the id is unknown. */
@Composable
private fun RefusalBlock(reasonId: String?, detail: String?, fallbackTitle: String, tag: String) {
    val title = resolvedId(reasonId) ?: detail?.takeIf { it.isNotBlank() } ?: fallbackTitle
    StateBlock(
        ListState.Error(
            title = title,
            detail = detail?.takeIf { it.isNotBlank() && it != title },
        ),
        tag = tag,
        inline = true,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** The six digits, large enough to compare across a table. */
@Composable
private fun MatchCode(code: String, tag: String) {
    val t = CirisTheme.tokens
    val grouped = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code
    Text(
        grouped,
        style = CirisTheme.type.signed.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
        color = t.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(CirisShape.card)
            .background(t.sunken)
            .padding(vertical = 12.dp)
            .testable(tag, code),
    )
}

// ════════════════════════════ THE NEW DEVICE ════════════════════════════

/**
 * **The wizard's first question** (Eric's ruling, `FSD/ADD_DEVICE.md` §1.1):
 * a new CIRIS ID, or this device added to one the person already has. Neither
 * is preselected. On a node older than 0.5.231 the second is shown disabled
 * with the reason, and the new-ID path is unchanged.
 */
@Composable
fun IdentityPathChoice(
    path: IdentityPath?,
    availability: AddDeviceAvailability,
    onChoose: (IdentityPath) -> Unit,
    modifier: Modifier = Modifier,
    /** A request is live: the other answer is disabled until it is cancelled (Codex, PR #189). */
    locked: Boolean = false,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    Column(modifier = modifier.fillMaxWidth().testable(AddDeviceTags.PATH_CHOICE, path?.name), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(localizedString("mobile.setup_path_title"), style = type.title, color = t.ink)
        PathOption(
            title = localizedString("mobile.setup_path_new"),
            body = localizedString("mobile.setup_path_new_desc"),
            selected = path == IdentityPath.NEW,
            enabled = !locked || path == IdentityPath.NEW,
            tag = AddDeviceTags.PATH_NEW,
            onClick = { onChoose(IdentityPath.NEW) },
        )
        val tooOld = availability == AddDeviceAvailability.NODE_TOO_OLD
        PathOption(
            title = localizedString("mobile.setup_path_existing"),
            body = localizedString("mobile.setup_path_existing_desc"),
            selected = path == IdentityPath.EXISTING,
            enabled = !tooOld && (!locked || path == IdentityPath.EXISTING),
            tag = AddDeviceTags.PATH_EXISTING,
            onClick = { onChoose(IdentityPath.EXISTING) },
        )
        if (tooOld) {
            val why = localizedString("mobile.add_device_node_too_old")
            Text(why, style = type.body, color = t.dim, modifier = Modifier.testable(AddDeviceTags.PATH_EXISTING_TOO_OLD, why))
        }
        if (locked) {
            val why = localizedString("mobile.setup_path_locked")
            Text(why, style = type.body, color = t.dim, modifier = Modifier.testable(AddDeviceTags.PATH_LOCKED, why))
        }
    }
}

@Composable
private fun PathOption(
    title: String,
    body: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CirisShape.card)
            .background(if (selected) t.sunken else t.raised)
            .border(CirisShape.hairlineWidth, if (selected) t.brand else t.hairlineStrong, CirisShape.card)
            .testableClickable(tag, enabled = enabled) { if (enabled) onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(title, style = type.body.copy(fontWeight = FontWeight.SemiBold), color = if (enabled) t.ink else t.mute)
        Spacer(Modifier.height(2.dp))
        Text(body, style = type.label, color = if (enabled) t.dim else t.mute)
    }
}

/**
 * **Add this device to an existing CIRIS ID** — the YOU step on that path
 * (CSD-093). Asks two things: this device's name (optional, the only naming
 * question) and the person's fedID the way *Share my ID* shows it (scan, long
 * form or short form). Then shows the match code big and where the request
 * stands. The rest of the wizard runs while the other device approves.
 */
@Composable
fun AddThisDeviceSection(
    state: AddDeviceSetupState,
    deviceName: String,
    onDeviceName: (String) -> Unit,
    onContact: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onAskCancel: () -> Unit = {},
    onConfirmCancel: () -> Unit = {},
    onDismissCancel: () -> Unit = {},
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val phase = state.phase
    val editable = phase == AddDevicePhase.IDLE || phase == AddDevicePhase.REFUSED || phase.needsNewRequest
    CardShell(modifier = modifier, tag = AddDeviceTags.CARD, accent = t.brand) {
        Text(localizedString("mobile.add_device_title"), style = type.title, color = t.ink)
        Spacer(Modifier.height(4.dp))
        Text(localizedString("mobile.add_device_desc"), style = type.body, color = t.dim)

        Spacer(Modifier.height(12.dp))
        Text(localizedString("mobile.add_device_name_label"), style = type.label, color = t.dim)
        CirisTextField(
            tag = AddDeviceTags.NAME,
            value = deviceName,
            onValueChange = onDeviceName,
            placeholder = localizedString("mobile.add_device_name_hint"),
            enabled = editable,
        )

        Spacer(Modifier.height(12.dp))
        Text(localizedString("mobile.add_device_contact_label"), style = type.label, color = t.dim)
        Text(localizedString("mobile.add_device_contact_hint"), style = type.label, color = t.mute)
        Spacer(Modifier.height(4.dp))
        CirisTextField(
            tag = AddDeviceTags.CONTACT,
            value = state.contact,
            onValueChange = onContact,
            enabled = editable,
            singleLine = false,
            mono = true,
        )
        if (editable) {
            // The same handler the paste field feeds: a scan fills the field and never sends.
            QrScanAction(
                onScanned = { scanned -> onContact(scanned.trim()) },
                tag = AddDeviceTags.SCAN,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        when {
            phase == AddDevicePhase.IDLE || phase == AddDevicePhase.SENDING -> CirisButton(
                label = localizedString(if (phase == AddDevicePhase.SENDING) "mobile.add_device_sending" else "mobile.add_device_send"),
                tag = AddDeviceTags.SEND,
                enabled = phase == AddDevicePhase.IDLE && state.contact.isNotBlank(),
                onClick = onSend,
            )
            phase == AddDevicePhase.REFUSED -> {
                RefusalBlock(
                    reasonId = state.refusalReasonId,
                    detail = state.detail,
                    fallbackTitle = localizedString("mobile.add_device_refused"),
                    tag = AddDeviceTags.REFUSAL,
                )
                Spacer(Modifier.height(8.dp))
                CirisButton(
                    label = localizedString("mobile.add_device_retry"),
                    tag = AddDeviceTags.SEND,
                    enabled = state.contact.isNotBlank() && state.availability != AddDeviceAvailability.NODE_TOO_OLD,
                    onClick = onSend,
                )
            }
            else -> AddDeviceProgressBlock(state = state, onNewRequest = onSend)
        }
        if (state.cancellable) {
            Spacer(Modifier.height(8.dp))
            CirisTextButton(
                label = localizedString(if (state.cancelling) "mobile.add_device_cancelling" else "mobile.add_device_cancel"),
                tag = AddDeviceTags.CANCEL,
                danger = true,
                enabled = !state.cancelling,
                onClick = onAskCancel,
            )
        }
        state.cancelError?.let { why ->
            RefusalBlock(reasonId = null, detail = why, fallbackTitle = localizedString("mobile.add_device_cancel_failed"), tag = AddDeviceTags.CANCEL_ERROR)
        }
        state.cancelOutcome?.let { outcome ->
            val key = when (outcome) {
                ai.ciris.mobile.shared.viewmodels.AddDeviceCancelOutcome.WITHDRAWN -> "mobile.add_device_cancelled"
                ai.ciris.mobile.shared.viewmodels.AddDeviceCancelOutcome.LOCAL_ONLY -> "mobile.add_device_cancelled_local"
                ai.ciris.mobile.shared.viewmodels.AddDeviceCancelOutcome.ALREADY_APPLIED -> "mobile.add_device_cancel_already_applied"
                ai.ciris.mobile.shared.viewmodels.AddDeviceCancelOutcome.STOPPED_RETRYING -> "mobile.add_device_stopped_retrying"
            }
            Spacer(Modifier.height(8.dp))
            Text(localizedString(key), style = type.body, color = t.dim, modifier = Modifier.testable(AddDeviceTags.CANCEL_OUTCOME, outcome.name.lowercase()))
            if (outcome == ai.ciris.mobile.shared.viewmodels.AddDeviceCancelOutcome.LOCAL_ONLY) {
                // A node at 0.5.231 or older cannot withdraw it: say what stays where.
                Text(localizedString("mobile.add_device_cancel_other"), style = type.label, color = t.dim)
                Text(localizedString("mobile.add_device_cancel_next"), style = type.label, color = t.dim)
            }
        }
    }
    if (state.confirmingCancel) {
        ConfirmSheet(
            title = localizedString("mobile.add_device_cancel_title"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.add_device_cancel_fact_here"), localizedString("mobile.add_device_withdraw_here")),
                ConfirmFact(localizedString("mobile.add_device_cancel_fact_other"), localizedString("mobile.add_device_withdraw_other")),
                ConfirmFact(localizedString("mobile.add_device_cancel_fact_next"), localizedString("mobile.add_device_withdraw_held")),
            ),
            confirmLabel = localizedString("mobile.add_device_cancel_confirm"),
            onConfirm = onConfirmCancel,
            onDismiss = onDismissCancel,
            destructive = true,
            tagPrefix = AddDeviceTags.CANCEL_CONFIRM_PREFIX,
        )
    }
}

/** The match code and where the request stands; shared by the YOU step and COMPLETE's waiting screen. */
@Composable
fun AddDeviceProgressBlock(state: AddDeviceSetupState, onNewRequest: () -> Unit) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val phase = state.phase
    // The code matters while the other device is deciding; after approval it is history.
    if (phase == AddDevicePhase.REQUESTED) {
        state.matchCode?.let { code ->
            Text(localizedString("mobile.add_device_match_title"), style = type.body, color = t.ink)
            Spacer(Modifier.height(6.dp))
            MatchCode(code, AddDeviceTags.MATCH_CODE)
            Spacer(Modifier.height(8.dp))
        }
    }
    val (key, tone) = when (phase) {
        AddDevicePhase.REQUESTED -> "mobile.add_device_requested" to t.dim
        AddDevicePhase.APPROVED -> "mobile.add_device_approved" to t.ok
        AddDevicePhase.APPLIED -> "mobile.add_device_applied" to t.ok
        AddDevicePhase.DENIED -> "mobile.add_device_denied" to t.danger
        // With a detail, the OTHER device no longer holds the request (its 15
        // minutes ran out, or an approval went uncollected for 15). Without
        // one, this device's own 15 minutes ran out.
        AddDevicePhase.EXPIRED -> (if (state.detail != null) "mobile.add_device_expired_elsewhere" else "mobile.add_device_expired") to t.danger
        AddDevicePhase.FAILED -> "mobile.add_device_failed" to t.danger
        AddDevicePhase.INTERRUPTED -> "mobile.add_device_interrupted" to t.danger
        // CIRISServer#792: the link to the other device can take minutes to come up.
        // CIRISServer#798: the other device's route has not reached this one yet;
        // the node's own id names the unannounced case (pairing QR or LAN).
        AddDevicePhase.RETRYING -> (
            if (state.refusalReasonId == SetupViewModel.ADD_DEVICE_OWNER_ROUTE_PENDING) SetupViewModel.ADD_DEVICE_OWNER_ROUTE_PENDING
            else "mobile.add_device_unreachable_retrying"
            ) to t.dim
        else -> "mobile.add_device_sending" to t.dim
    }
    val line = localizedString(key)
    Text(line, style = type.body, color = tone, modifier = Modifier.testable(AddDeviceTags.STATUS, phase.name.lowercase()))
    if ((phase == AddDevicePhase.APPROVED || phase == AddDevicePhase.APPLIED) && state.ownerKeyId != null) {
        // The identity is shown, never asked for: the server names it once the approval verifies.
        val joining = localizedString("mobile.add_device_joining", "owner", state.ownerKeyId)
        Spacer(Modifier.height(4.dp))
        Text(joining, style = type.signed, color = t.ink, modifier = Modifier.testable(AddDeviceTags.OWNER, state.ownerKeyId))
    }
    if (phase == AddDevicePhase.FAILED && !state.detail.isNullOrBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(state.detail, style = type.label, color = t.dim)
    }
    if (phase.needsNewRequest) {
        Spacer(Modifier.height(8.dp))
        CirisButton(
            label = localizedString("mobile.add_device_send_new"),
            tag = AddDeviceTags.NEW_REQUEST,
            enabled = state.contact.isNotBlank(),
            onClick = onNewRequest,
        )
    }
}

/**
 * COMPLETE on the add-device path when `add-device/complete` answered 409
 * `add_device.not_approved`: the rest of the wizard is done, the other device
 * has not approved yet. No countdown here: once approved, the approval is held
 * on THIS node with no expiry, so the other device's window does not matter.
 */
@Composable
fun AddDeviceWaiting(
    state: AddDeviceSetupState,
    onRetry: () -> Unit,
    onNewRequest: () -> Unit,
    retryEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    CardShell(modifier = modifier, tag = AddDeviceTags.WAITING) {
        Text(localizedString("mobile.add_device_waiting_title"), style = type.title, color = t.ink)
        Spacer(Modifier.height(4.dp))
        Text(localizedString("mobile.add_device_waiting_body"), style = type.body, color = t.dim)
        Spacer(Modifier.height(12.dp))
        AddDeviceProgressBlock(state = state, onNewRequest = onNewRequest)
        if (state.phase == AddDevicePhase.REQUESTED || state.phase == AddDevicePhase.EXPIRED) {
            // CIRISServer#794: from two devices an addition is HELD for the others to
            // agree; this device keeps "requested" and its poll ends at 15 minutes, so a
            // later landing needs a new request from here. Said once, plainly.
            val note = localizedString("mobile.add_device_held_note")
            Spacer(Modifier.height(6.dp))
            Text(note, style = type.label, color = t.dim, modifier = Modifier.testable(AddDeviceTags.WAITING_HELD_NOTE, state.phase.name.lowercase()))
        }
        if (!state.phase.needsNewRequest) {
            Spacer(Modifier.height(8.dp))
            CirisTextButton(
                label = localizedString("mobile.add_device_check_again"),
                tag = AddDeviceTags.WAITING_RETRY,
                enabled = retryEnabled,
                onClick = onRetry,
            )
        }
    }
}

// ════════════════════════════ THE APPROVING DEVICE ════════════════════════════

/**
 * **Add a device** on the device the person already has (CSD-094), on My
 * Identity next to the device roster. Open the window, see who is asking,
 * compare the match code, approve or deny — each behind a ConfirmSheet.
 *
 * The window gates NEW requests only. When it closes the requests already
 * listed stay approvable, each until its own 15 minutes run out, and "Open
 * again" reopens it.
 */
@Composable
fun ApproveDeviceCard(
    viewModel: ApproveDeviceViewModel,
    modifier: Modifier = Modifier,
    /** Brings the roster card (CSD-095) into view, where the held change is answered. */
    onOpenRoster: (() -> Unit)? = null,
    /** The lone path's window, as the roster card last read it (`w_self_secs`). */
    heldWindowSecs: Long = ai.ciris.mobile.shared.models.federation.ROSTER_W_SELF_DEFAULT_SECS,
) {
    val state by viewModel.state.collectAsState()
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    // Poll while visible: awaited, never overlapping (refreshNow holds a lock).
    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.refreshNow()
            delay(3_000)
        }
    }
    // The countdowns tick locally between polls.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }

    CardShell(modifier = modifier, tag = AddDeviceTags.APPROVE_CARD, accent = t.brand) {
        Text(localizedString("mobile.approve_device_title"), style = type.title, color = t.ink)
        Spacer(Modifier.height(4.dp))
        Text(localizedString("mobile.approve_device_desc"), style = type.body, color = t.dim)
        Spacer(Modifier.height(10.dp))

        if (state.nodeTooOld) {
            val why = localizedString("mobile.approve_device_too_old")
            Text(why, style = type.body, color = t.dim, modifier = Modifier.testable(AddDeviceTags.APPROVE_TOO_OLD, why))
            return@CardShell
        }

        val windowLeft = state.windowSecsLeftAt(now)
        if (windowLeft != null) {
            val line = localizedString("mobile.approve_device_window", "time", formatCountdown(windowLeft))
            Text(line, style = type.body, color = t.ok, modifier = Modifier.testable(AddDeviceTags.APPROVE_WINDOW, "open"))
        } else {
            if (state.loaded) {
                val line = localizedString("mobile.approve_device_window_closed")
                Text(line, style = type.body, color = t.dim, modifier = Modifier.testable(AddDeviceTags.APPROVE_WINDOW, "closed"))
                Spacer(Modifier.height(8.dp))
            }
            CirisButton(
                label = localizedString(if (state.windowWasOpen) "mobile.approve_device_open_again" else "mobile.approve_device_open"),
                tag = AddDeviceTags.APPROVE_OPEN,
                enabled = !state.opening,
                onClick = { viewModel.open() },
            )
        }

        Spacer(Modifier.height(10.dp))
        val live = state.requests.filter { state.requestExpiresInSecs(it, now) > 0 }
        if (state.loaded && live.isEmpty()) {
            StateBlock(
                ListState.Empty(localizedString("mobile.approve_device_empty")),
                tag = AddDeviceTags.APPROVE_EMPTY,
                inline = true,
            )
        }
        live.forEach { req -> RequestRow(req, state.requestExpiresInSecs(req, now), busy = state.working != null, viewModel = viewModel) }

        state.refusal?.let { r ->
            RefusalBlock(r.reasonId, r.detail, localizedString("mobile.approve_device_refused"), AddDeviceTags.APPROVE_REFUSAL)
        }
        state.outcome?.let { o ->
            Spacer(Modifier.height(8.dp))
            val line = when (o) {
                is DeviceRequestOutcome.Approved -> localizedString("mobile.approve_device_approved", "label", o.label.ifBlank { localizedString("mobile.approve_device_unnamed") })
                is DeviceRequestOutcome.Denied -> localizedString("mobile.approve_device_denied", "label", o.label.ifBlank { localizedString("mobile.approve_device_unnamed") })
                // 202 {held: true} (CIRISServer#794), or 409 add_device.roster_change_held on
                // 0.5.232 without it (CIRISServer#798): pending, not failed.
                is DeviceRequestOutcome.Held -> localizedString("mobile.approve_device_held", "label", o.label.ifBlank { localizedString("mobile.approve_device_unnamed") })
            }
            Text(
                line,
                style = type.body,
                color = if (o is DeviceRequestOutcome.Approved) t.ok else t.dim,
                modifier = Modifier.testable(
                    AddDeviceTags.APPROVE_OUTCOME,
                    when (o) {
                        is DeviceRequestOutcome.Approved -> "approved"
                        is DeviceRequestOutcome.Denied -> "denied"
                        is DeviceRequestOutcome.Held -> "held"
                    },
                ),
            )
            if (o is DeviceRequestOutcome.Held) {
                val hours = (heldWindowSecs / 3600).coerceAtLeast(1)
                val window = localizedString("mobile.roster_window_hours", "hours", hours.toString())
                Spacer(Modifier.height(4.dp))
                Text(
                    localizedString("mobile.approve_device_held_window", "window", window),
                    style = type.label, color = t.dim,
                    modifier = Modifier.testable(AddDeviceTags.APPROVE_HELD_WINDOW, o.proposalId ?: "unnamed"),
                )
                if (onOpenRoster != null) {
                    CirisTextButton(
                        label = localizedString("mobile.approve_device_held_open"),
                        tag = AddDeviceTags.APPROVE_HELD_OPEN,
                        onClick = onOpenRoster,
                    )
                }
            }
        }
    }

    state.confirming?.let { c ->
        val label = c.request.label.ifBlank { localizedString("mobile.approve_device_unnamed") }
        ConfirmSheet(
            title = localizedString(if (c.approve) "mobile.approve_device_confirm_title" else "mobile.deny_device_confirm_title", "label", label),
            facts = listOf(
                ConfirmFact(localizedString("mobile.approve_device_fact_device"), deviceClassText(c.request.deviceClass)?.let { "$label · $it" } ?: label),
                ConfirmFact(localizedString("mobile.approve_device_fact_code"), c.request.matchCode, mono = true),
                ConfirmFact(
                    localizedString(if (c.approve) "mobile.approve_device_fact_effect" else "mobile.deny_device_fact_effect"),
                    localizedString(if (c.approve) "mobile.approve_device_effect" else "mobile.deny_device_effect"),
                ),
            ),
            confirmLabel = localizedString(if (c.approve) "mobile.approve_device_approve" else "mobile.approve_device_deny"),
            onConfirm = { viewModel.confirm() },
            onDismiss = { viewModel.dismissConfirm() },
            destructive = !c.approve,
            tagPrefix = AddDeviceTags.CONFIRM_PREFIX,
        )
    }
}

@Composable
private fun RequestRow(req: DeviceJoinRequest, expiresIn: Long, busy: Boolean, viewModel: ApproveDeviceViewModel) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val label = req.label.ifBlank { localizedString("mobile.approve_device_unnamed") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(CirisShape.card)
            .border(CirisShape.hairlineWidth, t.hairline, CirisShape.card)
            .padding(12.dp)
            .testable(AddDeviceTags.requestRow(req.requestId), label),
    ) {
        Text(localizedString("mobile.approve_device_consent", "label", label), style = type.body.copy(fontWeight = FontWeight.SemiBold), color = t.ink)
        deviceClassText(req.deviceClass)?.let { cls ->
            Text(
                localizedString("mobile.approve_device_class", "class", cls),
                style = type.label, color = t.dim,
                modifier = Modifier.testable(AddDeviceTags.requestClass(req.requestId), req.deviceClass),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(localizedString("mobile.approve_device_match"), style = type.label, color = t.dim)
        MatchCode(req.matchCode, AddDeviceTags.requestCode(req.requestId))
        Spacer(Modifier.height(6.dp))
        val expires = localizedString("mobile.approve_device_expires", "time", formatCountdown(expiresIn))
        Text(expires, style = type.label, color = t.dim, modifier = Modifier.testable(AddDeviceTags.requestExpires(req.requestId), expiresIn.toString()))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CirisButton(
                label = localizedString("mobile.approve_device_approve"),
                tag = AddDeviceTags.approve(req.requestId),
                enabled = !busy,
                onClick = { viewModel.askApprove(req) },
            )
            Spacer(Modifier.width(8.dp))
            CirisTextButton(
                label = localizedString("mobile.approve_device_deny"),
                tag = AddDeviceTags.deny(req.requestId),
                enabled = !busy,
                danger = true,
                onClick = { viewModel.askDeny(req) },
            )
        }
    }
}

/**
 * The words for a request's `device_class`: the bundle's when this client has
 * them, else the server's token as sent (never a guess), else nothing.
 */
@Composable
private fun deviceClassText(deviceClass: String): String? {
    val phone = localizedString("mobile.device_class_phone")
    val laptop = localizedString("mobile.device_class_laptop")
    val server = localizedString("mobile.device_class_server")
    return deviceClassDisplay(deviceClass) { key ->
        when (key) {
            "mobile.device_class_phone" -> phone
            "mobile.device_class_laptop" -> laptop
            else -> server
        }
    }
}
