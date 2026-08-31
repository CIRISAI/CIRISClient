package ai.ciris.mobile.shared.backend

import ai.ciris.mobile.shared.platform.PythonRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Desktop's half of the resume/restart contract.
 *
 * Desktop is the easiest platform to get right and had the least: it spawns a
 * real child process and so can answer "is it alive?" exactly, via
 * `Process.isAlive` — and never once asked. Nothing monitored the node. If it
 * died, the first anyone knew was a failing request, and the only recovery was
 * a button on the connection screen that a user had to find.
 *
 * Laptop sleep is the same failure the phones have, under a different name. A
 * child process usually survives a suspend, but its sockets may not, and the
 * distinction between "still coming back" and "gone" is exactly the one this
 * package exists to keep.
 */
class DesktopBackendController(
    private val runtime: PythonRuntime,
) : LocalBackendController {

    override val canRevive: Boolean = true

    /**
     * `isServerStarted` is a flag we set; the process handle is the ground
     * truth. Prefer the truth — a stale flag claiming a live server is how the
     * connection screen came to show a STOP button next to "Disconnected".
     *
     * UNKNOWN rather than DEAD when there is no handle at all: on desktop the
     * node may have been started outside this process (SmartStartup attaches to
     * an already-listening server), and "we did not spawn it" is not "it is
     * dead". The supervisor degrades to patience, which is the safe direction,
     * and a refused connection still supplies the evidence to act.
     */
    override suspend fun hostLiveness(): HostLiveness =
        when (runtime.isServerStarted()) {
            true -> HostLiveness.ALIVE
            false -> HostLiveness.UNKNOWN
        }

    /**
     * Kill and respawn. Idempotent in the sense the supervisor needs: shutdown
     * on an already-dead process is a no-op, and `startServer` attaches to a
     * listening node rather than starting a second one.
     */
    override suspend fun revive(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            runtime.shutdown()
            runtime.startServer().getOrThrow()
            Unit
        }
    }

    companion object {
        /**
         * Health probe that reports WHY it failed — the same classification
         * Android uses, on the same JVM exceptions, deliberately kept identical
         * so the two platforms cannot drift into disagreeing about what a
         * timeout means.
         */
        suspend fun probe(url: String, timeoutMs: Int = 2_000): ProbeOutcome =
            withContext(Dispatchers.IO) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL("$url/v1/system/health").openConnection() as HttpURLConnection).apply {
                        connectTimeout = timeoutMs
                        readTimeout = timeoutMs
                        requestMethod = "GET"
                    }
                    // ANY HTTP RESPONSE PROVES SOMETHING IS LISTENING.
                    //
                    // A non-2xx is therefore NOT death evidence — it is a node
                    // that is up and not ready, which is what a slow boot looks
                    // like (503 while services come up). Classifying it REFUSED
                    // would make the supervisor restart a node that was starting
                    // normally, and on a platform that cannot report host
                    // liveness that is a boot loop. TIMEOUT is the honest
                    // reading: keep waiting, and act only if it never settles.
                    if (conn.responseCode in 200..299) ProbeOutcome.ANSWERED else ProbeOutcome.TIMEOUT
                } catch (e: ConnectException) {
                    ProbeOutcome.REFUSED
                } catch (e: SocketTimeoutException) {
                    ProbeOutcome.TIMEOUT
                } catch (e: UnknownHostException) {
                    ProbeOutcome.TRANSPORT
                } catch (e: Exception) {
                    ProbeOutcome.TIMEOUT
                } finally {
                    conn?.disconnect()
                }
            }
    }
}
