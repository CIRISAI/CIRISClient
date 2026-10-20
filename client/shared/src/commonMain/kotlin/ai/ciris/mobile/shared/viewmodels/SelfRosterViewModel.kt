package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.SelfRosterApi
import ai.ciris.mobile.shared.models.federation.ROSTER_W_SELF_DEFAULT_SECS
import ai.ciris.mobile.shared.models.federation.RosterProposal
import ai.ciris.mobile.shared.models.federation.RosterStanding
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

private const val TAG = "SelfRosterVM"

/** The answer this device refuses itself: it proposed the change (`src/self_devices.rs:1506-1513`). */
const val ROSTER_OWN_PROPOSAL = "self.roster_own_proposal"

/** The change is no longer open, or never was (`src/self_devices.rs:1494-1501`). */
const val ROSTER_PROPOSAL_UNKNOWN = "self.roster_proposal_unknown"

/** A refusal the node named, rendered by id. */
data class RosterRefusal(val reasonId: String?, val detail: String?, val status: Int)

/** The answer a [SelfRosterUiState.confirming] sheet is about to sign. */
data class RosterAnswerConfirm(val standing: RosterStanding, val assent: Boolean)

/** What this device's last answer did. */
data class RosterAnswerOutcome(
    val proposalId: String,
    val assent: Boolean,
    /** The node no longer holds it open: it landed, or objections cancelled it. */
    val settled: Boolean,
    /** The signed `scores` row the answer is — its receipt. */
    val attestationId: String?,
)

/** How a change that left the open set ended, as far as this card can tell. */
enum class RosterSettledHow {
    /** The roster now shows it: an added key is on it, an evicted key is off it. */
    LANDED,

    /** It left the open set and the roster does not show it: cancelled or withdrawn. */
    CLOSED,

    /** Its last decision was a cancel, with [RosterSettled.reason]. */
    CANCELLED,

    /** Its last decision was a refusal, with [RosterSettled.reason]. */
    REFUSED,
}

/** A change that was open while this card watched it and no longer is. */
data class RosterSettled(val proposal: RosterProposal, val how: RosterSettledHow, val reason: String? = null)

/**
 * The self-key roster card (CSD-095). Every field is as of [fetchedAtMs].
 *
 * [ownProposals] are the changes THIS device proposed, as far as the client
 * knows: a held add-device approval named its proposal (202 `proposal_id`), a
 * held revoke named its target, or the node refused an answer with
 * [ROSTER_OWN_PROPOSAL]. The node is the authority (it compares the answering
 * pen to the proposer); this set only spares the person a button the node will
 * refuse. [answered] is this device's answers in this session.
 */
data class SelfRosterUiState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** A bare 404 on the list: the node predates CIRISServer#794. The card says so and offers nothing. */
    val nodeTooOld: Boolean = false,
    val refusal: RosterRefusal? = null,
    val identityKeyId: String = "",
    val roster: List<String> = emptyList(),
    val wSelfSecs: Long = ROSTER_W_SELF_DEFAULT_SECS,
    val proposals: List<RosterStanding> = emptyList(),
    val fetchedAtMs: Long = 0,
    val ownProposals: Set<String> = emptySet(),
    val answered: Map<String, Boolean> = emptyMap(),
    /** Names this device knows for keys the roster does not label yet (a device it approved). */
    val proposedLabels: Map<String, String> = emptyMap(),
    val confirming: RosterAnswerConfirm? = null,
    /** The proposal id an answer is in flight for. */
    val working: String? = null,
    val lastAnswer: RosterAnswerOutcome? = null,
    val answerRefusal: RosterRefusal? = null,
    val history: List<RosterSettled> = emptyList(),
) {
    /** Changes still open (pending), whatever answered them. */
    val open: List<RosterStanding> get() = proposals.filter { it.decision.outcome == "pending" }

    /** Open changes THIS device can still answer: the badge on Devices & keys. */
    val awaitingThisDevice: Int
        get() = open.count { it.proposal.id !in ownProposals && it.proposal.id !in answered }

    /** Whether this device may answer [s] now (the node still decides). */
    fun canAnswer(s: RosterStanding): Boolean =
        s.decision.outcome == "pending" && s.proposal.id !in ownProposals && working == null
}

/** Signatures so far: the proposer's, and every live approver's. */
fun RosterStanding.signaturesSoFar(): Int = 1 + approvers.size

/**
 * Signatures the quorum path needs in all: so far plus `needs` while pending;
 * otherwise a strict majority of the live [rosterSize].
 */
fun RosterStanding.signaturesNeeded(rosterSize: Int): Int =
    decision.needs?.takeIf { decision.outcome == "pending" }?.let { signaturesSoFar() + it }
        ?: (rosterSize / 2 + 1)

/** Whether [roster] shows [p] done: an added key on it, an evicted key off it. */
internal fun rosterShows(p: RosterProposal, roster: List<String>): Boolean = when (p.kind) {
    "add" -> p.target in roster
    "evict" -> p.target !in roster
    else -> false
}

/** How [p] reads against the roster that followed it, or its last decision when that says it lands. */
internal fun settledHow(p: RosterProposal, last: RosterStanding?, roster: List<String>): RosterSettled {
    return when {
        rosterShows(p, roster) || last?.decision?.outcome == "lands" -> RosterSettled(p, RosterSettledHow.LANDED)
        last?.decision?.outcome == "cancelled" -> RosterSettled(p, RosterSettledHow.CANCELLED, last.decision.reason)
        last?.decision?.outcome == "refused" -> RosterSettled(p, RosterSettledHow.REFUSED, last.decision.reason)
        else -> RosterSettled(p, RosterSettledHow.CLOSED)
    }
}

class SelfRosterViewModel(
    private val api: SelfRosterApi,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    private val _state = MutableStateFlow(SelfRosterUiState())
    val state: StateFlow<SelfRosterUiState> = _state.asStateFlow()

    private val listing = Mutex()
    private var listJob: Job? = null

    /**
     * Bumped by every answer. A read applies only if no answer landed since it
     * started, so a list fetched before an answer never paints over it.
     */
    private var epoch = 0L

    /** Evict targets a held revoke on this device named; their proposal is this device's. */
    private val evictTargetsHere = mutableSetOf<String>()

    /**
     * Re-read the open changes. Stands down while an answer is in flight (the
     * answer re-reads when it is done), and a call while a read is out returns
     * THAT read to join rather than racing it.
     */
    fun refresh(): Job {
        if (_state.value.working != null) return Job().apply { complete() }
        listJob?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch { read() }.also { listJob = it }
    }

    /** One read, awaited. */
    suspend fun refreshNow() {
        if (_state.value.working != null) return
        read()
    }

    private suspend fun read() = listing.withLock {
        val started = epoch
        _state.value = _state.value.copy(loading = true)
        val apply: (SelfRosterUiState) -> SelfRosterUiState = try {
            val r = api.proposals()
            val at = clock();
            { s ->
                val nowIds = r.proposals.map { it.proposal.id }.toSet()
                val gone = s.proposals.filter { it.proposal.id !in nowIds }
                    .map { settledHow(it.proposal, it, r.roster) }
                    .filter { g -> s.history.none { it.proposal.id == g.proposal.id } }
                val ownByTarget = r.proposals
                    .filter { it.proposal.kind == "evict" && it.decision.outcome == "pending" && it.proposal.target in evictTargetsHere }
                    .groupBy { it.proposal.target }
                    .filterValues { it.size == 1 }
                    .values.map { it.single().proposal.id }
                s.copy(
                    loaded = true,
                    loading = false,
                    nodeTooOld = false,
                    refusal = null,
                    identityKeyId = r.identityKeyId,
                    roster = r.roster,
                    wSelfSecs = r.wSelfSecs,
                    proposals = r.proposals,
                    fetchedAtMs = at,
                    ownProposals = s.ownProposals + ownByTarget,
                    // A change filed CLOSED is looked at again on every read: the node's
                    // roster can lag the open set by a moment (T10, CIRISServer#802), so
                    // the read that saw it leave may not show it landed yet.
                    history = gone + s.history.map { h ->
                        if (h.how == RosterSettledHow.CLOSED && rosterShows(h.proposal, r.roster)) h.copy(how = RosterSettledHow.LANDED, reason = null) else h
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NodeRefusal) {
            val tooOld = e.statusCode == 404 && e.reasonId == null;
            { s ->
                s.copy(
                    loaded = true,
                    loading = false,
                    nodeTooOld = tooOld,
                    refusal = if (tooOld) null else RosterRefusal(e.reasonId, e.detail, e.statusCode),
                )
            }
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "list failed: ${e.message}");
            { s -> s.copy(loaded = true, loading = false, refusal = RosterRefusal(null, e.message, 0)) }
        }
        _state.value = if (epoch == started) apply(_state.value) else _state.value.copy(loading = false)
    }

    /** A held add-device approval on THIS device named [proposalId] (202, CIRISServer#794). */
    fun markProposedHere(proposalId: String, targetLabel: String? = null, target: String? = null) {
        val s = _state.value
        _state.value = s.copy(
            ownProposals = s.ownProposals + proposalId,
            proposedLabels = if (target != null && !targetLabel.isNullOrBlank()) s.proposedLabels + (target to targetLabel) else s.proposedLabels,
        )
        refresh()
    }

    /** A revoke on THIS device was held: the evict proposal naming [occurrenceKeyId] is this device's. */
    fun markEvictionProposedHere(occurrenceKeyId: String) {
        evictTargetsHere += occurrenceKeyId
        refresh()
    }

    fun askAssent(s: RosterStanding) {
        _state.value = _state.value.copy(confirming = RosterAnswerConfirm(s, assent = true), answerRefusal = null, lastAnswer = null)
    }

    fun askObject(s: RosterStanding) {
        _state.value = _state.value.copy(confirming = RosterAnswerConfirm(s, assent = false), answerRefusal = null, lastAnswer = null)
    }

    fun dismissConfirm() {
        _state.value = _state.value.copy(confirming = null)
    }

    /**
     * Sign the confirmed answer. Claimed synchronously (two taps sign once);
     * polling stands down until it is done, then the list is re-read.
     */
    fun confirm(): Job {
        val c = _state.value.confirming ?: return Job().apply { complete() }
        if (_state.value.working != null) return Job().apply { complete() }
        val id = c.standing.proposal.id
        _state.value = _state.value.copy(confirming = null, working = id, answerRefusal = null, lastAnswer = null)
        return viewModelScope.launch {
            // An in-flight read finishes first and cannot land over the answer.
            listJob?.join()
            try {
                val r = if (c.assent) api.assent(id) else api.objectTo(id)
                PlatformLogger.i(TAG, "answered $id ${r.answer} settled=${r.settled}")
                epoch++
                val s = _state.value
                val after = r.proposal
                _state.value = s.copy(
                    working = null,
                    answered = s.answered + (id to c.assent),
                    lastAnswer = RosterAnswerOutcome(id, c.assent, r.settled, r.answerAttestationId),
                    proposals = if (after != null && !r.settled) s.proposals.map { if (it.proposal.id == id) after else it }
                    else s.proposals.filterNot { it.proposal.id == id },
                    // Settled: a decision that lands is LANDED at once; otherwise the
                    // roster this card holds decides, and a CLOSED entry is looked at
                    // again on every read until the roster shows it (see read()).
                    history = if (r.settled && s.history.none { it.proposal.id == id }) {
                        listOf(settledHow(c.standing.proposal, after ?: c.standing, s.roster)) + s.history
                    } else s.history,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: NodeRefusal) {
                epoch++
                val s = _state.value
                PlatformLogger.w(TAG, "answer $id refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}")
                _state.value = when {
                    e.statusCode == 404 && e.reasonId == null -> s.copy(working = null, nodeTooOld = true)
                    e.reasonId == ROSTER_OWN_PROPOSAL -> s.copy(
                        working = null,
                        ownProposals = s.ownProposals + id,
                        answerRefusal = RosterRefusal(e.reasonId, e.detail, e.statusCode),
                    )
                    else -> s.copy(working = null, answerRefusal = RosterRefusal(e.reasonId, e.detail, e.statusCode))
                }
            } catch (e: Exception) {
                epoch++
                _state.value = _state.value.copy(working = null, answerRefusal = RosterRefusal(null, e.message, 0))
            }
            read()
        }
    }

    fun clearOutcome() {
        _state.value = _state.value.copy(lastAnswer = null, answerRefusal = null)
    }
}
