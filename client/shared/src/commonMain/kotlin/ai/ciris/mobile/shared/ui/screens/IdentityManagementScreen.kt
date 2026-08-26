package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.DirectoryPickerDialog
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.viewmodels.IdentityManagementViewModel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **Identity Management** — "manage my self + log in as myself on another device"
 * (CIRISServer#76, CEG §5.6.8.8 / §11.7). The client half of the second-occurrence /
 * laptop-loss-resilience feature.
 *
 * Two roles, one page:
 *  1. **Roster** — your self fed-ID + the list of occurrences (devices) bound to it,
 *     each with a revoke control. Adding a second device makes your fed-ID survive
 *     the loss of the first.
 *  2. **Add a device** (on the primary) — paste / scan the NEW device's fedcode, then
 *     enroll it. The node signs the enrollment with your fed-ID.
 *  3. **Log in as yourself on another device** (on the NEW device) — mint a local
 *     fed-ID and show its fedcode (text + QR) for the primary to enroll.
 *
 * The app holds NO keys and performs NO crypto — the LOCAL node signs on your behalf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityManagementScreen(
    viewModel: IdentityManagementViewModel,
    onBack: () -> Unit,
    /**
     * Open the node's repair route for a subject-blind identity
     * (CIRISServer#490). Defaulted to a no-op so every existing call site
     * compiles: the button that uses it only renders when the node supplied a
     * route, and a host that has not wired one has nothing to open.
     */
    onOpenRepair: (String) -> Unit = {},
) {
    val identityKeyId by viewModel.identityKeyId.collectAsState()
    val occurrences by viewModel.occurrences.collectAsState()
    val enrolledIdentity by viewModel.enrolledIdentity.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val subjectBlind by viewModel.subjectBlind.collectAsState()

    var deviceCode by remember { mutableStateOf("") }
    var pendingRevoke by remember { mutableStateOf<String?>(null) }
    // Portable software identity occurrence + associate-existing-fedID state.
    var portableDir by remember { mutableStateOf("") }
    var showPortablePicker by remember { mutableStateOf(false) }
    var associateDir by remember { mutableStateOf("") }
    var showAssociatePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizedString("mobile.identity_title")) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testableClickable("btn_identity_back") { onBack() },
                    ) {
                        Icon(CIRISIcons.arrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.testableClickable("btn_identity_refresh") { viewModel.refresh() },
                    ) {
                        Icon(CIRISIcons.refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                localizedString("mobile.identity_subtitle"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Self fed-ID card ──────────────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().testable("identity_self_card"),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        localizedString("mobile.identity_self_title"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        identityKeyId ?: localizedString("mobile.identity_self_unresolved"),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testable("identity_self_key_id"),
                    )
                }
            }

            // ── Notice / error banners ────────────────────────────────────────
            notice?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(10.dp).testable("identity_notice"),
                    )
                }
            }
            error?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp).testable("identity_error"),
                    )
                }
            }

            // ── Device roster ─────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            Text(
                localizedString("mobile.identity_roster_title"),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            if (loading && occurrences.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(localizedString("mobile.common_loading"), fontSize = 12.sp)
                }
            } else if (occurrences.isEmpty()) {
                Text(
                    localizedString("mobile.identity_roster_empty"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth().testable("identity_device_list")) {
                    occurrences.forEach { occ ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .testable("identity_row_${occ.occurrenceKeyId}"),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = deviceClassIcon(occ.deviceClass),
                                    contentDescription = occ.deviceClass,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        truncMid(occ.occurrenceKeyId),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            occ.deviceClass,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (occ.hasEncryptionPubkeys) {
                                            Spacer(Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                            ) {
                                                Text(
                                                    localizedString("mobile.identity_has_encryption"),
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                    occ.assertedAt?.let {
                                        Text(
                                            it,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = { pendingRevoke = occ.occurrenceKeyId },
                                    enabled = !busy,
                                    modifier = Modifier.testableClickable("btn_identity_revoke_${occ.occurrenceKeyId}") {
                                        pendingRevoke = occ.occurrenceKeyId
                                    },
                                ) {
                                    Text(localizedString("mobile.identity_revoke"))
                                }
                            }
                        }
                    }
                }
            }

            // ── Add a device (on the primary) ─────────────────────────────────
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        localizedString("mobile.identity_add_title"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        localizedString("mobile.identity_add_desc"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deviceCode,
                        onValueChange = { deviceCode = it },
                        label = { Text(localizedString("mobile.identity_device_code_label")) },
                        singleLine = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testable("input_identity_device_code"),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                viewModel.addDevice(deviceCode)
                                deviceCode = ""
                            },
                            enabled = !busy && deviceCode.isNotBlank(),
                            modifier = Modifier.testableClickable("btn_identity_add_device") {
                                viewModel.addDevice(deviceCode)
                                deviceCode = ""
                            },
                        ) {
                            Text(localizedString("mobile.identity_add_button"))
                        }
                        Spacer(Modifier.width(8.dp))
                        // QR-scan affordance. Actual camera capture is a platform
                        // stub today (NotSupported) — paste the fedcode above.
                        OutlinedButton(
                            onClick = { /* TODO: platform QR capture; paste fallback above. */ },
                            enabled = false,
                            modifier = Modifier.testable("btn_identity_scan_qr"),
                        ) {
                            Text(localizedString("mobile.identity_scan_qr"))
                        }
                        if (busy) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            // ── Log in as yourself on another device (on the NEW device) ──────
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        localizedString("mobile.identity_enroll_title"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        localizedString("mobile.identity_enroll_desc"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    val minted = enrolledIdentity
                    if (minted == null) {
                        Button(
                            onClick = { viewModel.enrollThisDevice() },
                            enabled = !busy,
                            modifier = Modifier.testableClickable("btn_identity_enroll_this_device") {
                                viewModel.enrollThisDevice()
                            },
                        ) {
                            Text(localizedString("mobile.identity_enroll_button"))
                        }
                    } else {
                        Text(
                            localizedString("mobile.identity_enroll_show"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        // QR of the fedcode for the primary to scan. The fedcode text
                        // below it is the load-bearing data (paste fallback).
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            FedcodeQr(
                                value = minted.fedcode,
                                modifier = Modifier.testable("identity_enroll_qr"),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                minted.fedcode,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .testable("identity_enroll_fedcode"),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { viewModel.clearEnrolled() },
                            modifier = Modifier.testableClickable("btn_identity_enroll_dismiss") {
                                viewModel.clearEnrolled()
                            },
                        ) {
                            Text(localizedString("mobile.common_dismiss"))
                        }
                    }
                }
            }

            // ── A damaged identity, and the fact that it is repairable ────────
            //
            // Here rather than on a node-degradation banner (CIRISServer#490):
            // this is not the node failing to serve, it is a fact about THIS
            // PERSON'S portable identity — the one they import from USB in the
            // wizard and use to mint the occurrences the card below creates. It
            // sits directly above that card because that is what it affects.
            //
            // Rendered from `code`, never from `Warning.message`: the node's
            // sentence arrives already composed and never localized, so echoing
            // it would ship one English string to 29 audiences on a screen whose
            // whole subject is the operator's own identity.
            //
            // ERROR CONTAINER FROM A WARNING-SEVERITY SIGNAL, on purpose. The
            // node sends `warning` because `error` would set degraded_mode, and
            // this condition is permanent until repaired — it would pin the node
            // as degraded forever over something that degrades no service.
            // Severity answers "is this node degrading?"; it does not answer
            // "how loudly does the person whose identity is broken need to hear
            // it". That second question is answered here, off the code.
            subjectBlind?.let { blind ->
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().testable("identity_repair_card"),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            localizedString("mobile.identity_repair_title"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            localizedString("mobile.identity_repair_key_label"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            blind.keyId,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.testable("identity_repair_key_id"),
                        )
                        Spacer(Modifier.height(8.dp))
                        // Four sentences, in the order the operator needs them:
                        // what is wrong, that it is recoverable, what recovery
                        // takes, and that nothing is being deleted.
                        //
                        // Spelled out rather than looped over a list of key
                        // names: the localization guard's reference scan reads
                        // string-literal call sites only, so a key reached
                        // through a variable is a key it cannot see — and an id
                        // nothing appears to reference is an id that gets
                        // deleted as dead.
                        //
                        // The scan is literal enough to have flagged a call
                        // written INSIDE this comment while it was being
                        // explained, which is the behaviour working.
                        listOf(
                            localizedString("mobile.identity_repair_body"),
                            localizedString("mobile.identity_repair_recoverable"),
                            localizedString("mobile.identity_repair_needs"),
                            localizedString("mobile.identity_repair_keeps"),
                        ).forEach { sentence ->
                            Text(
                                sentence,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        // Only when the node named somewhere to go. An action
                        // button that leads nowhere is worse than none on a card
                        // whose entire message is "this is fixable".
                        blind.actionUrl?.let { url ->
                            TextButton(
                                onClick = { onOpenRepair(url) },
                                modifier = Modifier.testableClickable("btn_identity_repair_action") {
                                    onOpenRepair(url)
                                },
                            ) {
                                Text(localizedString("mobile.identity_repair_action"))
                            }
                        }
                    }
                }
            }

            // ── Create portable software identity occurrence → directory ──────
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().testable("identity_portable_card"),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        localizedString("mobile.identity_portable_title"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    // The danger sublabel — a software keyset is inherently insecure.
                    Text(
                        localizedString("mobile.identity_portable_danger"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        localizedString("mobile.identity_portable_desc"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = portableDir,
                        onValueChange = { portableDir = it },
                        singleLine = true,
                        enabled = !busy,
                        label = { Text(localizedString("mobile.identity_portable_dir_label")) },
                        placeholder = { Text(localizedString("mobile.identity_portable_dir_placeholder")) },
                        trailingIcon = {
                            TextButton(
                                onClick = { if (!busy) showPortablePicker = true },
                                enabled = !busy,
                                modifier = Modifier.testableClickable("btn_identity_portable_browse") {
                                    if (!busy) showPortablePicker = true
                                },
                            ) {
                                Text(localizedString("mobile.identity_browse"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testable("input_identity_portable_dir"),
                    )
                    DirectoryPickerDialog(
                        show = showPortablePicker,
                        purpose = ai.ciris.mobile.shared.platform.DirectoryPickerPurpose.UsbCustody,
                        onDirectoryPicked = {
                            portableDir = it
                            showPortablePicker = false
                        },
                        onDismiss = { showPortablePicker = false },
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.createPortableOccurrence(portableDir) },
                        enabled = !busy && portableDir.isNotBlank(),
                        modifier = Modifier.testableClickable("btn_identity_portable_create") {
                            viewModel.createPortableOccurrence(portableDir)
                        },
                    ) {
                        Text(localizedString("mobile.identity_portable_button"))
                    }
                }
            }

            // ── Associate existing fedID ──────────────────────────────────────
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().testable("identity_associate_card"),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        localizedString("mobile.identity_associate_title"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        localizedString("mobile.identity_associate_desc"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = associateDir,
                        onValueChange = { associateDir = it },
                        singleLine = true,
                        enabled = !busy,
                        label = { Text(localizedString("mobile.identity_associate_dir_label")) },
                        placeholder = { Text(localizedString("mobile.identity_portable_dir_placeholder")) },
                        trailingIcon = {
                            TextButton(
                                onClick = { if (!busy) showAssociatePicker = true },
                                enabled = !busy,
                                modifier = Modifier.testableClickable("btn_identity_associate_browse") {
                                    if (!busy) showAssociatePicker = true
                                },
                            ) {
                                Text(localizedString("mobile.identity_browse"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testable("input_identity_associate_dir"),
                    )
                    DirectoryPickerDialog(
                        show = showAssociatePicker,
                        purpose = ai.ciris.mobile.shared.platform.DirectoryPickerPurpose.UsbCustody,
                        onDirectoryPicked = {
                            associateDir = it
                            showAssociatePicker = false
                        },
                        onDismiss = { showAssociatePicker = false },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { viewModel.associateFedId(sourceDir = associateDir) },
                            enabled = !busy && associateDir.isNotBlank(),
                            modifier = Modifier.testableClickable("btn_identity_associate_dir") {
                                viewModel.associateFedId(sourceDir = associateDir)
                            },
                        ) {
                            Text(localizedString("mobile.identity_associate_dir_button"))
                        }
                        Spacer(Modifier.width(8.dp))
                        // YubiKey-backed association — clearly disabled "coming soon"
                        // until the on-device PKCS#11 read is wired (server 501s today).
                        OutlinedButton(
                            onClick = { /* gated — see mobile.identity_associate_yubikey */ },
                            enabled = false,
                            modifier = Modifier.testable("btn_identity_associate_yubikey"),
                        ) {
                            Text(localizedString("mobile.identity_associate_yubikey"))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ── Revoke confirmation dialog ────────────────────────────────────────────
    pendingRevoke?.let { keyId ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text(localizedString("mobile.identity_revoke_confirm")) },
            text = { Text(localizedString("mobile.identity_revoke_confirm_body")) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRevoke = null
                        viewModel.revoke(keyId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.testableClickable("btn_identity_revoke_confirm") {
                        pendingRevoke = null
                        viewModel.revoke(keyId)
                    },
                ) {
                    Text(localizedString("mobile.identity_revoke"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRevoke = null },
                    modifier = Modifier.testableClickable("btn_identity_revoke_cancel") {
                        pendingRevoke = null
                    },
                ) {
                    Text(localizedString("mobile.common_cancel"))
                }
            },
        )
    }
}

/** Map a `device_class` to an icon (phone | laptop | agent). */
private fun deviceClassIcon(deviceClass: String) = when (deviceClass.lowercase()) {
    "phone" -> CIRISIcons.person
    "agent" -> CIRISIcons.agent
    else -> CIRISIcons.identity // laptop / default
}

/** Truncate a long key_id in the middle for compact display. */
private fun truncMid(s: String, head: Int = 12, tail: Int = 8): String =
    if (s.length <= head + tail + 1) s else "${s.take(head)}…${s.takeLast(tail)}"

/**
 * A deterministic QR-style matrix rendered from [value] on a Canvas. This draws
 * scan-target finder patterns plus a data field hashed from the fedcode so the
 * card reads as a QR. NOTE: this is a visual stand-in — a real scannable QR needs
 * a QR-encoder library wired into commonMain (TODO). The fedcode text shown beside
 * it is the load-bearing data (paste fallback always works).
 */
@Composable
private fun FedcodeQr(value: String, modifier: Modifier = Modifier) {
    val modules = 21 // QR v1 grid
    Box(
        modifier = modifier
            .size(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(144.dp)) {
            val cell = size.width / modules
            // A stable per-cell bit derived from the fedcode bytes.
            fun dataBit(r: Int, c: Int): Boolean {
                if (value.isEmpty()) return false
                val idx = (r * modules + c)
                val ch = value[idx % value.length].code
                return ((ch ushr (idx % 7)) and 1) == 1
            }
            fun inFinder(r: Int, c: Int): Boolean {
                val finders = listOf(0 to 0, 0 to (modules - 7), (modules - 7) to 0)
                return finders.any { (fr, fc) -> r in fr..fr + 6 && c in fc..fc + 6 }
            }
            for (r in 0 until modules) {
                for (c in 0 until modules) {
                    val dark = if (inFinder(r, c)) {
                        // Finder pattern: outer ring + center 3x3.
                        val fr = if (r < 7) 0 else modules - 7
                        val fc = if (c < 7) 0 else if (c >= modules - 7) modules - 7 else 0
                        val lr = r - fr
                        val lc = c - fc
                        lr == 0 || lr == 6 || lc == 0 || lc == 6 || (lr in 2..4 && lc in 2..4)
                    } else {
                        dataBit(r, c)
                    }
                    if (dark) {
                        drawRect(
                            color = Color.Black,
                            topLeft = androidx.compose.ui.geometry.Offset(c * cell, r * cell),
                            size = androidx.compose.ui.geometry.Size(cell, cell),
                        )
                    }
                }
            }
        }
    }
}
