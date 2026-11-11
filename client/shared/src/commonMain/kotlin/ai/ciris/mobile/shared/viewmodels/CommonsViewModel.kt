package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientCommons
import ai.ciris.mobile.shared.api.CommonsApi
import ai.ciris.mobile.shared.models.surfaces.CommonsBallotRequest
import ai.ciris.mobile.shared.models.surfaces.CommonsDismissalRequest
import ai.ciris.mobile.shared.models.surfaces.CommonsObjectionRequest
import ai.ciris.mobile.shared.models.surfaces.CommonsScrubSig
import ai.ciris.mobile.shared.models.surfaces.CommonsStanding
import ai.ciris.mobile.shared.models.surfaces.CommonsWriteResult
import ai.ciris.mobile.shared.models.surfaces.objectionIds
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * What one dismissal dry run answered, held until it is submitted or let go.
 *
 * [request] is the request the dry run was taken over, kept whole: the
 * submission is this request and nothing typed since. [envelope] is the node's
 * stamped envelope, which goes back verbatim (CIRISServer#402).
 */
data class DismissalDraft(
    val request: CommonsDismissalRequest,
    val payloadSha256: String,
    val envelope: JsonElement?,
)

/**
 * The submission a held dry run turns into: the same cohort, key, action,
 * objection and grounds, the stamped envelope, and the co-signatures gathered
 * over it. Pure, so "the submit sends the dry run's bytes" is a test
 * (CSD-070 §2.3, law L7).
 */
internal fun dismissalSubmission(draft: DismissalDraft, cosigners: List<CommonsScrubSig>): CommonsDismissalRequest =
    draft.request.copy(dryRun = false, additionalScrubs = cosigners, stampedEnvelope = draft.envelope)

/**
 * Drives the **Commons** surface — persist's reverse-quorum plane
 * (CIRISServer#367, `src/commons_surface.rs`).
 *
 * # The asymmetry is structural here too
 *
 * [raiseObjection] is one call with one argument: grounds. There is nothing to
 * gather, nobody to wait for, and no threshold to reach — one member raises the
 * brake.
 *
 * [submitDismissal] cannot even be reached without [dismissDryRun] first,
 * because the co-signatures must be produced over the exact canonical bytes the
 * submission will carry and only the dry run knows them. That is not a UI
 * nicety layered on top of a symmetric API: the m-of-n is literally unreachable
 * without the round trip, so the state machine here has the same shape the
 * substrate rule does.
 *
 * # No arithmetic
 *
 * Not one threshold, count or window comparison happens in this class. Every
 * number rendered by the screen comes from [standing] or from a write's
 * `quorum`, both of which are persist's fold read verbatim. A second
 * implementation of a rule is a second answer that can disagree with the first.
 *
 * # An answer is about its question
 *
 * The card's `csd:inputs` block (CSD-070 §2.3) is this class written down, and
 * `packaging/check_csd5.py` walks it. Three rules come from there:
 *
 * - [standing] is the answer to the cohort, key and action that were asked.
 *   Changing any of them lets it go, with the dismissal draft taken under it.
 * - A read that is in flight or failed draws nothing from the read before it.
 * - [submitDismissal] sends [DismissalDraft.request], never the fields as they
 *   are now.
 */
class CommonsViewModel(
    private val api: CommonsApi,
) : ViewModel() {

    constructor(apiClient: CIRISApiClient) : this(ClientCommons(apiClient))

    companion object {
        private const val TAG = "CommonsVM"

        /**
         * The rostered cohorts, as persist's own `Cohort::from_token` spells
         * them. `self` parses but is refused by the surface with its own token:
         * one identity's own devices are not a commons and have no roster to be
         * a quorum over.
         */
        val COHORTS = listOf("family", "community", "affiliations")
    }

    // ── What the operator is asking about ────────────────────────────────────

    private val _cohort = MutableStateFlow(COHORTS.first())
    val cohort: StateFlow<String> = _cohort.asStateFlow()

    private val _cohortKeyId = MutableStateFlow("")
    val cohortKeyId: StateFlow<String> = _cohortKeyId.asStateFlow()

    private val _actionId = MutableStateFlow("")
    val actionId: StateFlow<String> = _actionId.asStateFlow()

    fun setCohort(v: String) {
        if (v == _cohort.value) return
        _cohort.value = v
        questionChanged()
    }

    fun setCohortKeyId(v: String) {
        if (v == _cohortKeyId.value) return
        _cohortKeyId.value = v
        questionChanged()
    }

    fun setActionId(v: String) {
        if (v == _actionId.value) return
        _actionId.value = v
        questionChanged()
    }

    /**
     * The question on screen is no longer the one anything held was asked
     * about. The standing, a read still in flight, the grounds typed under that
     * standing and the dismissal draft all go; a write that was answered stays
     * until it is closed, because it happened.
     */
    private fun questionChanged() {
        readToken++
        _standing.value = null
        _loading.value = false
        _error.value = null
        _writeError.value = null
        _raiseGrounds.value = ""
        clearDismissalDraft()
    }

    // ── What came back ───────────────────────────────────────────────────────

    /**
     * The fold's answer to the question on screen. `null` means there is none:
     * nothing was asked, the read is in flight, or it failed — [loading] and
     * [error] say which.
     */
    private val _standing = MutableStateFlow<CommonsStanding?>(null)
    val standing: StateFlow<CommonsStanding?> = _standing.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The READ did not arrive. An unreadable plane is an ANSWER, not an error. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * A WRITE or a dry run did not arrive. Its own value, because the standing
     * under it is still the answer to the question and stays drawn.
     */
    private val _writeError = MutableStateFlow<String?>(null)
    val writeError: StateFlow<String?> = _writeError.asStateFlow()

    private val _writeResult = MutableStateFlow<CommonsWriteResult?>(null)
    val writeResult: StateFlow<CommonsWriteResult?> = _writeResult.asStateFlow()

    /** Raised by every new question and every new read; an answer to an older one is dropped. */
    private var readToken = 0

    // ── Raising: the grounds ─────────────────────────────────────────────────

    /** Grounds for an objection, typed under the standing on screen and gone with it. */
    private val _raiseGrounds = MutableStateFlow("")
    val raiseGrounds: StateFlow<String> = _raiseGrounds.asStateFlow()

    fun setRaiseGrounds(v: String) { _raiseGrounds.value = v }

    // ── The dismissal ceremony: dry run -> co-sign -> submit ─────────────────

    /** The held dry run. `null` keeps [submitDismissal] shut. */
    private val _dismissalDraft = MutableStateFlow<DismissalDraft?>(null)
    val dismissalDraft: StateFlow<DismissalDraft?> = _dismissalDraft.asStateFlow()

    private val _cosigners = MutableStateFlow<List<CommonsScrubSig>>(emptyList())
    val cosigners: StateFlow<List<CommonsScrubSig>> = _cosigners.asStateFlow()

    /** Raised whenever the draft is let go; a dry run answered after that is not kept. */
    private var draftToken = 0

    /** A co-signature is over the held dry run's bytes; with none held there is nothing to sign. */
    fun addCosigner(keyId: String, classical: String, pqc: String?) {
        if (_dismissalDraft.value == null) return
        if (keyId.isBlank() || classical.isBlank()) return
        _cosigners.value = _cosigners.value + CommonsScrubSig(
            scrubKeyId = keyId.trim(),
            scrubSignatureClassical = classical.trim(),
            scrubSignaturePqc = pqc?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    fun removeCosigner(index: Int) {
        _cosigners.value = _cosigners.value.filterIndexed { i, _ -> i != index }
    }

    /**
     * Let the pending ceremony go — the bytes, the signatures over them and the
     * card that showed the bytes go together.
     */
    fun clearDismissalDraft() {
        draftToken++
        _dismissalDraft.value = null
        _cosigners.value = emptyList()
        if (_writeResult.value?.let { it.dryRun && !it.refused } == true) _writeResult.value = null
    }

    /**
     * The dismissal grounds for [objectionId] now read [grounds]. A dry run
     * taken over other grounds is not a preview of them, so it is let go.
     */
    fun dismissalGroundsEdited(objectionId: String, grounds: String) {
        val draft = _dismissalDraft.value ?: return
        if (draft.request.objectionId == objectionId.trim() && draft.request.grounds != grounds.trim()) {
            clearDismissalDraft()
        }
    }

    fun clearWriteResult() {
        _writeResult.value = null
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * Ask the fold about the named action. The standing held before is let go
     * first: while this is in flight, and if it fails, the screen has no answer
     * and says so.
     */
    fun readStanding() {
        val cohortKey = _cohortKeyId.value.trim()
        val action = _actionId.value.trim()
        if (cohortKey.isEmpty() || action.isEmpty()) return
        val token = ++readToken
        _standing.value = null
        _error.value = null
        _writeError.value = null
        _raiseGrounds.value = ""
        _loading.value = true
        viewModelScope.launch {
            try {
                val answer = api.standing(cohort = _cohort.value, cohortKeyId = cohortKey, actionId = action)
                if (token != readToken) return@launch
                _standing.value = answer
                // A draft is for one objection. A standing that answered and no
                // longer names it has nothing left to lift; a refusal or a
                // failed read says nothing about it, so the draft stays.
                val drafted = _dismissalDraft.value?.request?.objectionId
                if (drafted != null && !answer.refused && drafted !in answer.objectionIds()) {
                    clearDismissalDraft()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLogger.e(TAG, "[readStanding] ${e.message}", e)
                if (token == readToken) _error.value = e.message ?: "commons standing read failed"
            } finally {
                if (token == readToken) _loading.value = false
            }
        }
    }

    /**
     * One write. Done: the answer is shown and the standing is read again.
     * Refused: the answer is shown and nothing typed is lost. Unanswered:
     * [writeError], and nothing typed is lost.
     */
    private fun write(name: String, onDone: () -> Unit = {}, call: suspend () -> CommonsWriteResult) {
        val token = readToken
        _busy.value = true
        viewModelScope.launch {
            try {
                val result = call()
                _writeError.value = null
                if (result.refused) {
                    _writeResult.value = result
                } else {
                    onDone()
                    _writeResult.value = result
                    if (token == readToken) readStanding()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLogger.e(TAG, "[$name] ${e.message}", e)
                _writeError.value = e.message ?: "$name failed"
            } finally {
                _busy.value = false
            }
        }
    }

    // ── The one-signature door ───────────────────────────────────────────────

    /**
     * **Raise the brake.** One member is enough; nothing is gathered and nobody
     * is waited for. The objection is a MARKER, not a command — nothing is
     * changed by its arrival, and it replicates on the ordinary attestation
     * plane so a peer partitioned during the window still counts it later.
     *
     * Sends [raiseGrounds], which is cleared only once the node has taken it.
     */
    fun raiseObjection() {
        val cohortKey = _cohortKeyId.value.trim()
        val action = _actionId.value.trim()
        val grounds = _raiseGrounds.value.trim()
        if (cohortKey.isEmpty() || action.isEmpty() || grounds.isEmpty()) return
        val request = CommonsObjectionRequest(
            cohort = _cohort.value,
            cohortKeyId = cohortKey,
            actionId = action,
            grounds = grounds,
        )
        write("raiseObjection", onDone = { _raiseGrounds.value = "" }) { api.objection(request) }
    }

    /** Answer a question the duty-holders left open. One signature, no force on its own. */
    fun castBallot(objectionId: String, upholds: Boolean, grounds: String) {
        val cohortKey = _cohortKeyId.value.trim()
        val action = _actionId.value.trim()
        if (cohortKey.isEmpty() || action.isEmpty() || objectionId.isBlank() || grounds.isBlank()) return
        val request = CommonsBallotRequest(
            cohort = _cohort.value,
            cohortKeyId = cohortKey,
            actionId = action,
            grounds = grounds.trim(),
            objectionId = objectionId.trim(),
            upholds = upholds,
        )
        write("castBallot") { api.ballot(request) }
    }

    // ── The m-of-n door ──────────────────────────────────────────────────────

    /**
     * Step 1 of lifting a brake: ask for the canonical envelope and its
     * `payload_sha256` WITHOUT signing or submitting anything. The co-signers
     * sign these bytes and no others.
     *
     * A draft held before, and every co-signature over it, goes first: each dry
     * run is stamped with its own row id and instant, so signatures over the
     * old one verify against nothing.
     */
    fun dismissDryRun(objectionId: String, grounds: String) {
        val cohortKey = _cohortKeyId.value.trim()
        val action = _actionId.value.trim()
        if (cohortKey.isEmpty() || action.isEmpty() || objectionId.isBlank() || grounds.isBlank()) return
        clearDismissalDraft()
        val token = draftToken
        val request = CommonsDismissalRequest(
            cohort = _cohort.value,
            cohortKeyId = cohortKey,
            actionId = action,
            grounds = grounds.trim(),
            objectionId = objectionId.trim(),
            dryRun = true,
        )
        _busy.value = true
        viewModelScope.launch {
            try {
                val result = api.dismissal(request)
                // Let go while it was in flight: the question or the grounds
                // changed, so this is a preview of something not on screen.
                if (token != draftToken) return@launch
                _writeError.value = null
                val sha = result.payloadSha256
                if (!result.refused && sha != null) {
                    _dismissalDraft.value = DismissalDraft(request, sha, result.envelope)
                }
                _writeResult.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLogger.e(TAG, "[dismissDryRun] ${e.message}", e)
                if (token == draftToken) _writeError.value = e.message ?: "dismissal dry run failed"
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Step 3: submit the held dry run with whatever co-signatures were gathered.
     * It takes no grounds: what is sent is [dismissalSubmission] of the draft,
     * so the hash on screen is the hash of what goes.
     *
     * What the co-signatures are WORTH is counted by the substrate; the returned
     * `quorum` names counted / required / roster_size on both arms. A refusal
     * keeps the draft and the signatures, because a shortfall is answered by
     * gathering one more over the same bytes.
     */
    fun submitDismissal(objectionId: String) {
        val draft = _dismissalDraft.value ?: return
        if (draft.request.objectionId != objectionId.trim()) return
        val request = dismissalSubmission(draft, _cosigners.value)
        write("submitDismissal", onDone = { clearDismissalDraft() }) { api.dismissal(request) }
    }
}
