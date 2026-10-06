package ai.ciris.mobile.shared.backend

import ai.ciris.mobile.shared.platform.BackendEndpoint
import ai.ciris.mobile.shared.platform.NODE_ONLY_ENDPOINT

/**
 * WHAT THE iOS HOST DRAWS OVER THE APP, AND WHETHER IT MAY RESTART ANYTHING.
 *
 * The Swift host (`ContentView.swift`) draws two blocking overlays from the
 * shared [BackendSupervisor]: "Resuming… Please wait…" while the backend is
 * thawing or being revived, and "restart required" once the crash-loop guard
 * has tripped. Both are about the embedded AGENT on `:8080`.
 *
 * CIRISClient#138: after **Run without AI** the agent on `:8080` stops ON
 * PURPOSE — the node on `:4243` is the whole server from then on. The iOS
 * bridge kept probing a hard-coded `:8080`, read the deliberate stop as a death,
 * wrote `.restart_signal` and held "Resuming…" over the app for the rest of the
 * session (run 37427594052: Global Commons covered, csd_047 failed).
 *
 * WHY THE INPUT IS THE ENDPOINT AND NOT [ai.ciris.mobile.shared.models.ClientMode].
 * The two answer different questions. `ClientMode` answers "are the agent
 * surfaces live" and is the one source for THAT. It cannot answer "is a `:8080`
 * process supposed to be running": it is NODE on every with-AI first run (an
 * unconfigured brain is NODE, CIRISAgent#1075), so keying recovery on it would
 * leave a with-AI install whose agent died during setup with no recovery; and
 * in the failing run it was still unprobed (`clientMode=unprobed`) when the
 * overlay went up, so it could not have suppressed it. Which backend this
 * install runs is the person's recorded choice, `CIRIS_RUN_WITHOUT_AI`, held in
 * [ai.ciris.mobile.shared.platform.ActiveBackend] — the same value
 * `PythonRuntime.ios.serverUrl` and the startup wait already read.
 *
 * Pure, so the rule is under test on Linux; the Swift host cannot be compiled
 * there and the bridge that calls this is iosMain.
 */
enum class HostOverlay {
    /** Nothing over the app. */
    NONE,

    /** "Resuming… Please wait…" — the embedded agent is thawing or being revived. */
    RESUMING,

    /** "Restart required" — the crash-loop guard tripped. */
    RESTART_REQUIRED,
}

/**
 * Is an embedded agent on `:8080` part of this install at all?
 *
 * False after Run without AI: the agent has stopped by design and the node is
 * the server, so there is nothing to resume.
 */
fun embeddedAgentExpected(endpoint: BackendEndpoint): Boolean = endpoint != NODE_ONLY_ENDPOINT

/**
 * May the iOS supervisor revive (write `.restart_signal`) for this install?
 *
 * Only when an embedded agent is expected. A run-without-AI install has no
 * agent to bring back, and on this platform the in-session node runs inside the
 * parked runtime (CIRISAgent#1152), so restarting it is not recovery. A dead
 * node is still REPORTED — [BackendStatus] feeds the in-app banner — it is just
 * not restarted behind the person's back.
 */
fun mayReviveEmbeddedBackend(endpoint: BackendEndpoint): Boolean = embeddedAgentExpected(endpoint)

/**
 * Which blocking overlay the iOS host shows for [state] on an install whose
 * backend is [endpoint].
 *
 * Run without AI → [HostOverlay.NONE] whatever the state: the backend being down
 * is the intended state there, and a blocking overlay over a working node is
 * the bug. With AI → the overlay the supervisor's state calls for, unchanged.
 */
fun hostOverlayFor(endpoint: BackendEndpoint, state: BackendState): HostOverlay = when {
    !embeddedAgentExpected(endpoint) -> HostOverlay.NONE
    state is BackendState.Thawing || state is BackendState.Reviving -> HostOverlay.RESUMING
    state is BackendState.GaveUp -> HostOverlay.RESTART_REQUIRED
    else -> HostOverlay.NONE
}
