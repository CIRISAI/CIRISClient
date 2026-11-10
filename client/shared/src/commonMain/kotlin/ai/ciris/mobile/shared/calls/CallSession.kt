package ai.ciris.mobile.shared.calls

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One call in one room (CSD-134), against the routes agreed for CIRISServer
 * 0.5.246 (CIRISServer#859). Media is not here: this holds who is in the call,
 * one tile per publisher, and why anyone left, from the join answer, the
 * socket's text events and the frames themselves.
 *
 * Tiles are keyed by the publisher's key id, never by arrival order, and a
 * member who rejoins with a new stream is a new tile, never the old one resumed.
 * Members are key ids only: the screen names them from its own contacts.
 */
class CallSession(
    private val room: String,
    private val transport: CallTransport,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _frames = kotlinx.coroutines.flow.MutableSharedFlow<ReceivedFrame>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * Every decoded frame from another member, in arrival order, for the media
     * engine. Live media is never late-delivered: a slow collector loses the
     * oldest frames, which the decoder then conceals as gaps.
     */
    val frames: kotlinx.coroutines.flow.SharedFlow<ReceivedFrame> = _frames

    private var socket: CallSocket? = null
    private var reader: Job? = null

    fun join(video: Boolean) {
        if (_state.value is CallState.Joining || _state.value is CallState.InCall) return
        _state.value = CallState.Joining
        scope.launch {
            val answer = try {
                transport.join(room, video)
            } catch (e: CallRefusal) {
                _state.value = CallState.Refused(e.reasonId)
                return@launch
            }
            val s = try {
                transport.openFrames(room)
            } catch (e: Exception) {
                runCatching { transport.leave(room) }
                _state.value = CallState.Refused(PLUMBING)
                return@launch
            }
            socket = s
            _state.value = CallState.InCall(
                callId = answer.callId,
                myStreamId = answer.streamId,
                epoch = answer.epoch,
                frameBudgetBytes = answer.frameBudgetBytes,
                serviceTier = answer.serviceTier,
                attendees = answer.attendees.associateWith { Attendee(it) },
            )
            reader = scope.launch { s.incoming.collect { onMessage(it) } }
        }
    }

    fun leave() {
        val wasIn = _state.value is CallState.InCall
        reader?.cancel()
        socket?.close()
        socket = null
        if (wasIn) scope.launch { runCatching { transport.leave(room) } }
        _state.value = CallState.Left(LeaveReason.LEFT)
    }

    /**
     * Publish one frame. Over the call's budget it is not sent, and the state says
     * so: the node would refuse it, and a frame cut to fit is a corrupt one.
     */
    fun publish(frame: OutgoingFrame): Boolean {
        val call = _state.value as? CallState.InCall ?: return false
        if (!call.ready) return false
        if (!call.fragmenting && frame.payload.size > call.frameBudgetBytes) {
            _state.value = call.copy(notice = CallNotice.OverBudget(call.frameBudgetBytes))
            return false
        }
        socket?.send(CallFrames.encode(frame)) ?: return false
        return true
    }

    internal fun onMessage(message: CallSocketMessage) {
        when (message) {
            is CallSocketMessage.Binary -> onFrame(message.bytes)
            is CallSocketMessage.Text -> CallEvent.parse(message.text)?.let(::onEvent)
            is CallSocketMessage.Closed -> {
                socket = null
                _state.value = CallState.Left(LeaveReason.of(message.reason))
            }
        }
    }

    private fun onFrame(bytes: ByteArray) {
        val frame = try { CallFrames.decodeReceived(bytes) } catch (e: CallFrameException) { return }
        _frames.tryEmit(frame)
        updateCall { call ->
            val prior = call.attendees[frame.publisher] ?: Attendee(frame.publisher)
            // A frame on a new stream from a known member is a rejoin: a new tile.
            val base = if (prior.streamId != null && !prior.streamId.contentEquals(frame.streamId)) {
                Attendee(frame.publisher)
            } else prior
            val updated = if (frame.codecId == CallFrames.CODEC_OPAQUE) {
                base.copy(streamId = frame.streamId, sendingAudio = true)
            } else {
                base.copy(streamId = frame.streamId, sendingVideo = true, videoPaused = null)
            }
            call.copy(attendees = call.attendees + (frame.publisher to updated), lastFrame = frame)
        }
    }

    private fun onEvent(event: CallEvent) {
        updateCall { call ->
            when (event) {
                is CallEvent.Ready -> call.copy(ready = true, frameBudgetBytes = event.frameBudgetBytes, fragmenting = event.fragmenting)
                is CallEvent.AttendeeJoined ->
                    call.copy(attendees = call.attendees + (event.member to Attendee(event.member, streamId = streamIdBytes(event.streamId), person = event.person)))
                is CallEvent.AttendeeLeft ->
                    call.copy(
                        attendees = call.attendees - event.member,
                        departed = call.departed + (event.member to event.left),
                    )
                is CallEvent.EpochRolled -> call.copy(epoch = event.epoch)
                is CallEvent.TierChanged -> call.copy(serviceTier = event.tier)
                is CallEvent.VideoPaused -> call.attendees[event.member]?.let {
                    call.copy(attendees = call.attendees + (event.member to it.copy(videoPaused = event.reason)))
                } ?: call
                is CallEvent.KeepRefused -> call.copy(notice = CallNotice.KeepRefused(event.reasonId))
                is CallEvent.PublishRefused -> call.copy(notice = CallNotice.PublishRefused(event.reason, event.budget))
                // Same tile, new stream: what it was sending carries on until the new
                // stream's frames say otherwise, so the picture never reads as "left".
                is CallEvent.StreamReplaced -> {
                    val prior = call.attendees[event.member] ?: Attendee(event.member)
                    call.copy(attendees = call.attendees + (event.member to prior.copy(streamId = streamIdBytes(event.newStreamId))))
                }
            }
        }
    }

    private fun updateCall(change: (CallState.InCall) -> CallState.InCall) {
        _state.update { if (it is CallState.InCall) change(it) else it }
    }

    companion object {
        const val PLUMBING = "call.plumbing"

        /**
         * An event's `stream_id` as the frames' raw 32 bytes, when it is 64 hex
         * characters. Anything else leaves the tile's stream unset until its first
         * frame names it, so a format we did not expect costs nothing.
         */
        internal fun streamIdBytes(text: String?): ByteArray? {
            if (text == null || text.length != 64 || !text.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return ByteArray(32) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }
}

sealed interface CallState {
    data object Idle : CallState
    data object Joining : CallState

    data class InCall(
        val callId: String,
        val myStreamId: String,
        val epoch: Long,
        val frameBudgetBytes: Int,
        val serviceTier: String?,
        /** Keyed by member key id; one tile each. */
        val attendees: Map<String, Attendee>,
        /** Members who left, and why, so their tile can say it. */
        val departed: Map<String, LeaveReason> = emptyMap(),
        val notice: CallNotice? = null,
        val lastFrame: ReceivedFrame? = null,
        /** The node said `ready`. Nothing is published before it (CIRISServer d662d99a). */
        val ready: Boolean = false,
        /**
         * The node fragments frames itself (Edge 45.1, `ready.fragmenting`): the budget
         * is then information, and a whole frame of any size goes. Read from the node,
         * never assumed from a version.
         */
        val fragmenting: Boolean = false,
    ) : CallState

    /** The node said no, by id (`call.not_a_member`, `call.anonymous_mode`, …). */
    data class Refused(val reasonId: String) : CallState

    /** This device is out of the call; [reason] tells "you were removed" from "you left". */
    data class Left(val reason: LeaveReason) : CallState
}

data class Attendee(
    /** The NODE key the stream arrives from (CIRISServer: Edge names a stream by its link). */
    val member: String,
    val streamId: ByteArray? = null,
    val sendingAudio: Boolean = false,
    val sendingVideo: Boolean = false,
    /** The node's reason when it paused this member's video, said on the tile. */
    val videoPaused: String? = null,
    /**
     * The PERSON who owns that node, from the server's roster, or null until it is
     * bound: the tile then reads "someone in this room". Name them from contacts.
     */
    val person: String? = null,
) {
    override fun equals(other: Any?) = other is Attendee && member == other.member &&
        (streamId?.contentEquals(other.streamId) ?: (other.streamId == null)) &&
        sendingAudio == other.sendingAudio && sendingVideo == other.sendingVideo && videoPaused == other.videoPaused &&
        person == other.person
    override fun hashCode() = listOf(member, streamId?.contentHashCode(), sendingAudio, sendingVideo, videoPaused, person).hashCode()
}

sealed interface CallNotice {
    data class OverBudget(val budget: Int) : CallNotice
    data class KeepRefused(val reasonId: String) : CallNotice
    data class PublishRefused(val reason: String, val budget: Int?) : CallNotice
}

/** CSD-134: why someone left. A word this client does not know reads as [LOST], never as a choice. */
enum class LeaveReason(val wire: String) {
    LEFT("left"), BACKGROUNDED("backgrounded"), REMOVED("removed"), LOST("lost");

    companion object {
        fun of(wire: String?): LeaveReason = entries.firstOrNull { it.wire == wire } ?: LOST
    }
}

/** The socket's text events (CSD-134 §3): one JSON object each, members as key ids only. */
sealed interface CallEvent {
    /** The node is ready for this device's frames, at this budget; sent again when the budget changes. */
    data class Ready(val frameBudgetBytes: Int, val fragmenting: Boolean = false) : CallEvent
    data class AttendeeJoined(val member: String, val streamId: String?, val person: String? = null) : CallEvent
    data class AttendeeLeft(val member: String, val streamId: String?, val left: LeaveReason, val person: String? = null) : CallEvent
    data class EpochRolled(val epoch: Long) : CallEvent
    data class TierChanged(val tier: String) : CallEvent
    data class VideoPaused(val member: String, val reason: String) : CallEvent
    data class KeepRefused(val reasonId: String) : CallEvent
    data class PublishRefused(val reason: String, val budget: Int?) : CallEvent
    /**
     * A codec change (CC, 2026-11-10): the member's new stream replaces the old one
     * in the same membership, make-before-break. The tile swaps in place; nobody left.
     */
    data class StreamReplaced(val member: String, val oldStreamId: String?, val newStreamId: String?) : CallEvent

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null for an unreadable or unknown event: a newer node's event is skipped, never fatal. */
        fun parse(text: String): CallEvent? {
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            return when (str("type")) {
                "ready" -> (o["frame_budget_bytes"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let {
                    Ready(it, (o["fragmenting"] as? JsonPrimitive)?.contentOrNull == "true")
                }
                "attendee_joined" -> str("member")?.let { AttendeeJoined(it, str("stream_id"), str("person")) }
                "attendee_left" -> str("member")?.let { AttendeeLeft(it, str("stream_id"), LeaveReason.of(str("left")), str("person")) }
                "epoch_rolled" -> (o["epoch"] as? JsonPrimitive)?.longOrNull?.let { EpochRolled(it) }
                "tier_changed" -> str("tier")?.let { TierChanged(it) }
                "video_paused" -> str("member")?.let { VideoPaused(it, str("reason") ?: "") }
                "keep_refused" -> str("reason_id")?.let { KeepRefused(it) }
                "publish_refused" -> PublishRefused(str("reason") ?: "", (o["budget"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull())
                "stream_replaced" -> str("member")?.let { StreamReplaced(it, str("old_stream_id"), str("new_stream_id")) }
                else -> null
            }
        }
    }
}

/** `POST /v1/chat/{room}/call` answer (CSD-134 §3). */
data class CallJoinAnswer(
    val callId: String,
    val streamId: String,
    val epoch: Long,
    val frameBudgetBytes: Int,
    val serviceTier: String?,
    val attendees: List<String>,
) {
    companion object {
        /**
         * Read leniently: an attendee may arrive as a bare key id or as an object
         * with `member` or `key_id`, until the routes are on a tag.
         */
        fun parse(o: JsonObject): CallJoinAnswer {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val attendees = (o["attendees"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: emptyList<JsonElement>())
                .mapNotNull { a ->
                    (a as? JsonPrimitive)?.contentOrNull
                        ?: runCatching { a.jsonObject }.getOrNull()?.let { (it["member"] ?: it["key_id"])?.jsonPrimitive?.contentOrNull }
                }
            return CallJoinAnswer(
                callId = str("call_id") ?: "",
                streamId = str("stream_id") ?: "",
                epoch = (o["epoch"] as? JsonPrimitive)?.longOrNull ?: 0,
                frameBudgetBytes = (o["frame_budget_bytes"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                serviceTier = str("service_tier"),
                attendees = attendees,
            )
        }
    }
}

/** A refusal by id from the call routes. */
class CallRefusal(val reasonId: String, detail: String? = null) : Exception(detail ?: reasonId)

/** The node side, injected so the session is driven by a fake in tests. */
interface CallTransport {
    suspend fun join(room: String, video: Boolean): CallJoinAnswer
    suspend fun leave(room: String)
    suspend fun openFrames(room: String): CallSocket
}

interface CallSocket {
    val incoming: Flow<CallSocketMessage>
    fun send(bytes: ByteArray)
    fun close()
}

sealed interface CallSocketMessage {
    class Binary(val bytes: ByteArray) : CallSocketMessage
    class Text(val text: String) : CallSocketMessage
    /** [reason] is the close reason: this device's own leave reason when the node ended it. */
    class Closed(val reason: String?) : CallSocketMessage
}
