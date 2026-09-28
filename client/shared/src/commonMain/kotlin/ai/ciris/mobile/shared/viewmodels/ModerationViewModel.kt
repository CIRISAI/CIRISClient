package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ModerationProposalResult
import ai.ciris.mobile.shared.api.RouteNotOnThisHost
import ai.ciris.mobile.shared.models.safety.AgeBand
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The three proposable moderation actions of the CC 4.5.13 reverse-quorum
 * open-labeling path. ANYONE (any adult member) MAY propose any of these
 * against any piece of content — none of them is the authoritative
 * duty-holder action; each merely OPENS the 48-hour community window.
 *
 * [wire] is the token the server's report→`scores` Contribution carries
 * (see [CIRISApiClient.proposeModeration]).
 */
enum class ModerationAction(val wire: String) {
    /** Flag for attention — "something is off here." */
    REPORT("report"),

    /** Ask the community/moderator to remove the content. */
    TAKEDOWN("takedown"),

    /** Open a neutral keep-or-remove question for the window to decide. */
    QUESTION("question"),
}

/**
 * UI-facing state of a single moderation proposal submission.
 */
data class ModerationSubmitState(
    val isSubmitting: Boolean = false,
    /** Set once the proposal has been accepted (the window is open). */
    val result: ModerationProposalResult? = null,
    /** The node was asked and the submit failed (never a 404: that is [notOnThisNode]). */
    val error: String? = null,
    /**
     * The node has no `/v1/safety/reports` (CIRISServer#665). A fact about
     * the node, said as one; not an error and not "submitted".
     */
    val notOnThisNode: Boolean = false,
    /**
     * This person's age assurance on the node says `minor`. A minor raises a
     * moderation concern through their steward (CC 3.4.13), so the proposal
     * is refused HERE, before any request, with the steward path named.
     */
    val refusedAsMinor: Boolean = false,
) {
    val isDone: Boolean get() = result != null
}

/**
 * Drives the **reverse-quorum moderation PROPOSAL** flow (CC 4.5.13).
 *
 * This VM does not adjudicate anything — adjudication is server-side
 * governance (a present moderator/steward acts within 48h, else the live
 * community falls back). Its only job is to take the user's chosen
 * [ModerationAction] + optional reason for a given target content id and
 * POST the open-labeling report→`scores` Contribution via
 * [CIRISApiClient.proposeModeration], then surface the submit state.
 *
 * **The minor gate is enforced here.** Before the request, this person's
 * age assurance is read from the local node (`GET
 * /v1/safety/age-assurance/{self}`); a recorded `minor` band is refused
 * with the steward path named. No record is NOT a refusal: CC 4.5.13
 * opens the proposal to anyone, the node adjudicates the rest, and
 * locking every unrecorded person out of reporting harm would serve
 * nobody the gate protects.
 */
class ModerationViewModel(
    private val apiClient: CIRISApiClient,
) : ViewModel() {

    companion object {
        private const val TAG = "ModerationVM"

        /** The constitutional participation window — CC 4.5.13. */
        const val WINDOW_HOURS = 48
    }

    private val _state = MutableStateFlow(ModerationSubmitState())
    val state: StateFlow<ModerationSubmitState> = _state.asStateFlow()

    /** Reset before opening the sheet for a fresh target. */
    fun reset() {
        _state.value = ModerationSubmitState()
    }

    /**
     * Submit a moderation proposal against [targetId]. Opens the 48-hour
     * community window. [onComplete] fires (on success) so the caller can
     * dismiss the sheet.
     */
    fun submit(
        targetId: String,
        action: ModerationAction,
        reason: String?,
        onComplete: () -> Unit = {},
    ) {
        if (_state.value.isSubmitting) return
        _state.value = ModerationSubmitState(isSubmitting = true)
        viewModelScope.launch {
            if (selfIsRecordedMinor()) {
                PlatformLogger.i(TAG, "propose moderation refused: this person's age band is minor")
                _state.value = ModerationSubmitState(refusedAsMinor = true)
                return@launch
            }
            try {
                PlatformLogger.i(
                    TAG,
                    "propose moderation target=$targetId action=${action.wire} hasReason=${!reason.isNullOrBlank()}",
                )
                val result = apiClient.proposeModeration(
                    targetId = targetId,
                    action = action.wire,
                    reason = reason?.trim()?.ifBlank { null },
                )
                _state.value = ModerationSubmitState(result = result)
                onComplete()
            } catch (e: RouteNotOnThisHost) {
                PlatformLogger.w(TAG, "moderation proposal: ${e.message}")
                _state.value = ModerationSubmitState(notOnThisNode = true)
            } catch (e: Exception) {
                PlatformLogger.e(TAG, "moderation proposal failed: ${e.message}")
                _state.value = ModerationSubmitState(
                    error = e.message ?: "Could not submit. Please try again.",
                )
            }
        }
    }

    /**
     * Is this person recorded as a minor on the local node? Only a RECORDED
     * `minor` refuses; no identity, no record or an unreadable record does
     * not, for the reason in the class doc.
     */
    private suspend fun selfIsRecordedMinor(): Boolean {
        val self = runCatching { apiClient.getSelfKeyRecord().keyId }.getOrNull() ?: return false
        val assurance = runCatching { apiClient.getAgeAssurance(self).assurance }
            .onFailure { PlatformLogger.w(TAG, "age assurance unread for $self: ${it.message}") }
            .getOrNull()
        return assurance?.band == AgeBand.MINOR
    }
}
