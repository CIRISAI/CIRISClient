package ai.ciris.mobile.shared.viewmodels.federation

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.federation.FederationMetricsResponse
import ai.ciris.mobile.shared.viewmodels.BaseFederationViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * VM for the Network → Queue sub-screen.
 *
 * Polls ``GET /v1/federation/metrics`` every 5 s (faster than Interfaces
 * because durable queue depth can change between thoughts) and exposes
 * the aggregate counters the queue screen renders:
 *
 *  - [queueDepth] — sum across all queue kinds in
 *    ``durable_queue_depth``
 *  - [envelopesSent] / [envelopesReceived] — session totals across all
 *    envelope kinds
 *  - [sendFailures] / [verifyFailures] — total failure counters; non-zero
 *    is the UI's signal to flip the failure-section cards red
 *  - [bytesIn] / [bytesOut] — session throughput totals for the
 *    sparkline-ish summary at the bottom
 *
 * The polling interval is intentionally cheap-side rather than chatty;
 * the snapshot endpoint is O(1) on the backend so 5 s is a safe default.
 */
class NetworkQueueViewModel(
    apiClient: CIRISApiClient,
) : BaseFederationViewModel(apiClient) {

    override val tag: String = "NetworkQueueVM"

    private val _metrics = MutableStateFlow<FederationMetricsResponse?>(null)
    val metrics: StateFlow<FederationMetricsResponse?> = _metrics.asStateFlow()

    private var autoRefreshJob: Job? = null

    // Null until a snapshot has been READ (CSD-049): a counter nobody read is
    // not 0, and drawing it as 0 beside a failed read is a reading nobody took.
    val queueDepth: Long? get() = _metrics.value?.getQueueDepth()
    val envelopesSent: Long? get() = _metrics.value?.getEnvelopesSent()
    val envelopesReceived: Long? get() = _metrics.value?.getEnvelopesReceived()
    val sendFailures: Long? get() = _metrics.value?.getSendFailures()
    val verifyFailures: Long? get() = _metrics.value?.getVerifyFailures()
    val bytesIn: Long? get() = _metrics.value?.getBytesIn()
    val bytesOut: Long? get() = _metrics.value?.getBytesOut()

    fun refreshNow() {
        viewModelScope.launch {
            runRead("getFederationMetrics") { apiClient.getFederationMetrics() }?.let { _metrics.value = it }
        }
    }

    fun startAutoRefresh(intervalMs: Long = 5_000L) {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                runRead("getFederationMetrics:auto") {
                    apiClient.getFederationMetrics()
                }?.let { _metrics.value = it }
                delay(intervalMs)
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    override fun onCleared() {
        stopAutoRefresh()
        super.onCleared()
    }
}
