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
 *  3. **A reserve for the user**: the governor keeps a MIRROR of the
 *     agent's bucket — every request this process sends it, of any class,
 *     spends a mirror token, and the mirror refills at the agent's rate. When
 *     the mirror is below [USER_RESERVE], background traffic waits for it to
 *     refill that far, and unmarked traffic waits (up to
 *     [UNCLASSIFIED_MAX_WAIT_MS]) rather than send into a bucket it
 *     knows is empty. The agent's answer corrects it — `X-RateLimit-Remaining`
 *     lowers the mirror, and a 429 empties it — because it also counts
 *     traffic this process did not send.
 *
 * Why a mirror (gate run 39764738615, linux-ai): with only the background
 * budget, unmarked traffic — screens that never said who was asking — still
 * emptied the bucket 36 s after launch, and the policy read was refused on
 * every retry. Pacing only the traffic that declared itself left the rest
 * free to spend the user's half.
 *
 * User traffic bypasses the budget and the reserve; it is the reserve's
 * reason to exist. One governor per agent HOST, process-wide — not per
 * origin: the node on :4243 reverse-proxies every brain route (/v1/agent/…,
 * telemetry, audit, WA, tools, memory, …) onto the agent's :8080 from
 * 127.0.0.1 with the caller's Bearer (CIRISAgent `brain_adapter.py`,
 * BRAIN_UPSTREAM), so both ports spend one bucket. Per origin, the :8080
 * mirror saw 25 requests in the minute the agent refused the policy read
 * (gate run 39774629469); the other 35+ had gone through :4243.
 */
class AgentRateGovernor internal constructor() {

    private val state = Mutex()

    /** Background acquirers queue here, FIFO, so they never stampede at a pause's end. */
    private val queue = Mutex()

    /** Unmarked traffic queues here, FIFO, apart from background so neither starves the other. */
    private val unmarkedQueue = Mutex()

    private var pausedUntilMs = 0L
    private var tokens = BACKGROUND_BURST.toDouble()
    private var refilledAtMs: Long? = null

    /** The agent's limit, per its `X-RateLimit-Limit` once seen. */
    private var agentLimit = AGENT_LIMIT_PER_MINUTE

    /**
     * Has this host shown it has a limiter (an `X-RateLimit-Limit` header or a
     * 429)? Until it has, nothing is held back — not the background budget,
     * not the reserve, not the unmarked floor: a node with no agent behind it
     * has no bucket to protect, and pacing it against an imaginary one only
     * throttled it (keyed per host, every test server on 127.0.0.1 shared one
     * 30/min background budget and the desktop suite stalled).
     */
    private var agentLimited = false

    /** The mirror of the agent's bucket: a new caller starts full, as the agent's does. */
    private var agentTokens = AGENT_LIMIT_PER_MINUTE.toDouble()
    private var agentRefilledAtMs: Long? = null

    /** What this process sent the agent in the last minute, for the 429 breakdown. */
    private val recent = ArrayDeque<Sent>()
    private var lastBreakdownAtMs: Long? = null

    /**
     * The agent's last `X-RateLimit-Remaining`, on which route and when: said
     * beside every 429, so a refusal while another route was just told "40
     * left" shows two buckets, not one empty one (gate runs 39802071995 and
     * 39808985094: only the policy route was ever refused).
     */
    private var lastRemaining: Int? = null
    private var lastRemainingRoute: String? = null
    private var lastRemainingAtMs: Long? = null

    private class Sent(val atMs: Long, val route: String, val cls: AgentTrafficClass?)

    private val msPerAgentToken get() = 60_000.0 / agentLimit

    /**
     * Wait until a request of [cls] to [route] may go, and count it. The check
     * and the spend are one step under the lock, so waiters released by the
     * same refill cannot all pass on one token.
     */
    suspend fun admit(route: String, cls: AgentTrafficClass?) {
        when (cls) {
            AgentTrafficClass.USER -> state.withLock { spendLocked(clock(), route, cls) }
            AgentTrafficClass.BACKGROUND -> queue.withLock { waitAndSpend(route, cls, Long.MAX_VALUE) }
            null -> unmarkedQueue.withLock { waitAndSpend(route, cls, UNCLASSIFIED_MAX_WAIT_MS) }
        }
    }

    /** Background acquirers' rule (budget, reserve, pause); unmarked's (floor, pause), until [maxWaitMs]. */
    private suspend fun waitAndSpend(route: String, cls: AgentTrafficClass?, maxWaitMs: Long) {
        var waited = 0L
        while (true) {
            val wait = state.withLock {
                val now = clock()
                refill(now)
                val hold = when {
                    pausedUntilMs > now -> pausedUntilMs - now
                    cls == null -> if (agentLimited && agentTokens < UNMARKED_FLOOR) msUntilMirror(UNMARKED_FLOOR) else 0L
                    !agentLimited -> 0L
                    agentTokens < USER_RESERVE + 1 -> msUntilMirror(USER_RESERVE + 1.0)
                    tokens < 1.0 -> ((1.0 - tokens) * MS_PER_BACKGROUND_TOKEN).toLong().coerceAtLeast(1L)
                    else -> { tokens -= 1.0; 0L }
                }
                if (hold <= 0L || waited >= maxWaitMs) {
                    spendLocked(now, route, cls)
                    0L
                } else {
                    hold.coerceAtMost(maxWaitMs - waited)
                }
            }
            if (wait <= 0L) return
            delay(wait)
            waited += wait
        }
    }

    private fun spendLocked(now: Long, route: String, cls: AgentTrafficClass?) {
        refill(now)
        // The agent's bucket never goes below zero: a refused request costs it
        // nothing. A mirror allowed to go negative (every send counted, the
        // refused ones too) would hold background traffic for minutes after
        // the agent had refilled.
        agentTokens = maxOf(0.0, agentTokens - 1.0)
        recent.addLast(Sent(now, route, cls))
        while (recent.isNotEmpty() && recent.first().atMs < now - 60_000) recent.removeFirst()
    }

    /** Tokens the mirror says the agent has left (tests and the breakdown read it). */
    suspend fun mirrorTokens(): Double = state.withLock { refill(clock()); agentTokens }

    /** Milliseconds left on the shared 429 pause (0 when none). */
    suspend fun pauseRemainingMs(): Long = state.withLock { (pausedUntilMs - clock()).coerceAtLeast(0L) }

    /** Fold an agent response into the shared state. */
    suspend fun observe(
        status: Int,
        retryAfterHeader: String?,
        remainingHeader: String?,
        limitHeader: String?,
        route: String? = null,
    ) {
        state.withLock {
            val now = clock()
            refill(now)
            limitHeader?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { agentLimit = it; agentLimited = true }
            if (status == 429) {
                agentLimited = true
                val seconds = retryAfterSeconds(retryAfterHeader)
                pausedUntilMs = maxOf(pausedUntilMs, now + seconds * 1000L)
                val mirrored = agentTokens
                // A 429 means the bucket is empty; the reserve has to refill before background goes again.
                agentTokens = 0.0
                val said = lastRemaining?.let { r ->
                    "agent last said Remaining=$r on ${lastRemainingRoute ?: "?"} ${now - (lastRemainingAtMs ?: now)}ms ago"
                } ?: "agent has not said Remaining yet"
                PlatformLogger.w(
                    TAG,
                    "agent answered 429${route?.let { " on $it" } ?: ""}: mirror had ${(mirrored * 10).toInt() / 10.0} tokens, $said; " +
                        "background traffic paused ${seconds}s, held until $USER_RESERVE tokens refill",
                )
                logBreakdown(now)
                return@withLock
            }
            // The agent stamps Remaining AFTER its handler ran, so a slow request's
            // reading is older than requests sent since: it may lower the mirror
            // (traffic this process did not see), never raise it.
            remainingHeader?.trim()?.toIntOrNull()?.let {
                agentTokens = minOf(agentTokens, it.toDouble())
                lastRemaining = it; lastRemainingRoute = route; lastRemainingAtMs = now
            }
        }
    }

    /**
     * WHO SPENT THE BUDGET, said at the moment it ran out (at most every
     * [BREAKDOWN_EVERY_MS]): a gate log that shows only the refused request
     * cannot say which screen emptied the bucket.
     */
    private fun logBreakdown(now: Long) {
        val last = lastBreakdownAtMs
        if (last != null && now - last < BREAKDOWN_EVERY_MS) return
        lastBreakdownAtMs = now
        val window = recent.filter { it.atMs >= now - 60_000 }
        val byClass = window.groupingBy { it.cls?.name?.lowercase() ?: "unmarked" }.eachCount()
        val top = window.groupingBy { "${it.route} (${it.cls?.name?.lowercase() ?: "unmarked"})" }.eachCount()
            .entries.sortedByDescending { it.value }.take(BREAKDOWN_TOP_ROUTES)
            .joinToString(", ") { "${it.key} ×${it.value}" }
        PlatformLogger.w(TAG, "agent traffic from this process in the last 60 s: ${window.size} $byClass — $top")
    }

    /** Milliseconds until the mirror refills to [target] tokens. */
    private fun msUntilMirror(target: Double): Long =
        ((target - agentTokens) * msPerAgentToken).toLong().coerceAtLeast(1L)

    private fun refill(now: Long) {
        val last = refilledAtMs
        if (last != null && now > last) {
            tokens = minOf(BACKGROUND_BURST.toDouble(), tokens + (now - last).toDouble() / MS_PER_BACKGROUND_TOKEN)
        }
        refilledAtMs = now
        val agentLast = agentRefilledAtMs
        if (agentLast != null && now > agentLast) {
            agentTokens = minOf(agentLimit.toDouble(), agentTokens + (now - agentLast).toDouble() / msPerAgentToken)
        }
        agentRefilledAtMs = now
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

        /**
         * The longest unmarked traffic waits out a pause or a near-empty mirror
         * before going anyway: a safety net for a mirror gone wrong, not a pace.
         */
        const val UNCLASSIFIED_MAX_WAIT_MS = 60_000L

        /** Unmarked traffic waits while the mirror is below this; the rest of the reserve is the user's alone. */
        const val UNMARKED_FLOOR = 3.0

        private const val BREAKDOWN_EVERY_MS = 10_000L
        private const val BREAKDOWN_TOP_ROUTES = 12

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
        private val byHost = mutableMapOf<String, AgentRateGovernor>()

        /** The governor for the agent on [url]'s host: every port of one host shares the agent's bucket. */
        suspend fun forUrl(url: Url): AgentRateGovernor {
            val host = url.host.lowercase().let { if (it == "localhost" || it == "::1" || it == "[::1]") "127.0.0.1" else it }
            return registry.withLock { byHost.getOrPut(host) { AgentRateGovernor() } }
        }

        /** The governor for [baseUrl], or null when it does not parse. */
        suspend fun forBaseUrl(baseUrl: String): AgentRateGovernor? =
            runCatching { Url(baseUrl) }.getOrNull()?.let { forUrl(it) }

        internal suspend fun resetForTests() = registry.withLock { byHost.clear() }

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
        // `localhost` is not resolved on every device (LoopbackLiteral.kt): send
        // the loopback address itself. Every agent-bound client installs this
        // plugin, so this is the one place the rewrite has to live.
        if (ai.ciris.mobile.shared.platform.isLoopbackName(request.url.host)) {
            request.url.host = ai.ciris.mobile.shared.platform.LOOPBACK_LITERAL
        }
        val url = request.url.build()
        if (AgentRateGovernor.isExempt(url.encodedPath)) return@on proceed(request)
        val governor = AgentRateGovernor.forUrl(url)
        // USER is never queued (a 429 there is answered by the caller's visible retry);
        // BACKGROUND and unmarked wait per the governor's rules.
        // The port is in the route: :8080 (the agent) and :4243 (the node, which
        // proxies brain routes onto :8080) share this host's one budget.
        val route = "${request.method.value} :${url.port}${url.encodedPath}"
        governor.admit(route, currentCoroutineContext()[AgentTraffic]?.cls)
        val call = proceed(request)
        call.response.observeInto(governor, route)
        call
    }
}

private suspend fun HttpResponse.observeInto(governor: AgentRateGovernor, route: String) = governor.observe(
    status = status.value,
    retryAfterHeader = headers["Retry-After"],
    remainingHeader = headers["X-RateLimit-Remaining"],
    limitHeader = headers["X-RateLimit-Limit"],
    route = route,
)
