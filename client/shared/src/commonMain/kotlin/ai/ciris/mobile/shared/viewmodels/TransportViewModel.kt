package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.ui.screens.ReadFailure
import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.federation.FederationIdentity
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Transport screen (node concern).
 *
 * Two halves:
 *  - **Read**: the node's current transports — reuses [CIRISApiClient.getFederationIdentity]
 *    (GET /v1/federation/identity), which carries the Reticulum federation/signer
 *    address, peer counts, and advertised transport capabilities. No new read
 *    endpoint is invented.
 *  - **Write**: a serial LoRa / RNode radio configuration form persisted as
 *    `net.radio.*` `config:*` values via the existing owner-gated
 *    [CIRISApiClient.updateConfig] (PUT /v1/config/{key}) path.
 *
 * Radio activation is desktop-only (a sandboxed mobile node cannot open a serial
 * port); Apply always persists config, and the screen explains where it takes
 * effect.
 */
class TransportViewModel(
    private val apiClient: CIRISApiClient,
    /**
     * Where the radio keys live: the NODE. `net.radio.*` is read by the node's
     * config reconciler (`config_reconcile.rs:217-224`) and means nothing to an
     * agent, so on a with-agent install writing them at `$baseUrl` stored them
     * where nothing reads them (CSD-031). Overridable for tests.
     */
    private val nodeUrl: () -> String = { CIRISApiClient.LOCAL_NODE_URL },
) : ViewModel() {

    companion object {
        private const val TAG = "TransportViewModel"

        // config:* keys written by the radio form (owner-gated /v1/config).
        const val KEY_ENABLED = "net.radio.enabled"
        const val KEY_SERIAL_PORT = "net.radio.serial_port"
        const val KEY_FREQUENCY_HZ = "net.radio.frequency_hz"
        const val KEY_BANDWIDTH_HZ = "net.radio.bandwidth_hz"
        const val KEY_SPREADING_FACTOR = "net.radio.spreading_factor"
        const val KEY_CODING_RATE = "net.radio.coding_rate"
        const val KEY_TX_POWER_DBM = "net.radio.tx_power_dbm"
    }

    private val _state = MutableStateFlow(TransportScreenState())
    val state: StateFlow<TransportScreenState> = _state.asStateFlow()

    private var dataLoadStarted = false

    /** Begin loading current-transport facts. Idempotent; call on screen show. */
    fun startPolling() {
        if (dataLoadStarted) return
        dataLoadStarted = true
        loadTransports()
        loadRadioConfig()
    }

    /** Allow a fresh load next time the screen becomes visible. */
    fun stopPolling() {
        dataLoadStarted = false
    }

    fun refresh() {
        loadTransports()
        loadRadioConfig()
    }

    /** Fetch the node's current transports (federation identity aggregate). */
    fun loadTransports() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val identity = apiClient.getFederationIdentity()
                PlatformLogger.i(
                    TAG,
                    "transports loaded: signer=${identity.signerKeyId.take(12)}…, peers=${identity.peerCountTotal}",
                )
                _state.update { it.copy(identity = identity, isLoading = false, error = null, loadFailure = null) }
            } catch (e: Exception) {
                PlatformLogger.e(TAG, "loadTransports failed: ${e.message}", e)
                _state.update {
                    it.withLoadFailure(e)
                }
            }
        }
    }

    /**
     * Read the saved radio keys back from the node and fill the form with them.
     * The form used to start from hard-coded defaults and never read back, so
     * it could not show what was saved — Apply then overwrote it with those
     * defaults (CSD-031). A failed read leaves the form as it is and is logged;
     * the transports card already says whether the node answers.
     */
    fun loadRadioConfig() {
        viewModelScope.launch {
            try {
                val saved = apiClient.listConfigs(prefix = "net.radio.", host = nodeUrl()).configs
                    .associateBy { it.key }
                fun text(k: String) = saved[k]?.displayValue?.takeIf { it != "(empty)" }
                _state.update { st ->
                    st.copy(
                        radioEnabled = text(KEY_ENABLED)?.equals("true", ignoreCase = true) ?: st.radioEnabled,
                        serialPort = text(KEY_SERIAL_PORT) ?: st.serialPort,
                        frequencyHz = text(KEY_FREQUENCY_HZ) ?: st.frequencyHz,
                        bandwidthHz = text(KEY_BANDWIDTH_HZ) ?: st.bandwidthHz,
                        spreadingFactor = text(KEY_SPREADING_FACTOR) ?: st.spreadingFactor,
                        codingRate = text(KEY_CODING_RATE) ?: st.codingRate,
                        txPowerDbm = text(KEY_TX_POWER_DBM) ?: st.txPowerDbm,
                        radioConfigRead = saved.isNotEmpty(),
                    )
                }
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "radio config read failed: ${e.message}")
            }
        }
    }

    // ─── Radio form field updates ───────────────────────────────────────────────

    fun updateEnabled(value: Boolean) = _state.update { it.copy(radioEnabled = value) }
    fun updateSerialPort(value: String) = _state.update { it.copy(serialPort = value) }
    fun updateFrequencyHz(value: String) = _state.update { it.copy(frequencyHz = value.filter { c -> c.isDigit() }) }
    fun updateBandwidthHz(value: String) = _state.update { it.copy(bandwidthHz = value.filter { c -> c.isDigit() }) }
    fun updateSpreadingFactor(value: String) = _state.update { it.copy(spreadingFactor = value.filter { c -> c.isDigit() }) }
    fun updateCodingRate(value: String) = _state.update { it.copy(codingRate = value.filter { c -> c.isDigit() }) }
    fun updateTxPowerDbm(value: String) = _state.update { it.copy(txPowerDbm = value.filter { c -> c.isDigit() }) }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearSuccess() = _state.update { it.copy(successMessage = null) }

    /**
     * Persist the radio form as `net.radio.*` config via the owner-gated
     * /v1/config write path, at the node, each key with the JSON type the node
     * reads. Activation happens on a desktop node
     * with the serial radio backend; on other nodes the values simply persist.
     */
    fun applyRadioConfig() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null, successMessage = null) }
            try {
                val reason = "Radio (LoRa/RNode) configured via Transport screen"
                // TYPED, at the NODE: the node reads `snap.bool()` / `snap.i64()`
                // and does not coerce "true" or "868000000" (CSD-031).
                for ((key, value) in radioConfigValues(s)) {
                    apiClient.updateConfig(key, value, reason, host = nodeUrl())
                }
                PlatformLogger.i(TAG, "radio config applied (enabled=${s.radioEnabled}, port=${s.serialPort})")
                _state.update {
                    it.copy(isSaving = false, successMessage = "Radio configuration saved")
                }
            } catch (e: Exception) {
                PlatformLogger.e(TAG, "applyRadioConfig failed: ${e.message}", e)
                _state.update {
                    it.copy(isSaving = false, error = "Failed to save radio configuration: ${e.message}")
                }
            }
        }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}

/**
 * UI state for the Transport screen: the read-only current-transport facts plus
 * the editable radio form fields. Numeric radio fields are held as strings so the
 * text inputs round-trip cleanly (validated/coerced on write).
 */
/**
 * A failed transports read: the load's own state, drawn in the transports card
 * as "this node doesn't report…" or "couldn't read" — not the one speculative
 * "node degraded?" sentence, and not in `error`, which stays for the form's
 * writes (CSD-031).
 */
/**
 * The seven `net.radio.*` values as the node reads them: a boolean, a string
 * and five integers. A numeric field left blank is not written (an empty string
 * where the node wants an i64 was a silent fallback to its default).
 */
internal fun radioConfigValues(s: TransportScreenState): List<Pair<String, kotlinx.serialization.json.JsonElement>> {
    val out = mutableListOf<Pair<String, kotlinx.serialization.json.JsonElement>>()
    out += TransportViewModel.KEY_ENABLED to kotlinx.serialization.json.JsonPrimitive(s.radioEnabled)
    out += TransportViewModel.KEY_SERIAL_PORT to kotlinx.serialization.json.JsonPrimitive(s.serialPort.trim())
    listOf(
        TransportViewModel.KEY_FREQUENCY_HZ to s.frequencyHz,
        TransportViewModel.KEY_BANDWIDTH_HZ to s.bandwidthHz,
        TransportViewModel.KEY_SPREADING_FACTOR to s.spreadingFactor,
        TransportViewModel.KEY_CODING_RATE to s.codingRate,
        TransportViewModel.KEY_TX_POWER_DBM to s.txPowerDbm,
    ).forEach { (k, v) -> v.trim().toLongOrNull()?.let { out += k to kotlinx.serialization.json.JsonPrimitive(it) } }
    return out
}

internal fun TransportScreenState.withLoadFailure(e: Throwable): TransportScreenState =
    copy(isLoading = false, loadFailure = ReadFailure.of(e))

data class TransportScreenState(
    // Read-only current transports
    val identity: FederationIdentity? = null,
    val isLoading: Boolean = false,
    /** Why the transports read produced no facts; null after a success. */
    val loadFailure: ReadFailure? = null,
    val error: String? = null,
    val successMessage: String? = null,
    // Radio (LoRa / RNode) form
    val radioEnabled: Boolean = false,
    val serialPort: String = "",
    val frequencyHz: String = "868000000",
    val bandwidthHz: String = "125000",
    val spreadingFactor: String = "7",
    val codingRate: String = "5",
    val txPowerDbm: String = "17",
    val isSaving: Boolean = false,
    /** True once the form holds values read back from the node, not defaults. */
    val radioConfigRead: Boolean = false,
)
