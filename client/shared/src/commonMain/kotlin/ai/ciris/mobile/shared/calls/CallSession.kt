package ai.ciris.mobile.shared.calls

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ai.ciris.mobile.shared.platform.PlatformLogger

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

    /**
     * [relay] is this device's choice to carry others' media (CSD-134 §2.1, the
     * relay switch): null leaves it to the node, which decides by what it is.
     */
    fun join(video: Boolean, relay: Boolean? = null, alert: CallAlert? = null) {
        join(video, alert, suspend { relay })
    }

    /** As above, where the answer has to be read first (the person's stored choice). */
    fun join(video: Boolean, relay: suspend () -> Boolean?) = join(video, null, relay)

    /**
     * [alert] is how the room's other people hear of the call, the opener's choice
     * (CIRISServer 0.5.255): null leaves it to the node (a room of at most four rings,
     * a bigger one is told). Joining a call already live alerts nobody, whatever is asked.
     */
    fun join(video: Boolean, alert: CallAlert?, relay: suspend () -> Boolean?) {
        if (_state.value is CallState.Joining || _state.value is CallState.InCall) return
        _state.value = CallState.Joining
        scope.launch {
            val say = relay()
            // Left while it was read: nothing was asked of the node, and nothing is.
            if (_state.value !is CallState.Joining) return@launch
            val answer = try {
                transport.join(room, video, say, alert)
            } catch (e: CallRefusal) {
                PlatformLogger.w(TAG, "[join] refused reason_id=${e.reasonId}")
                _state.value = CallState.Refused(e.reasonId, e.maxPeople)
                return@launch
            }
            PlatformLogger.i(
                TAG,
                "[join] answered call_id=${answer.callId} attendees=${answer.attendees.size} " +
                    "video_allowed=${answer.videoAllowed} budget=${answer.frameBudgetBytes}",
            )
            val s = try {
                transport.openFrames(room)
            } catch (e: Exception) {
                // This was silent, so a node that logged `left why="left"` right after every join
                // (2026-11-21) left nothing in the app's log to say the socket was why.
                PlatformLogger.w(TAG, "[join] frames socket did not open, leaving: ${e::class.simpleName}: ${redactTicket(e.message)}")
                runCatching { transport.leave(room) }
                _state.value = CallState.Refused(PLUMBING)
                return@launch
            }
            PlatformLogger.i(TAG, "[join] frames socket open")
            socket = s
            _state.value = CallState.InCall(
                callId = answer.callId,
                myStreamId = answer.streamId,
                epoch = answer.epoch,
                frameBudgetBytes = answer.frameBudgetBytes,
                serviceTier = answer.serviceTier,
                attendees = answer.attendees.associateWith { Attendee(it, person = answer.attendeePeople[it]) },
                videoAllowed = answer.videoAllowed,
                videoMaxPeople = answer.videoMaxPeople,
                relaying = answer.relay?.relaying,
                relayingFor = answer.relay?.relayingFor ?: 0,
                relaySeat = answer.relay?.serveSeat ?: false,
                alert = answer.alert,
            )
            reader = scope.launch { s.incoming.collect { onMessage(it) } }
        }
    }

    fun leave(reason: LeaveReason = LeaveReason.LEFT) {
        val wasIn = _state.value is CallState.InCall
        PlatformLogger.i(TAG, "[leave] reason=$reason in_call=$wasIn")
        reader?.cancel()
        socket?.close()
        socket = null
        if (wasIn) scope.launch { runCatching { transport.leave(room, reason) } }
        _state.value = CallState.Left(reason)
    }

    /**
     * Turn relaying for others on or off, live (POST …/call/relay; edge
     * `Call::set_relay`). The node's answer is the truth: on a CIRIS public
     * server it stays off and says `serve_seat`, and the switch goes away.
     * A [RelayAsk.FAILED] one may be tried again; a [RelayAsk.REFUSED] one may not.
     */
    suspend fun setRelay(on: Boolean): RelayAsk {
        if (_state.value !is CallState.InCall) return RelayAsk.REFUSED
        return try {
            val a = transport.setRelay(room, on)
            PlatformLogger.i(TAG, "[relay] asked=$on relaying=${a.relaying} for=${a.relayingFor} serve_seat=${a.serveSeat}")
            updateCall { it.copy(relaying = a.relaying, relayingFor = a.relayingFor, relaySeat = a.serveSeat) }
            RelayAsk.ANSWERED
        } catch (e: CancellationException) {
            throw e
        } catch (e: CallRefusal) {
            PlatformLogger.w(TAG, "[relay] refused reason_id=${e.reasonId}")
            RelayAsk.REFUSED
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[relay] failed: ${e::class.simpleName}")
            RelayAsk.FAILED
        }
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
                PlatformLogger.w(TAG, "[socket] closed by the node, reason=${message.reason}")
                socket = null
                // A `call_ended` already said why; the close that follows must not blur it to "lost".
                if (_state.value !is CallState.Left) _state.value = CallState.Left(LeaveReason.of(message.reason))
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
        if (event is CallEvent.CallEnded) {
            // Before the socket closes: the event names why; the close reason only repeats it.
            PlatformLogger.i(TAG, "[ended] ended=${event.ended} reason_id=${event.reasonId ?: "-"}")
            reader?.cancel()
            socket?.close()
            socket = null
            // A ring nobody answered ends the call from the node's side: keep why, so the
            // screen says "nobody answered" rather than only "the call ended".
            val noAnswer = (_state.value as? CallState.InCall)?.noAnswer
            _state.value = CallState.Left(LeaveReason.of(event.ended), event.maxPeople, noAnswer)
            return
        }
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
                is CallEvent.MediaPathFailed -> {
                    val f = event.failure
                    PlatformLogger.w(
                        TAG,
                        "[path] no media path to ${f.member}: cause=${f.cause} detail=${f.detail ?: "-"} retryable=${f.retryable} " +
                            "local=${f.localNat?.legacyClass ?: "-"} peer=${f.peerNat?.legacyClass ?: "-"} relay=${f.relay ?: "-"} " +
                            "tried=" + f.tried.joinToString(",") { "${it.kind}:${it.outcome}" },
                    )
                    call.copy(pathFailures = call.pathFailures + (f.member to f), pathRestored = call.pathRestored - f.member)
                }
                is CallEvent.MediaPathRestored -> {
                    val r = event.restored
                    PlatformLogger.i(TAG, "[path] media path to ${r.member} restored via=${r.via ?: "-"} through=${r.through ?: "-"}")
                    call.copy(
                        pathFailures = call.pathFailures - r.member,
                        pathRestored = call.pathRestored + (r.member to r),
                    )
                }
                is CallEvent.RelayStatus -> {
                    PlatformLogger.i(TAG, "[relay] relaying=${event.relaying} for=${event.relayingFor} people")
                    call.copy(relaying = event.relaying, relayingFor = event.relayingFor)
                }
                // Handled before this: the call is over, so the state is not InCall.
                is CallEvent.CallEnded -> call
                is CallEvent.Rung -> {
                    PlatformLogger.i(TAG, "[ring] rung " + event.members.joinToString(",") { "${it.person ?: it.member}:${it.state.wire ?: "other"}" })
                    call.copy(rung = call.rung + event.members.associate { (it.person ?: it.member) to it.state })
                }
                is CallEvent.Declined -> {
                    PlatformLogger.i(TAG, "[ring] declined by ${event.person ?: event.member} reason=${event.reason}")
                    val st = if (event.reason == "busy") RungState.BUSY else RungState.DECLINED
                    call.copy(rung = call.rung + ((event.person ?: event.member) to st))
                }
                is CallEvent.NoAnswerEvent -> {
                    PlatformLogger.i(TAG, "[ring] no answer why=${event.why}")
                    call.copy(noAnswer = event.why)
                }
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
        private const val TAG = "CallSession"
        const val PLUMBING = "call.plumbing"

        /** Why video is not sent in a call of more than [CallState.InCall.videoMaxPeople] people (CIRISServer 0.5.246). */
        const val VIDEO_TWO_PEOPLE_ONLY = "call.video_two_people_only"

        /** Why one member's video does not reach this device: the route to them carries audio only (CIRISServer 0.5.246). */
        const val RELAY_AUDIO_ONLY = "call.relay_canonical_audio_only"
        /** The call has as many people as the node carries (CIRISServer 0.5.254); `max_people` rides with it. */
        const val CALL_FULL = "call.full"

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
        /**
         * Members this device could not reach directly, and why (CSD-134 §2.1; edge
         * 45.1.11 via CIRISServer 0.5.254). The latest report per member; a restore
         * clears it. Nobody is removed from the call for it: a leg can come back.
         */
        val pathFailures: Map<String, MediaPathFailure> = emptyMap(),
        /** Members whose path came back, and how: direct, or through another member. */
        val pathRestored: Map<String, PathRestored> = emptyMap(),
        /** Whether this device is relaying others' media, once the node says; null before. */
        val relaying: Boolean? = null,
        /** How many PEOPLE (not nodes) this device relays for; the node maps nodes to owners. */
        val relayingFor: Int = 0,
        /** This node is a CIRIS public server: it never relays calls, so there is no switch. */
        val relaySeat: Boolean = false,
        /**
         * What happened when this call rang the room's other people (CIRISServer 0.5.255),
         * by person key: ringing, busy, declined, unreachable, refused or in_call. Empty
         * until the node says; a call joined into one already going never rings.
         */
        val rung: Map<String, RungState> = emptyMap(),
        /** Nobody answered: everyone rung declined or was busy, or 45 s passed with nobody joining. */
        val noAnswer: NoAnswer? = null,
        /** How the node told the room, from the join answer; null when this joined a call already going. */
        val alert: CallAlert? = null,
    ) : CallState

    /** The node said no, by id (`call.not_a_member`, `call.anonymous_mode`, …). */
    /** [maxPeople] is the node's cap, where the refusal is `call.full`. */
    data class Refused(val reasonId: String, val maxPeople: Int? = null) : CallState

    /** This device is out of the call; [reason] tells "you were removed" from "you left". */
    data class Left(val reason: LeaveReason, val maxPeople: Int? = null, val noAnswer: NoAnswer? = null) : CallState
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

/**
 * CSD-134 §2.1: why the media path to one member could not be set up. Every field
 * is the node's token, verbatim; the screen turns them into sentences by literal
 * key and shows anything it does not recognise raw, under Details.
 */
data class MediaPathFailure(
    val member: String,
    /** A closed set ([MediaPathText.CAUSES]); one this client does not know reads as `other`. */
    val cause: String,
    val reasonId: String?,
    /** Edge's own reading of whether trying again could help. */
    val retryable: Boolean,
    val retryAfterS: Int?,
    /** A stable snake_case token, always set for `other`; shown raw, never as a sentence. */
    val detail: String?,
    val localNat: NatReport?,
    /** The PEER's own claim about its network, on its clock: "as they report it". */
    val peerNat: NatReport?,
    /** `none_available`, `refused_by_policy` (only a server offered; servers never relay calls), or a newer token. */
    val relay: String?,
    val tried: List<PathAttempt>,
    /** The node's name for the member's owner, when it sends one; else the attendee map names them. */
    val person: String? = null,
)

/** One network's NAT behaviour (RFC 4787 axes), as edge measured or the peer reported it. */
data class NatReport(
    /** The one the sentence uses: open, full_cone, restricted, port_restricted, symmetric, unknown. */
    val legacyClass: String,
    val mapping: String? = null,
    val allocation: String? = null,
    val filtering: String? = null,
    val portPreserving: Boolean? = null,
    /** held_pcp, held_nat_pmp, held_upnp, none, not_tried. */
    val routerMapping: String? = null,
    val family: String? = null,
    val evidence: String? = null,
    val measuredAt: Long? = null,
) {
    val portHeld: Boolean get() = routerMapping?.startsWith("held_") == true
}

/** One way edge tried to reach the member, and what came of it. Details only. */
data class PathAttempt(
    val kind: String,
    val outcome: String,
    val attempts: Int? = null,
    val elapsedMs: Long? = null,
    val knocksSent: Int? = null,
    val sockets: Int? = null,
)

/** CSD-134: why someone left. A word this client does not know reads as [LOST], never as a choice. */
enum class LeaveReason(val wire: String) {
    LEFT("left"), BACKGROUNDED("backgrounded"), REMOVED("removed"), LOST("lost"),

    /** Edge 45.2.0 `ended:expired`: an ad-hoc call's community expired. */
    EXPIRED("expired"),

    /**
     * This device stopped carrying call media mid-call (edge 45.2.0
     * `ended:<token>`): it was set to shaped links only, or to anonymous mode.
     * Said with the same words as the join refusal, and not undone by a tap.
     */
    SHAPED_ONLY("shaped_only_no_media"),
    ANONYMOUS("onion_anonymous_no_media"),

    /**
     * This join made the call more people than the node carries (CIRISServer
     * 0.5.254: four). The people already there stay. Rejoining may work: two who
     * joined in the same seconds can both be turned away.
     */
    FULL("call_full"),

    /**
     * The call rang and nobody joined within 45 s, so the node left it (CIRISServer
     * 0.5.255: `call_ended {ended: "no_answer", reason_id: "call.no_answer"}`).
     */
    NO_ANSWER("no_answer");

    companion object {
        /**
         * Reads this client's words, edge's end tokens (`hung_up`, `revoked`, with or
         * without `ended:`) and the socket's close reason, which is the bare token.
         */
        fun of(wire: String?): LeaveReason = when (val w = wire?.removePrefix("ended:")) {
            "hung_up" -> LEFT
            "revoked" -> REMOVED
            else -> entries.firstOrNull { it.wire == w } ?: LOST
        }
    }
}

/** A path to [member] came back (edge 45.2.0): direct, or relayed by the member [through]. */
data class PathRestored(
    val member: String,
    /** A path kind; `peer_relay` means another member relays it, anything else reads "direct". */
    val via: String?,
    /** The relaying member's node key id, the same id space as attendees; null when direct. */
    val through: String? = null,
    /** The node's name for [member]'s owner, when it sends one. */
    val person: String? = null,
    /** The node's name for [through]'s owner, when it sends one. */
    val throughPerson: String? = null,
) {
    val direct: Boolean get() = via != "peer_relay"
}

/** `POST …/call/relay` answer: what the node is doing now, not what was asked. */
data class RelayAnswer(val relaying: Boolean, val relayingFor: Int, val serveSeat: Boolean) {
    companion object {
        /** The join answer's `relay` object (CIRISServer 0.5.254). Absent on an older node, or without a ruling: null. */
        fun parse(o: JsonObject): RelayAnswer? {
            fun bool(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()
            return RelayAnswer(
                relaying = bool("relaying") ?: return null,
                relayingFor = (o["relaying_for"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                serveSeat = bool("serve_seat") ?: false,
            )
        }
    }
}

/** How asking the node to relay, or to stop, ended. */
enum class RelayAsk { ANSWERED, REFUSED, FAILED }

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
    /** No media path to [failure].member could be set up (CSD-134 §2.1). Sent once per member, again only with changed facts. */
    data class MediaPathFailed(val failure: MediaPathFailure) : CallEvent
    /** A path came up after all; re-sent when the relaying member changes. */
    data class MediaPathRestored(val restored: PathRestored) : CallEvent
    /** Whether this device relays others' media, and for how many people. */
    data class RelayStatus(val relaying: Boolean, val relayingFor: Int) : CallEvent
    /** The call is over for this device ([ended] is edge's token); the socket closes next. */
    /** [maxPeople] is the node's cap, sent with `call_full`. */
    data class CallEnded(val ended: String, val reasonId: String?, val maxPeople: Int? = null) : CallEvent
    /** How each person rung answered the ring, once every node has answered or failed to. */
    data class Rung(val members: List<RungMember>) : CallEvent
    data class Declined(val member: String, val person: String?, val reason: String) : CallEvent
    data class NoAnswerEvent(val why: NoAnswer) : CallEvent

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()

        internal fun natReport(o: JsonObject?): NatReport? = o?.let {
            NatReport(
                legacyClass = it.s("legacy_class") ?: "unknown",
                mapping = it.s("mapping"), allocation = it.s("allocation"), filtering = it.s("filtering"),
                portPreserving = it.b("port_preserving"), routerMapping = it.s("router_mapping"),
                family = it.s("family"), evidence = it.s("evidence"), measuredAt = it.l("measured_at"),
            )
        }

        /**
         * Lenient on purpose: a missing or odd field never drops the event, because a
         * call that fails silently is the defect this event exists to end. A missing
         * `retryable` reads as false, so nothing offers a retry edge did not.
         */
        internal fun mediaPathFailure(member: String, o: JsonObject): MediaPathFailure {
            // CIRISServer 0.5.254 nests the diagnostics under `diag`; 45.1.11's were top level.
            val d = (o["diag"] as? JsonObject) ?: o
            val peer = d["peer_nat"] as? JsonObject
            return MediaPathFailure(
                member = member,
                person = o.s("person"),
                cause = o.s("cause") ?: o.s("reason_id")?.substringAfterLast('.') ?: "other",
                reasonId = o.s("reason_id"),
                retryable = o.b("retryable") ?: false,
                retryAfterS = o.l("retry_after_s")?.toInt(),
                detail = d.s("detail") ?: o.s("detail"),
                localNat = natReport(d["local_nat"] as? JsonObject),
                // Frozen shape: {report, source: "self_reported"}; a bare report is read too.
                peerNat = natReport((peer?.get("report") as? JsonObject) ?: peer?.takeIf { "legacy_class" in it }),
                relay = o.s("relay"),
                tried = (d["tried"] as? JsonArray).orEmpty().mapNotNull { e ->
                    (e as? JsonObject)?.let {
                        PathAttempt(
                            kind = it.s("kind") ?: return@let null,
                            outcome = it.s("outcome") ?: "unknown",
                            attempts = it.l("attempts")?.toInt(), elapsedMs = it.l("elapsed_ms"),
                            knocksSent = it.l("knocks_sent")?.toInt(), sockets = it.l("sockets")?.toInt(),
                        )
                    }
                },
            )
        }

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
                "media_path_failed" -> str("member")?.let { m -> MediaPathFailed(mediaPathFailure(m, o)) }
                "media_path_restored" -> str("member")?.let {
                    MediaPathRestored(PathRestored(it, str("via"), str("through"), str("person"), str("through_person")))
                }
                // `relaying_for` is PEOPLE (the server maps nodes to owners); `relaying_for_nodes` is diag.
                "relay_status" -> (o["relaying"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()
                    ?.let { RelayStatus(it, int("relaying_for") ?: 0) }
                "call_ended" -> str("ended")?.let { CallEnded(it, str("reason_id"), int("max_people")) }
                "ringing" -> Rung(
                    (o["members"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                        val m = e as? JsonObject ?: return@mapNotNull null
                        fun s(k: String) = (m[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        val member = s("member") ?: return@mapNotNull null
                        RungMember(member, s("person"), RungState.of(s("state")))
                    },
                )
                "notified" -> Rung(
                    (o["members"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                        val m = e as? JsonObject ?: return@mapNotNull null
                        fun s(k: String) = (m[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        val member = s("member") ?: return@mapNotNull null
                        RungMember(member, s("person"), RungState.of(s("state")))
                    },
                )
                "declined" -> str("member")?.let { Declined(it, str("person"), str("reason") ?: "declined") }
                "no_answer" -> NoAnswerEvent(if (str("why") == "timeout") NoAnswer.TIMEOUT else NoAnswer.DECLINED)
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
    /** Whether the node is relaying others' media as of this join; null from a node before 0.5.254. */
    val relay: RelayAnswer? = null,
    /** Each attendee node's owner (`attendee_people`), so a tile can name the person. */
    val attendeePeople: Map<String, String> = emptyMap(),
    /** The alert the node used (CIRISServer 0.5.255); null for a join into a live call, or from an older node. */
    val alert: CallAlert? = null,
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
                relay = (o["relay"] as? JsonObject)?.let { RelayAnswer.parse(it) },
                attendeePeople = (o["attendee_people"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: emptyList<JsonElement>())
                    .mapNotNull { e ->
                        val p = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
                        val m = (p["member"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        val who = (p["person"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        m to who
                    }.toMap(),
                alert = CallAlert.of(str("alert")),
            )
        }
    }
}

/** A refusal by id from the call routes. */
class CallRefusal(val reasonId: String, detail: String? = null, val maxPeople: Int? = null) : Exception(detail ?: reasonId)

/** The node side, injected so the session is driven by a fake in tests. */
interface CallTransport {
    suspend fun join(room: String, video: Boolean): CallJoinAnswer

    /** Join, saying whether this device relays for others; null leaves it to the node. */
    suspend fun join(room: String, video: Boolean, relay: Boolean?): CallJoinAnswer = join(room, video)
    /** With the opener's [alert] (CIRISServer 0.5.255); a transport that cannot say it leaves it to the node. */
    suspend fun join(room: String, video: Boolean, relay: Boolean?, alert: CallAlert?): CallJoinAnswer = join(room, video, relay)
    suspend fun leave(room: String)

    /** POST …/call/relay `{relay}`: switch relaying live. A node without the route refuses by name. */
    suspend fun setRelay(room: String, relay: Boolean): RelayAnswer = throw CallRefusal(RELAY_UNAVAILABLE)

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
        const val RELAY_UNAVAILABLE = "call.relay_unavailable"
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

/**
 * An exception from opening the frames socket may quote the request URL, and the
 * URL carries the single-use ticket. Logs get the message with the ticket removed.
 */
internal fun redactTicket(message: String?): String? =
    message?.replace(Regex("(ticket=)[^&\\s\"')]+"), "$1<redacted>")

/** One person rung, as the node reports it on the caller's socket. */
data class RungMember(val member: String, val person: String?, val state: RungState)

/** How a ring went for one person (CIRISServer 0.5.255 `member_states`). An unknown word is [OTHER]. */
enum class RungState(val wire: String?) {
    RINGING("ringing"),
    /** Told of the call, not rung (`alert: notify`). */
    NOTIFIED("notified"),
    IN_CALL("in_call"),
    BUSY("busy"),
    DECLINED("declined"),
    /** No path to them now, or no answer within 8 s. */
    UNREACHABLE("unreachable"),
    /** Their node refused the ring, e.g. it does not hold this room yet. */
    REFUSED("refused"),
    OTHER(null);

    companion object {
        fun of(s: String?): RungState = entries.firstOrNull { it.wire != null && it.wire == s } ?: OTHER
    }
}

/** Why nobody answered: everyone rung declined or was busy, or the 45 s ran out. */
enum class NoAnswer { DECLINED, TIMEOUT }

/**
 * How the room's other people hear of a call, the opener's choice (CIRISServer 0.5.255,
 * Eric's ruling): [RING] rings them, [NOTIFY] tells them without ringing, [OPEN] sends
 * nothing. An unknown word reads as null, which leaves it to the node.
 */
enum class CallAlert(val wire: String) {
    OPEN("open"), NOTIFY("notify"), RING("ring");

    companion object {
        fun of(s: String?): CallAlert? = entries.firstOrNull { it.wire == s }

        /** The node's own default when none is asked: a room of at most four rings, a bigger one is told. */
        const val RING_UP_TO_PEOPLE = 4
        fun defaultFor(roomPeople: Int?): CallAlert =
            if ((roomPeople ?: 2) <= RING_UP_TO_PEOPLE) RING else NOTIFY
    }
}
