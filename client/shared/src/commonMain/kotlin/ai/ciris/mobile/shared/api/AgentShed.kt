package ai.ciris.mobile.shared.api

import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * **The agent is shedding: runtime self-protection, not an agent decision**
 * (CIRISAgent#1242, agent 2.15+; CIRISClient#173).
 *
 * While a resource is at CRITICAL pressure, `POST /v1/agent/message` and
 * `POST /v1/agent/interact` answer `503` with a `Retry-After` header and
 *
 * ```
 * {"detail": {"error": "resource_shed", "reason": "RESOURCE_SHED",
 *             "resources": ["memory_mb"], "retry_after_seconds": 30}}
 * ```
 *
 * (`routes/agent.py` `_raise_if_shed`, v2.16.2-stable). The refusal happens
 * in `base_observer.handle_incoming_message` BEFORE credit is charged and
 * BEFORE any task exists, so the message was NOT admitted: resending the same
 * text cannot duplicate it. That is what makes the automatic resend safe.
 *
 * It is never DEFER or REJECT wording — those are H3ERE actions.
 */
class AgentShedException(
    /** Seconds to wait before resending, from `Retry-After` (else the body, else 30). */
    val retryAfterSeconds: Int,
    /** The resources under pressure, as the agent names them (`memory_mb`). */
    val resources: List<String> = emptyList(),
) : RuntimeException("agent shedding new work (RESOURCE_SHED), retry after ${retryAfterSeconds}s") {
    /** The wire's reason, which is also the localization id suffix. */
    val reasonId: String get() = REASON_ID

    companion object {
        const val REASON_ID = "RESOURCE_SHED"
        const val ERROR_TOKEN = "resource_shed"
    }
}

/**
 * The resend gave up: [lastShed] is the final refusal. The message was never
 * admitted (see [AgentShedException]), so the caller keeps the user's text.
 */
class AgentShedGaveUp(val lastShed: AgentShedException, val attempts: Int) :
    RuntimeException("agent still shedding after $attempts attempts")

object AgentShed {
    /** Sends in total, the first included. Three sheds and we stop. */
    const val MAX_ATTEMPTS = 3

    /** The whole resend wait never exceeds this; a longer `Retry-After` gives up instead. */
    const val MAX_TOTAL_WAIT_SECONDS = 120

    /** The agent's own default (`SHED_RETRY_AFTER_SECONDS`), used when neither header nor body says. */
    const val DEFAULT_RETRY_AFTER_SECONDS = 30

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The [AgentShedException] a `503` stands for, or null when it is some
     * other 503 (a missing message handler, the credit provider) — those stay
     * errors. Only the agent's named token makes it a shed.
     */
    fun fromResponse(status: Int, retryAfterHeader: String?, body: String?, now: Instant = Clock.System.now()): AgentShedException? {
        if (status != 503) return null
        val detail = runCatching { json.parseToJsonElement(body ?: "").jsonObject["detail"] as? JsonObject }.getOrNull()
            ?: return null
        val error = detail["error"]?.jsonPrimitive?.contentOrNull
        val reason = detail["reason"]?.jsonPrimitive?.contentOrNull
        if (error != AgentShedException.ERROR_TOKEN && reason != AgentShedException.REASON_ID) return null
        val bodySeconds = runCatching { detail["retry_after_seconds"]?.jsonPrimitive?.intOrNull }.getOrNull()
        val resources = runCatching {
            detail["resources"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrNull() ?: emptyList()
        return AgentShedException(retryAfterSeconds(retryAfterHeader, now, bodySeconds), resources)
    }

    /**
     * `Retry-After` is delta-seconds or an HTTP-date (RFC 9110 §10.2.3). A
     * date in the past is "now" (1 s, never 0 — no tight loop). Unparseable
     * falls to the body's `retry_after_seconds`, then to the agent's default.
     */
    fun retryAfterSeconds(header: String?, now: Instant, bodySeconds: Int? = null): Int {
        val h = header?.trim().orEmpty()
        val parsed: Int? = when {
            h.isEmpty() -> null
            h.all { it.isDigit() } -> h.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            else -> parseHttpDate(h)?.let { at ->
                val ms = (at - now).inWholeMilliseconds
                ((ms + 999) / 1000).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
        return (parsed ?: bodySeconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceAtLeast(1)
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    // IMF-fixdate "Sun, 06 Nov 1994 08:49:37 GMT" and RFC 850 "Sunday, 06-Nov-94 08:49:37 GMT".
    private val IMF = Regex("""^[A-Za-z]+,\s*(\d{1,2})[ -]([A-Za-z]{3})[ -](\d{2,4})\s+(\d{2}):(\d{2}):(\d{2})\s*GMT$""")

    // asctime "Sun Nov  6 08:49:37 1994".
    private val ASCTIME = Regex("""^[A-Za-z]{3}\s+([A-Za-z]{3})\s+(\d{1,2})\s+(\d{2}):(\d{2}):(\d{2})\s+(\d{4})$""")

    fun parseHttpDate(s: String): Instant? = runCatching {
        IMF.matchEntire(s.trim())?.let { m ->
            val (d, mon, y, hh, mm, ss) = m.destructured
            val year = y.toInt().let { if (y.length == 2) (if (it < 70) 2000 + it else 1900 + it) else it }
            return@runCatching at(year, mon, d.toInt(), hh.toInt(), mm.toInt(), ss.toInt())
        }
        ASCTIME.matchEntire(s.trim())?.let { m ->
            val (mon, d, hh, mm, ss, y) = m.destructured
            return@runCatching at(y.toInt(), mon, d.toInt(), hh.toInt(), mm.toInt(), ss.toInt())
        }
        null
    }.getOrNull()

    private fun at(year: Int, mon: String, day: Int, h: Int, m: Int, s: Int): Instant? {
        val month = MONTHS.indexOf(mon.lowercase()) + 1
        if (month == 0) return null
        return LocalDateTime(year, month, day, h, m, s).toInstant(TimeZone.UTC)
    }

    /**
     * Send, and on a shed wait out `Retry-After` and send the SAME request
     * again — bounded by [MAX_ATTEMPTS] sends and [MAX_TOTAL_WAIT_SECONDS] of
     * waiting, never a tight loop (every wait is at least 1 s).
     *
     * [onWait] is told the seconds remaining once a second during a wait
     * (`attempt` is the send that will follow), and `null` the moment the
     * resend goes out. Throws [AgentShedGaveUp] when the bound is reached;
     * every other failure propagates unchanged on the first occurrence.
     */
    suspend fun <T> sendWithShedRetry(
        onWait: (ShedWait?) -> Unit = {},
        sleep: suspend (Long) -> Unit = { delay(it) },
        send: suspend () -> T,
    ): T {
        var waited = 0
        var attempt = 1
        while (true) {
            val shed = try {
                return send()
            } catch (e: AgentShedException) {
                e
            }
            val wait = shed.retryAfterSeconds
            if (attempt >= MAX_ATTEMPTS || waited + wait > MAX_TOTAL_WAIT_SECONDS) {
                onWait(null)
                throw AgentShedGaveUp(shed, attempt)
            }
            for (remaining in wait downTo 1) {
                onWait(ShedWait(remaining, attempt + 1, MAX_ATTEMPTS, shed.resources))
                sleep(1000L)
            }
            waited += wait
            attempt++
            onWait(null)
        }
    }
}

/** A countdown tick: [secondsRemaining] until send number [nextAttempt] of [maxAttempts]. */
data class ShedWait(
    val secondsRemaining: Int,
    val nextAttempt: Int,
    val maxAttempts: Int,
    val resources: List<String> = emptyList(),
)
