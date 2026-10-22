package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.platform.PlatformLogger
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * **Who is asking.** The agent's limiter cannot tell a poll from a person, so
 * the client must: [BACKGROUND] traffic (pollers, ambient refreshes, SSE
 * reconnects) is paced to a budget and steps aside for the user; [USER]
 * traffic (sending a message, the attachment policy read, an explicit refresh)
 * is never queued behind it. Unmarked traffic waits out an active 429 pause
 * (briefly) and is otherwise left alone.
 */
enum class AgentTrafficClass { BACKGROUND, USER }

/** The coroutine-context marker [AgentRateLimit] reads to classify a request. */
class AgentTraffic private constructor(val cls: AgentTrafficClass) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentTraffic> {
        val Background = AgentTraffic(AgentTrafficClass.BACKGROUND)
        val User = AgentTraffic(AgentTrafficClass.USER)
    }
}

/** Run [block]'s agent requests as background traffic (paced, yields to the user). */
suspend fun <T> asAgentBackground(block: suspend CoroutineScope.() -> T): T = withContext(AgentTraffic.Background, block)

/** Run [block]'s agent requests as user-initiated traffic (never queued behind polls). */
suspend fun <T> asAgentUser(block: suspend CoroutineScope.() -> T): T = withContext(AgentTraffic.User, block)

/**
 * **One budget for everything the client sends one agent** (CIRISClient
 * gate 39755201812, linux-ai: the user's `POST /v1/agent/interact` answered
 * 429 four times while the chat screen's pollers kept the bucket empty).
 *
 * The agent (ciris-agent 2.16.2, `api/middleware/rate_limiter.py:27`) allows
 * 60 requests a minute per caller — a token bucket of 60 refilled at one a
 * second, keyed `auth_127.0.0.1` for a session token, so EVERY request this
 * process sends that agent draws on one bucket. A denied request costs no
 * token but carries `Retry-After`; an admitted one carries
 * `X-RateLimit-Remaining`. This reads both and holds three rules:
 *
 *  1. **A budget for background traffic**: at most [BACKGROUND_BUDGET_PER_MINUTE]
 *     a minute (bursts of [BACKGROUND_BURST]), half the agent's limit, so the
 *     other half is the user's whatever the pollers do.
 *  2. **A 429 on any route pauses all background traffic** for its
 *     `Retry-After`, shared across every call site and client instance.
 *  3. **A reserve for the user**: when the agent says fewer than
 *     [USER_RESERVE] tokens remain, background traffic waits for the bucket
 *     to refill that far. The agent's answer is the truth — it also counts
 *     traffic this process did not send.
 *
 * User traffic bypasses the budget and the reserve; it is the reserve's
 * reason to exist. One governor per agent origin, process-wide.
 */
class AgentRateGovernor internal constructor() {

    private val state = Mutex()

    /** Background acquirers queue here, FIFO, so they never stampede at a pause's end. */
    private val queue = Mutex()

    private var pausedUntilMs = 0L
    private var reserveUntilMs = 0L
    private var tokens = BACKGROUND_BURST.toDouble()
    private var refilledAtMs: Long? = null

    /** Wait until a background request may go, and spend its budget token. */
    suspend fun acquireBackground() = queue.withLock {
        while (true) {
            val wait = state.withLock {
                val now = clock()
                refill(now)
                val hold = maxOf(pausedUntilMs, reserveUntilMs) - now
                when {
                    hold > 0 -> hold
                    tokens >= 1.0 -> { tokens -= 1.0; 0L }
                    else -> ((1.0 - tokens) * MS_PER_BACKGROUND_TOKEN).toLong().coerceAtLeast(1L)
                }
            }
            if (wait <= 0L) return@withLock
            delay(wait)
        }
    }

    /** Wait out an active 429 pause, at most [maxWaitMs]. */
    suspend fun awaitPause(maxWaitMs: Long) {
        val wait = state.withLock { pausedUntilMs - clock() }
        if (wait > 0) delay(wait.coerceAtMost(maxWaitMs))
    }

    /** Milliseconds left on the shared 429 pause (0 when none). */
    suspend fun pauseRemainingMs(): Long = state.withLock { (pausedUntilMs - clock()).coerceAtLeast(0L) }

    /** Fold an agent response into the shared state. */
    suspend fun observe(status: Int, retryAfterHeader: String?, remainingHeader: String?, limitHeader: String?) {
        val limit = limitHeader?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: AGENT_LIMIT_PER_MINUTE
        val msPerAgentToken = 60_000L / limit
        state.withLock {
            val now = clock()
            if (status == 429) {
                val seconds = retryAfterSeconds(retryAfterHeader)
                pausedUntilMs = maxOf(pausedUntilMs, now + seconds * 1000L)
                // A 429 means the bucket is empty: the reserve has to refill too.
                reserveUntilMs = maxOf(reserveUntilMs, now + USER_RESERVE * msPerAgentToken)
                PlatformLogger.w(TAG, "agent answered 429: background traffic paused ${seconds}s, held until $USER_RESERVE tokens refill")
                return@withLock
            }
            val remaining = remainingHeader?.trim()?.toIntOrNull() ?: return@withLock
            reserveUntilMs = if (remaining < USER_RESERVE) {
                maxOf(reserveUntilMs, now + (USER_RESERVE - remaining) * msPerAgentToken)
            } else {
                // The agent says there is room: a hold computed from an older reading is stale.
                minOf(reserveUntilMs, now)
            }
        }
    }

    private fun refill(now: Long) {
        val last = refilledAtMs
        if (last != null && now > last) {
            tokens = minOf(BACKGROUND_BURST.toDouble(), tokens + (now - last).toDouble() / MS_PER_BACKGROUND_TOKEN)
        }
        refilledAtMs = now
    }

    companion object {
        private const val TAG = "AgentRateGovernor"

        /** The agent's default (`RateLimiter(requests_per_minute=60)`), used when it sends no `X-RateLimit-Limit`. */
        const val AGENT_LIMIT_PER_MINUTE = 60

        /** Background requests a minute to one agent: half its limit. */
        const val BACKGROUND_BUDGET_PER_MINUTE = 30

        /** Background requests that may go at once (chat opening's one-shot reads). */
        const val BACKGROUND_BURST = 10

        /** Agent tokens kept free for the user: below this, background traffic waits. */
        const val USER_RESERVE = 10

        /** A `Retry-After` longer than this is clamped; a confused value must not stop polling for good. */
        const val MAX_RETRY_AFTER_SECONDS = 60L

        /** How long unmarked traffic waits out a pause before going anyway. */
        const val UNCLASSIFIED_MAX_PAUSE_WAIT_MS = 5_000L

        private const val MS_PER_BACKGROUND_TOKEN = 60_000.0 / BACKGROUND_BUDGET_PER_MINUTE

        /**
         * Paths the agent's limiter never counts (`RateLimitMiddleware.exempt_paths`,
         * rate_limiter.py @ v2.16.2-stable). Pacing them would spend budget on
         * requests that cost the agent nothing.
         */
        val EXEMPT_PATHS = setOf(
            "/openapi.json", "/docs", "/redoc", "/emergency/shutdown",
            "/v1/system/health", "/v1/system/adapters", "/v1/setup/adapter-types",
        )

        /** The clock; tests replace it with virtual time. */
        internal var clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }

        private val registry = Mutex()
        private val byOrigin = mutableMapOf<String, AgentRateGovernor>()

        /** The governor for the agent at [url]'s origin (scheme, host, port). */
        suspend fun forUrl(url: Url): AgentRateGovernor {
            val origin = "${url.protocol.name}://${url.host}:${url.port}"
            return registry.withLock { byOrigin.getOrPut(origin) { AgentRateGovernor() } }
        }

        /** The governor for [baseUrl], or null when it does not parse. */
        suspend fun forBaseUrl(baseUrl: String): AgentRateGovernor? =
            runCatching { Url(baseUrl) }.getOrNull()?.let { forUrl(it) }

        internal suspend fun resetForTests() = registry.withLock { byOrigin.clear() }

        fun isExempt(path: String): Boolean = path.trimEnd('/').ifEmpty { "/" } in EXEMPT_PATHS

        /** `Retry-After` delta-seconds, clamped to [1, MAX_RETRY_AFTER_SECONDS]; absent or unreadable is 1 s. */
        fun retryAfterSeconds(header: String?): Long =
            (header?.trim()?.toLongOrNull() ?: 1L).coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
    }
}

/**
 * The single choke point: installed on every HttpClient [CIRISApiClient] and
 * [ReasoningStreamClient] make. Classifies the request by the caller's
 * [AgentTraffic], holds it per [AgentRateGovernor]'s rules, and folds the
 * agent's answer back in.
 */
val AgentRateLimit = createClientPlugin("AgentRateLimit") {
    on(Send) { request ->
        val url = request.url.build()
        if (AgentRateGovernor.isExempt(url.encodedPath)) return@on proceed(request)
        val governor = AgentRateGovernor.forUrl(url)
        when (currentCoroutineContext()[AgentTraffic]?.cls) {
            AgentTrafficClass.BACKGROUND -> governor.acquireBackground()
            AgentTrafficClass.USER -> Unit // never queued: a 429 here is answered by the caller's visible retry
            null -> governor.awaitPause(AgentRateGovernor.UNCLASSIFIED_MAX_PAUSE_WAIT_MS)
        }
        val call = proceed(request)
        call.response.observeInto(governor)
        call
    }
}

private suspend fun HttpResponse.observeInto(governor: AgentRateGovernor) = governor.observe(
    status = status.value,
    retryAfterHeader = headers["Retry-After"],
    remainingHeader = headers["X-RateLimit-Remaining"],
    limitHeader = headers["X-RateLimit-Limit"],
)
