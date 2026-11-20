package ai.ciris.mobile.shared.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Is [url]'s host this machine? Only a loopback address is asked to stop: the
 * Reset button says "this device", and a node on another machine is never
 * this app's to stop. The route itself also refuses non-local callers; this
 * keeps the request from being sent at all.
 */
fun isLoopbackUrl(url: String): Boolean {
    val afterScheme = url.substringAfter("://", url)
    // The authority ends at the first '/', '?' or '#', and its host follows any
    // user-info: `http://localhost:x@remote.example` is remote (Codex,
    // CIRISClient#316).
    val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
    } else {
        authority.substringBefore(':')
    }
    return host == "127.0.0.1" || host == "::1" || isLoopbackName(host)
}

/** What [stopAttachedBackend] found, for the log and the caller's message. */
sealed interface AttachedStop {
    /** No loopback URL to ask: nothing was sent. */
    data object NotLocal : AttachedStop

    /** Every asked URL stopped answering within the bound. */
    data class Stopped(val urls: List<String>) : AttachedStop

    /** At least one URL still answered when the bound ran out. */
    data class StillRunning(val urls: List<String>) : AttachedStop

    /**
     * A backend that will not stop on request: no stop route (404/405/501, a
     * CIRISServer node before 0.5.250) or a refusal (403: not loopback, behind
     * a proxy, or a server-class node). It is not waited on; [stopped] lists
     * any that did stop, and [running] any other URL that could be asked but
     * still answered when the bound ran out (Codex, CIRISClient#316: a node
     * without the route beside an agent that would not stop lost the agent).
     */
    data class CannotAsk(
        val urls: List<String>,
        val stopped: List<String>,
        val running: List<String> = emptyList(),
    ) : AttachedStop
}

/**
 * STOP AN ATTACHED LOCAL BACKEND BEFORE ITS FILES ARE DELETED.
 *
 * On desktop the agent's launcher starts the backend and then this app attaches
 * to it, so `PythonRuntime.shutdown()` correctly declines to kill a process it
 * did not start (CIRISClient#55). Reset then deleted the home under a live
 * process. On Unix that succeeds and the node keeps serving its old state; on
 * Windows an open SQLite or WAL handle makes `data\` undeletable, and the reset
 * fails whenever a handle happens to be open at that moment (CIRISAgent
 * five-platform runs 44006352958 and 44127415181 on ciris-server 0.5.249;
 * 44011436515, same versions, passed).
 *
 * The backend's own localhost-only `POST /v1/system/local-shutdown` is the
 * stop: it lets the process close its handles itself. This asks each distinct
 * loopback URL once, re-asks on a 409/503 ("retry later"), and waits, bounded,
 * until none answers.
 *
 * [answering] must read a TIMEOUT as answering: only a refused connection
 * means gone. A live backend busy enough to miss the probe still holds its
 * files (Codex, CIRISClient#316); the bound then reports it as running.
 *
 * A URL without the route (404/405/501) is NOT waited on. The first version
 * waited the full bound for a bare node that could never be asked, so Reset
 * sat 30 s and the agent's gate (20 s) gave up first (CIRISAgent run
 * 44346546111, the node-only pass: `local-shutdown at :4243 answered 404`).
 */
suspend fun stopAttachedBackend(
    urls: List<String>,
    requestStop: suspend (String) -> Int?,
    answering: suspend (String) -> Boolean,
    timeoutMs: Long = 30_000,
    pollMs: Long = 500,
): AttachedStop {
    val local = urls.map { it.trimEnd('/') }.distinct().filter(::isLoopbackUrl)
    if (local.isEmpty()) return AttachedStop.NotLocal

    val pending = local.filter { answering(it) }.toMutableList()
    if (pending.isEmpty()) return AttachedStop.Stopped(local)

    val asked = mutableSetOf<String>()
    val noRoute = mutableListOf<String>()
    withTimeoutOrNull(timeoutMs) {
        while (pending.isNotEmpty()) {
            for (url in pending.toList()) {
                if (url !in asked) {
                    val status = requestStop(url)
                    if (status in NO_STOP_ROUTE) {
                        noRoute += url
                        pending.remove(url)
                        continue
                    }
                    // 409 (resume in progress), 429 (rate limited) and 503 (not
                    // ready) all say "retry later"; anything else is an answer,
                    // asked once.
                    if (status !in RETRY_LATER) asked += url
                }
                if (!answering(url)) pending.remove(url)
            }
            if (pending.isNotEmpty()) delay(pollMs)
        }
    }
    return when {
        noRoute.isNotEmpty() -> AttachedStop.CannotAsk(
            urls = noRoute.toList(),
            stopped = local - noRoute.toSet() - pending.toSet(),
            running = pending.toList(),
        )
        pending.isEmpty() -> AttachedStop.Stopped(local)
        else -> AttachedStop.StillRunning(pending.toList())
    }
}

/** No such route, or a refusal: the backend will not stop on request, so nothing waits on it. */
private val NO_STOP_ROUTE = setOf(403, 404, 405, 501)

/** "Ask again shortly": resume in progress, rate limited, not ready. */
private val RETRY_LATER = setOf(409, 429, 503)

/**
 * Does a failed probe still mean "something is there"? A timeout does: on
 * loopback a stopped process refuses the connection at once, so a probe that
 * runs out of time reached a live process too busy to answer. Only a refused
 * or otherwise failed connection reads as gone.
 */
fun probeFailureMeansAlive(e: Throwable): Boolean =
    e is io.ktor.client.plugins.HttpRequestTimeoutException ||
        e is io.ktor.client.network.sockets.ConnectTimeoutException ||
        e is io.ktor.client.network.sockets.SocketTimeoutException
