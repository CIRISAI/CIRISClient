package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.ui.components.StartupBudget

/**
 * Where the startup path stands on THIS device's node (CIRISClient#149).
 *
 * The client reads the agent (`:8080`) and then the node (`:4243`) on every
 * start, first runs included, and the node binds a few seconds into its boot.
 * A refused node read in that window is the node not being up YET. It is not
 * an answer, and it used to be treated as one: the app stopped its timer, never
 * asked again, and sat on the splash.
 */
sealed interface NodeWait {
    /** Not waiting: the node answered the first time, or nobody has asked yet. */
    data object Idle : NodeWait

    /** The node has refused [attempt] reads over [elapsedSeconds] and is being asked again. */
    data class Waiting(val elapsedSeconds: Int, val attempt: Int) : NodeWait

    /**
     * Nothing usable came from [nodeUrl] for the whole deadline. Rendered as an
     * error with Retry. [detail] is the last read's failure, verbatim, when it
     * had one (the gate probe does; a refused bind has nothing to add).
     */
    data class TimedOut(val nodeUrl: String, val waitedSeconds: Int, val detail: String? = null) : NodeWait
}

/**
 * The numbers for that wait, kept pure so a test can pin them.
 *
 * THE EVIDENCE (CIRISAgent run 37010300145 against the passing 36965860343,
 * same versions, same runner type):
 *
 * - passing run: the node bound at 05:11:57.534 and the client asked it about
 *   2 s later, at 05:11:59.602;
 * - failing run: the client asked at 13:32:45.100 and the node bound at
 *   13:32:45.688, 0.6 s later;
 * - across runs on that runner, the node bound 2.5 to 5.6 s after its fold
 *   started, depending on contention.
 *
 * THE BACKOFF starts at 250 ms and doubles to a 2 s cap: 250, 500, 1000, 2000,
 * 2000 and so on. A miss by 0.6 s, the failing run's margin, is answered by the
 * second or third read, under a second after the first. Each read after that is
 * one `connect()` to loopback, so a cold boot costs at most one cheap read every
 * two seconds.
 *
 * THE DEADLINE is 90 s, or the device's startup budget when that is longer
 * (240 s on a 32-bit device, see [StartupBudget]). The CI simulator's worst
 * observed bind was 5.6 s on a desktop-class host. A phone cold-booting the
 * node after a reset is slower, and how much slower varies, so the deadline is
 * about 16 times the worst case we have measured. A node still silent after
 * that is broken, not slow, and the person gets an error with Retry, not a
 * splash that never changes.
 */
object NodeBindWait {
    const val FIRST_DELAY_MS = 250L
    const val MAX_DELAY_MS = 2_000L
    const val DEADLINE_SECONDS = 90

    /** The pause before read number [attempt] + 1 (0-based): 250 ms doubling, capped at 2 s. */
    fun delayFor(attempt: Int): Long {
        var d = FIRST_DELAY_MS
        repeat(attempt.coerceAtMost(8)) { d = (d * 2).coerceAtMost(MAX_DELAY_MS) }
        return d.coerceAtMost(MAX_DELAY_MS)
    }

    /** The deadline for this device: never shorter than the startup budget it already has. */
    fun deadlineSeconds(): Int = maxOf(DEADLINE_SECONDS, StartupBudget.seconds())
}

/**
 * Whether [e] is the node not being up YET — a refused, reset or timed-out
 * connection — rather than an answer. A [ai.ciris.mobile.shared.api.NodeRefusal]
 * is always an answer. Read from class names and messages along the cause
 * chain, because common code cannot name `java.net.ConnectException`.
 */
fun looksUnreachable(e: Throwable): Boolean {
    var c: Throwable? = e
    var depth = 0
    while (c != null && depth < 8) {
        if (c is ai.ciris.mobile.shared.api.NodeRefusal) return false
        val name = c::class.simpleName.orEmpty()
        val msg = c.message.orEmpty().lowercase()
        if ("Connect" in name || "Timeout" in name || "UnresolvedAddress" in name ||
            "refused" in msg || "connection reset" in msg || "failed to connect" in msg || "timed out" in msg
        ) return true
        c = c.cause
        depth++
    }
    return false
}

/**
 * A screen's FIRST read of the node, with the startup path's patience
 * (CIRISClient#149/#151): while [block] fails because the node is not up yet,
 * say so through [onWait] ([NodeWait.Waiting]) and ask again on [NodeBindWait]'s
 * backoff; past the deadline, report [NodeWait.TimedOut] and return null. Any
 * other failure — an answer, a refusal — is rethrown at once: only absence is
 * waited out.
 */
suspend fun <T> awaitNodeFirstRead(
    nodeUrl: String,
    onWait: (NodeWait) -> Unit,
    deadlineSeconds: Int = NodeBindWait.deadlineSeconds(),
    now: () -> Long = { ai.ciris.mobile.shared.platform.currentTimeMillis() },
    pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    block: suspend () -> T,
): T? {
    val started = now()
    var attempt = 0
    while (true) {
        try {
            val v = block()
            onWait(NodeWait.Idle)
            return v
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!looksUnreachable(e)) {
                onWait(NodeWait.Idle)
                throw e
            }
            val elapsed = ((now() - started) / 1000).toInt()
            if (elapsed >= deadlineSeconds) {
                onWait(NodeWait.TimedOut(nodeUrl, elapsed, e.message))
                return null
            }
            onWait(NodeWait.Waiting(elapsed, attempt + 1))
            pause(NodeBindWait.delayFor(attempt))
            attempt++
        }
    }
}
