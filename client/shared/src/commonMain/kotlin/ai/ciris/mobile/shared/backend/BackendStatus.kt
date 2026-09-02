package ai.ciris.mobile.shared.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where the UI reads backend liveness from.
 *
 * The supervisor is constructed by each platform's entry point — MainActivity
 * on Android, Main.kt on desktop, IosBackendBridge on iOS — because only the
 * entry point knows which node it drives and how to revive it. The screens live
 * in commonMain and cannot reach any of those.
 *
 * THIS EXISTS BECAUSE BUILDING THE SUPERVISOR WAS NOT ENOUGH.
 *
 * BackendSupervisor shipped in 0.5.196, wired to lifecycle on three platforms
 * and covered by 18 tests, and NOTHING IN THE UI READ ITS STATE. It ran, it
 * decided correctly, and every screen carried on showing what it had always
 * shown — so a user whose backend was dead still read "Cannot connect to
 * server. Please check your connection." The machinery was right and
 * disconnected, which from the user's side is the same as absent.
 *
 * One flow, installed once, read by any screen. If nothing installs a
 * supervisor the state is null and the UI shows nothing at all — an
 * un-instrumented build must not invent a status.
 */
object BackendStatus {

    private val _state = MutableStateFlow<BackendState?>(null)

    /**
     * Current backend state, or null when no supervisor is installed.
     *
     * Null is "nobody is watching", which is NOT the same as healthy and not
     * the same as broken. Callers must render nothing for it rather than
     * choosing one — collapsing an absence into a definite answer is the
     * mistake this whole package exists to stop making.
     */
    val state: StateFlow<BackendState?> = _state.asStateFlow()

    private var installed: BackendSupervisor? = null

    /** Mirror [supervisor]'s state so screens can observe it. Idempotent. */
    fun install(supervisor: BackendSupervisor, scope: CoroutineScope) {
        if (installed === supervisor) return
        installed = supervisor
        // Publish what the supervisor already thinks, SYNCHRONOUSLY, before the
        // collector is even scheduled. A screen composed in the same frame as
        // install() would otherwise render nothing until the next state change
        // — which on a healthy backend can be five seconds of a blank status
        // where a "waking" line belongs.
        _state.value = supervisor.state.value
        scope.launch {
            supervisor.state.collect { _state.value = it }
        }
    }

    /**
     * The user asked to try again. Clears the crash-loop guard and reopens a
     * wait; the supervisor takes it from there.
     *
     * Safe when nothing is installed — a Retry button that cannot act must not
     * crash, though [noticeFor] only offers one on states a supervisor produced.
     */
    fun retry() {
        installed?.retryNow()
    }

    /** For tests: forget the installed supervisor. */
    fun reset() {
        installed = null
        _state.value = null
    }
}
