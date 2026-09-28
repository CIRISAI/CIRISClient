package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import ai.ciris.mobile.shared.ui.screens.RuntimeData
import ai.ciris.mobile.shared.ui.screens.StepResult
import ai.ciris.mobile.shared.ui.screens.TrackedTask
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
 * Shared ViewModel for runtime control screen
 * Based on ~/CIRISGUI-Standalone/apps/agui/app/runtime/page.tsx
 *
 * Features:
 * - Runtime state polling
 * - Pause/Resume/Single-step control
 * - Pipeline step tracking
 * - Stream connection monitoring (simulated for now)
 * - Task/Thought tracking
 */
class RuntimeViewModel(
    private val apiClient: CIRISApiClient
) : ViewModel() {

    companion object {
        private const val TAG = "RuntimeViewModel"
        private const val POLL_INTERVAL_MS = 2000L // 2 seconds
        private val FORBIDDEN = Regex("""\b403\b""")
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

    // Runtime data state
    private val _runtimeData = MutableStateFlow(RuntimeData())
    val runtimeData: StateFlow<RuntimeData> = _runtimeData.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Status message for user feedback
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /**
     * May this session drive the runtime? Answered BY THE AGENT, not assumed.
     *
     * It defaulted to `true` and nothing ever set it (`setAdminStatus` had no
     * callers), so an observer saw live Pause / Step buttons that 403 (CSD-024).
     * Every runtime action — the `state` read included — is ADMIN on CIRISAgent
     * main, so a successful state read is the proof, and a 403 is the refusal.
     * False until the first read answers: no control is offered on a guess.
     */
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    /** The agent answered 403: this person may not drive the runtime. Rendered as the admin card, not an error. */
    private val _adminRefused = MutableStateFlow(false)
    val adminRefused: StateFlow<Boolean> = _adminRefused.asStateFlow()

    private fun isForbidden(e: Throwable): Boolean = FORBIDDEN.containsMatchIn(e.message ?: "")

    /** A 403 on any runtime call: record the refusal; the values it would have read are not drawn. */
    private fun noteRefusal(e: Throwable): Boolean {
        if (!isForbidden(e)) return false
        _isAdmin.value = false
        _adminRefused.value = true
        _runtimeData.value = _runtimeData.value.copy(
            streamConnected = false,
            cognitiveState = null,
            queueDepth = null,
            readFailure = null,
        )
        return true
    }

    // Polling job
    private var pollingJob: Job? = null
    private var isFirstLoad = true
    private var updateCount = 0
    private var pollingStarted = false

    init {
        logInfo("init", "RuntimeViewModel initialized (polling deferred until startPolling() called)")
        // NOTE: Don't auto-start polling here - wait for startPolling() to be called
        // when the screen becomes visible and has a valid auth token
    }

    /**
     * Start automatic runtime state polling.
     * Must be called explicitly when the screen becomes visible.
     */
    fun startPolling() {
        val method = "startPolling"
        if (pollingStarted) {
            logDebug(method, "Polling already started, skipping")
            return
        }
        pollingStarted = true

        logInfo(method, "Starting runtime polling (interval=${POLL_INTERVAL_MS}ms)")

        pollingJob = viewModelScope.launch {
            var pollCount = 0
            while (isActive) {
                pollCount++
                logDebug(method, "Poll cycle #$pollCount starting")

                try {
                    fetchRuntimeStateInternal()
                    _error.value = null

                    if (pollCount % 10 == 0) {
                        logInfo(method, "Poll cycle #$pollCount completed successfully")
                    }
                } catch (e: Exception) {
                    logError(method, "Poll cycle #$pollCount failed: ${e::class.simpleName}: ${e.message}")
                    _error.value = "Connection error: ${e.message}"
                } finally {
                    if (isFirstLoad) {
                        logInfo(method, "First load complete")
                        _isLoading.value = false
                        isFirstLoad = false
                    }
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop automatic polling
     */
    fun stopPolling() {
        val method = "stopPolling"
        logInfo(method, "Stopping runtime polling")
        pollingJob?.cancel()
        pollingJob = null
        pollingStarted = false // Allow restart
    }

    /**
     * Manual refresh triggered by user
     */
    fun refresh() {
        val method = "refresh"
        logInfo(method, "Manual refresh triggered")

        viewModelScope.launch {
            _isLoading.value = true
            logDebug(method, "Loading state set to true")

            try {
                fetchRuntimeStateInternal()
                _error.value = null
                _statusMessage.value = "Runtime state refreshed"
                logInfo(method, "Manual refresh completed successfully")
            } catch (e: Exception) {
                logError(method, "Manual refresh failed: ${e::class.simpleName}: ${e.message}")
                _error.value = "Refresh failed: ${e.message}"
            } finally {
                _isLoading.value = false
                logDebug(method, "Loading state set to false")
            }
        }
    }

    /**
     * Pause runtime processing
     */
    fun pauseRuntime() {
        val method = "pauseRuntime"
        logInfo(method, "Pausing runtime")

        viewModelScope.launch {
            _isLoading.value = true

            try {
                val result = apiClient.pauseRuntime()
                logInfo(method, "Pause result: processorState=${result.processorState}")

                // Update local state
                _runtimeData.value = _runtimeData.value.copy(
                    processorState = result.processorState
                )

                _statusMessage.value = "Runtime paused"

                // Refresh to get latest state
                fetchRuntimeStateInternal()

            } catch (e: Exception) {
                logError(method, "Pause failed: ${e::class.simpleName}: ${e.message}")
                noteRefusal(e)
                _error.value = "Pause failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Resume runtime processing
     */
    fun resumeRuntime() {
        val method = "resumeRuntime"
        logInfo(method, "Resuming runtime")

        viewModelScope.launch {
            _isLoading.value = true

            try {
                val result = apiClient.resumeRuntime()
                logInfo(method, "Resume result: processorState=${result.processorState}")

                // Update local state
                _runtimeData.value = _runtimeData.value.copy(
                    processorState = if (result.processorState == "active") "running" else result.processorState,
                    currentStepPoint = null,
                    lastStepResult = null
                )

                _statusMessage.value = "Runtime resumed"

                // Refresh to get latest state
                fetchRuntimeStateInternal()

            } catch (e: Exception) {
                logError(method, "Resume failed: ${e::class.simpleName}: ${e.message}")
                noteRefusal(e)
                _error.value = "Resume failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Execute a single step
     */
    fun singleStep() {
        val method = "singleStep"
        logInfo(method, "Executing single step")

        viewModelScope.launch {
            _isLoading.value = true

            try {
                val result = apiClient.singleStepProcessor()
                logInfo(method, "Single step result: stepPoint=${result.stepPoint}, message=${result.message}")

                // Build step result
                val stepResult = StepResult(
                    message = result.message,
                    processingTimeMs = result.processingTimeMs,
                    tokensUsed = result.tokensUsed,
                    stepPoint = result.stepPoint
                )

                // Update completed steps
                val completedSteps = _runtimeData.value.completedSteps.toMutableList()
                result.stepPoint?.let { step ->
                    if (!completedSteps.contains(step)) {
                        completedSteps.add(step)
                    }
                }

                // Update local state
                _runtimeData.value = _runtimeData.value.copy(
                    currentStepPoint = result.stepPoint,
                    lastStepTimeMs = result.processingTimeMs,
                    tokensUsed = result.tokensUsed,
                    lastStepResult = stepResult,
                    completedSteps = completedSteps
                )

                _statusMessage.value = "Step completed: ${result.message ?: result.stepPoint}"

            } catch (e: Exception) {
                logError(method, "Single step failed: ${e::class.simpleName}: ${e.message}")
                noteRefusal(e)
                _error.value = "Step failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Set admin status (to be called by auth system)
     */
    fun setAdminStatus(isAdmin: Boolean) {
        logInfo("setAdminStatus", "Admin status set to: $isAdmin")
        _isAdmin.value = isAdmin
    }

    /**
     * Fetch runtime state from API
     */
    private suspend fun fetchRuntimeStateInternal() {
        val method = "fetchRuntimeStateInternal"
        logDebug(method, "Fetching runtime state from API")

        try {
            val response = apiClient.getRuntimeState()
            logDebug(method, "API response received")

            updateCount++

            // Map API response to RuntimeData
            val runtimeData = RuntimeData(
                processorState = response.processorState,
                cognitiveState = response.cognitiveState,
                queueDepth = response.queueDepth,
                currentStepPoint = _runtimeData.value.currentStepPoint, // Preserve from single-step
                lastStepTimeMs = _runtimeData.value.lastStepTimeMs, // Preserve from single-step
                tokensUsed = _runtimeData.value.tokensUsed, // Preserve from single-step
                streamConnected = true, // Simulated - we're successfully polling
                updatesReceived = updateCount,
                completedSteps = _runtimeData.value.completedSteps, // Preserve completed steps
                activeTasks = response.activeTasks.map { task ->
                    TrackedTask(
                        taskId = task.taskId,
                        status = task.status,
                        thoughtCount = task.thoughtCount,
                        lastUpdated = task.lastUpdated
                    )
                },
                lastStepResult = _runtimeData.value.lastStepResult, // Preserve from single-step
                readFailure = null,
            )

            // The state read is ADMIN on the agent: answering it is the proof.
            _isAdmin.value = true
            _adminRefused.value = false

            logInfo(method, "Runtime updated: state=${runtimeData.processorState}, " +
                    "cognitive=${runtimeData.cognitiveState}, queue=${runtimeData.queueDepth}")

            _runtimeData.value = runtimeData

        } catch (e: Exception) {
            logError(method, "Failed to fetch runtime state: ${e::class.simpleName}: ${e.message}")
            if (noteRefusal(e)) throw e
            // Say the read failed, and drop the values it would have refreshed:
            // a failed read must not leave a confident "WORK · queue 0" on
            // screen, whether that came from a default or from the last
            // success (CSD-024, CSD/3 §2.2).
            _runtimeData.value = _runtimeData.value.copy(
                streamConnected = false,
                cognitiveState = null,
                queueDepth = null,
                readFailure = ReadFailure.of(e),
            )
            throw e
        }
    }

    /**
     * Clear status message
     */
    fun clearStatus() {
        _statusMessage.value = null
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        logInfo("onCleared", "ViewModel cleared, cancelling polling job")
        super.onCleared()
        pollingJob?.cancel()
    }
}
