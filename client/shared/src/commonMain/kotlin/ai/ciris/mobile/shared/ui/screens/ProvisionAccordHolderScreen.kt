package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.DirectoryPickerDialog
import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.platform.rememberInputSinks
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.viewmodels.ProvisionAccordHolderViewModel
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciris.mobile.shared.platform.testableVerticalScroll
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar

/**
 * **Provision Accord Holder** — the foolproof guided flow (CIRISServer #41, the
 * safe-mesh custody floor). The would-be accord holder mints their portable-2FA
 * HUMANITY_ACCORD identity from an already-FIPS-approved FIPS YubiKey.
 *
 * Three steps:
 *   1. Confirm the YubiKey is inserted + already FIPS-approved (acknowledgement +
 *      a one-line note linking to the out-of-band ykman prep).
 *   2. **Select the ML-DSA USB path** — the centerpiece. The AEAD-wrapped
 *      ML-DSA-65 seed is written to this USB folder, unwrappable only by the
 *      YubiKey (both-keys + PIN + touch).
 *   3. Provision → `POST /v1/accord/provision-holder`. On success: the minted
 *      key_id + a clear "now ask the node owner to register you" next step. On
 *      failure: the plain-language reason (no key / wrong PIN / USB not writable /
 *      not FIPS-approved).
 *
 * No crypto in the app: it only POSTs to the loopback endpoint; the node opens
 * the YubiKey + does the wrap + mints both artifacts. Touching the physical
 * YubiKey (PIN + touch) is the real authority.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvisionAccordHolderScreen(
    viewModel: ProvisionAccordHolderViewModel,
    onBack: () -> Unit,
) {
    val fipsAck by viewModel.fipsAcknowledged.collectAsState()
    val keyId by viewModel.keyId.collectAsState()
    val usbPath by viewModel.usbPath.collectAsState()
    val userPin by viewModel.userPin.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val provisionedKeyId by viewModel.provisionedKeyId.collectAsState()
    val custodyTier by viewModel.custodyTier.collectAsState()
    val yubiKeyStatus by viewModel.yubiKeyStatus.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshYubiKeyStatus() }

    // TEXT ENTRY FOR TEST AUTOMATION (CIRISClient#30). The three fields carried
    // `input_*` tags and nothing subscribed to them, so `/input` had nothing to
    // apply to: CSD-068's flow reached this screen for the first time on
    // 2026-09-29 and failed its third step on a form a person can type into.
    // Declared beside the dispatch, as SetupScreen does, so the two cannot
    // drift apart (check_ui_drivable.py fails a dispatched tag with no sink).
    rememberInputSinks("input_provision_holder_key_id", "input_provision_holder_usb_path")
    // The PIN is applied by /input and never stored or echoed (SensitiveInputs).
    rememberInputSinks("input_provision_holder_pin", sensitive = true)
    val textInputRequest by TestAutomation.textInputRequests.collectAsState()
    LaunchedEffect(textInputRequest) {
        textInputRequest?.let { request ->
            val (current, apply) = when (request.testTag) {
                "input_provision_holder_key_id" -> keyId to viewModel::setKeyId
                "input_provision_holder_usb_path" -> usbPath to viewModel::setUsbPath
                "input_provision_holder_pin" -> userPin to viewModel::setUserPin
                else -> return@let
            }
            apply(if (request.clearFirst) request.text else current + request.text)
            TestAutomation.clearTextInputRequest()
        }
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("mobile.provision_holder_title")) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testableClickable("btn_provision_holder_back") { onBack() },
                    ) {
                        Icon(CIRISIcons.arrowBack, contentDescription = localizedString("mobile.common_back"))
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
                .testableVerticalScroll(),
        ) {
            Spacer(Modifier.height(8.dp))
            // The empty state: the three steps, none done yet — and ONLY then.
            // CSD-068 declares this tag as the empty state and
            // `provision_holder_error` as the error; drawn in every state, a
            // refused submit showed both at once, and error and empty must
            // never look alike (CSD/3 §2.2; the local Linux leg, 2026-09-29).
            if (!busy && error == null && provisionedKeyId == null) {
                Text(
                    text = localizedString("mobile.provision_holder_subtitle"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable("txt_provision_holder_start"),
                )
            }

            // ── Success state ────────────────────────────────────────────────
            val doneKeyId = provisionedKeyId
            if (doneKeyId != null) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().testable("provision_holder_success"),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                CIRISIcons.check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                localizedString("mobile.provision_holder_success_title"),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            doneKeyId,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.testable("txt_provision_holder_key_id"),
                        )
                        Spacer(Modifier.height(4.dp))
                        // The custody class the node recorded — a producer claim
                        // (CC 4.2.2.1), shown to the producer rather than dropped.
                        Text(
                            localizedString("mobile.provision_holder_custody_label") + " " +
                                (custodyTier ?: localizedString("mobile.provision_holder_custody_none")),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.testable("txt_provision_holder_custody"),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            localizedString("mobile.provision_holder_success_next"),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // The key id is handed to the node owner out of band.
                            val copyKey = {
                                clipboard.setText(AnnotatedString(doneKeyId))
                                copied = true
                            }
                            OutlinedButton(
                                onClick = copyKey,
                                modifier = Modifier.testableClickable("btn_provision_holder_copy") { copyKey() },
                            ) {
                                Text(
                                    localizedString(
                                        if (copied) "mobile.provision_holder_copied" else "mobile.provision_holder_copy",
                                    ),
                                )
                            }
                            OutlinedButton(
                                onClick = { copied = false; viewModel.reset() },
                                modifier = Modifier.testableClickable("btn_provision_holder_again") {
                                    copied = false
                                    viewModel.reset()
                                },
                            ) {
                                Text(localizedString("mobile.provision_holder_again"))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            // ── Error ─────────────────────────────────────────────────────────
            error?.let { e ->
                val msg = e.detail?.let { localizedString(e.key, "detail", it) } ?: localizedString(e.key)
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp).testable("provision_holder_error"),
                    )
                }
            }

            // ── Step 1: YubiKey inserted + already FIPS-approved ───────────────
            Spacer(Modifier.height(16.dp))
            StepHeader(1, localizedString("mobile.provision_holder_step1_title"))
            Spacer(Modifier.height(4.dp))
            Text(
                localizedString("mobile.provision_holder_step1_desc"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().testableClickable("chk_provision_holder_fips") {
                    viewModel.setFipsAcknowledged(!fipsAck)
                },
            ) {
                Checkbox(
                    checked = fipsAck,
                    onCheckedChange = { viewModel.setFipsAcknowledged(it) },
                    enabled = !busy,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    localizedString("mobile.provision_holder_fips_ack"),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                localizedString("mobile.provision_holder_prep_note"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Whether a token is plugged in at all is the node's to say
            // (`GET /v1/accord/yubikey-status`); FIPS approval stays the person's claim.
            Spacer(Modifier.height(8.dp))
            YubiKeyStatusBanner(yubiKeyStatus) { viewModel.refreshYubiKeyStatus() }

            // ── key_id ─────────────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            Text(
                localizedString("mobile.provision_holder_key_id_label"),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = keyId,
                onValueChange = { viewModel.setKeyId(it) },
                singleLine = true,
                enabled = !busy,
                label = { Text(localizedString("mobile.provision_holder_key_id_hint")) },
                modifier = Modifier.fillMaxWidth().testable("input_provision_holder_key_id"),
            )

            // ── Step 2 (the centerpiece): the ML-DSA USB path ──────────────────
            Spacer(Modifier.height(20.dp))
            StepHeader(2, localizedString("mobile.provision_holder_step2_title"))
            Spacer(Modifier.height(4.dp))
            Text(
                localizedString("mobile.provision_holder_step2_desc"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // A native directory-picker affordance ("Browse…") + a validated path
            // field. The field stays the source of truth; the picker just fills it.
            // Desktop opens a real folder chooser; mobile/wasm fall back to typing.
            var showDirPicker by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = usbPath,
                onValueChange = { viewModel.setUsbPath(it) },
                singleLine = true,
                enabled = !busy,
                isError = error != null && usbPath.isBlank(),
                label = { Text(localizedString("mobile.provision_holder_usb_label")) },
                placeholder = { Text(localizedString("mobile.provision_holder_usb_placeholder")) },
                leadingIcon = {
                    Icon(CIRISIcons.pkg, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    TextButton(
                        onClick = { if (!busy) showDirPicker = true },
                        enabled = !busy,
                        modifier = Modifier.testableClickable("btn_provision_holder_usb_browse", enabled = !busy) {
                            if (!busy) showDirPicker = true
                        },
                    ) {
                        Text(localizedString("mobile.provision_holder_usb_browse"))
                    }
                },
                modifier = Modifier.fillMaxWidth().testable("input_provision_holder_usb_path"),
            )
            DirectoryPickerDialog(
                show = showDirPicker,
                purpose = ai.ciris.mobile.shared.platform.DirectoryPickerPurpose.UsbCustody,
                onDirectoryPicked = {
                    viewModel.setUsbPath(it)
                    showDirPicker = false
                },
                onDismiss = { showDirPicker = false },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                localizedString("mobile.provision_holder_usb_guidance"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Optional PIN ───────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = userPin,
                onValueChange = { viewModel.setUserPin(it) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(localizedString("mobile.provision_holder_pin_label")) },
                modifier = Modifier.fillMaxWidth().testable("input_provision_holder_pin"),
            )
            Text(
                localizedString("mobile.provision_holder_pin_note"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Step 3: Provision ──────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            val canProvision = fipsAck && keyId.isNotBlank() && usbPath.isNotBlank() && !busy
            // `enabled` goes to the automation handler too (CIRISClient#69):
            // without it `/click` ran provision() behind the greyed-out
            // button and put the "confirm your YubiKey" banner on a form
            // nobody had submitted (Windows leg, run 36600766576).
            Button(
                onClick = { viewModel.provision() },
                enabled = canProvision,
                modifier = Modifier.fillMaxWidth().testableClickable(
                    "btn_provision_holder_submit",
                    enabled = canProvision,
                ) {
                    viewModel.provision()
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp).testable("spinner_provision_holder"),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(localizedString("mobile.provision_holder_busy"))
                } else {
                    Icon(CIRISIcons.keySecure, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(localizedString("mobile.provision_holder_submit"))
                }
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                Text(
                    localizedString("mobile.provision_holder_touch_note"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A numbered "Step N · Title" header row for the guided flow. */
@Composable
private fun StepHeader(number: Int, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    number.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
