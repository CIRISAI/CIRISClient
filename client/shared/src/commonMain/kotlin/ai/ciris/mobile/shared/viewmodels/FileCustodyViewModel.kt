package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DriveApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.drive.FileCustody
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which file the "Where is this file" card is about, and the per-file query
 * every drive route takes: [cohort] (`self` | `family` | `community`) and, for
 * a family or community file, the [roomId]. [name] is what the card calls it.
 */
data class CustodyTarget(
    val attestationId: String,
    val cohort: String,
    val roomId: String?,
    val name: String?,
) {
    /** The query as the route takes it: `self` names no room — the room IS the owner. */
    val queryRoom: String? get() = roomId?.takeIf { cohort != "self" && it.isNotBlank() }
}

/** The card's states (CSD-107 §2.2). Exactly one at a time. */
sealed interface CustodyState {
    data object Closed : CustodyState
    data class Loading(val target: CustodyTarget) : CustodyState
    data class Ready(val target: CustodyTarget, val custody: FileCustody) : CustodyState

    /** A bare 404 (no id): the route is not on this node — every released node today. A version fact, never an empty card. */
    data class NodeTooOld(val target: CustodyTarget) : CustodyState

    /** The node refused by name, or could not answer. Never looks like empty. */
    data class Failed(val target: CustodyTarget, val reasonId: String?, val detail: String?) : CustodyState
}

/**
 * The one "Where is this file" card, reached from the drive, notes and chat
 * hamburgers (CSD-107). One instance per app: whichever surface opens it, it is
 * the same card over the same route.
 */
class FileCustodyViewModel(private val api: DriveApi) : ViewModel() {
    private val _state = MutableStateFlow<CustodyState>(CustodyState.Closed)
    val state: StateFlow<CustodyState> = _state.asStateFlow()

    private var request = 0

    fun open(target: CustodyTarget) {
        val mine = ++request
        _state.value = CustodyState.Loading(target)
        viewModelScope.launch {
            val next = try {
                CustodyState.Ready(target, api.readCustody(target.attestationId, target.cohort, target.queryRoom))
            } catch (e: NodeRefusal) {
                if (e.statusCode == 404 && e.reasonId == null) {
                    PlatformLogger.i("FileCustodyVM", "node predates /v1/files/{id}/custody (bare 404)")
                    CustodyState.NodeTooOld(target)
                } else {
                    CustodyState.Failed(target, e.reasonId, e.detail)
                }
            } catch (e: Exception) {
                PlatformLogger.w("FileCustodyVM", "readCustody failed: ${e.message}")
                CustodyState.Failed(target, null, e.message ?: e::class.simpleName)
            }
            if (mine == request) _state.value = next
        }
    }

    fun close() {
        request++
        _state.value = CustodyState.Closed
    }
}
