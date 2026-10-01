package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.federation.ClaimedSessionGrant

/**
 * The new device's owner session, collected after ANOTHER device approved it
 * (CSD-093, CIRISServer 0.5.218 `POST /v1/setup/claimed-session`).
 *
 * Since 0.5.218 the approving device's claim does not carry the session back:
 * this node keeps it for this device's own wizard, and the wizard collects it
 * with the claim PIN it showed. Each state says what the card does next.
 */
sealed interface ClaimedSessionState {
    /** Nothing asked yet. */
    data object Idle : ClaimedSessionState

    /** The request is in flight. */
    data object Collecting : ClaimedSessionState

    /** The session is this client's now; the wizard leaves signed in. */
    data class Collected(val role: String?) : ClaimedSessionState

    /**
     * No claim PIN on this device, so there is nothing to collect with. The
     * card's `approval_code_no_pin` state; nothing was sent.
     */
    data object NoPin : ClaimedSessionState

    /**
     * The node named why. Rendered by id: `auth.claim.pin_invalid`,
     * `auth.claim.no_pending_session` and every other server id resolve in
     * the bundles. [goesToLogin] says whether the card should fall back to
     * signing in rather than retrying.
     */
    data class Refused(val reasonId: String?, val detail: String?, val status: Int) : ClaimedSessionState {
        /**
         * `no_pending_session`: nothing is waiting — the session was already
         * collected, the 15 minutes ran out, or this device was not claimed by
         * another. Retrying cannot help; sign in is the way on.
         */
        val goesToLogin: Boolean get() = reasonId == NO_PENDING_SESSION
    }

    /**
     * Bare 404: the node predates 0.5.218 and has no such route. Its claim
     * already handed the session to the approving device, so the behaviour
     * before this route existed stands: "Approved. Sign in to finish." and Login.
     */
    data object NodeTooOld : ClaimedSessionState

    /** The node did not answer at all. Distinct from a refusal: try again. */
    data class Failed(val detail: String?) : ClaimedSessionState

    companion object {
        const val PIN_INVALID = "auth.claim.pin_invalid"
        const val NO_PENDING_SESSION = "auth.claim.no_pending_session"
    }
}

/** How the wizard collects; the real one is [ai.ciris.mobile.shared.api.CIRISApiClient.collectClaimedSession]. */
fun interface ClaimedSessionCollector {
    suspend fun collect(claimPin: String): ClaimedSessionGrant
}
