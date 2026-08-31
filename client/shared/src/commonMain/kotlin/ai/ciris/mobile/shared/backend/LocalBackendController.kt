package ai.ciris.mobile.shared.backend

/**
 * The ONE platform capability the supervisor needs: can this platform tell us
 * whether its backend host is alive, and can it bring the backend back?
 *
 * Capability only — no policy. When to wait, how long to wait, how many times
 * to retry and when to stop all live in [BackendSupervisor], in common code,
 * under test. The whole point of this split is that iOS already had the policy
 * and it was unreachable: written in Swift, invisible to KMP tests, and
 * un-inherited by the three other platforms that needed it.
 *
 * Implementations:
 *
 * - **Android** — `startForegroundService`; host liveness from the service's
 *   own run state.
 * - **iOS** — writes `.restart_signal` for the node's Python watchdog; host
 *   liveness from `runtimeThread.isExecuting` / `.isFinished`, which the Swift
 *   code already computed and threw away.
 * - **Desktop** — kill and respawn the child process; host liveness from
 *   `Process.isAlive`, which desktop has never once consulted.
 * - **wasm** — [canRevive] is false. Browser clients have no local backend.
 */
interface LocalBackendController {

    /**
     * Whether this platform can revive a local backend AT ALL. A false here is
     * a permanent property of the build (wasm has no local node); it is NOT
     * the same question as whether the *currently selected* node is ours, which
     * the supervisor asks separately via [Ownership]. Both must be true before
     * anything is restarted.
     */
    val canRevive: Boolean

    /**
     * Cheap, local, non-networked: is the host still there?
     *
     * This is what makes a 30-second thaw budget safe. Without it the only way
     * to distinguish "thawing" from "dead" is to wait out the clock, which
     * forces the budget short, which is exactly the trade iOS made when it gave
     * a suspended interpreter two seconds.
     *
     * Return [HostLiveness.UNKNOWN] rather than guessing. A platform that
     * cannot answer must not manufacture one — the supervisor degrades to
     * patience, which is the safe direction.
     */
    suspend fun hostLiveness(): HostLiveness

    /**
     * Bring the backend back. MUST be idempotent: the supervisor may call this
     * when the backend is already healthy (a probe can answer between the
     * decision and the call), and a second revive must not kill a working node.
     */
    suspend fun revive(): Result<Unit>
}

/**
 * The honest controller for a platform with no local backend, and for any
 * session pointed at a remote node.
 *
 * Deliberately not an object with a `TODO()` revive: "cannot" is a real answer
 * and the supervisor is built to act on it, rather than a gap to be filled in
 * later by something that restarts a node it does not own.
 */
object NoLocalBackend : LocalBackendController {
    override val canRevive: Boolean = false
    override suspend fun hostLiveness(): HostLiveness = HostLiveness.UNKNOWN
    override suspend fun revive(): Result<Unit> =
        Result.failure(IllegalStateException("no local backend on this platform"))
}

/**
 * Is the node at [url] one we are allowed to restart?
 *
 * The rule already existed, spelled out inline in
 * `ServerConnectionViewModel.updateLocalServerStatus`. It is lifted here rather
 * than copied because a second, subtly different definition of "local" is how a
 * client eventually restarts somebody else's node — and the consequence of
 * getting this wrong is not a cosmetic bug, it is killing a research agent
 * mid-run on a machine we do not own.
 *
 * Loopback only, deliberately: a node reachable on the LAN is someone's box,
 * even when that box is on the same desk.
 */
fun ownershipOf(url: String): Ownership {
    val u = url.lowercase()
    val loopback = u.contains("localhost") || u.contains("127.0.0.1") || u.contains("0.0.0.0")
    return if (loopback) Ownership.OURS else Ownership.REMOTE
}
