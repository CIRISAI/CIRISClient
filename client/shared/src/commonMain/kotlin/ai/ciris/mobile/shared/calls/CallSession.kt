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
                videoAllowed = answer.videoAllowed,
                videoMaxPeople = answer.videoMaxPeople,
            )
            reader = scope.launch { s.incoming.collect { onMessage(it) } }
        }
    }

    fun leave(reason: LeaveReason = LeaveReason.LEFT) {
        val wasIn = _state.value is CallState.InCall
        reader?.cancel()
        socket?.close()
        socket = null
        if (wasIn) scope.launch { runCatching { transport.leave(room, reason) } }
        _state.value = CallState.Left(reason)
    }

    /**
     * Publish one frame. Over the call's budget it is not sent, and the state says
     * so: the node would refuse it, and a frame cut to fit is a corrupt one.
     */
    fun publish(frame: OutgoingFrame): Boolean {
        val call = _state.value as? CallState.InCall ?: return false
        if (!call.ready) return false
        // The node refuses video while the call is over its size for video; it is not sent at all.
        if (!call.videoAllowed && frame.codecId != CallFrames.CODEC_OPAQUE) return false
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
                is CallEvent.StreamAdded -> call
                is CallEvent.Stranded -> call.copy(stranded = true)
                is CallEvent.VideoRestored -> call.attendees[event.member]?.let {
                    call.copy(attendees = call.attendees + (event.member to it.copy(videoPaused = null)))
                } ?: call
                is CallEvent.KeepRefused -> call.copy(notice = CallNotice.KeepRefused(event.reasonId))
                // The refusal says the same as the event, so a missed event is not a camera
                // that goes on encoding for nobody.
                is CallEvent.PublishRefused -> if (event.reasonId == VIDEO_TWO_PEOPLE_ONLY) {
                    call.copy(videoAllowed = false, videoMaxPeople = event.maxPeople ?: call.videoMaxPeople)
                } else {
                    call.copy(notice = CallNotice.PublishRefused(event.reason, event.budget))
                }
                is CallEvent.VideoAllowed -> call.copy(videoAllowed = event.allowed, videoMaxPeople = event.maxPeople ?: call.videoMaxPeople)
                // Same tile, new stream: what it was sending carries on until the new
                // stream's frames say otherwise, so the picture never reads as "left".
                is CallEvent.ShareStarted ->
                    call.copy(shares = call.shares + (event.streamId.orEmpty() to Share(event.member, event.person)))
                is CallEvent.ShareStopped -> call.copy(shares = call.shares - event.streamId.orEmpty())
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

        /** Why video is not sent in a call of more than [CallState.InCall.videoMaxPeople] people (CIRISServer 0.5.246). */
        const val VIDEO_TWO_PEOPLE_ONLY = "call.video_two_people_only"

        /** Why one member's video does not reach this device: the route to them carries audio only (CIRISServer 0.5.246). */
        const val RELAY_AUDIO_ONLY = "call.relay_canonical_audio_only"

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
        /** The node said the call could not follow the room's keys: say so, and offer to join again. */
        val stranded: Boolean = false,
        val lastFrame: ReceivedFrame? = null,
        /** Screens and windows being shared, by their stream id (hex): one tile each. */
        val shares: Map<String, Share> = emptyMap(),
        /** The node said `ready`. Nothing is published before it (CIRISServer d662d99a). */
        val ready: Boolean = false,
        /**
         * The node fragments frames itself (Edge 45.0.0, `ready.fragmenting`): the budget
         * is then information, and a whole frame of any size goes. Read from the node,
         * never assumed from a version.
         */
        val fragmenting: Boolean = false,
        /**
         * False while the call has more people than the node carries video for: it is
         * then audio only, the node refuses every video frame, and none is sent. The
         * node says so on join and again each time it changes. A node that says
         * nothing has no such limit.
         */
        val videoAllowed: Boolean = true,
        /** How many people a call may have and still carry video, when the node says. */
        val videoMaxPeople: Int? = null,
    ) : CallState

    /** The node said no, by id (`call.not_a_member`, `call.anonymous_mode`, …). */
    data class Refused(val reasonId: String) : CallState

    /** This device is out of the call; [reason] tells "you were removed" from "you left". */
    data class Left(val reason: LeaveReason) : CallState
}

/** A member's share: whose it is, so the tile reads "Ada's screen". */
data class Share(val member: String, val person: String?)

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
    /** The node says [member]'s video reaches this device again. */
    data class VideoRestored(val member: String) : CallEvent
    /** A member's media stream started: their frames carry what it is, so nothing changes. */
    data class StreamAdded(val member: String, val streamId: String?) : CallEvent
    /**
     * The call could not follow the room's change of keys ([epoch]): this device may
     * neither hear nor be heard until it joins again. [reason] is the node's
     * diagnostic, never shown.
     */
    data class Stranded(val epoch: Long?, val reason: String?) : CallEvent
    data class TierChanged(val tier: String) : CallEvent
    data class VideoPaused(val member: String, val reason: String) : CallEvent
    data class KeepRefused(val reasonId: String) : CallEvent
    data class PublishRefused(val reason: String, val budget: Int?, val reasonId: String? = null, val maxPeople: Int? = null) : CallEvent
    /** Whether this call carries video now: the node counts the people in it and says when that changes. */
    data class VideoAllowed(val allowed: Boolean, val maxPeople: Int?) : CallEvent
    /**
     * A codec change (CC, 2026-11-10): the member's new stream replaces the old one
     * in the same membership, make-before-break. The tile swaps in place; nobody left.
     */
    data class StreamReplaced(val member: String, val oldStreamId: String?, val newStreamId: String?) : CallEvent
    /** A member started sharing: a second stream and a second tile, "Ada's screen". */
    data class ShareStarted(val member: String, val streamId: String?, val person: String?) : CallEvent
    data class ShareStopped(val member: String, val streamId: String?) : CallEvent

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null for an unreadable or unknown event: a newer node's event is skipped, never fatal. */
        fun parse(text: String): CallEvent? {
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun int(k: String) = str(k)?.toIntOrNull()
            return when (str("type")) {
                "ready" -> (o["frame_budget_bytes"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let {
                    Ready(it, (o["fragmenting"] as? JsonPrimitive)?.contentOrNull == "true")
                }
                "attendee_joined" -> str("member")?.let { AttendeeJoined(it, str("stream_id"), str("person")) }
                "attendee_left" -> str("member")?.let { AttendeeLeft(it, str("stream_id"), LeaveReason.of(str("left")), str("person")) }
                "epoch_rolled" -> (o["epoch"] as? JsonPrimitive)?.longOrNull?.let { EpochRolled(it) }
                "tier_changed" -> str("tier")?.let { TierChanged(it) }
                "keep_refused" -> str("reason_id")?.let { KeepRefused(it) }
                "publish_refused" -> PublishRefused(str("reason") ?: "", int("budget"), str("reason_id"), int("max_people"))
                // `allowed` must be a JSON true or false: anything else is not a ruling either way.
                "video_allowed" -> (o["allowed"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()
                    ?.let { VideoAllowed(it, int("max_people")) }
                // The server's vectors (b08f3eca) name the new stream `new_stream_id`; its
                // 0.5.246 socket was described as sending `stream_id`. Either is read.
                "stream_replaced" -> str("member")?.let { StreamReplaced(it, str("old_stream_id"), str("new_stream_id") ?: str("stream_id")) }
                // 0.5.246: one member's video stopped reaching this device, by id
                // (call.relay_canonical_audio_only: the route carries audio only).
                "video_unavailable" -> str("member")?.let { VideoPaused(it, str("reason_id") ?: "") }
                "video_restored" -> str("member")?.let { VideoRestored(it) }
                "share_started" -> str("member")?.let { ShareStarted(it, str("stream_id"), str("person")) }
                // Edge v45.0.0's names: a second stream (kind `share`) is a shared screen; any
                // other kind is a member's media, which their frames already say (CC rc18:
                // an unknown kind renders as media).
                "stream_added" -> str("member")?.let { m ->
                    if (str("kind") == "share") ShareStarted(m, str("stream_id"), str("person")) else StreamAdded(m, str("stream_id"))
                }
                "stream_ended" -> str("member")?.let { ShareStopped(it, str("stream_id")) }
                "stranded" -> Stranded((o["epoch"] as? JsonPrimitive)?.longOrNull, str("reason"))
                "share_stopped" -> str("member")?.let { ShareStopped(it, str("stream_id")) }
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
    /** False when the call already has more people than the node carries video for. Absent from the node means no limit. */
    val videoAllowed: Boolean = true,
    val videoMaxPeople: Int? = null,
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
                videoAllowed = (o["video_allowed"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                videoMaxPeople = str("video_max_people")?.toIntOrNull(),
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

    /** Leave, saying why: the node passes `backgrounded` on to the others (CSD-134). */
    suspend fun leave(room: String, reason: LeaveReason) = leave(room)
    suspend fun openFrames(room: String): CallSocket

    /**
     * Start sharing a screen or window (CSD-134): a second own stream with no
     * `replaces`, its frames on their own socket. Needs Edge 45.1 on the node; a
     * node without the route refuses by name.
     */
    suspend fun startShare(room: String): CallShareAnswer = throw CallRefusal(SHARE_UNAVAILABLE)
    suspend fun openShareFrames(room: String): CallSocket = throw CallRefusal(SHARE_UNAVAILABLE)
    suspend fun stopShare(room: String) = Unit

    companion object {
        const val SHARE_UNAVAILABLE = "call.share_unavailable"
    }
}

/** `POST /v1/chat/{room}/call/share` answer. */
data class CallShareAnswer(val streamId: String, val frameBudgetBytes: Int)

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

/**
 * Sharing this device's screen or one window (CSD-134): its own stream and its
 * own socket. Frames from [source] go through [encoder] and out; nothing comes
 * back on this socket (receivers see the share on their main call socket).
 */
class CallShare(
    private val room: String,
    private val transport: CallTransport,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Sharing(val streamId: String, val budget: Int, val fragmenting: Boolean = false) : State
        data class Refused(val reasonId: String) : State
        data object Stopped : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var socket: CallSocket? = null
    private var reader: Job? = null

    fun start() {
        if (_state.value is State.Starting || _state.value is State.Sharing) return
        _state.value = State.Starting
        scope.launch {
            val answer = try { transport.startShare(room) } catch (e: CallRefusal) {
                _state.value = State.Refused(e.reasonId); return@launch
            }
            val s = try { transport.openShareFrames(room) } catch (e: Exception) {
                runCatching { transport.stopShare(room) }
                _state.value = State.Refused(CallSession.PLUMBING); return@launch
            }
            socket = s
            _state.value = State.Sharing(answer.streamId, answer.frameBudgetBytes)
            reader = scope.launch {
                s.incoming.collect { m ->
                    if (m is CallSocketMessage.Text) {
                        val e = CallEvent.parse(m.text)
                        val cur = _state.value
                        if (e is CallEvent.Ready && cur is State.Sharing) {
                            _state.value = cur.copy(budget = e.frameBudgetBytes, fragmenting = e.fragmenting)
                        }
                    }
                    if (m is CallSocketMessage.Closed) _state.value = State.Stopped
                }
            }
        }
    }

    fun publish(frame: OutgoingFrame): Boolean {
        val s = _state.value as? State.Sharing ?: return false
        if (!s.fragmenting && frame.payload.size > s.budget) return false
        socket?.send(CallFrames.encode(frame)) ?: return false
        return true
    }

    fun stop() {
        reader?.cancel()
        socket?.close()
        socket = null
        if (_state.value is State.Sharing) scope.launch { runCatching { transport.stopShare(room) } }
        _state.value = State.Stopped
    }
}
