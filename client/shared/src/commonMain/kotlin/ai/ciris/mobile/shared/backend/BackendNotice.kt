package ai.ciris.mobile.shared.backend

/**
 * What the user is told about the backend, and what they can DO about it.
 *
 * THE SITUATION THIS MAKES IMPOSSIBLE
 * -----------------------------------
 * A user on a 32-bit Android phone opened the app and read:
 *
 *     Cannot connect to server. Please check your connection.
 *
 * The backend runs INSIDE the app. There was no server to connect to and no
 * connection to check. They restarted the phone, then asked why the app kept
 * demanding a login and why their Google account would not work — because an
 * unreachable backend surfaces on the login screen, so that is what it looked
 * like. None of that was true, none of it was actionable, and the app knew the
 * real reason the whole time: the service caught the startup exception and put
 * it in a NOTIFICATION.
 *
 * Three separate failures, and the user could not have diagnosed any of them.
 *
 * WHY THIS IS A `when` AND NOT A STRING TABLE
 * -------------------------------------------
 * [noticeFor] is an exhaustive `when` over a sealed interface with no `else`.
 * Adding a state to [BackendState] without deciding what the user is told and
 * what they can do about it is a COMPILE ERROR, not a screen that says
 * something false. That is the whole point: the situation is prevented by the
 * type system rather than by everyone remembering.
 *
 * The old message was not merely wrong, it was structurally unable to be right:
 * one `catch (e: Exception)` produced it for a dead backend, airplane mode, a
 * DNS failure and a cancelled request alike.
 */
data class BackendNotice(
    /** Localization key for the one-line status. Never a raw English string. */
    val headlineKey: String,
    /**
     * The REAL reason, when we have one — a Python import error, a refused
     * socket, an attempt count. Shown as supporting detail.
     *
     * Never invented. Null means we genuinely do not know, and the UI must not
     * fill that silence with a guess, which is exactly how "check your
     * connection" came to be shown to someone whose network was fine.
     */
    val detail: String?,
    /** What the user can do. Never [BackendAction.None] on a non-working state. */
    val action: BackendAction,
    /** True when this is an error the user should see as one. */
    val isError: Boolean,
)

/**
 * The action offered alongside a notice.
 *
 * Every non-working state carries something the user can actually do. "Nothing
 * you can do" is not one of the options, because that is the state Esu was left
 * in for hours.
 */
sealed interface BackendAction {
    /** Working. No action, no error styling, nothing in the way. */
    data object None : BackendAction

    /**
     * We are on it — thawing after a resume, or restarting. A spinner, not an
     * error. This is the common healthy path on mobile and must never look like
     * a failure.
     */
    data object Waiting : BackendAction

    /** Try again now. Clears the crash-loop guard. */
    data object Retry : BackendAction

    /**
     * The only state for which "check your connection" is a true sentence: we
     * could not ASK. Says nothing about the backend.
     */
    data object CheckNetwork : BackendAction

    /**
     * A remote node we do not own is not answering. Not the user's network, not
     * ours to restart — but they can point the app somewhere else.
     */
    data object ChooseNode : BackendAction
}

/**
 * The single mapping from machine state to what a person sees.
 *
 * Exhaustive by construction. A new [BackendState] does not compile until it
 * has an answer here.
 */
fun noticeFor(state: BackendState): BackendNotice = when (state) {
    is BackendState.Live -> BackendNotice(
        headlineKey = "mobile.backend_live",
        detail = null,
        action = BackendAction.None,
        isError = false,
    )

    is BackendState.Thawing -> BackendNotice(
        // "Waking the agent…" — the common path after a phone unlocks. Not an
        // error, not styled as one, and it resolves itself.
        headlineKey = "mobile.backend_waking",
        detail = null,
        action = BackendAction.Waiting,
        isError = false,
    )

    is BackendState.Down -> BackendNotice(
        headlineKey = "mobile.backend_down",
        // Say WHICH: observed death reads differently from a budget that ran
        // out, and a user forwarding a screenshot should be forwarding a fact.
        detail = when (val e = state.evidence) {
            is DeathEvidence.Observed -> e.detail
            is DeathEvidence.Refused -> "nothing is listening on the local port"
            is DeathEvidence.BudgetExpired -> "no response within ${e.budgetMs / 1000}s"
        },
        action = BackendAction.Retry,
        isError = true,
    )

    is BackendState.Reviving -> BackendNotice(
        headlineKey = "mobile.backend_restarting",
        detail = "attempt ${state.attempt}",
        action = BackendAction.Waiting,
        isError = false,
    )

    is BackendState.GaveUp -> BackendNotice(
        headlineKey = "mobile.backend_gave_up",
        // THE ERROR THE APP ALREADY HAD. On Android the service caught the
        // Python startup exception and wrote it to a notification while the
        // screen said "check your connection". It belongs here.
        detail = state.lastError.ifBlank { null },
        action = BackendAction.Retry,
        isError = true,
    )

    is BackendState.Unreachable -> BackendNotice(
        // The ONE place the old message was honest.
        headlineKey = "mobile.backend_unreachable",
        detail = state.cause,
        action = BackendAction.CheckNetwork,
        isError = true,
    )

    is BackendState.NotOurs -> BackendNotice(
        headlineKey = "mobile.backend_remote_silent",
        detail = null,
        action = BackendAction.ChooseNode,
        isError = true,
    )
}
