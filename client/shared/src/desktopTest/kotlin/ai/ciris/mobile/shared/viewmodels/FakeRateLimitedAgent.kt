package ai.ciris.mobile.shared.viewmodels

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.InternalAPI
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher

/**
 * An agent in-process, on virtual time: every request is answered by
 * [route] AFTER passing a faithful port of ciris-agent 2.16.2's limiter
 * (`api/middleware/rate_limiter.py`: a 60-token bucket refilled at one a
 * second, exempt paths uncounted, a denied request consuming nothing and
 * answered 429 with `Retry-After = int(tokens_needed * 60/rate) + 1`, an
 * admitted one carrying `X-RateLimit-Remaining`).
 *
 * [now] is the test scheduler's clock; [dispatcher] is the test dispatcher,
 * so no request ever leaves virtual time.
 */
class FakeRateLimitedAgent(
    private val dispatcherOverride: CoroutineDispatcher,
    private val now: () -> Long,
    initialTokens: Double = 60.0,
    private val rate: Int = 60,
) {
    data class Hit(val atMs: Long, val method: String, val path: String, val status: Int, val body: String)

    /** Every request that reached the agent, exempt ones included (filter with [counted]). */
    val hits = mutableListOf<Hit>()

    /** A scripted answer that overrides routing and the bucket (a forced 429 for one request). */
    var override: ((method: String, path: String) -> Reply?)? = null

    data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap(), val stream: Boolean = false)

    private var tokens = initialTokens
    private var lastUpdate: Long? = null

    val url = "http://fake-agent.test:8080"

    private val exempt = setOf(
        "/openapi.json", "/docs", "/redoc", "/emergency/shutdown",
        "/v1/system/health", "/v1/system/adapters", "/v1/setup/adapter-types",
    )

    fun counted(): List<Hit> = hits.filter { it.path !in exempt }

    /** The most counted requests in any [windowMs] window. */
    fun maxInAnyWindow(windowMs: Long, from: Long = 0L): Int {
        val times = counted().map { it.atMs }.filter { it >= from }.sorted()
        var best = 0
        var j = 0
        for (i in times.indices) {
            while (times[i] - times[j] >= windowMs) j++
            best = maxOf(best, i - j + 1)
        }
        return best
    }

    /** rate_limiter.py RateLimiter.check_rate_limit + get_retry_after, verbatim in effect. */
    private fun admit(): Pair<Boolean, Int> {
        val t = now()
        val last = lastUpdate
        if (last != null) tokens = minOf(rate.toDouble(), tokens + (t - last) / 1000.0 * (rate / 60.0))
        lastUpdate = t
        if (tokens >= 1) {
            tokens -= 1
            return true to tokens.toInt()
        }
        val retryAfter = ((1 - tokens) * (60.0 / rate)).toInt() + 1
        return false to retryAfter
    }

    private fun route(method: String, path: String): Reply = when (path) {
        "/v1/system/health" -> Reply(200, """{"data":{"status":"healthy","cognitive_state":"work","services":{}}}""")
        "/v1/setup/config" -> Reply(200, """{"data":{"llm_provider":"anthropic","llm_model":"m","llm_api_key_set":true}}""")
        "/v1/system/llm/ciris-services/status" -> Reply(200, """{"data":{"disabled":true}}""")
        "/v1/system/runtime/reasoning-stream" -> Reply(200, "", mapOf("Content-Type" to "text/event-stream"), stream = true)
        "/v1/agent/interact" -> Reply(200, """{"data":{"message_id":"srv-1","response":"I am here","state":"WORK","processing_time_ms":5}}""")
        else -> Reply(404, """{"detail":"Not Found"}""")
    }

    val engine = object : HttpClientEngineBase("fake-rate-limited-agent") {
        override val config = HttpClientEngineConfig()
        override val dispatcher: CoroutineDispatcher get() = dispatcherOverride
        override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(HttpTimeoutCapability)

        @OptIn(InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val path = data.url.encodedPath
            val method = data.method.value
            val body = when (val b = data.body) {
                is TextContent -> b.text
                is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
                else -> ""
            }
            val reply = override?.invoke(method, path) ?: if (path in exempt) {
                route(method, path)
            } else {
                val (ok, n) = admit()
                if (ok) {
                    route(method, path).let { it.copy(headers = it.headers + mapOf("X-RateLimit-Limit" to "$rate", "X-RateLimit-Remaining" to "$n")) }
                } else {
                    Reply(
                        429,
                        """{"detail":"Too many requests. Please wait $n seconds before trying again.","error":"rate_limit_exceeded","retry_after":$n,"message":"You're sending requests too quickly. The system will be ready again shortly."}""",
                        mapOf("Retry-After" to "$n", "X-RateLimit-Limit" to "$rate", "X-RateLimit-Remaining" to "0"),
                    )
                }
            }
            hits += Hit(now(), method, path, reply.status, body)
            val headers = Headers.build {
                append("Content-Type", reply.headers["Content-Type"] ?: "application/json")
                reply.headers.filterKeys { it != "Content-Type" }.forEach { (k, v) -> append(k, v) }
            }
            val content: Any = if (reply.stream) ByteChannel() /* an SSE stream that stays open */ else ByteReadChannel(reply.body.encodeToByteArray())
            return HttpResponseData(HttpStatusCode.fromValue(reply.status), GMTDate(), headers, HttpProtocolVersion.HTTP_1_1, content, callContext())
        }
    }
}
