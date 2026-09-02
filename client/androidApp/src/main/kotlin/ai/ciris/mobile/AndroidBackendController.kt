package ai.ciris.mobile

import ai.ciris.mobile.shared.backend.HostLiveness
import ai.ciris.mobile.shared.backend.LocalBackendController
import ai.ciris.mobile.shared.backend.ProbeOutcome
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Android's half of the resume/restart contract.
 *
 * Capability only — no policy. When to wait and when to give up lives in
 * [ai.ciris.mobile.shared.backend.BackendSupervisor], under test in
 * `commonMain`, shared with every other platform.
 *
 * The failure this exists to end: a production build backgrounds, the service's
 * 3-minute timer fires `stopSelf()` to save battery (correct), and then nothing
 * ever brings it back. `onDestroy` removes the lifecycle observer, so the one
 * object watching foreground transitions stops watching; `MainActivity.onResume`
 * handles billing and returns; and the sole `startForegroundService` call sits
 * inside `setContent`, so it re-runs only when the Activity is destroyed and
 * recreated. That last detail is why the bug looked random — a low-memory kill
 * accidentally cured it.
 *
 * `START_STICKY` on the service reads like protection against exactly this and
 * cannot provide it: Android restarts a sticky service it killed, never one
 * that called `stopSelf()`.
 */
class AndroidBackendController(context: Context) : LocalBackendController {

    private val appContext = context.applicationContext

    override val canRevive: Boolean = true

    /**
     * The service's own run flag IS the evidence, and it is exact: `isRunning`
     * is set in `onStartCommand` and cleared in `onDestroy`, so a false here
     * means the runtime host is genuinely gone rather than merely quiet. That
     * lets the supervisor skip its thaw window instead of waiting out a clock
     * for something already known dead.
     */
    override suspend fun hostLiveness(): HostLiveness =
        if (PythonRuntimeService.isRunning) HostLiveness.ALIVE else HostLiveness.DEAD

    /**
     * Idempotent by construction: `onStartCommand` guards on `serverStarted`,
     * and `startServer()` attaches to an already-listening node rather than
     * starting a second one. Calling this on a healthy backend is a no-op, which
     * the supervisor relies on — a probe can answer between its decision and
     * this call.
     */
    override suspend fun revive(): Result<Unit> = withContext(Dispatchers.IO) {
        // A PREVIOUS FAILURE IS THE MOST USEFUL THING WE KNOW.
        //
        // If the runtime already tried and failed — a missing native module, a
        // bad interpreter state — starting the service again will fail the same
        // way. Surfacing that reason lets the supervisor's GaveUp carry
        // something a person can act on or forward, instead of the app
        // repeating a restart it has no reason to expect will work.
        val previousFailure = PythonRuntimeService.lastStartupError
        val started = runCatching {
            Log.i(TAG, "revive() — starting PythonRuntimeService")
            val intent = Intent(appContext, PythonRuntimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            Unit
        }

        // ALWAYS ATTEMPT THE START, THEN CARRY THE REASON.
        //
        // Short-circuiting on a previous failure would make one transient error
        // permanent and turn the user's Retry into a button that does nothing —
        // which is the shape of the bug being fixed, not a fix for it. So the
        // start is always requested; a stale reason only decides what we REPORT.
        //
        // Reporting failure while a previous reason stands is what puts the real
        // string into GaveUp. A start that works clears it, the next probe
        // answers, and the supervisor goes Live before the cap is reached.
        when {
            started.isFailure -> started
            previousFailure != null -> Result.failure(IllegalStateException(previousFailure))
            else -> started
        }
    }

    companion object {
        private const val TAG = "AndroidBackendCtl"

        /**
         * Health probe that reports WHY it failed.
         *
         * The distinction is the whole reason a generous thaw budget is safe.
         * `ConnectException` means nothing is listening — positive evidence, act
         * now. A timeout means the process may be thawing — wait. And
         * `UnknownHostException` means we could not ask at all, which says
         * nothing about the backend and must never trigger a restart.
         *
         * The old code collapsed all three into one `catch`, which is how a
         * device with its own embedded node came to display "Cannot connect to
         * server. Please check your connection."
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
                    // Unknown failures resolve toward PATIENCE, not toward a
                    // restart: the cost of waiting is a slower recovery, the
                    // cost of a wrong restart is a cold boot the user watches.
                    Log.w(TAG, "probe: unclassified ${e::class.simpleName}: ${e.message}")
                    ProbeOutcome.TIMEOUT
                } finally {
                    conn?.disconnect()
                }
            }
    }
}
