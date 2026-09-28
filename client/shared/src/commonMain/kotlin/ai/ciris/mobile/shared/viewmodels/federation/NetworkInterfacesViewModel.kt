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
 * Per-transport row aggregated from the flat [FederationMetricsResponse].
 *
 * The metrics envelope ships counters keyed by transport identifier; we
 * collate those into a per-transport view so the rnstatus-style cards can
 * render without re-iterating every render pass.
 *
 * - [bytesIn] / [bytesOut]: cumulative session totals from
 *   ``transport_bytes_in_total`` / ``transport_bytes_out_total``.
 * - [reachRatio] / [peerCount]: derived from ``peer_reachability_ratio``
 *   sub-keys that match the transport identifier (typically formatted
 *   ``"{peer_key_id}|{transport}"``). When no measurement exists yet, both
 *   are ``null`` — the UI renders "Not yet measured".
 * - [rssiDbm] / [snrDb]: radio-only metrics surfaced by RNode-style
 *   transports; the families ``rssi_dbm`` and ``snr_db`` are open-ended
 *   and may not be present on every release of Edge. Nullable by design.
 */
data class TransportRow(
    val id: String,
    val bytesIn: Long,
    val bytesOut: Long,
    val reachRatio: Double?,
    val peerCount: Int?,
    val rssiDbm: Double? = null,
    val snrDb: Double? = null,
)

/**
 * Activity status derived from per-transport bytes in/out.
 *
 * Heuristic only — the metrics snapshot lacks a per-transport timestamp,
 * so we infer "active" from non-zero throughput and grade DEGRADED based
 * on a sub-100% reachability ratio. If both totals are zero and there is
 * no reachability sample, the transport is rendered grey.
 */
enum class TransportStatus { ACTIVE, DEGRADED, IDLE, UNKNOWN }

/**
 * VM for the Network → Interfaces sub-screen.
 *
 * Polls ``GET /v1/federation/metrics`` every 10 s and projects the flat
 * envelope into a list of [TransportRow]s. Aggregation rules:
 *
 * - Transport identifiers come from the union of ``transport_bytes_in``,
 *   ``transport_bytes_out`` and ``peer_reachability_ratio`` sub-keys.
 *   The node keys reachability ``"{peer_key_id}:{medium}"``
 *   (federation_surface.rs:222); see [collateTransports].
 * - Reachability ratio is averaged across all per-peer samples for that
 *   transport; peer count is the sample count.
 * - RSSI / SNR families are sub-keyed by transport identifier directly
 *   and surfaced only when present in the snapshot.
 */
class NetworkInterfacesViewModel(
    apiClient: CIRISApiClient,
) : BaseFederationViewModel(apiClient) {

    override val tag: String = "NetworkInterfacesVM"

    private val _metrics = MutableStateFlow<FederationMetricsResponse?>(null)
    val metrics: StateFlow<FederationMetricsResponse?> = _metrics.asStateFlow()

    private val _transportRows = MutableStateFlow<List<TransportRow>>(emptyList())
    val transportRows: StateFlow<List<TransportRow>> = _transportRows.asStateFlow()

    private var autoRefreshJob: Job? = null

    /** Fire a single fetch and project. Safe to call before/after auto-refresh. */
    fun refreshNow() {
        viewModelScope.launch {
            runRead("getFederationMetrics") { apiClient.getFederationMetrics() }?.let { snapshot ->
                _metrics.value = snapshot
                _transportRows.value = collateTransports(snapshot)
            }
        }
    }

    /** Start polling every 10 s. Idempotent — a previous job is cancelled. */
    fun startAutoRefresh(intervalMs: Long = 10_000L) {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                runRead("getFederationMetrics:auto") {
                    apiClient.getFederationMetrics()
                }?.let { snapshot ->
                    _metrics.value = snapshot
                    _transportRows.value = collateTransports(snapshot)
                }
                delay(intervalMs)
            }
        }
    }

    /** Stop polling. Called from the Composable's DisposableEffect cleanup. */
    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    override fun onCleared() {
        stopAutoRefresh()
        super.onCleared()
    }

    // ─── Aggregation helpers ─────────────────────────────────────────────────

    /**
     * Collate per-transport rows. Visible for testing.
     *
     * Reachability sub-keys arrive as ``"{peer_key_id}:{medium}"`` from the
     * node (``"{peer}|{transport}"`` from the retired agent route); both
     * collate to the medium ([transportOfReachKey]).
     */
    internal fun collateTransports(snapshot: FederationMetricsResponse): List<TransportRow> {
        val bytesIn = snapshot.transportBytesInTotal
        val bytesOut = snapshot.transportBytesOutTotal
        val reach = snapshot.peerReachabilityRatio

        // Build a transport → list-of-ratios index from the reachability map.
        // The NODE keys it `"<peer_key>:<medium>"` (CIRISServer
        // src/federation_surface.rs:222); the retired agent route used `|`.
        // Reading only `|` made every peer:medium pair its own "transport"
        // row (CSD-048). A key ending in a transport id we already know from
        // the byte counters resolves to it, so a transport id that itself
        // carries ':' survives; otherwise the medium is everything after the
        // peer key's first ':'.
        val knownTransports = bytesIn.keys + bytesOut.keys
        val reachByTransport: Map<String, List<Double>> = reach.entries
            .groupBy { entry -> transportOfReachKey(entry.key, knownTransports) }
            .mapValues { (_, entries) -> entries.map { it.value } }

        // toSortedSet() is JVM-only; commonMain uses sorted() + distinct().
        val transportIds = (bytesIn.keys + bytesOut.keys + reachByTransport.keys).distinct().sorted()
        return transportIds.map { id ->
            val ratios = reachByTransport[id].orEmpty()
            TransportRow(
                id = id,
                bytesIn = bytesIn[id] ?: 0L,
                bytesOut = bytesOut[id] ?: 0L,
                reachRatio = if (ratios.isEmpty()) null else ratios.average(),
                peerCount = if (ratios.isEmpty()) null else ratios.size,
                // RSSI / SNR are not exposed as typed families on
                // FederationMetricsResponse yet; surface only when a future
                // backend release adds them.  We keep the slots so the UI
                // shell is forward-compatible.
                rssiDbm = null,
                snrDb = null,
            )
        }
    }

    private fun transportOfReachKey(key: String, known: Set<String>): String {
        val pipeIdx = key.lastIndexOf('|')
        if (pipeIdx >= 0) return key.substring(pipeIdx + 1)
        known.firstOrNull { key.endsWith(":$it") }?.let { return it }
        val colonIdx = key.indexOf(':')
        return if (colonIdx >= 0) key.substring(colonIdx + 1) else key
    }

    /** Coarse activity bucket — used by the card-header status dot. */
    fun statusOf(row: TransportRow): TransportStatus = when {
        row.bytesIn == 0L && row.bytesOut == 0L && row.reachRatio == null -> TransportStatus.UNKNOWN
        row.reachRatio != null && row.reachRatio < 0.5 -> TransportStatus.DEGRADED
        row.bytesIn > 0L || row.bytesOut > 0L -> TransportStatus.ACTIVE
        else -> TransportStatus.IDLE
    }
}
