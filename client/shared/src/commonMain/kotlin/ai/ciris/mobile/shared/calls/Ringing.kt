package ai.ciris.mobile.shared.calls

import ai.ciris.mobile.shared.calls.media.AudioSink
import ai.ciris.mobile.shared.calls.media.CallAudioFormat
import ai.ciris.mobile.shared.platform.PlatformLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ringing (CSD-134; CIRISServer 0.5.255, `tests/vectors/call_frames_v1.json` § ringing).
 *
 * Someone who joins a call in a room with nobody else in it rings every node of every
 * other person in the room. The node tells this device on `GET /v1/calls/events`, an SSE
 * on the owner's session. A ring is transient call control: never a row, never stored.
 * It rings while the app runs; a backgrounded phone is not woken (CIRISServer#24).
 */
data class IncomingRing(
    val room: String,
    val callId: String,
    /** The caller's node. */
    val byMember: String,
    /** The caller's person key, which names them. */
    val byPerson: String?,
    val video: Boolean,
    val sinceMs: Long?,
) {
    companion object {
        fun parse(o: JsonObject): IncomingRing? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val by = o["by"] as? JsonObject
            fun byStr(k: String) = (by?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val room = str("room") ?: return null
            val callId = str("call_id") ?: return null
            val member = byStr("member") ?: return null
            return IncomingRing(
                room = room,
                callId = callId,
                byMember = member,
                byPerson = byStr("person"),
                video = (o["video"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false,
                sinceMs = (o["since_ms"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull,
            )
        }

        /** GET /v1/calls/ringing's body, and the stream's opening `ringing_now`: `{ringing: [...]}`. */
        fun parseList(o: JsonObject): List<IncomingRing> =
            (o["ringing"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parse) }
    }
}

/** Why a ring stopped on this device. An unknown word from a newer node reads as [OTHER]. */
enum class RingEndReason(val wire: String?) {
    ANSWERED("answered"),
    ANSWERED_ELSEWHERE("answered_elsewhere"),
    DECLINED("declined"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
    OTHER(null);

    companion object {
        fun of(s: String?): RingEndReason = entries.firstOrNull { it.wire != null && it.wire == s } ?: OTHER
    }
}

/** One frame of the ring stream, by its SSE event name. */
sealed interface RingEvent {
    /** The stream's first frame: everything ringing right now. It replaces what was held. */
    data class Now(val ringing: List<IncomingRing>) : RingEvent
    data class Ringing(val ring: IncomingRing) : RingEvent
    /** Someone started a call and the room is told, not rung (`alert: notify`): no decline, no timeout. */
    data class Notice(val ring: IncomingRing) : RingEvent
    data class Ended(val room: String, val callId: String, val why: RingEndReason) : RingEvent
    /** The stream fell behind: fetch GET /v1/calls/ringing and replace. */
    data object Resync : RingEvent

    companion object {
        /** An unknown event name, or a frame missing what it must carry, is skipped: null. */
        fun parse(eventName: String?, o: JsonObject): RingEvent? {
            val type = eventName ?: (o["type"] as? JsonPrimitive)?.contentOrNull
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            return when (type) {
                "ringing_now" -> Now(IncomingRing.parseList(o))
                "call_ringing" -> IncomingRing.parse(o)?.let { Ringing(it) }
                "call_notice" -> IncomingRing.parse(o)?.let { Notice(it) }
                "call_ring_ended" -> {
                    val room = str("room") ?: return null
                    Ended(room, str("call_id") ?: "", RingEndReason.of(str("why")))
                }
                "resync" -> Resync
                else -> null
            }
        }
    }
}

/** The two reasons this device may give for not answering (`POST …/call/decline`). */
enum class DeclineReason(val wire: String) { DECLINED("declined"), BUSY("busy") }

/** The node's answer to a decline. [callerTold] false: the ring was declined but the caller's node did not hear it. */
sealed interface DeclineAnswer {
    data class Declined(val callerTold: Boolean) : DeclineAnswer
    /** Nothing was ringing any more by the time the decline arrived. */
    data object NotRinging : DeclineAnswer

    companion object {
        fun parse(o: JsonObject): DeclineAnswer =
            if ((o["declined"] as? JsonPrimitive)?.contentOrNull == "not_ringing") NotRinging
            else Declined((o["caller_told"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false)
    }
}

/** What the ring watcher needs from the node. */
interface RingTransport {
    /** Opens the stream and calls [onEvent] for each frame until it closes or fails. */
    suspend fun stream(onEvent: suspend (RingEvent) -> Unit)
    suspend fun ringingNow(): List<IncomingRing>
    suspend fun decline(room: String, reason: DeclineReason): DeclineAnswer
}

/**
 * Holds what is ringing on this device. One per signed-in node session.
 *
 * The stream is reopened when it drops, with a growing wait (1 s doubling to 30 s,
 * reset once a frame arrives), and every opening frame replaces what was held, so a
 * ring that ended while the stream was down does not linger.
 */
class RingWatcher(
    private val transport: RingTransport,
    private val scope: CoroutineScope,
) {
    private val _ringing = MutableStateFlow<List<IncomingRing>>(emptyList())
    val ringing: StateFlow<List<IncomingRing>> = _ringing.asStateFlow()

    /** Rooms this device answered or declined: their ring is dropped here at once, before the node says so. */
    private val handled = mutableSetOf<String>()
    private var job: Job? = null

    /**
     * Calls the room was told of without a ring. A notice has no end from the node,
     * so it is shown for [NOTICE_SHOW_MS] and then put away; the call may well go on.
     */
    private val _notices = MutableStateFlow<List<IncomingRing>>(emptyList())
    val notices: StateFlow<List<IncomingRing>> = _notices.asStateFlow()
    private val noticeTimers = mutableMapOf<String, Job>()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var wait = MIN_WAIT_MS
            while (isActive) {
                try {
                    transport.stream { event ->
                        wait = MIN_WAIT_MS
                        apply(event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PlatformLogger.i(TAG, "ring stream dropped: ${e.message?.take(120)}")
                }
                delay(wait)
                wait = (wait * 2).coerceAtMost(MAX_WAIT_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _ringing.value = emptyList()
        noticeTimers.values.forEach { it.cancel() }
        noticeTimers.clear()
        _notices.value = emptyList()
    }

    internal suspend fun apply(event: RingEvent) {
        when (event) {
            is RingEvent.Now -> replace(event.ringing)
            is RingEvent.Ringing -> {
                handled.remove(event.ring.room)
                _ringing.value = _ringing.value.filterNot { it.room == event.ring.room } + event.ring
            }
            is RingEvent.Notice -> {
                val ring = event.ring
                _notices.value = _notices.value.filterNot { it.room == ring.room } + ring
                noticeTimers.remove(ring.room)?.cancel()
                noticeTimers[ring.room] = scope.launch {
                    delay(NOTICE_SHOW_MS)
                    dismissNotice(ring.room)
                }
            }
            is RingEvent.Ended -> {
                PlatformLogger.i(TAG, "[ring] ended room=${event.room} why=${event.why.wire ?: "other"}")
                _ringing.value = _ringing.value.filterNot { it.room == event.room && (event.callId.isEmpty() || it.callId == event.callId) }
            }
            RingEvent.Resync -> replace(runCatching { transport.ringingNow() }.getOrElse { _ringing.value })
        }
    }

    private fun replace(now: List<IncomingRing>) {
        _ringing.value = now.filterNot { it.room in handled }
    }

    /** This device is answering [room]: the call screen joins, and that join stops the ring here. */
    fun answered(room: String) {
        handled += room
        _ringing.value = _ringing.value.filterNot { it.room == room }
        dismissNotice(room)
    }

    /** Put a notice away on this device. Nothing is sent: a notice asks nothing of anyone. */
    fun dismissNotice(room: String) {
        noticeTimers.remove(room)?.cancel()
        _notices.value = _notices.value.filterNot { it.room == room }
    }

    /** Declines [room]'s ring. The ring goes from this device at once, whatever the node answers. */
    suspend fun decline(room: String, reason: DeclineReason): DeclineAnswer? {
        handled += room
        _ringing.value = _ringing.value.filterNot { it.room == room }
        PlatformLogger.i(TAG, "[ring] decline room=$room reason=${reason.wire}")
        return runCatching { transport.decline(room, reason) }
            .onFailure { PlatformLogger.w(TAG, "[ring] decline failed: ${it.message?.take(120)}") }
            .getOrNull()
    }

    companion object {
        private const val TAG = "RingWatcher"
        const val MIN_WAIT_MS = 1_000L
        const val MAX_WAIT_MS = 30_000L
        /** How long a "started a call" notice stays up when nobody acts on it. */
        const val NOTICE_SHOW_MS = 120_000L
    }
}

/**
 * The ring's sound, through the call speaker so it is the same on every platform: two
 * tones (440 and 480 Hz) for 2 s, then 4 s of quiet, until stopped.
 */
class RingTone(private val openSink: () -> AudioSink?, private val scope: CoroutineScope) {
    private var job: Job? = null
    /** Opened when the ring starts and released when it stops, so a call can have the speaker. */
    private var sink: AudioSink? = null

    fun start() {
        if (job?.isActive == true) return
        val sink = runCatching { openSink() }.getOrNull() ?: return
        this.sink = sink
        job = scope.launch {
            var phase = 0
            while (isActive) {
                val cycle = phase % CYCLE_FRAMES
                if (cycle < ON_FRAMES) sink.play(PUBLISHER, frame(phase)) else sink.play(PUBLISHER, SILENCE)
                phase++
                delay(CallAudioFormat.FRAME_MS.toLong())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        sink?.let { runCatching { it.remove(PUBLISHER); it.stop() } }
        sink = null
    }

    companion object {
        const val PUBLISHER = "ring"
        private const val ON_FRAMES = 2_000 / CallAudioFormat.FRAME_MS
        private const val CYCLE_FRAMES = 6_000 / CallAudioFormat.FRAME_MS
        private val SILENCE = ShortArray(CallAudioFormat.FRAME_SAMPLES)

        /** One 20 ms frame of the two tones, continuous across frames. */
        internal fun frame(index: Int): ShortArray {
            val n = CallAudioFormat.FRAME_SAMPLES
            val base = index.toLong() * n
            return ShortArray(n) { i ->
                val t = (base + i).toDouble() / CallAudioFormat.SAMPLE_RATE
                ((sin(2 * PI * 440 * t) + sin(2 * PI * 480 * t)) * 4_000).roundToInt().toShort()
            }
        }
    }
}
