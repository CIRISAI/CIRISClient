package ai.ciris.mobile.shared.backend

/**
 * THE THIRD STATE.
 *
 * The client used to have exactly two answers about its backend: reachable, or
 * `DISCONNECTED`. Everything else — a node still thawing after the OS suspended
 * it, a phone in airplane mode, a DNS failure, a request cancelled mid-resume —
 * collapsed into the second one, along with the message "Cannot connect to
 * server. Please check your connection." on a device whose backend runs inside
 * the app and had been stopped by the app itself.
 *
 * That collapse is what made resume-from-sleep unrecoverable, and it made it
 * unrecoverable in two opposite ways at once:
 *
 *   iOS was IMPATIENT. `PythonBridge.ensureServerRunning` gave a suspended
 *   Python interpreter two one-second polls to prove it was alive before
 *   writing `.restart_signal`, so a runtime that would have woken on its own a
 *   second later was cold-booted on EVERY resume. The same function logged
 *   `isExecuting` / `isFinished` — the evidence that would have told it to wait
 *   — and branched on neither.
 *
 *   Android was ABSENT. It never asked at all, which is why a thawed process
 *   kept working (no impatient checker could wrongly declare it dead), and why
 *   a service genuinely stopped by the 3-minute background timeout stayed dead
 *   until the user relaunched. `START_STICKY` reads like protection against
 *   this and cannot fire: Android only restarts a sticky service IT killed,
 *   never one that called `stopSelf()`.
 *
 * So the states below exist to make one distinction the old enum could not:
 * **a backend that has not answered YET is not a backend that has died.**
 * [Thawing] is the common, healthy path on mobile resume and is deliberately
 * not an error. [Unreachable] is "we could not ask", which no amount of
 * restarting a backend will fix.
 */
sealed interface BackendState {

    /** The probe answered. */
    data object Live : BackendState

    /**
     * No answer yet, and there is reason to expect one — a resume just
     * happened, or the host reports its runtime is still executing. Presumed
     * alive until [budgetMs] elapses. NOT an error state, and never rendered
     * as one.
     */
    data class Thawing(val elapsedMs: Long, val budgetMs: Long) : BackendState

    /**
     * Positive evidence of death, or the thaw budget expired without one.
     * [evidence] records WHICH, because "we saw it die" and "we ran out of
     * patience" are different claims and only the first is certain.
     */
    data class Down(val evidence: DeathEvidence) : BackendState

    /** Ours, dead, and being brought back. */
    data class Reviving(val attempt: Int, val nextDelayMs: Long) : BackendState

    /**
     * The crash-loop guard tripped. A human has to look. Carries the attempt
     * count and last error so the UI can say something specific instead of
     * spinning forever.
     */
    data class GaveUp(val attempts: Int, val lastError: String) : BackendState

    /**
     * We could not ASK — no network, DNS failure, TLS failure. This says
     * nothing about the backend, so it must never trigger a revive: restarting
     * a node does not bring back the Wi-Fi. This is the ONLY state for which
     * "check your connection" is a true sentence.
     */
    data class Unreachable(val cause: String) : BackendState

    /**
     * The active node is not ours — a remote node, someone else's process. We
     * reconnect and report honestly; we never restart it. Whether that node
     * carries a brain is a different axis entirely ([ai.ciris.mobile.shared.models.ModeProbe.brainPresent]),
     * and conflating the two is what shipped as CIRISClient#21.
     */
    data object NotOurs : BackendState
}

/**
 * How we concluded the backend is down. The distinction matters because only
 * [Observed] justifies skipping the thaw window — [BudgetExpired] means we
 * merely stopped waiting, which is a weaker claim and worth logging as one.
 */
sealed interface DeathEvidence {
    /** The host told us: thread finished, process exited, service not running. */
    data class Observed(val detail: String) : DeathEvidence

    /** Connection refused — something answered the socket layer with "nothing is listening". */
    data object Refused : DeathEvidence

    /** No positive evidence; the thaw budget simply ran out. */
    data class BudgetExpired(val budgetMs: Long) : DeathEvidence
}

/**
 * What a single health probe learned. [TIMEOUT] and [REFUSED] are split
 * deliberately: a refused connection is positive evidence that nothing is
 * listening, while a timeout is the signature of a process still thawing.
 * Treating them alike is what made a long thaw window unsafe.
 */
enum class ProbeOutcome {
    /** Healthy response. */
    ANSWERED,

    /** Connection refused — nothing bound to the port. */
    REFUSED,

    /** No answer within the probe timeout. Could be thawing. */
    TIMEOUT,

    /** We could not ask: no network, DNS, TLS. Says nothing about the backend. */
    TRANSPORT,
}

/** What the host process/service can tell us about itself, cheaply and locally. */
enum class HostLiveness {
    /** Runtime thread executing / process alive / service running. */
    ALIVE,

    /** Thread finished, process exited, service not running. */
    DEAD,

    /** The platform cannot answer this cheaply. */
    UNKNOWN,
}

/** Whether the active node is one we are allowed to restart. */
enum class Ownership { OURS, REMOTE }

/**
 * Timings for the resume/restart contract.
 *
 * Two waits, deliberately, because they answer different questions: is this
 * backend still WAKING ([thawBudgetMs]), versus is the one I just started still
 * BOOTING ([bootBudgetMs]). Kubernetes separates liveness from startup probes
 * for exactly this reason, and collapsing them is what produces a restart loop
 * against an app that was starting normally.
 *
 * Both are only safe because genuine death is caught by evidence
 * ([HostLiveness.DEAD], [ProbeOutcome.REFUSED]) in well under a second and never
 * waits out either clock. iOS shipped a 2s wait with no evidence check at all,
 * which is why it restarted on every resume.
 */
data class RevivePolicy(
    /**
     * How long to wait for a RESUMING backend before concluding it is dead.
     *
     * Sized to a resume, which is fast — the process is already built, its
     * pages are mostly resident, and it has only to thaw and re-accept. Field
     * numbers for comparison: a COLD BOOT is ~14s on a modern phone and up to
     * ~45s on arm32, and a resume is much quicker than either.
     *
     * This was 30s, which was wrong in both directions at once: far slower than
     * a resume ever needs, and — because the same value was also used after a
     * revive — shorter than an arm32 cold boot, so the supervisor would have
     * restarted a node it had just started, mid-boot. That is the CrashLoopBackOff
     * that Kubernetes' startup probe exists to prevent, and the fix is the same
     * one: separate the startup wait from the liveness wait ([bootBudgetMs]).
     */
    val thawBudgetMs: Long = 5_000,

    /**
     * How long to wait after a revive before concluding it failed.
     *
     * A revived node COLD BOOTS — 22 services, ~14s on a phone and up to ~45s
     * on arm32 — so this is the startup probe, not the liveness probe, and it
     * has to cover the slowest device we support with headroom. The usual
     * guidance is to set the startup window well above worst acceptable
     * startup rather than near it; 90s is 2x the arm32 figure.
     *
     * Being generous here costs nothing: the node is already being started, and
     * the only thing a shorter window buys is a second restart on top of a boot
     * that was going to succeed.
     *
     * BOTH BUDGETS ARE ARGUED FROM BOOT FIGURES, NOT MEASURED. The resume
     * distribution — which is what [thawBudgetMs] actually depends on — is
     * being measured on arm32 in CIRISAgent#1125. Adjust them from that data,
     * not from a hunch, and keep the two separate whatever the numbers say.
     */
    val bootBudgetMs: Long = 90_000,
    val maxAttempts: Int = 5,
    val backoffMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000),
    val backoffCapMs: Long = 30_000,

    /**
     * How long the backend must stay [BackendState.Live] before the attempt
     * counter resets.
     *
     * NOT "reset on first success". A backend that dies two seconds after every
     * boot would clear the counter on each brief success and loop until the
     * battery is flat — the crash-loop guard would be present, armed, and
     * unable to ever trip. Sustained health is the only reset that makes the
     * cap mean anything.
     */
    val sustainedHealthMs: Long = 60_000,
) {
    /** Delay before attempt [n] (1-based), capped. */
    fun backoffFor(n: Int): Long {
        val idx = (n - 1).coerceAtLeast(0)
        val raw = backoffMs.getOrElse(idx) { backoffMs.lastOrNull() ?: backoffCapMs }
        return minOf(raw, backoffCapMs)
    }
}
