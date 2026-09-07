package ai.ciris.mobile.shared.platform

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * WAIT FOR THE PORTS, NOT FOR THE PID (CIRISClient#40).
 *
 * After a factory reset the next setup could not boot: the backend the app
 * had just stopped still held `:4242` (Edge transport) and `:4243` (node API),
 * and the new one died on Edge init. Two facts from the agent side decide the
 * shape of this file:
 *
 *   1. SIGTERM is accepted, logged, and never acted on (CIRISAgent#1152) — the
 *      graceful release in `runtime.shutdown()` is never entered, so no wait on
 *      a polite request completes. `SIGKILL` is the only thing that works.
 *   2. `SIGKILL` releases the ports QUICKLY — about 2s measured — but not
 *      immediately, and `Process.waitFor()` returns as soon as the pid is
 *      gone. Starting the next backend in that gap is exactly the failure.
 *
 * So the invariant a restart needs is "these ports can be bound", checked by
 * binding them. A pid being dead is not evidence of that. And there is NO
 * second SIGTERM anywhere in this path: the agent's handler turns a second
 * signal into `KeyboardInterrupt`, which bypasses the graceful release on a
 * build where #1152 is fixed.
 */
object PortRelease {

    /** The Edge transport port. Not in [BackendEndpoint] because the client never talks to it. */
    const val EDGE_PORT = 4242

    /** Can this process bind [port] right now? A bind is the only honest probe. */
    fun isFree(port: Int): Boolean =
        try {
            ServerSocket().use {
                it.reuseAddress = false
                it.bind(InetSocketAddress(port))
            }
            true
        } catch (_: IOException) {
            false
        }

    /**
     * Block until every port in [ports] is free, or [timeoutMs] passes.
     *
     * @return the ports STILL HELD when this returns — empty means success.
     *         The caller decides what a non-empty answer means; this does not
     *         throw, because on a shutdown path there is nobody to catch it.
     */
    fun awaitFree(
        ports: List<Int>,
        timeoutMs: Long,
        pollMs: Long = 200,
        isFree: (Int) -> Boolean = ::isFree,
        now: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = Thread::sleep,
    ): List<Int> {
        val deadline = now() + timeoutMs
        while (true) {
            val held = ports.filter { !isFree(it) }
            if (held.isEmpty()) return emptyList()
            if (now() >= deadline) return held
            sleep(pollMs)
        }
    }
}
