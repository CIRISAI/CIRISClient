package ai.ciris.mobile.shared.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the answer to "is the backend alive, and if not, whose job is it?"
 *
 * PREFER RESUME. RESTART IS THE FALLBACK OF LAST RESORT. A backend that wakes
 * on its own is faster than one we cold-boot, and it keeps whatever state it
 * had; the only reason iOS restarts on every resume is that it stopped waiting
 * after two seconds. So the default response to silence is patience
 * ([BackendState.Thawing]), and a restart happens only when the host gives us
 * positive evidence of death or the budget genuinely runs out.
 *
 * ## Testability is the design
 *
 * This class does not own a loop. [tick] advances it exactly one step and
 * returns; [run] is a thin driver that calls [tick] on an interval. Tests drive
 * ticks directly against a virtual clock, so the resume/restart contract —
 * budgets, backoff, the crash-loop cap, the sustained-health reset — is
 * asserted without a single real sleep.
 *
 * This is the same reason `mayEnterWithFederationIdentity` was extracted before
 * it: a rule that can be read but not run gets read wrong.
 */
class BackendSupervisor(
    private val probe: suspend () -> ProbeOutcome,
    private val controller: LocalBackendController,
    private val ownership: () -> Ownership,
    private val policy: RevivePolicy = RevivePolicy(),
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow<BackendState>(BackendState.Thawing(0, policy.thawBudgetMs))

    /**
     * The single source of truth for every liveness surface in the UI. Status
     * text, banners and controls all derive from this one flow, so they cannot
     * tell the user different stories the way "Disconnected" next to a live
     * STOP button did.
     */
    val state: StateFlow<BackendState> = _state.asStateFlow()

    /** Attempts since the last SUSTAINED period of health — see [RevivePolicy.sustainedHealthMs]. */
    private var attempts: Int = 0

    /** When the current thaw window began, or null when we are not waiting on one. */
    private var thawStartedAt: Long? = null

    /** When the backend most recently became [BackendState.Live]. */
    private var liveSince: Long? = null

    /** Earliest time the next revive may run (backoff). */
    private var nextReviveAt: Long = 0

    private var foregrounded: Boolean = true

    private var lastError: String = ""

    // ------------------------------------------------------------------
    // Lifecycle input
    // ------------------------------------------------------------------

    /**
     * The app came back to the foreground. Opens a fresh thaw window.
     *
     * This is the event Android never had. iOS had it (`scenePhase == .active`)
     * and spent it on a two-second deadline.
     */
    fun onResumed() {
        foregrounded = true
        openThawWindow("resumed")
    }

    /**
     * The app went to the background.
     *
     * Reviving here would fight the deliberate 3-minute battery stop and turn a
     * power optimisation into a power leak, so [tick] refuses to revive while
     * backgrounded. We keep observing; we just do not act.
     */
    fun onBackgrounded() {
        foregrounded = false
    }

    private fun openThawWindow(why: String) {
        thawStartedAt = now()
        _state.value = BackendState.Thawing(0, policy.thawBudgetMs)
        log("thaw window opened ($why), budget=${policy.thawBudgetMs}ms")
    }

    // ------------------------------------------------------------------
    // One step
    // ------------------------------------------------------------------

    /**
     * Advance the machine one step: probe, classify, and act if acting is
     * warranted. Safe to call at any cadence; all timing decisions come from
     * [now] rather than from how often this is invoked.
     */
    suspend fun tick() {
        val t = now()

        // A remote node is never ours to restart, whatever the platform can do.
        if (ownership() == Ownership.REMOTE) {
            when (probe()) {
                ProbeOutcome.ANSWERED -> markLive(t)
                ProbeOutcome.TRANSPORT -> _state.value = BackendState.Unreachable("no route to the node")
                else -> _state.value = BackendState.NotOurs
            }
            return
        }

        when (val outcome = probe()) {
            ProbeOutcome.ANSWERED -> markLive(t)

            // We could not ASK. This says nothing about the backend, so it must
            // not consume the thaw budget and must never trigger a revive.
            ProbeOutcome.TRANSPORT -> {
                _state.value = BackendState.Unreachable("no network")
                log("transport failure — not a backend verdict, not reviving")
            }

            ProbeOutcome.REFUSED, ProbeOutcome.TIMEOUT -> handleSilence(t, outcome)
        }
    }

    private fun markLive(t: Long) {
        if (liveSince == null) liveSince = t
        thawStartedAt = null

        // Reset ONLY after sustained health. Resetting on first success would
        // let a backend that dies two seconds after every boot clear the
        // counter forever, so the crash-loop cap could never trip.
        val held = t - (liveSince ?: t)
        if (attempts > 0 && held >= policy.sustainedHealthMs) {
            log("healthy for ${held}ms — clearing $attempts attempt(s)")
            attempts = 0
        }
        _state.value = BackendState.Live
    }

    private suspend fun handleSilence(t: Long, outcome: ProbeOutcome) {
        liveSince = null

        val host = controller.hostLiveness()

        // POSITIVE EVIDENCE SHORT-CIRCUITS PATIENCE.
        //
        // This is what lets the thaw budget be generous. A dead host is known
        // dead in well under a second, so waiting 30s would only delay the fix;
        // a live host that has not answered is thawing, and restarting it is
        // the mistake we are here to stop.
        val evidence: DeathEvidence? = when {
            host == HostLiveness.DEAD -> DeathEvidence.Observed("host reports runtime not running")
            outcome == ProbeOutcome.REFUSED && host != HostLiveness.ALIVE -> DeathEvidence.Refused
            else -> null
        }

        if (evidence == null) {
            // No evidence of death: wait, unless the budget has run out.
            val started = thawStartedAt ?: now().also { thawStartedAt = it }
            val elapsed = t - started
            if (elapsed < policy.thawBudgetMs) {
                _state.value = BackendState.Thawing(elapsed, policy.thawBudgetMs)
                return
            }
            return goDown(t, DeathEvidence.BudgetExpired(policy.thawBudgetMs))
        }

        return goDown(t, evidence)
    }

    private suspend fun goDown(t: Long, evidence: DeathEvidence) {
        thawStartedAt = null
        _state.value = BackendState.Down(evidence)
        log("backend down: $evidence")
        maybeRevive(t)
    }

    private suspend fun maybeRevive(t: Long) {
        if (!controller.canRevive) {
            log("platform cannot revive a local backend — reporting only")
            return
        }
        if (!foregrounded) {
            log("backgrounded — not reviving (the 3-minute stop is deliberate)")
            return
        }
        if (attempts >= policy.maxAttempts) {
            _state.value = BackendState.GaveUp(attempts, lastError)
            return
        }
        if (t < nextReviveAt) return  // still inside backoff

        val attempt = attempts + 1
        val delayForNext = policy.backoffFor(attempt)
        _state.value = BackendState.Reviving(attempt, delayForNext)
        log("reviving, attempt $attempt/${policy.maxAttempts}")

        val result = controller.revive()
        attempts = attempt
        nextReviveAt = t + delayForNext

        result.onFailure { e ->
            lastError = e.message ?: e::class.simpleName ?: "revive failed"
            log("revive attempt $attempt failed: $lastError")
            if (attempts >= policy.maxAttempts) {
                _state.value = BackendState.GaveUp(attempts, lastError)
            }
        }.onSuccess {
            // Deliberately NOT Live: the revive was requested, not confirmed.
            // The next probe decides, which is the same discipline that keeps
            // "we asked" separate from "it answered" everywhere else here.
            openThawWindow("revive requested")
        }
    }

    /** Clear the crash-loop guard after a human intervenes. */
    fun retryNow() {
        attempts = 0
        nextReviveAt = 0
        lastError = ""
        openThawWindow("manual retry")
    }

    // ------------------------------------------------------------------
    // Driver
    // ------------------------------------------------------------------

    /**
     * Thin loop over [tick]. Probing accelerates while thawing — the interesting
     * window is the first few seconds after a resume — and backs off to a
     * steady heartbeat once the answer is settled.
     */
    fun run(scope: CoroutineScope, fastMs: Long = 500, steadyMs: Long = 5_000): Job =
        scope.launch {
            while (isActive) {
                tick()
                delay(if (_state.value is BackendState.Thawing) fastMs else steadyMs)
            }
        }
}
