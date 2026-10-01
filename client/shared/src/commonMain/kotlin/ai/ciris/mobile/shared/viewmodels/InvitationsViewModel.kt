package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.MembershipInvitesApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.InboxInvite
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.InviteSupport
import ai.ciris.mobile.shared.ui.screens.isInviteRouteMissing
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The inbox read. A node without the route and a person with no invitations never draw alike. */
sealed interface InboxRead {
    data object NotAsked : InboxRead
    data object Loading : InboxRead
    data class Loaded(val invites: List<InboxInvite>) : InboxRead
    /** A node older than 0.5.218: "this node can't carry invitations yet", never "you have none". */
    data object NotOnThisNode : InboxRead
    /** The node refused or the read failed; [refusal] is its typed answer when it gave one. */
    data class Failed(val refusal: NodeRefusal?, val detail: String?) : InboxRead
}

/**
 * One group's invitations, as its roster reads them (`GET …/{id}/invites`).
 * [NotOnThisNode] is the route missing — a node older than 0.5.218, where
 * the roster keeps today's direct add.
 */
sealed interface GroupInvitesRead {
    data object NotAsked : GroupInvitesRead
    data object Loading : GroupInvitesRead
    /** [viewerKeyId] is the node's `viewer_key_id` (0.5.219+), null when it did not send one. */
    data class Loaded(
        val invites: List<ai.ciris.mobile.shared.models.federation.GroupInvite>,
        val viewerKeyId: String? = null,
    ) : GroupInvitesRead
    data object NotOnThisNode : GroupInvitesRead
    data class Failed(val refusal: NodeRefusal?, val detail: String?) : GroupInvitesRead

    companion object {
        fun of(e: Throwable): GroupInvitesRead = when {
            isInviteRouteMissing(e) -> NotOnThisNode
            e is NodeRefusal -> Failed(e, e.detail)
            else -> Failed(null, e.message ?: e::class.simpleName)
        }
    }
}

/**
 * The client's own refusal when a confirmed INVITATION met a node with no
 * invites route. Nothing is sent in its place: the person confirmed an
 * invitation, and a direct add is a different act. The roster now offers
 * the add, with its own confirm.
 */
const val INVITES_NOT_ON_THIS_NODE = "mobile.invites_node_adds_directly"

/** An answer waiting on its three-fact confirm. */
data class InviteAnswerAsk(val invite: InboxInvite, val accept: Boolean)

/**
 * What just happened, said once. [awaiting] is set on an accept: the group
 * still seats them, so the hub says "accepted, waiting for the group" and never
 * "joined" (CSD-106 §6, "Accepted is not joined").
 */
data class InviteAnswered(val accepted: Boolean, val invite: InboxInvite, val awaiting: String?)

/**
 * **The invitee's inbox** (CSD-106) — every invitation into a household or a
 * community addressed to this node's owner, with accept and decline.
 *
 * One instance, app-scoped, shared by the three hubs that draw it: the
 * household hub shows the `family` invitations and each community hub the
 * `community` ones ([ai.ciris.mobile.shared.ui.screens.inboxFor]). One read,
 * so an answer given on one hub is gone from the others.
 *
 * It also answers, for the whole client, whether this node carries
 * invitations at all ([support]): a bare 404 on `GET /v1/self/invites` is a
 * node older than 0.5.218. The founding cards read it to decide whether a
 * group can still be founded with other people named in it.
 */
class InvitationsViewModel(private val api: MembershipInvitesApi) : ViewModel() {

    companion object {
        private const val TAG = "InvitationsVM"
    }

    /** Advanced by [clearSessionState]; a publish after an await re-checks it. */
    private var sessionEpoch: Long = 0L

    private val _inbox = MutableStateFlow<InboxRead>(InboxRead.NotAsked)
    val inbox: StateFlow<InboxRead> = _inbox.asStateFlow()

    private val _support = MutableStateFlow(InviteSupport.UNKNOWN)
    val support: StateFlow<InviteSupport> = _support.asStateFlow()

    private val _confirming = MutableStateFlow<InviteAnswerAsk?>(null)
    val confirming: StateFlow<InviteAnswerAsk?> = _confirming.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The node's refusal of the last answer, rendered by its `membership.*` id. */
    private val _refusal = MutableStateFlow<NodeRefusal?>(null)
    val refusal: StateFlow<NodeRefusal?> = _refusal.asStateFlow()

    private val _answered = MutableStateFlow<InviteAnswered?>(null)
    val answered: StateFlow<InviteAnswered?> = _answered.asStateFlow()

    fun load() {
        val epoch = sessionEpoch
        viewModelScope.launch { reload(epoch) }
    }

    private suspend fun reload(epoch: Long) {
        if (_inbox.value !is InboxRead.Loaded) _inbox.value = InboxRead.Loading
        val next = try {
            InboxRead.Loaded(api.myInvites().invites)
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[load] ${(e as? NodeRefusal)?.reasonId ?: e.message}")
            when {
                isInviteRouteMissing(e) -> InboxRead.NotOnThisNode
                e is NodeRefusal -> InboxRead.Failed(e, e.detail)
                else -> InboxRead.Failed(null, e.message ?: e::class.simpleName)
            }
        }
        if (epoch != sessionEpoch) return
        _inbox.value = next
        when (next) {
            is InboxRead.Loaded -> _support.value = InviteSupport.INVITES
            InboxRead.NotOnThisNode -> _support.value = InviteSupport.LEGACY
            else -> {}
        }
    }

    /** Ask to accept or decline; the confirm names who, into what, and what it means. */
    fun request(invite: InboxInvite, accept: Boolean) {
        _refusal.value = null
        _answered.value = null
        _confirming.value = InviteAnswerAsk(invite, accept)
    }

    fun cancelConfirm() {
        _confirming.value = null
    }

    /** Send the answer waiting on the confirm. Nothing is sent without one. */
    fun confirm() {
        val ask = _confirming.value ?: return
        _confirming.value = null
        if (_busy.value) return
        _busy.value = true
        _refusal.value = null
        _answered.value = null
        val epoch = sessionEpoch
        viewModelScope.launch {
            try {
                val answer = if (ask.accept) api.accept(ask.invite.proposalId) else api.decline(ask.invite.proposalId)
                if (epoch != sessionEpoch) return@launch
                _answered.value = InviteAnswered(ask.accept, ask.invite, answer.awaiting)
                reload(epoch)
            } catch (e: NodeRefusal) {
                if (epoch != sessionEpoch) return@launch
                PlatformLogger.w(TAG, "[answer] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}")
                _refusal.value = e
                // A lapsed, withdrawn or already-answered invitation is gone:
                // re-read so the row the person just tried no longer offers itself.
                reload(epoch)
            } catch (e: Exception) {
                if (epoch != sessionEpoch) return@launch
                PlatformLogger.w(TAG, "[answer] failed: ${e.message}")
                _refusal.value = NodeRefusal(null, e.message, 0)
            } finally {
                if (epoch == sessionEpoch) _busy.value = false
            }
        }
    }

    fun clearMessages() {
        _refusal.value = null
        _answered.value = null
    }

    /** Owner-gated content: nothing of it survives into the next session. */
    fun clearSessionState() {
        sessionEpoch += 1
        _inbox.value = InboxRead.NotAsked
        _support.value = InviteSupport.UNKNOWN
        _confirming.value = null
        _busy.value = false
        clearMessages()
    }
}
