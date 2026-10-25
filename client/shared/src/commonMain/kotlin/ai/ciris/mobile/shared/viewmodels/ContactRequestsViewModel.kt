package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.ContactRequestsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.ContactRequest
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.isInviteRouteMissing
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What People knows about contact requests (CSD-109 §2's states). */
sealed interface ContactRequestsRead {
    data object NotAsked : ContactRequestsRead
    data object Loading : ContactRequestsRead
    /** A bare 404: a node older than 0.5.237. Nothing is drawn; never "no requests". */
    data object NotOnThisNode : ContactRequestsRead
    data class Loaded(val requests: List<ContactRequest>, val ignored: List<ContactRequest>) : ContactRequestsRead
    data class Failed(val refusal: NodeRefusal?, val detail: String?) : ContactRequestsRead
}

/** What just happened, said once. */
enum class ContactRequestNotice {
    /** The node answered the add without a `reason_id`: you are each other's contacts. */
    ACCEPTED,
    /** A 2xx with a `reason_id` (CSD-005's pending row): not contacts yet. */
    ACCEPT_PENDING,
    IGNORED,
    UNIGNORED,
}

/**
 * **Contact requests** (CSD-109): who added this owner by code and has not
 * been added back, with Accept (behind a confirm) and Ignore (private,
 * reversible), and the ignored ones with Un-ignore.
 *
 * The node has no push for requests; they arrive by replication 10–30 s after
 * the other person acts. So People reads on open, after each answer, and every
 * [pollMs] while it is on screen ([startPolling] / [stopPolling]).
 */
class ContactRequestsViewModel(
    private val api: ContactRequestsApi,
    private val nodeUrl: () -> String,
    /** A request became a contact: People's own list re-reads. */
    private val onAccepted: () -> Unit = {},
    private val pollMs: Long = POLL_MS,
    private val wait: suspend (ms: Long) -> Unit = { delay(it) },
) : ViewModel() {

    companion object {
        private const val TAG = "ContactRequestsVM"
        const val POLL_MS = 15_000L
    }

    /** Advanced by [clearSessionState] and [nodeChanged]; a publish after an await re-checks it. */
    private var epoch: Long = 0L

    private val _read = MutableStateFlow<ContactRequestsRead>(ContactRequestsRead.NotAsked)
    val read: StateFlow<ContactRequestsRead> = _read.asStateFlow()

    private val _confirming = MutableStateFlow<ContactRequest?>(null)
    val confirming: StateFlow<ContactRequest?> = _confirming.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The node's refusal of the last answer, rendered by its `contacts.*` id. */
    private val _refusal = MutableStateFlow<NodeRefusal?>(null)
    val refusal: StateFlow<NodeRefusal?> = _refusal.asStateFlow()

    private val _notice = MutableStateFlow<ContactRequestNotice?>(null)
    val notice: StateFlow<ContactRequestNotice?> = _notice.asStateFlow()

    private var polling: Job? = null

    fun load() {
        val e = epoch
        viewModelScope.launch { reload(e) }
    }

    /** Read now, then every [pollMs] until [stopPolling]. One loop at a time. */
    fun startPolling() {
        if (polling?.isActive == true) return
        val e = epoch
        polling = viewModelScope.launch {
            while (e == epoch) {
                reload(e)
                wait(pollMs)
            }
        }
    }

    fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    private suspend fun reload(e: Long) {
        val before = _read.value
        if (before !is ContactRequestsRead.Loaded) _read.value = ContactRequestsRead.Loading
        val next = try {
            val list = api.list(nodeUrl())
            ContactRequestsRead.Loaded(
                list.requests.sortedByDescending { it.requestedAt.orEmpty() },
                list.ignored.sortedByDescending { it.requestedAt.orEmpty() },
            )
        } catch (x: Exception) {
            if (x is kotlinx.coroutines.CancellationException) throw x
            PlatformLogger.w(TAG, "[load] ${(x as? NodeRefusal)?.reasonId ?: x.message}")
            when {
                isInviteRouteMissing(x) -> ContactRequestsRead.NotOnThisNode
                // A failed poll keeps the rows it has; the error state is for a first read.
                before is ContactRequestsRead.Loaded -> before
                x is NodeRefusal -> ContactRequestsRead.Failed(x, x.detail)
                else -> ContactRequestsRead.Failed(null, x.message ?: x::class.simpleName)
            }
        }
        if (e != epoch) return
        _read.value = next
    }

    /** Ask to accept; the confirm names who, what, and what it means. */
    fun requestAccept(request: ContactRequest) {
        clearMessages()
        _confirming.value = request
    }

    fun cancelConfirm() {
        _confirming.value = null
    }

    /** Send the accept waiting on the confirm. Nothing is sent without one. */
    fun confirmAccept() {
        val request = _confirming.value ?: return
        _confirming.value = null
        answer("accept") {
            val added = api.accept(nodeUrl(), request)
            if (added.reasonId != null) ContactRequestNotice.ACCEPT_PENDING
            else ContactRequestNotice.ACCEPTED.also { onAccepted() }
        }
    }

    /** One tap, no confirm: private, and undone from the Ignored section. */
    fun ignore(request: ContactRequest) {
        clearMessages()
        answer("ignore") {
            api.ignore(nodeUrl(), request.grantAttestationId)
            ContactRequestNotice.IGNORED
        }
    }

    fun unignore(request: ContactRequest) {
        clearMessages()
        answer("unignore") {
            api.unignore(nodeUrl(), request.grantAttestationId)
            ContactRequestNotice.UNIGNORED
        }
    }

    private fun answer(what: String, act: suspend () -> ContactRequestNotice) {
        if (_busy.value) return
        _busy.value = true
        val e = epoch
        viewModelScope.launch {
            try {
                val outcome = act()
                if (e != epoch) return@launch
                _notice.value = outcome
                reload(e)
            } catch (x: NodeRefusal) {
                if (e != epoch) return@launch
                PlatformLogger.w(TAG, "[$what] refused reason_id=${x.reasonId ?: "<none>"} status=${x.statusCode}")
                _refusal.value = x
                // Answered elsewhere, withdrawn, or not ignored after all: the
                // re-read stops the row offering what the node just refused.
                reload(e)
            } catch (x: Exception) {
                if (x is kotlinx.coroutines.CancellationException) throw x
                if (e != epoch) return@launch
                PlatformLogger.w(TAG, "[$what] failed: ${x.message}")
                _refusal.value = NodeRefusal(null, x.message, 0)
            } finally {
                if (e == epoch) _busy.value = false
            }
        }
    }

    fun clearMessages() {
        _refusal.value = null
        _notice.value = null
    }

    /** The active node moved: its requests are not the old node's. A shown screen keeps polling, the new node. */
    fun nodeChanged() {
        val wasPolling = polling?.isActive == true
        reset()
        if (wasPolling) startPolling()
    }

    /** Owner-gated content: nothing of it survives into the next session. */
    fun clearSessionState() {
        reset()
    }

    private fun reset() {
        epoch += 1
        stopPolling()
        _read.value = ContactRequestsRead.NotAsked
        _confirming.value = null
        _busy.value = false
        clearMessages()
    }
}
