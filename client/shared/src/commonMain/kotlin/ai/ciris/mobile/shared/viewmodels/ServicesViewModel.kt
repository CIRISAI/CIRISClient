package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import ai.ciris.mobile.shared.ui.screens.ServiceProvider
import ai.ciris.mobile.shared.ui.screens.ServicesData
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
 * Shared ViewModel for services screen
 * Based on ~/CIRISGUI-Standalone/apps/agui/app/services/page.tsx
 *
 * Features:
 * - Service registry polling
 * - Service health monitoring
 * - Circuit breaker management
 * - Service diagnostics
 * - Auto-refresh with configurable interval
 */
class ServicesViewModel(
    private val apiClient: CIRISApiClient
) : ViewModel() {

    companion object {
        private const val TAG = "ServicesViewModel"
        private const val POLL_INTERVAL_MS = 10000L // 10 seconds
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

    // Services data state
    private val _servicesData = MutableStateFlow(ServicesData())
    val servicesData: StateFlow<ServicesData> = _servicesData.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Status message for user feedback
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Expansion state - tracks which services are expanded (persists during session)
    private val _expandedServiceIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedServiceIds: StateFlow<Set<String>> = _expandedServiceIds.asStateFlow()

    /**
     * Toggle service expansion state.
     */
    fun toggleServiceExpanded(serviceId: String) {
        val current = _expandedServiceIds.value
        _expandedServiceIds.value = if (serviceId in current) {
            current - serviceId
        } else {
            current + serviceId
        }
    }

    /**
     * Check if a service is expanded.
     */
    fun isServiceExpanded(serviceId: String): Boolean = serviceId in _expandedServiceIds.value

    // Polling job
    private var pollingJob: Job? = null
    private var isFirstLoad = true
    private var pollingStarted = false

    init {
        logInfo("init", "ServicesViewModel initialized (polling deferred until startPolling() called)")
        // NOTE: Don't auto-start polling here - wait for startPolling() to be called
        // when the screen becomes visible and has a valid auth token
    }

    /**
     * Start automatic services polling.
     * Must be called explicitly when the screen becomes visible.
     */
    fun startPolling() {
        val method = "startPolling"
        if (pollingStarted) {
            logDebug(method, "Polling already started, skipping")
            return
        }
        pollingStarted = true

        logInfo(method, "Starting services polling (interval=${POLL_INTERVAL_MS}ms)")

        pollingJob = viewModelScope.launch {
            var pollCount = 0
            while (isActive) {
                pollCount++
                logDebug(method, "Poll cycle #$pollCount starting")

                try {
                    fetchServicesInternal()
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
        logInfo(method, "Stopping services polling")
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
                fetchServicesInternal()
                _error.value = null
                _statusMessage.value = "Services refreshed"
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
     * Kept for its caller; there is no diagnostics route.
     *
     * This used to re-count the list already on screen and present the count
     * as a diagnosis — its "open breakers" number was the unhealthy count
     * restated, and the breaker state itself was derived from `healthy`
     * (CSD-016). The screen no longer offers it. It now does the one honest
     * thing a "check again" can do: read again.
     */
    fun runDiagnostics() {
        logInfo("runDiagnostics", "No diagnostics route — re-reading instead")
        refresh()
    }

    /**
     * Kept for its caller; there is no circuit-breaker reset route.
     *
     * This used to set a status reading "Circuit breakers ... reset (API not yet
     * implemented)" and report success. It resets nothing, so it says so: the
     * refusal goes to [error], and nothing claims a reset happened. The screen
     * no longer offers the control (CSD-016).
     */
    fun resetCircuitBreakers(serviceType: String?) {
        val method = "resetCircuitBreakers"
        logWarn(method, "No circuit-breaker reset route; nothing was reset (serviceType=$serviceType)")
        _error.value = "Circuit breakers cannot be reset from here: no reset route exists"
    }

    /**
     * Fetch services data from API
     */
    private suspend fun fetchServicesInternal() {
        val method = "fetchServicesInternal"
        logDebug(method, "Fetching services data from API")

        try {
            val response = apiClient.getServices()
            logDebug(method, "API response received")

            // Parse global services
            val globalServices = mutableMapOf<String, List<ServiceProvider>>()
            response.globalServices.forEach { (serviceType, providers) ->
                globalServices[serviceType] = providers.map { provider ->
                    provider.toServiceProvider()
                }
            }

            // Parse handler services
            val handlerServices = mutableMapOf<String, Map<String, List<ServiceProvider>>>()
            response.handlers.forEach { (handler, serviceTypes) ->
                val handlerMap = mutableMapOf<String, List<ServiceProvider>>()
                serviceTypes.forEach { (serviceType, providers) ->
                    handlerMap[serviceType] = providers.map { provider ->
                        provider.toServiceProvider()
                    }
                }
                handlerServices[handler] = handlerMap
            }

            // Count from the one health fact the wire carries (`healthy`). A
            // provider whose health was not reported counts as neither.
            val allProviders = globalServices.values.flatten() +
                handlerServices.values.flatMap { it.values.flatten() }
            val healthyCount = allProviders.count { it.healthy == true }
            val unhealthyCount = allProviders.count { it.healthy == false }

            val totalServices = allProviders.size
            val overallHealth = when {
                totalServices == 0 -> "unknown"
                unhealthyCount == 0 && healthyCount == totalServices -> "healthy"
                unhealthyCount < totalServices / 2 -> "degraded"
                else -> "critical"
            }

            val servicesData = ServicesData(
                overallHealth = overallHealth,
                totalServices = totalServices,
                healthyServices = healthyCount,
                unhealthyServices = unhealthyCount,
                globalServices = globalServices,
                handlerServices = handlerServices,
                hasReading = true,
            )

            logInfo(method, "Services updated: total=$totalServices, healthy=$healthyCount, unhealthy=$unhealthyCount")
            _servicesData.value = servicesData

        } catch (e: Exception) {
            logError(method, "Failed to fetch services: ${e::class.simpleName}: ${e.message}")
            // No reading: say why, and drop the list rather than let "No
            // Services Found" (or the last success) stand for a failed read.
            _servicesData.value = ServicesData(readFailure = ReadFailure.of(e))
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

    private fun ai.ciris.mobile.shared.api.ServiceProviderData.toServiceProvider() = ServiceProvider(
        name = name,
        priority = priority,
        priorityGroup = priorityGroup,
        strategy = strategy,
        circuitBreakerState = circuitBreakerState,
        capabilities = capabilities,
        healthy = healthy,
    )

    override fun onCleared() {
        logInfo("onCleared", "ViewModel cleared, cancelling polling job")
        super.onCleared()
        pollingJob?.cancel()
    }
}
