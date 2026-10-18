package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DeviceRequestsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.ADD_DEVICE_REQUEST_TTL_SECS
import ai.ciris.mobile.shared.models.federation.DeviceJoinRequest
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

private const val TAG = "ApproveDeviceVM"

/** A refusal the node named, kept for rendering by id. */
data class DeviceRequestRefusal(val reasonId: String?, val detail: String?, val status: Int)

/** The act a [ApproveDeviceUiState.confirming] sheet is about to perform. */
data class DeviceRequestConfirm(val request: DeviceJoinRequest, val approve: Boolean)

/** What the last decision did, for the line under the list. */
sealed interface DeviceRequestOutcome {
    data class Approved(val label: String, val matchCode: String) : DeviceRequestOutcome
    data class Denied(val label: String) : DeviceRequestOutcome
}

/**
 * The approving device's "Add a device" card (CSD-094), from the owner's
 * routes (`src/add_device.rs:939-1096` at CIRISServer v0.5.231).
 *
 * [windowSecsLeft] is the window for NEW requests and nothing else: when it
 * closes, the node refuses new joins (`add_device.not_accepting` on the new
 * device) but every request already [requests] stays approvable until its own
 * [ADD_DEVICE_REQUEST_TTL_SECS] runs out. Both countdowns are as of
 * [fetchedAtMs]; [windowSecsLeftAt] and [requestExpiresInSecs] age them.
 */
data class ApproveDeviceUiState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val requests: List<DeviceJoinRequest> = emptyList(),
    val windowSecsLeft: Long? = null,
    val fetchedAtMs: Long = 0,
    val opening: Boolean = false,
    val confirming: DeviceRequestConfirm? = null,
    /** The request id an approve/deny is in flight for. */
    val working: String? = null,
    val outcome: DeviceRequestOutcome? = null,
    val refusal: DeviceRequestRefusal? = null,
    /** A bare 404: the node predates 0.5.231 and has no add-device plane. The card says so and offers nothing. */
    val nodeTooOld: Boolean = false,
    /** The window has been open since this card loaded, so a closed one offers "Open again". */
    val windowWasOpen: Boolean = false,
)

/** The window's seconds left at [nowMs], or null when it is closed. 0 is closed too. */
fun ApproveDeviceUiState.windowSecsLeftAt(nowMs: Long): Long? {
    val left = windowSecsLeft ?: return null
    val aged = left - ((nowMs - fetchedAtMs).coerceAtLeast(0) / 1000)
    return aged.takeIf { it > 0 }
}

/** Seconds until [request] expires on the node (REQUEST_TTL − its age), at [nowMs]. Never negative. */
fun ApproveDeviceUiState.requestExpiresInSecs(request: DeviceJoinRequest, nowMs: Long): Long {
    val age = request.requestedSecsAgo + ((nowMs - fetchedAtMs).coerceAtLeast(0) / 1000)
    return (ADD_DEVICE_REQUEST_TTL_SECS - age).coerceAtLeast(0)
}

/**
 * The bundle key naming a request's `device_class`, or null for a class this
 * client has no word for (the screen then shows the server's token as-is,
 * never a guess). Since `fix/client-copy-0.5.232` (`b3e05e27`) the class is
 * the requesting HOST's (`host_device_class()`: phone on mobile, laptop under
 * the desktop launcher, server headless); v0.5.231 always sent `laptop`.
 */
fun deviceClassKey(deviceClass: String): String? = when (deviceClass.trim().lowercase()) {
    "phone" -> "mobile.device_class_phone"
    "laptop" -> "mobile.device_class_laptop"
    "server" -> "mobile.device_class_server"
    else -> null
}

/**
 * What a request's row says for its `device_class`: the bundle's word via
 * [words] when [deviceClassKey] knows the class, else the server's token as
 * sent (never a guess), else nothing (no row line at all).
 */
fun deviceClassDisplay(deviceClass: String, words: (key: String) -> String): String? =
    deviceClassKey(deviceClass)?.let(words) ?: deviceClass.trim().ifBlank { null }

/** `m:ss` for a countdown. */
fun formatCountdown(secs: Long): String {
    val s = secs.coerceAtLeast(0)
    val m = s / 60
    val r = s % 60
    return "$m:${if (r < 10) "0" else ""}$r"
}

class ApproveDeviceViewModel(
    private val api: DeviceRequestsApi,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    private val _state = MutableStateFlow(ApproveDeviceUiState())
    val state: StateFlow<ApproveDeviceUiState> = _state.asStateFlow()

    /** One list call at a time: the screen polls, and a poll never overlaps the last. */
    private val listing = Mutex()

    /** Read the pending requests and the window. Awaited; a second caller waits rather than overlapping. */
    suspend fun refreshNow() {
        listing.withLock {
            _state.value = _state.value.copy(loading = true)
            try {
                val r = api.list()
                _state.value = _state.value.copy(
                    loaded = true,
                    loading = false,
                    requests = r.requests,
                    windowSecsLeft = r.windowSecsLeft,
                    fetchedAtMs = clock(),
                    nodeTooOld = false,
                    windowWasOpen = _state.value.windowWasOpen || r.windowSecsLeft != null,
                )
            } catch (e: NodeRefusal) {
                val tooOld = e.statusCode == 404 && e.reasonId == null
                _state.value = _state.value.copy(
                    loaded = true,
                    loading = false,
                    nodeTooOld = tooOld,
                    refusal = if (tooOld) null else DeviceRequestRefusal(e.reasonId, e.detail, e.statusCode),
                )
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "list failed: ${e.message}")
                _state.value = _state.value.copy(
                    loaded = true,
                    loading = false,
                    refusal = DeviceRequestRefusal(null, e.message, 0),
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    /** Open the window for new requests (the node's default when [seconds] is null: 10 min, max 30). */
    fun open(seconds: Long? = null) {
        if (_state.value.opening) return
        _state.value = _state.value.copy(opening = true, refusal = null)
        viewModelScope.launch {
            try {
                val r = api.open(seconds)
                PlatformLogger.i(TAG, "window open for ${r.seconds}s")
                _state.value = _state.value.copy(opening = false, windowWasOpen = true)
                refreshNow()
            } catch (e: NodeRefusal) {
                _state.value = _state.value.copy(
                    opening = false,
                    nodeTooOld = e.statusCode == 404 && e.reasonId == null,
                    refusal = DeviceRequestRefusal(e.reasonId, e.detail, e.statusCode)
                        .takeUnless { e.statusCode == 404 && e.reasonId == null },
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(opening = false, refusal = DeviceRequestRefusal(null, e.message, 0))
            }
        }
    }

    /** Raise the confirm for approving [request]. Nothing is sent until [confirm]. */
    fun askApprove(request: DeviceJoinRequest) {
        _state.value = _state.value.copy(confirming = DeviceRequestConfirm(request, approve = true), outcome = null, refusal = null)
    }

    /** Raise the confirm for denying [request]. */
    fun askDeny(request: DeviceJoinRequest) {
        _state.value = _state.value.copy(confirming = DeviceRequestConfirm(request, approve = false), outcome = null, refusal = null)
    }

    fun dismissConfirm() {
        _state.value = _state.value.copy(confirming = null)
    }

    /** Perform the confirmed act. The node frees the request's slot either way (N3); the list is re-read. */
    fun confirm() {
        val c = _state.value.confirming ?: return
        if (_state.value.working != null) return
        val id = c.request.requestId
        _state.value = _state.value.copy(confirming = null, working = id, refusal = null)
        viewModelScope.launch {
            try {
                val outcome = if (c.approve) {
                    val r = api.approve(id)
                    DeviceRequestOutcome.Approved(c.request.label, r.matchCode ?: c.request.matchCode)
                } else {
                    api.deny(id)
                    DeviceRequestOutcome.Denied(c.request.label)
                }
                PlatformLogger.i(TAG, "request $id ${if (c.approve) "approved" else "denied"}")
                _state.value = _state.value.copy(
                    working = null,
                    outcome = outcome,
                    requests = _state.value.requests.filterNot { it.requestId == id },
                )
            } catch (e: NodeRefusal) {
                PlatformLogger.w(TAG, "request $id refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}")
                _state.value = _state.value.copy(working = null, refusal = DeviceRequestRefusal(e.reasonId, e.detail, e.statusCode))
            } catch (e: Exception) {
                _state.value = _state.value.copy(working = null, refusal = DeviceRequestRefusal(null, e.message, 0))
            }
            refreshNow()
        }
    }

    fun clearOutcome() {
        _state.value = _state.value.copy(outcome = null, refusal = null)
    }
}
