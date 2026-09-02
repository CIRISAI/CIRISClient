@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ai.ciris.mobile.shared.backend

import kotlinx.cinterop.*
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.*
import kotlin.coroutines.resume

/**
 * iOS's half of the resume/restart contract.
 *
 * iOS already had all of this — resume detection, health probing, restart,
 * attempt counting — implemented in Swift in `ContentView.checkAndRestartServerIfNeeded`
 * and `PythonBridge.ensureServerRunning`. Being in Swift is exactly why it went
 * wrong and stayed wrong: KMP tests could not reach it, the three other
 * platforms could not inherit it, and the one constant that mattered was
 * invisible to review.
 *
 * That constant: `for i in 1...2`. A Python interpreter suspended for hours got
 * two one-second polls to prove it was alive before iOS wrote `.restart_signal`
 * and paid a full cold boot. Threads have to thaw, pages have to fault back in,
 * and the listening socket has to start accepting again; two seconds does not
 * cover it, so iOS restarted on every single resume.
 *
 * Swift keeps what only Swift can do — booting Python, owning `scenePhase`,
 * seeing the runtime `Thread` — and pushes those facts here through
 * [IosBackendBridge]. Everything about WHEN to act now lives in
 * [BackendSupervisor], in common code, under test.
 */
class IosBackendController : LocalBackendController {

    override val canRevive: Boolean = true

    /**
     * The evidence `PythonBridge.ensureServerRunning` already computed and threw
     * away. It logged:
     *
     *     NSLog("  - isExecuting: \(thread.isExecuting)")
     *     NSLog("  - isFinished:  \(thread.isFinished)")
     *
     * and then proceeded identically whichever way they read. An executing
     * thread means the runtime is THAWING and must be waited for; a finished
     * thread means it is dead and waiting is pure delay. Taking that branch is
     * what lets the thaw budget be generous instead of two seconds.
     */
    override suspend fun hostLiveness(): HostLiveness = IosBackendBridge.hostLiveness()

    /**
     * Ask the node's Python watchdog to restart the runtime, the same mechanism
     * [ai.ciris.mobile.shared.platform.AppRestarter] uses. iOS cannot spawn a
     * process, so signalling the in-process runtime is the only revive
     * available — which is also why patience matters more here than anywhere
     * else: the fallback is expensive.
     */
    override suspend fun revive(): Result<Unit> = runCatching {
        val cirisDir = "${NSHomeDirectory()}/Documents/ciris"
        NSFileManager.defaultManager.createDirectoryAtPath(
            cirisDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        @Suppress("CAST_NEVER_SUCCEEDS")
        val content = "restart" as NSString
        val ok = content.writeToFile(
            "$cirisDir/.restart_signal",
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        if (!ok) error("could not write .restart_signal")
        NSLog("[IosBackendController] .restart_signal written")
    }
}

/**
 * The Swift → Kotlin seam.
 *
 * State is PUSHED from Swift rather than pulled through a closure: a Kotlin
 * lambda held across the ObjC interop boundary is awkward to hand back from
 * Swift, while a plain setter is unambiguous from both sides and survives the
 * framework regeneration.
 *
 * Swift calls [setRuntimeThreadState] whenever it learns something about the
 * runtime thread — at minimum on every resume, which is when it matters — and
 * [reportResumed] / [reportBackgrounded] on `scenePhase` transitions.
 */
object IosBackendBridge {

    private var executing = false
    private var finished = false
    private var everReported = false

    /**
     * The node this app is currently talking to. Defaults to the embedded one;
     * CIRISApp calls [setNodeUrl] when the user connects elsewhere, at which
     * point [ownershipOf] stops calling it ours and the supervisor will observe
     * without ever restarting it.
     */
    private var nodeUrl: String = "http://127.0.0.1:8080"

    fun setNodeUrl(url: String) { nodeUrl = url }

    /**
     * Owned here rather than injected, so Swift has exactly one symbol to
     * touch and no startup ordering to get right. Policy still lives in
     * [BackendSupervisor] — this object only supplies the platform facts.
     */
    val supervisor: BackendSupervisor by lazy {
        BackendSupervisor(
            probe = { iosProbe(nodeUrl) },
            controller = IosBackendController(),
            ownership = { ownershipOf(nodeUrl) },
            now = {
                (NSDate().timeIntervalSince1970 * 1000.0).toLong()
            },
            log = { NSLog("[backend] $it") },
        )
    }

    /**
     * For the Swift reconnect overlay. Deliberately a plain getter rather than
     * a StateFlow: exposing a flow across the ObjC boundary buys nothing here,
     * and the overlay is polled by SwiftUI anyway.
     */
    val isRecovering: Boolean
        get() = supervisor.state.value.let {
            it is BackendState.Thawing || it is BackendState.Reviving
        }

    val hasGivenUp: Boolean
        get() = supervisor.state.value is BackendState.GaveUp

    /** Attempts so far, for the overlay's "tried N times". */
    val attemptCount: Int
        get() = supervisor.state.value.let {
            when (it) {
                is BackendState.Reviving -> it.attempt
                is BackendState.GaveUp -> it.attempts
                else -> 0
            }
        }

    /** Clear the crash-loop guard when the user taps retry. */
    fun retryNow() { supervisor.retryNow() }

    /**
     * Swift reports `runtimeThread.isExecuting` / `.isFinished`.
     *
     * Both false with no thread at all is [HostLiveness.UNKNOWN], not DEAD:
     * "we have not looked yet" must not read as "it died", which is the same
     * distinction the rest of this package exists to keep.
     */
    fun setRuntimeThreadState(isExecuting: Boolean, isFinished: Boolean) {
        executing = isExecuting
        finished = isFinished
        everReported = true
    }

    fun hostLiveness(): HostLiveness = when {
        !everReported -> HostLiveness.UNKNOWN
        finished -> HostLiveness.DEAD
        executing -> HostLiveness.ALIVE
        else -> HostLiveness.UNKNOWN
    }

    private var job: kotlinx.coroutines.Job? = null

    /**
     * Start the probe loop. Idempotent, and called from [reportResumed] so
     * Swift cannot forget it — a supervisor that is never ticked reports
     * Thawing forever, which would look like a hang rather than an error.
     */
    fun start() {
        if (job != null) return
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
        )
        job = supervisor.run(scope)
        BackendStatus.install(supervisor, scope)
        NSLog("[IosBackendBridge] supervisor started")
    }

    /** `scenePhase == .active` */
    fun reportResumed() {
        NSLog("[IosBackendBridge] resumed")
        start()
        supervisor.onResumed()
    }

    /** `scenePhase == .background` */
    fun reportBackgrounded() {
        NSLog("[IosBackendBridge] backgrounded")
        supervisor.onBackgrounded()
    }
}

/**
 * A health probe that reports WHY it failed.
 *
 * `PythonRuntime.ios.checkHealth` resolves every `error != null` to
 * `Result.success(false)` — backend refused, request timed out and phone in
 * airplane mode all arrive as the same `false`. That flattening is what left
 * the supervisor nothing to reason about, so it is not reused here.
 *
 * NSURLError codes are given as literals rather than the imported symbols
 * because the numeric values are stable ABI and read unambiguously next to the
 * outcome they map to.
 */
suspend fun iosProbe(serverUrl: String, timeoutSeconds: Double = 2.0): ProbeOutcome =
    suspendCancellableCoroutine { cont ->
        val nsUrl = NSURL.URLWithString("$serverUrl/v1/system/health")
        if (nsUrl == null) {
            cont.resume(ProbeOutcome.TRANSPORT)
            return@suspendCancellableCoroutine
        }
        val request = NSMutableURLRequest.requestWithURL(nsUrl)
        request.setHTTPMethod("GET")
        request.setTimeoutInterval(timeoutSeconds)

        val task = NSURLSession.sharedSession
            .dataTaskWithRequest(request) { _, response, error ->
                val outcome = when {
                    error != null -> when (error.code.toInt()) {
                        -1001 -> ProbeOutcome.TIMEOUT      // timed out — may be thawing
                        -1004 -> ProbeOutcome.REFUSED      // cannot connect — nothing listening
                        -1005 -> ProbeOutcome.TIMEOUT      // connection lost — may be thawing
                        -1003 -> ProbeOutcome.TRANSPORT    // cannot find host
                        -1009 -> ProbeOutcome.TRANSPORT    // not connected to internet
                        // Unclassified resolves toward PATIENCE: a slow recovery
                        // costs less than a cold boot the user watches.
                        else -> ProbeOutcome.TIMEOUT
                    }
                    else -> {
                        val code = (response as? NSHTTPURLResponse)
                            ?.statusCode?.toInt() ?: -1
                        // Any HTTP response proves something is listening, so a
                        // non-2xx is a node that is up and not ready — a slow
                        // boot, not a death. See the JVM probes for why calling
                        // this REFUSED would boot-loop a healthy start.
                        if (code in 200..299) ProbeOutcome.ANSWERED else ProbeOutcome.TIMEOUT
                    }
                }
                cont.resume(outcome)
            }
        task.resume()
    }
