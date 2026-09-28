package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.AgentMode
import ai.ciris.mobile.shared.models.AgentModeStatus
import ai.ciris.mobile.shared.models.federation.FederationIdentityResponse
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * NetworkViewModel — drives the Network hub screen and the Network card.
 *
 * Owns the agent-mode READ (`GET /v1/system/agent-mode`, the Network card,
 * CSD-036) plus the
 * Edge-federation address surface. The hub's live-stats strip stays in
 * placeholder mode until Edge 1.0 exposes the corresponding FFI surface;
 * we model that as the absence of values rather than fake numbers.
 */
class NetworkViewModel(
    private val apiClient: CIRISApiClient,
) : ViewModel() {

    companion object {
        private const val TAG = "NetworkViewModel"
    }

    // ─── State ───────────────────────────────────────────────────────────────

    private val _status = MutableStateFlow<AgentModeStatus?>(null)
    val status: StateFlow<AgentModeStatus?> = _status.asStateFlow()

    /** The SELECTOR's mode on the hub and Settings, which need a value to
     *  highlight. Never render it as a reading: see [modeRead]. */
    private val _mode = MutableStateFlow(AgentMode.PROXY)
    val mode: StateFlow<AgentMode> = _mode.asStateFlow()

    /** The mode as READ from the host: null until a read answers, and null
     *  again when one fails. The Network card (CSD-036) renders this, never
     *  [mode], whose PROXY default is a fact about this class, not the node. */
    private val _modeRead = MutableStateFlow<AgentMode?>(null)
    val modeRead: StateFlow<AgentMode?> = _modeRead.asStateFlow()

    /** Why the last agent-mode read produced no reading: the route is not on
     *  this host (a node without an agent) or the read failed. */
    private val _modeFailure = MutableStateFlow<ReadFailure?>(null)
    val modeFailure: StateFlow<ReadFailure?> = _modeFailure.asStateFlow()

    /** Why the signer-key read produced no reading. */
    private val _identityFailure = MutableStateFlow<ReadFailure?>(null)
    val identityFailure: StateFlow<ReadFailure?> = _identityFailure.asStateFlow()

    /** Edge federation address (the local agent's signer_key_id). Populated by
     *  [loadFederationIdentity] from GET /v1/federation/identity; null while Edge
     *  is unavailable (degraded mode → 503), which the card renders as "—". */
    private val _federationAddress = MutableStateFlow<String?>(null)
    val federationAddress: StateFlow<String?> = _federationAddress.asStateFlow()

    /** persist's full identity aggregate (Federation ID card) — null while
     *  loading and on graceful 503 (identity still initializing). */
    private val _federationId = MutableStateFlow<FederationIdentityResponse?>(null)
    val federationId: StateFlow<FederationIdentityResponse?> = _federationId.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ─── Actions ─────────────────────────────────────────────────────────────

    /** Load the current agent-mode + disk facts. Idempotent + safe to retry. */
    fun loadAgentMode() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val s = apiClient.getAgentMode()
                recordModeRead(s, null)
                PlatformLogger.i(TAG, "loaded agent-mode: ${s.mode.wire}, server_eligible=${s.serverEligible}")
            } catch (e: Exception) {
                recordModeRead(null, e)
                _error.value = e.message ?: e::class.simpleName ?: "unknown error"
                PlatformLogger.e(TAG, "loadAgentMode failed: ${e.message}", e)
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Record the outcome of one agent-mode read. A failure CLEARS the reading
     * rather than leaving the last one (or the selector default) standing, and
     * says why: a node without an agent has no such route, which is a fact
     * about the node, not a failed read.
     */
    internal fun recordModeRead(status: AgentModeStatus?, failure: Throwable?) {
        if (status != null) {
            _status.value = status
            _mode.value = status.mode
            _modeRead.value = status.mode
            _modeFailure.value = null
        } else {
            _status.value = null
            _modeRead.value = null
            _modeFailure.value = failure?.let { ReadFailure.of(it) } ?: ReadFailure.Failed(null)
        }
    }

    // The mode is SET in Settings (CSD-022 §2.0.1), its one door: the hub's
    // selector that used to call `PUT /v1/system/agent-mode` from here was the
    // second door, on a surface every build shows (CSD-051 §3).

    /** Acknowledge a transient error after the user sees it. */
    fun clearError() {
        _error.value = null
    }

    /** Fetch the real local federation identity (signer_key_id) from Edge via
     *  GET /v1/federation/identity. On degraded mode (Edge unavailable → 503 →
     *  thrown) the address stays null and the identity card shows "—". Safe to
     *  retry; never throws. */
    fun loadFederationIdentity() {
        viewModelScope.launch {
            try {
                val identity = apiClient.getFederationIdentity()
                _federationAddress.value = identity.signerKeyId
                _identityFailure.value = null
                PlatformLogger.i(TAG, "federation identity: signer_key_id=${identity.signerKeyId.take(12)}…")
            } catch (e: Exception) {
                // Edge unavailable / degraded — leave address null (card → "—")
                // and say why, so "—" is never the whole story.
                _federationAddress.value = null
                _identityFailure.value = ReadFailure.of(e)
                PlatformLogger.d(TAG, "federation identity unavailable (Edge degraded?): ${e.message}")
            }
            // persist's full identity aggregate (null on 503 — initializing)
            runCatching { apiClient.getFederationIdentityAggregate() }
                .onSuccess { _federationId.value = it }
                .onFailure { e ->
                    PlatformLogger.d(TAG, "federation aggregate unavailable: ${e.message}")
                }
        }
    }
}
