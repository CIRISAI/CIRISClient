package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.NodeStateReadout
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.SystemChannelInfo
import ai.ciris.mobile.shared.ui.screens.SystemScreenData
import ai.ciris.mobile.shared.ui.screens.SystemServiceInfo
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import ai.ciris.mobile.shared.ui.screens.knownCognitiveState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel for SystemScreen
 * Handles system status and control operations
 *
 * Features:
 * - Load system health and resource usage
 * - Load environmental impact metrics
 * - The agent's processor state, read-only (control is Runtime's, CSD-024)
 * - Auto-refresh polling
 */
class SystemViewModel(
    private val apiClient: CIRISApiClient
) : ViewModel() {

    companion object {
        private const val TAG = "SystemViewModel"
        private const val REFRESH_INTERVAL_MS = 5000L

        /** Take the node-state read every Nth telemetry tick (~15 s). */
        private const val NODE_STATE_EVERY_N_POLLS = 3
    }

    private fun log(level: String, method: String, message: String) {
        val fullMessage = "[$method] $message"
        when (level) {
            "DEBUG" -> PlatformLogger.d(TAG, fullMessage)
            "INFO" -> PlatformLogger.i(TAG, fullMessage)
            "WARN" -> PlatformLogger.w(TAG, fullMessage)
            "ERROR" -> PlatformLogger.e(TAG, fullMessage)
            else -> PlatformLogger.i(TAG, fullMessage)
        }
    }

    private fun logDebug(method: String, message: String) = log("DEBUG", method, message)
    private fun logInfo(method: String, message: String) = log("INFO", method, message)
    private fun logWarn(method: String, message: String) = log("WARN", method, message)
    private fun logError(method: String, message: String) = log("ERROR", method, message)

    // System data state
    private val _systemData = MutableStateFlow(SystemScreenData())
    val systemData: StateFlow<SystemScreenData> = _systemData.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Success message
    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    // ── The operator surface — GET /v1/node/state (CIRISServer#356/#369/#370) ──
    //
    // Held as its own StateFlow rather than folded into SystemScreenData: this
    // reading has FIVE outcomes (loading / unreachable / refused / malformed /
    // present) and only the last carries a band. Flattening it into the
    // telemetry blob would give an unreachable node a fabricated healthy zero —
    // the exact collapse FSD/RCA_INGEST_REJECTION_2026-08-05.md is about.
    private val _nodeState = MutableStateFlow<NodeStateReadout>(NodeStateReadout.Loading)
    val nodeState: StateFlow<NodeStateReadout> = _nodeState.asStateFlow()

    /**
     * The node-state read is a corpus aggregate, so it is NOT taken on every
     * 5 s telemetry tick — every [NODE_STATE_EVERY_N_POLLS]th one, and always
     * on an explicit refresh. The trace-plane band moves on elapsed HOURS; a
     * ~15 s cadence is orders of magnitude finer than the signal.
     */
    private var pollTick = 0

    // Auto-refresh job
    private var refreshJob: Job? = null
    private var pollingStarted = false

    init {
        logInfo("init", "SystemViewModel initialized (data load deferred until startPolling() called)")
        // NOTE: Don't auto-load here - wait for startPolling() to be called
        // when the screen becomes visible and has a valid auth token
    }

    /**
     * Is an agent attached? Set from the probed `ClientMode` by the caller.
     *
     * The agent half of this screen — resources, environmental cost, the
     * processor's cognitive state and the channels — is read from routes only
     * an agent serves (`/v1/telemetry/overview`, `/v1/agent/channels`). On a
     * bare node those reads 404, and they used to land as zeros and a "WORK"
     * that looked like a healthy idle agent (CSD-025 §6.1). Without an agent
     * they are not asked at all, and the screen says why.
     */
    @kotlin.concurrent.Volatile
    var agentAttached: Boolean = false
        private set

    fun setAgentAttached(attached: Boolean) {
        if (attached == agentAttached) return
        agentAttached = attached
        _systemData.value = SystemScreenData(agentAttached = attached)
    }

    /**
     * Load all system data from API.
     *
     * Pause and resume are NOT here any more: they were a second door onto
     * `POST /v1/system/runtime/{action}`, with Runtime (CSD-024) the first.
     * One runtime control, in This agent › Runtime; this screen links to it.
     */
    fun loadSystemData() {
        val method = "loadSystemData"
        logInfo(method, "Loading system data (agentAttached=$agentAttached)")

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                // Health is the node's as well as the agent's (a folded read).
                val healthResponse = try {
                    apiClient.getSystemHealth()
                } catch (e: Exception) {
                    logWarn(method, "Failed to load health: ${e.message}")
                    null
                }

                if (!agentAttached) {
                    _systemData.value = SystemScreenData(
                        health = healthResponse?.status,
                        agentAttached = false,
                    )
                    return@launch
                }

                // The agent half. A failed telemetry read is SAID, never drawn
                // as zeros (CSD/3 §2.2).
                val telemetry = runCatching { apiClient.getUnifiedTelemetry() }
                telemetry.exceptionOrNull()?.let { logWarn(method, "Failed to load telemetry: ${it.message}") }

                val environmentResponse = try {
                    apiClient.getEnvironmentalMetrics()
                } catch (e: Exception) {
                    logWarn(method, "Failed to load environmental metrics: ${e.message}")
                    null
                }

                // Channels: a failed read is a failure, not "no channels".
                val channelsResult = runCatching { apiClient.getChannelsOrThrow() }

                val t = telemetry.getOrNull()
                val services = t?.services?.map { (name, info) ->
                    SystemServiceInfo(
                        name = name,
                        healthy = info.healthy,
                        available = info.available,
                        serviceType = info.serviceType,
                        capabilities = info.capabilities
                    )
                } ?: emptyList()

                val channels = channelsResult.getOrNull()?.channels?.map { channel ->
                    SystemChannelInfo(
                        channelId = channel.channelId,
                        displayName = channel.displayName,
                        channelType = channel.channelType,
                        isActive = channel.isActive,
                        messageCount = channel.messageCount,
                        lastActivity = channel.lastActivity
                    )
                } ?: emptyList()

                _systemData.value = SystemScreenData(
                    health = healthResponse?.status ?: t?.health,
                    agentAttached = true,
                    agentReadFailure = telemetry.exceptionOrNull()?.let { ReadFailure.of(it) },
                    uptime = t?.uptime,
                    memoryMb = t?.memoryMb ?: 0,
                    // `/v1/telemetry/overview` carries no memory percentage; it is
                    // not drawn rather than drawn as "0% utilized".
                    memoryPercent = null,
                    cpuPercent = t?.cpuPercent ?: 0,
                    diskUsedMb = t?.diskUsedMb ?: 0.0,
                    carbonGrams = environmentResponse?.carbonGrams ?: 0.0,
                    energyKwh = environmentResponse?.energyKwh ?: 0.0,
                    costCents = environmentResponse?.costCents ?: 0.0,
                    tokensLastHour = environmentResponse?.tokensLastHour ?: 0,
                    tokens24h = environmentResponse?.tokens24h ?: 0,
                    cognitiveState = knownCognitiveState(healthResponse?.cognitiveState)
                        ?: knownCognitiveState(t?.cognitiveState),
                    services = services,
                    channels = channels,
                    channelsFailure = channelsResult.exceptionOrNull()?.let { ReadFailure.of(it) },
                )

                logInfo(method, "System data loaded: health=${_systemData.value.health}, " +
                        "services=${services.size}, channels=${channels.size}")

            } catch (e: Exception) {
                logError(method, "Failed to load system data: ${e::class.simpleName}: ${e.message}")
                _error.value = "Failed to load system data: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Read the composed operator surface — `GET /v1/node/state`.
     *
     * Deliberately does NOT touch [_error]: a node that refuses or cannot be
     * reached is a STATE this screen renders, not a toast that replaces it.
     * Routing it through the error banner would once again turn "we could not
     * ask" into a transient nothing.
     */
    fun loadNodeState() {
        val method = "loadNodeState"
        viewModelScope.launch {
            val readout = apiClient.getNodeOperatorState()
            _nodeState.value = readout
            when (readout) {
                is NodeStateReadout.Present -> logInfo(
                    method,
                    "band=${readout.state.band} " +
                        "trace_plane=${readout.state.tracePlane?.standing} " +
                        "ingest=${readout.state.ingest?.standing} " +
                        "unknown=${readout.state.unknown.size}"
                )
                is NodeStateReadout.Unreachable -> logWarn(method, "unreachable: ${readout.detail}")
                is NodeStateReadout.Refused -> logWarn(
                    method,
                    "refused: HTTP ${readout.status} ${readout.detail}"
                )
                is NodeStateReadout.NotOffered -> logWarn(
                    method,
                    "not offered: this node mounts no /v1/node/state surface"
                )
                is NodeStateReadout.Malformed -> logWarn(method, "malformed: ${readout.detail}")
                is NodeStateReadout.Loading -> Unit
            }
        }
    }

    /**
     * Start auto-refresh polling.
     * Must be called explicitly when the screen becomes visible.
     */
    fun startPolling() {
        val method = "startPolling"
        if (pollingStarted) {
            logDebug(method, "Polling already started, skipping")
            return
        }
        pollingStarted = true
        logInfo(method, "Starting auto-refresh polling (interval=${REFRESH_INTERVAL_MS}ms)")

        // Fetch initial data
        loadSystemData()
        // The operator surface on the FIRST tick, not on the third: an operator
        // opening this screen must not wait 15 s to learn the plane is dark.
        pollTick = 0
        loadNodeState()

        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                try {
                    loadSystemData()
                    pollTick += 1
                    if (pollTick % NODE_STATE_EVERY_N_POLLS == 0) {
                        loadNodeState()
                    }
                } catch (e: Exception) {
                    logError(method, "Error during auto-refresh: ${e.message}")
                }
            }
        }
    }

    /**
     * Stop auto-refresh polling
     */
    fun stopPolling() {
        val method = "stopPolling"
        logInfo(method, "Stopping auto-refresh polling")
        refreshJob?.cancel()
        refreshJob = null
        pollingStarted = false // Allow restart
    }

    /**
     * Refresh system data — including the operator surface. An explicit refresh
     * is a human asking "how is this node RIGHT NOW", so it always re-reads it.
     */
    fun refresh() {
        loadSystemData()
        loadNodeState()
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Clear success message
     */
    fun clearSuccess() {
        _successMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
