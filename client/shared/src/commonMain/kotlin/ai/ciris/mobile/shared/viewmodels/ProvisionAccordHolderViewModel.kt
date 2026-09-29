package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.federation.YubiKeyStatus
import ai.ciris.mobile.shared.models.federation.firstStringField
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the **Provision Accord Holder** guided flow (CIRISServer #41, the
 * safe-mesh custody floor). The would-be accord holder mints their portable-2FA
 * HUMANITY_ACCORD identity from an already-FIPS-approved FIPS YubiKey + a chosen
 * ML-DSA USB path.
 *
 * No-crypto posture (mirrors [AccordViewModel] / Delegations): the app holds NO
 * keys and does NO crypto. The single `provision` action POSTs to the loopback
 * `POST /v1/accord/provision-holder`; the node opens the YubiKey, AEAD-wraps the
 * ML-DSA seed to the USB, and mints the two artifacts. Touching the physical
 * YubiKey (PIN + touch) is the real authority.
 *
 * The flow is foolproof by construction: the YubiKey/FIPS acknowledgement (step
 * 1) and a non-empty ML-DSA USB path (step 2) gate the provision action (step 3),
 * and every device / USB / PIN / touch failure is mapped to plain language.
 *
 * Two facts the node states and this card shows rather than drops (CSD-068 §2.2):
 * whether a token is plugged in at all (`GET /v1/accord/yubikey-status` — the
 * FIPS-approval acknowledgement stays a claim, CC 4.2.2.1), and the custody
 * class the node recorded in the minted `custody_attestation`.
 */
class ProvisionAccordHolderViewModel(
    private val apiClient: CIRISApiClient,
) : ViewModel() {

    companion object {
        private const val TAG = "ProvisionHolderVM"
    }

    // ── Step 1: the YubiKey is inserted + already FIPS-approved (acknowledged). ──
    private val _fipsAcknowledged = MutableStateFlow(false)
    val fipsAcknowledged: StateFlow<Boolean> = _fipsAcknowledged.asStateFlow()

    // ── The holder's federation key_id (alias the artifacts are minted under). ──
    private val _keyId = MutableStateFlow("")
    val keyId: StateFlow<String> = _keyId.asStateFlow()

    // ── Step 2 (the centerpiece): the ML-DSA USB directory path. ────────────────
    private val _usbPath = MutableStateFlow("")
    val usbPath: StateFlow<String> = _usbPath.asStateFlow()

    // ── Optional PIV PIN (the token may prompt out of band when blank). ─────────
    private val _userPin = MutableStateFlow("")
    val userPin: StateFlow<String> = _userPin.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * A failure, as the KEY of the plain-language sentence (the screen
     * localizes) plus the node's detail where the sentence has a `{detail}`
     * slot. A view model never holds a rendered string.
     */
    data class ProvisionError(val key: String, val detail: String? = null)

    private val _error = MutableStateFlow<ProvisionError?>(null)
    val error: StateFlow<ProvisionError?> = _error.asStateFlow()

    /** The minted holder key_id, set once provisioning succeeds. Non-null = done. */
    private val _provisionedKeyId = MutableStateFlow<String?>(null)
    val provisionedKeyId: StateFlow<String?> = _provisionedKeyId.asStateFlow()

    /**
     * The custody class the node recorded in the minted `custody_attestation`
     * (`custody_tier`, e.g. `portable_2fa`) — the CC 4.2.2.1 producer claim, shown
     * to the producer. Null when the attestation names none this app can find.
     */
    private val _custodyTier = MutableStateFlow<String?>(null)
    val custodyTier: StateFlow<String?> = _custodyTier.asStateFlow()

    /** The inserted token's readiness (`GET /v1/accord/yubikey-status`); null until read. */
    private val _yubiKeyStatus = MutableStateFlow<YubiKeyStatus?>(null)
    val yubiKeyStatus: StateFlow<YubiKeyStatus?> = _yubiKeyStatus.asStateFlow()

    /** Re-probe the inserted YubiKey. "Is a token plugged in" need not be a claim. */
    fun refreshYubiKeyStatus() {
        viewModelScope.launch {
            _yubiKeyStatus.value = try {
                apiClient.getYubiKeyStatus(CIRISApiClient.LOCAL_NODE_URL)
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[refreshYubiKeyStatus] ${e.message}")
                null
            }
        }
    }

    fun setFipsAcknowledged(value: Boolean) {
        _fipsAcknowledged.value = value
    }

    fun setKeyId(value: String) {
        _keyId.value = value
    }

    fun setUsbPath(value: String) {
        _usbPath.value = value
    }

    fun setUserPin(value: String) {
        _userPin.value = value
    }

    /** True once steps 1 + 2 are satisfied — gates the Provision action (step 3). */
    fun canProvision(): Boolean =
        _fipsAcknowledged.value &&
            _keyId.value.isNotBlank() &&
            _usbPath.value.isNotBlank() &&
            !_busy.value

    /**
     * Step 3 — provision. POSTs to the loopback endpoint; the node does the
     * crypto. On success [provisionedKeyId] is set (the UI shows the success +
     * the "now ask the node owner to register you" next step). On failure the
     * plain-language reason is surfaced in [error].
     */
    fun provision() {
        if (_busy.value) return
        val keyId = _keyId.value.trim()
        val usb = _usbPath.value.trim()
        if (!_fipsAcknowledged.value) {
            _error.value = ProvisionError("mobile.provision_holder_err_ack")
            return
        }
        if (keyId.isBlank()) {
            _error.value = ProvisionError("mobile.provision_holder_err_key_id")
            return
        }
        if (usb.isBlank()) {
            _error.value = ProvisionError("mobile.provision_holder_err_usb")
            return
        }
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val res = apiClient.provisionAccordHolder(
                    keyId = keyId,
                    mldsaUsbPath = usb,
                    userPin = _userPin.value.takeIf { it.isNotBlank() },
                )
                _custodyTier.value = firstStringField(res.custodyAttestation, "custody_tier")
                _provisionedKeyId.value = res.keyId
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[provision] ${e.message}")
                _error.value = plainLanguageError(e.message.orEmpty())
            } finally {
                _busy.value = false
            }
        }
    }

    /** Reset to provision another holder (or retry after a fix). */
    fun reset() {
        _provisionedKeyId.value = null
        _custodyTier.value = null
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Map the raw server / transport error to plain language a holder can act on.
     * The server already returns human-readable messages; this catches the common
     * device / USB / PIN / touch / FIPS / feature failures and the auth statuses.
     */
    private fun plainLanguageError(msg: String): ProvisionError = when {
        msg.contains("501") || msg.contains("NotImplemented", ignoreCase = true) ||
            msg.contains("without the `pkcs11`") ->
            ProvisionError("mobile.provision_holder_err_pkcs11")
        msg.contains("YubiKey", ignoreCase = true) || msg.contains("slot-", ignoreCase = true) ->
            ProvisionError("mobile.provision_holder_err_yubikey")
        msg.contains("PIN", ignoreCase = true) ->
            ProvisionError("mobile.provision_holder_err_pin")
        msg.contains("not writable", ignoreCase = true) || msg.contains("not a directory", ignoreCase = true) ->
            ProvisionError("mobile.provision_holder_err_usb_unwritable")
        msg.contains("ykman", ignoreCase = true) ->
            ProvisionError("mobile.provision_holder_err_ykman")
        msg.contains("401") -> ProvisionError("mobile.provision_holder_err_owner")
        msg.contains("403") -> ProvisionError("mobile.provision_holder_err_loopback")
        else -> ProvisionError("mobile.provision_holder_err_other", detail = msg)
    }
}
