package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.CallKind
import ai.ciris.mobile.shared.platform.CallPermissionState
import ai.ciris.mobile.shared.platform.MediaSource
import ai.ciris.mobile.shared.platform.MediaUnavailable
import ai.ciris.mobile.shared.platform.callPermissionStep
import ai.ciris.mobile.shared.platform.rememberCallMediaPlatform
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A call in one room (CSD-134): choose voice or video, then the permissions that
 * call needs, asked at that moment and never before.
 *
 * What is built: the choice, the microphone and camera permissions on Android and
 * iOS, the fall-back to a voice call when the camera is refused, and the save
 * state. What is not: capture, encoding and the connection to the node, which
 * has no call routes yet (CIRISServer#859). So a ready call says it cannot
 * connect, and saving is listed as not ready (CIRISPersist#1067). The entry is
 * shown only on a node that declares calls, so nobody meets this screen as a
 * dead end before both halves exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(
    roomLabel: String,
    /** Set when the person answered a ring: the screen joins at once, as voice or video. */
    answer: CallKind? = null,
    /** How many people the room has, for how the room hears of the call by default; null for a pair. */
    roomPeople: Int? = null,
    onBack: () -> Unit,
    /** The call itself. Null on a node without the call routes: the screen then says it cannot connect. */
    call: ai.ciris.mobile.shared.viewmodels.CallViewModel? = null,
    /** Names a person key from this device's contacts; null when they are not a contact. */
    nameOf: (String) -> String? = { null },
) {
    val media = rememberCallMediaPlatform()
    var state by remember { mutableStateOf<CallPermissionState?>(null) }
    // How the room hears of the call, the opener's choice; the node's own default
    // until the person picks (CIRISServer 0.5.255). Answering alerts nobody.
    var alert by remember(roomPeople) { mutableStateOf(ai.ciris.mobile.shared.calls.CallAlert.defaultFor(roomPeople)) }

    // One attempt: ask what is due, then step again with what was asked.
    fun advance(kind: CallKind, asked: Set<MediaSource>) {
        val next = callPermissionStep(
            kind,
            microphone = media.access(MediaSource.MICROPHONE),
            camera = media.access(MediaSource.CAMERA),
            asked = asked,
        )
        state = next
        if (next is CallPermissionState.Asking) {
            media.requestAccess(next.source) { advance(kind, asked + next.source) }
        }
    }

    // Answering is the choice already made: go straight to asking for the devices.
    androidx.compose.runtime.LaunchedEffect(answer) {
        if (answer != null && state == null) advance(answer, emptySet())
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(roomLabel.ifBlank { localizedString("mobile.call_title") }) },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testableClickable("btn_call_back") { onBack() },
                        ) {
                            Icon(CIRISIcons.arrowBack, contentDescription = localizedString("common_back"))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()).testable("call_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                null -> {
                    CallChoice(onChoose = { advance(it, emptySet()) })
                    AlertChoice(alert, onChoose = { alert = it })
                }
                is CallPermissionState.Asking -> Text(
                    localizedString(
                        if (s.source == MediaSource.MICROPHONE) "mobile.call_asking_microphone"
                        else "mobile.call_asking_camera"
                    ),
                    modifier = Modifier.testable("call_asking", s.source.name.lowercase()),
                )
                CallPermissionState.MicrophoneDenied -> {
                    Text(
                        localizedString("mobile.call_microphone_denied"),
                        modifier = Modifier.testable("call_microphone_denied"),
                    )
                    OutlinedButton(
                        onClick = { state = null },
                        modifier = Modifier.testableClickable("btn_call_try_again") { state = null },
                    ) { Text(localizedString("mobile.call_try_again")) }
                }
                is CallPermissionState.Unavailable -> Text(
                    localizedString(
                        if (s.reason == MediaUnavailable.NO_DEVICE) "mobile.call_unavailable_no_device"
                        else "mobile.call_unavailable_platform"
                    ),
                    modifier = Modifier.testable("call_unavailable", s.reason.name.lowercase()),
                )
                is CallPermissionState.Ready -> {
                    val video = s.kind == CallKind.VIDEO && !s.audioOnly
                    Text(
                        localizedString(if (video) "mobile.call_ready_video" else "mobile.call_ready_voice"),
                        modifier = Modifier.testable("call_ready", if (video) "video" else "voice"),
                    )
                    if (s.audioOnly) {
                        Text(
                            localizedString("mobile.call_audio_only"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testable("call_audio_only"),
                        )
                    }
                    if (call == null) {
                        Text(
                            localizedString("mobile.call_not_connecting"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testable("call_not_connecting"),
                        )
                    } else {
                        androidx.compose.runtime.LaunchedEffect(call) { call.join(video, if (answer != null) null else alert) }
                        LiveCall(call, nameOf, onBack)
                    }
                }
            }
            CallSaveState()
            remember { ai.ciris.mobile.shared.calls.media.h264Download() }?.let { H264Control(it, call) }
        }
    }
}

/**
 * The person's switch for H.264 video, on a platform where H.264 is a separate
 * download (desktop; phones and the browser use the system's own). It is off
 * until they turn it on: turning it on fetches Cisco's OpenH264 library onto
 * this computer, and turning it off deletes it.
 *
 * Cisco's licence for that library asks for three things of this screen, and
 * they are why it reads as it does: the person can turn the codec on and off
 * here; Cisco's sentence is shown here, word for word and in English in every
 * language; and the licence can be read in full.
 */
@Composable
private fun H264Control(
    h264: ai.ciris.mobile.shared.calls.media.H264Download,
    call: ai.ciris.mobile.shared.viewmodels.CallViewModel?,
) {
    val state by h264.state.collectAsState()
    var showLicence by remember { mutableStateOf(false) }
    val needed = call?.h264Needed?.collectAsState()?.value == true
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testable("card_call_h264", state.name.lowercase()),
    ) {
        if (needed) {
            Text(localizedString("mobile.call_h264_needed"), modifier = Modifier.testable("txt_call_h264_needed"))
        }
        Text(
            localizedString(
                when (state) {
                    ai.ciris.mobile.shared.calls.media.H264Download.State.OFF -> "mobile.call_h264_off"
                    ai.ciris.mobile.shared.calls.media.H264Download.State.DOWNLOADING -> "mobile.call_h264_downloading"
                    ai.ciris.mobile.shared.calls.media.H264Download.State.ON -> "mobile.call_h264_on"
                    ai.ciris.mobile.shared.calls.media.H264Download.State.FAILED_DOWNLOAD -> "mobile.call_h264_failed_download"
                    ai.ciris.mobile.shared.calls.media.H264Download.State.FAILED_CHECK -> "mobile.call_h264_failed_check"
                    ai.ciris.mobile.shared.calls.media.H264Download.State.FAILED_LOAD -> "mobile.call_h264_failed_load"
                }
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testable("txt_call_h264_state"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                ai.ciris.mobile.shared.calls.media.H264Download.State.DOWNLOADING -> Unit
                ai.ciris.mobile.shared.calls.media.H264Download.State.ON -> OutlinedButton(
                    onClick = { h264.turnOff() },
                    modifier = Modifier.testableClickable("btn_call_h264_off") { h264.turnOff() },
                ) { Text(localizedString("mobile.call_h264_turn_off")) }
                else -> {
                    val failed = state != ai.ciris.mobile.shared.calls.media.H264Download.State.OFF
                    OutlinedButton(
                        onClick = { h264.turnOn() },
                        modifier = Modifier.testableClickable("btn_call_h264_on") { h264.turnOn() },
                    ) { Text(localizedString(if (failed) "mobile.call_try_again" else "mobile.call_h264_turn_on")) }
                    // Downloaded and unusable: the way to delete it is here too.
                    if (state == ai.ciris.mobile.shared.calls.media.H264Download.State.FAILED_LOAD) {
                        OutlinedButton(
                            onClick = { h264.turnOff() },
                            modifier = Modifier.testableClickable("btn_call_h264_off") { h264.turnOff() },
                        ) { Text(localizedString("mobile.call_h264_turn_off")) }
                    }
                }
            }
            OutlinedButton(
                onClick = { showLicence = !showLicence },
                modifier = Modifier.testableClickable("btn_call_h264_licence") { showLicence = !showLicence },
            ) { Text(localizedString(if (showLicence) "mobile.call_h264_licence_hide" else "mobile.call_h264_licence")) }
        }
        // Cisco's sentence, exactly: its licence requires these words where the switch is.
        Text(
            ai.ciris.mobile.shared.calls.media.H264Download.ATTRIBUTION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testable("txt_call_h264_attribution"),
        )
        if (showLicence) {
            Text(
                h264.licence,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testable("txt_call_h264_licence"),
            )
        }
    }
}

@Composable
private fun CallChoice(onChoose: (CallKind) -> Unit) {
    Text(localizedString("mobile.call_choose"))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { onChoose(CallKind.VOICE) },
            modifier = Modifier.weight(1f).testableClickable("btn_call_voice") { onChoose(CallKind.VOICE) },
        ) { Text(localizedString("mobile.call_voice")) }
        Button(
            onClick = { onChoose(CallKind.VIDEO) },
            modifier = Modifier.weight(1f).testableClickable("btn_call_video") { onChoose(CallKind.VIDEO) },
        ) { Text(localizedString("mobile.call_video")) }
    }
}

/**
 * How the room's other people hear of this call, if it starts one (CIRISServer 0.5.255,
 * Eric: the opener's choice). The node's default is picked to begin with: a room of at
 * most four rings, a bigger one is told. Joining a call already going alerts nobody.
 */
@Composable
private fun AlertChoice(
    chosen: ai.ciris.mobile.shared.calls.CallAlert,
    onChoose: (ai.ciris.mobile.shared.calls.CallAlert) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testable("opt_call_alert", chosen.wire)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ai.ciris.mobile.shared.calls.CallAlert.RING to "mobile.call_alert_ring",
                ai.ciris.mobile.shared.calls.CallAlert.NOTIFY to "mobile.call_alert_notify",
                ai.ciris.mobile.shared.calls.CallAlert.OPEN to "mobile.call_alert_open",
            ).forEach { (a, key) ->
                androidx.compose.material3.FilterChip(
                    selected = a == chosen,
                    onClick = { onChoose(a) },
                    label = { Text(localizedString(key)) },
                    modifier = Modifier.testableClickable("btn_call_alert_${a.wire}") { onChoose(a) },
                )
            }
        }
        Text(
            localizedString(
                when (chosen) {
                    ai.ciris.mobile.shared.calls.CallAlert.RING -> "mobile.call_alert_ring_says"
                    ai.ciris.mobile.shared.calls.CallAlert.NOTIFY -> "mobile.call_alert_notify_says"
                    ai.ciris.mobile.shared.calls.CallAlert.OPEN -> "mobile.call_alert_open_says"
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testable("txt_call_alert_says"),
        )
    }
}

/**
 * Always on screen, so silence never reads as either answer (CSD-134). Saving is
 * the person's own stream only, and it is not ready until CIRISPersist#1067.
 */
@Composable
private fun CallSaveState() {
    Text(localizedString("mobile.call_not_saved"), modifier = Modifier.testable("txt_call_save_state", "not_saved"))
    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.testable("opt_call_save_mine")) {
        Text(localizedString("mobile.call_save"))
    }
    Text(
        localizedString("mobile.call_save_unavailable"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testable("txt_call_save_unavailable"),
    )
}

/** The call once joined: who is here, what they send, and the controls. */
@Composable
private fun LiveCall(
    call: ai.ciris.mobile.shared.viewmodels.CallViewModel,
    nameOf: (String) -> String?,
    onBack: () -> Unit,
) {
    val state by call.state.collectAsState()
    val muted by call.muted.collectAsState()
    // The app leaving the screen ends the call, and the others are told why
    // (CSD-134). Nothing keeps a call running in the background in v1.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, call) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP &&
                call.state.value is ai.ciris.mobile.shared.calls.CallState.InCall
            ) {
                call.leave(ai.ciris.mobile.shared.calls.LeaveReason.BACKGROUNDED)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    when (val s = state) {
        ai.ciris.mobile.shared.calls.CallState.Idle,
        ai.ciris.mobile.shared.calls.CallState.Joining -> Text(
            localizedString("mobile.call_joining"),
            modifier = Modifier.testable("state_call_connecting"),
        )
        is ai.ciris.mobile.shared.calls.CallState.Refused -> Text(
            if (s.reasonId == ai.ciris.mobile.shared.calls.CallSession.CALL_FULL) fullText(s.maxPeople) else localizedString(s.reasonId),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testable("state_call_refused", s.reasonId),
        )
        is ai.ciris.mobile.shared.calls.CallState.Left -> {
            if (s.reason != ai.ciris.mobile.shared.calls.LeaveReason.NO_ANSWER) s.noAnswer?.let { NoAnswerLine(it) }
            Text(
                if (s.reason == ai.ciris.mobile.shared.calls.LeaveReason.FULL) fullText(s.maxPeople) else localizedString(youLeftKey(s.reason)),
                modifier = Modifier.testable("state_call_left", s.reason.wire),
            )
            // Removed from the room, a setting that refuses calls, or an expired room: not
            // something a tap undoes.
            if (s.reason !in NO_REJOIN) {
                Button(
                    onClick = { call.rejoin() },
                    modifier = Modifier.testableClickable("btn_call_rejoin") { call.rejoin() },
                ) { Text(localizedString("mobile.call_rejoin")) }
            }
        }
        is ai.ciris.mobile.shared.calls.CallState.InCall -> {
            if (s.stranded) {
                Text(
                    localizedString("mobile.call_stranded"),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testable("txt_call_stranded"),
                )
                Button(
                    onClick = { call.leave(); call.rejoin() },
                    modifier = Modifier.testableClickable("btn_call_rejoin_stranded") { call.leave(); call.rejoin() },
                ) { Text(localizedString("mobile.call_rejoin")) }
            }
            val audioUnavailable by call.audioUnavailable.collectAsState()
            if (audioUnavailable) {
                Text(
                    localizedString("mobile.call_audio_unavailable_here"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable("txt_call_audio_unavailable"),
                )
            }
            if (!s.videoAllowed) {
                Text(
                    localizedString(ai.ciris.mobile.shared.calls.CallSession.VIDEO_TWO_PEOPLE_ONLY),
                    modifier = Modifier.testable("txt_call_video_limited", s.videoMaxPeople?.toString() ?: ""),
                )
            }
            val videoUnavailable by call.videoUnavailable.collectAsState()
            if (videoUnavailable) {
                Text(
                    localizedString("mobile.call_video_unavailable_here"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable("txt_call_video_unavailable"),
                )
            }
            // Until the node says ready nothing goes out: say so, rather than look like a
            // call that is up and silent (CSD-134; Eric's call of 2026-11-26).
            // Not ready after a while, and the node has said nothing about why: the call has
            // failed so far, and the person is told, not left looking at a call that seems up.
            var slow by remember(s.callId) { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(s.callId, s.ready) {
                slow = false
                if (!s.ready) { kotlinx.coroutines.delay(CONNECT_PATIENCE_MS); slow = true }
            }
            // A call still ringing someone has not failed to reach them: the ring says where it is.
            // Nor has a call the room was only told of, or not told of at all: nobody is
            // expected yet, and "Waiting for others to join" says so.
            val ringing = s.rung.values.any { it == ai.ciris.mobile.shared.calls.RungState.RINGING } && s.noAnswer == null
            val waitingOnPeople = ringing ||
                s.rung.values.any { it == ai.ciris.mobile.shared.calls.RungState.NOTIFIED } ||
                s.alert == ai.ciris.mobile.shared.calls.CallAlert.OPEN
            if (!s.ready && !(waitingOnPeople && s.attendees.isEmpty())) {
                val notReached = slow && s.pathFailures.isEmpty()
                Text(
                    localizedString(if (notReached) "mobile.call_not_reached" else "mobile.call_connecting"),
                    color = if (notReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable(if (notReached) "txt_call_not_reached" else "txt_call_connecting"),
                )
            }
            // The person's own camera, from the moment it opens: they see what the others will.
            val self by call.selfPicture.collectAsState()
            self?.let { picture ->
                Column(modifier = Modifier.testable("media_call_self")) {
                    androidx.compose.foundation.Image(
                        bitmap = picture,
                        contentDescription = localizedString("mobile.call_you"),
                        modifier = Modifier.fillMaxWidth(0.35f).aspectRatio(picture.width.toFloat() / picture.height),
                    )
                    Text(localizedString("mobile.call_you"), style = MaterialTheme.typography.bodySmall)
                }
            }
            // One picture per stream: a member's camera and their screen are two tiles.
            val pictures by call.pictures.collectAsState()
            pictures.forEach { (stream, picture) ->
                val isShare = stream in s.shares
                val owner = (s.shares[stream]?.person ?: s.attendees[picture.publisher]?.person)
                    ?.let { nameOf(it) } ?: localizedString("mobile.call_someone")
                Column(modifier = Modifier.testable("media_call_video_$stream", picture.publisher)) {
                    androidx.compose.foundation.Image(
                        bitmap = picture.image,
                        contentDescription = if (isShare) {
                            localizedString("mobile.call_someones_screen", "who", owner)
                        } else owner,
                        modifier = Modifier.fillMaxWidth().aspectRatio(picture.image.width.toFloat() / picture.image.height),
                    )
                    if (isShare) Text(localizedString("mobile.call_someones_screen", "who", owner))
                }
            }
            if (s.attendees.isEmpty()) {
                Text(localizedString("mobile.call_alone"), modifier = Modifier.testable("state_call_alone"))
            }
            RungLines(s, nameOf)
            // Re-read the measurements twice a second, so the tiles (and the runner) see sound arrive.
            var tick by remember { mutableStateOf(0) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) { kotlinx.coroutines.delay(500); tick++ }
            }
            Column(modifier = Modifier.testable("card_call_tiles", s.attendees.size.toString())) {
                s.attendees.values.sortedBy { it.member }.forEach { a ->
                    val heard = call.heard(a.member).also { tick }
                    val who = a.person?.let { nameOf(it) ?: localizedString("mobile.call_someone") }
                        ?: localizedString("mobile.call_someone")
                    val tag = "row_call_tile_" + a.member
                    Text(
                        who + " · " + localizedString(
                            when {
                                a.sendingVideo -> "mobile.call_tile_video"
                                a.sendingAudio || (heard?.frames ?: 0) > 0 -> "mobile.call_tile_audio"
                                else -> "mobile.call_tile_quiet"
                            }
                        ),
                        modifier = Modifier.testable(
                            tag,
                            "frames=${heard?.frames ?: 0} hz=${heard?.hz?.let { kotlin.math.round(it).toInt() } ?: 0} " +
                                "audio=${a.sendingAudio} video=${a.sendingVideo} paused=${a.videoPaused ?: ""}",
                        ),
                    )
                    // The node's reason this person's video stopped reaching this device, by id.
                    a.videoPaused?.takeIf { it.isNotEmpty() }?.let { reason ->
                        Text(
                            localizedString(reason),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testable("txt_call_tile_paused_" + a.member, reason),
                        )
                    }
                }
            }
            // CSD-134 §2.1: a member this device could not reach directly, and why. They
            // stay in the call; a restore clears the panel.
            // The node names the owner when it can (CIRISServer 0.5.254); else the attendee map does.
            fun personOf(member: String, sent: String? = null) =
                (sent ?: s.attendees[member]?.person)?.let { nameOf(it) }
            s.pathFailures.values.sortedBy { it.member }.forEach { f ->
                MediaPathPanel(
                    failure = f,
                    person = personOf(f.member, f.person) ?: localizedString("mobile.call_path_unnamed"),
                    onRetry = { call.leave(); call.rejoin() },
                )
            }
            s.pathRestored.filterKeys { it !in s.pathFailures }.entries.sortedBy { it.key }.forEach { (member, r) ->
                val line = ai.ciris.mobile.shared.calls.MediaPathText.restored(
                    r,
                    personOf(member, r.person) ?: localizedString("mobile.call_path_unnamed"),
                    r.through?.let { personOf(it, r.throughPerson) } ?: localizedString("mobile.call_path_relayer_unnamed"),
                )
                Text(
                    localizedString(line.key, line.params),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable("row_call_path_restored.$member", r.via ?: ""),
                )
            }
            if (!s.relaySeat) RelayRow(call, s)
            // Sharing is video: in a call that carries none, the line above says why there is no button.
            if (s.videoAllowed) ShareControls(call)
            s.departed.forEach { (member, reason) ->
                Text(
                    localizedString(memberLeftKey(reason)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable("row_call_departed_$member", reason.wire),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                val cameraOff by call.cameraOff.collectAsState()
                if (!videoUnavailable) {
                    OutlinedButton(
                        onClick = { call.setCameraOff(!cameraOff) },
                        modifier = Modifier.weight(1f).testableClickable("btn_call_camera") { call.setCameraOff(!cameraOff) },
                    ) { Text(localizedString(if (cameraOff) "mobile.call_camera_on" else "mobile.call_camera_off")) }
                }
                OutlinedButton(
                    onClick = { call.setMuted(!muted) },
                    modifier = Modifier.weight(1f).testableClickable("btn_call_mute") { call.setMuted(!muted) },
                ) { Text(localizedString(if (muted) "mobile.call_unmute" else "mobile.call_mute")) }
                Button(
                    onClick = { call.leave(); onBack() },
                    modifier = Modifier.weight(1f).testableClickable("btn_call_leave") { call.leave(); onBack() },
                ) { Text(localizedString("mobile.call_leave")) }
            }
        }
    }
}

/** How long "Connecting…" may last before the screen says the call has reached no one. */
private const val CONNECT_PATIENCE_MS = 20_000L

private val NO_REJOIN = setOf(
    ai.ciris.mobile.shared.calls.LeaveReason.REMOVED,
    ai.ciris.mobile.shared.calls.LeaveReason.EXPIRED,
    ai.ciris.mobile.shared.calls.LeaveReason.SHAPED_ONLY,
    ai.ciris.mobile.shared.calls.LeaveReason.ANONYMOUS,
)

/**
 * Why this device left, as a literal key each, so every one is checked and
 * translated. A setting that ends a call says what its join refusal says (edge 45.2.0).
 */
private fun youLeftKey(r: ai.ciris.mobile.shared.calls.LeaveReason) = when (r) {
    ai.ciris.mobile.shared.calls.LeaveReason.LEFT -> "mobile.call_you_left"
    ai.ciris.mobile.shared.calls.LeaveReason.BACKGROUNDED -> "mobile.call_you_backgrounded"
    ai.ciris.mobile.shared.calls.LeaveReason.REMOVED -> "mobile.call_you_removed"
    ai.ciris.mobile.shared.calls.LeaveReason.LOST -> "mobile.call_you_lost"
    ai.ciris.mobile.shared.calls.LeaveReason.EXPIRED -> "call.end.expired"
    ai.ciris.mobile.shared.calls.LeaveReason.SHAPED_ONLY -> "call.shaped_only"
    ai.ciris.mobile.shared.calls.LeaveReason.ANONYMOUS -> "call.anonymous_mode"
    ai.ciris.mobile.shared.calls.LeaveReason.FULL -> ai.ciris.mobile.shared.calls.CallSession.CALL_FULL
    ai.ciris.mobile.shared.calls.LeaveReason.NO_ANSWER -> "call.no_answer"
}

/**
 * `call.full` is the server's own sentence, number included ("This call already has 4
 * people…"): a server id's English must equal what the server sends, so the cap is in
 * the text, and a new cap is a new sentence from the server. `max_people` is passed in
 * case a later text carries the placeholder. Without the number, the sentence that needs none.
 */
@Composable
private fun fullText(maxPeople: Int?): String =
    if (maxPeople != null) localizedString(ai.ciris.mobile.shared.calls.CallSession.CALL_FULL, mapOf("max_people" to maxPeople.toString()))
    else localizedString("mobile.call_full_no_count")

/** Why someone else left. The node sends only the first four about others; the rest read as "left". */
private fun memberLeftKey(r: ai.ciris.mobile.shared.calls.LeaveReason) = when (r) {
    ai.ciris.mobile.shared.calls.LeaveReason.LEFT -> "mobile.call_member_left"
    ai.ciris.mobile.shared.calls.LeaveReason.BACKGROUNDED -> "mobile.call_member_backgrounded"
    ai.ciris.mobile.shared.calls.LeaveReason.REMOVED -> "mobile.call_member_removed"
    ai.ciris.mobile.shared.calls.LeaveReason.LOST -> "mobile.call_member_lost"
    ai.ciris.mobile.shared.calls.LeaveReason.EXPIRED,
    ai.ciris.mobile.shared.calls.LeaveReason.SHAPED_ONLY,
    ai.ciris.mobile.shared.calls.LeaveReason.ANONYMOUS,
    ai.ciris.mobile.shared.calls.LeaveReason.FULL,
    ai.ciris.mobile.shared.calls.LeaveReason.NO_ANSWER -> "mobile.call_member_left"
}

/**
 * The relay switch (CSD-134 §2.1): let others in the call connect through the
 * node that carries it, which is this device or, where the person switched node,
 * another. Remembered for that node. A phone on a metered or mobile connection
 * has it off unless the person turns it on, and says why. Not shown on a CIRIS
 * public server, which never relays calls.
 */
@Composable
private fun RelayRow(call: ai.ciris.mobile.shared.viewmodels.CallViewModel, s: ai.ciris.mobile.shared.calls.CallState.InCall) {
    val choice by call.relayChoice.collectAsState()
    val metered by call.meteredNetwork.collectAsState()
    val on = s.relaying ?: (choice.on == true)
    val here = call.relayNode.onThisDevice
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                localizedString(if (here) "mobile.call_relay_switch" else "mobile.call_relay_switch_node"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Switch(
                checked = on,
                onCheckedChange = { call.setRelay(it) },
                modifier = Modifier.testableClickable("row_call_relay__act.self", if (on) "on" else "off") { call.setRelay(!on) },
            )
        }
        if (metered == true && choice.on != true) {
            Text(
                localizedString("mobile.call_relay_metered_note"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (on && s.relayingFor > 0) {
            Text(
                localizedString(
                    if (here) "mobile.call_relaying_for" else "mobile.call_relaying_for_node",
                    mapOf("count" to s.relayingFor.toString()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testable("row_call_relay.self", s.relayingFor.toString()),
            )
        }
    }
}

/**
 * Share a screen or a window (CSD-134). Desktop lists its own choices; phones and
 * the browser show the system's picker. While sharing, the screen says what is
 * shared, including that a whole screen shows this call too.
 */
@Composable
private fun ShareControls(call: ai.ciris.mobile.shared.viewmodels.CallViewModel) {
    val picker = ai.ciris.mobile.shared.calls.media.rememberSharePicker()
    val share by call.shareState.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var sharedKind by remember { mutableStateOf<ai.ciris.mobile.shared.calls.media.VideoKind?>(null) }
    when (val st = share) {
        is ai.ciris.mobile.shared.calls.CallShare.State.Sharing -> {
            Text(
                localizedString(
                    if (sharedKind == ai.ciris.mobile.shared.calls.media.VideoKind.WINDOW) "mobile.call_sharing_window"
                    else "mobile.call_sharing_screen"
                ),
                modifier = Modifier.testable("txt_call_sharing", sharedKind?.name?.lowercase() ?: "screen"),
            )
            OutlinedButton(
                onClick = { call.stopShare() },
                modifier = Modifier.testableClickable("btn_call_share_stop") { call.stopShare() },
            ) { Text(localizedString("mobile.call_share_stop")) }
        }
        is ai.ciris.mobile.shared.calls.CallShare.State.Starting -> Text(localizedString("mobile.call_share_starting"))
        else -> {
            if (st is ai.ciris.mobile.shared.calls.CallShare.State.Refused) {
                Text(
                    localizedString(st.reasonId),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable("txt_call_share_refused", st.reasonId),
                )
            }
            val start: (ai.ciris.mobile.shared.calls.media.ShareChoice?) -> Unit = { choice ->
                choosing = false
                picker.pick(choice) { source ->
                    if (source != null) {
                        sharedKind = source.kind
                        call.startShare(source)
                    }
                }
            }
            if (picker.unavailable != null) {
                Text(
                    localizedString("mobile.call_share_unavailable_here"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testable("txt_call_share_unavailable_here"),
                )
            } else {
                OutlinedButton(
                    onClick = { if (picker.choices().isEmpty()) start(null) else choosing = true },
                    modifier = Modifier.testableClickable("btn_call_share") {
                        if (picker.choices().isEmpty()) start(null) else choosing = true
                    },
                ) { Text(localizedString("mobile.call_share")) }
                if (choosing) {
                    picker.choices().forEach { c ->
                        OutlinedButton(
                            onClick = { start(c) },
                            modifier = Modifier.fillMaxWidth().testableClickable("btn_call_share_pick_${c.id}") { start(c) },
                        ) { Text(c.title) }
                    }
                }
            }
        }
    }
}

/**
 * CSD-134 §2.1: why the media path to one member could not be set up, as sentences
 * built from translated parts ([ai.ciris.mobile.shared.calls.MediaPathText]); the
 * node's raw tokens sit under Details. "Try again" rejoins, and is offered only
 * where edge says trying again could help.
 */
@Composable
private fun MediaPathPanel(
    failure: ai.ciris.mobile.shared.calls.MediaPathFailure,
    person: String,
    onRetry: () -> Unit,
) {
    val t = ai.ciris.mobile.shared.calls.MediaPathText
    val m = failure.member
    var open by remember(m) { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().testable("row_call_path.$m", failure.cause),
    ) {
        val head = t.headline(failure, person)
        Text(localizedString(head.key, head.params), color = MaterialTheme.colorScheme.error)
        val mine = localizedString(t.natKey(failure.localNat?.legacyClass))
        val theirs = failure.peerNat?.let { localizedString(t.natKey(it.legacyClass)) }
        listOfNotNull(t.relay(failure), t.networks(failure, person, mine, theirs), t.portOpen(failure), t.advice(failure, person))
            .forEach { line -> Text(localizedString(line.key, line.params), style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (t.offersRetry(failure)) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.testableClickable("row_call_path__act.$m") { onRetry() },
                ) { Text(localizedString("mobile.call_path_try_again")) }
            }
            androidx.compose.material3.TextButton(onClick = { open = !open }) {
                Text(localizedString(if (open) "mobile.call_path_details_hide" else "mobile.call_path_details"))
            }
        }
        if (open) {
            t.details(failure).forEach { d ->
                Text(
                    d,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * How the ring went for each person this call rang (CIRISServer 0.5.255), until they
 * join: their tile then speaks for them. Nothing is shown for someone already in the call.
 */
@Composable
private fun RungLines(s: ai.ciris.mobile.shared.calls.CallState.InCall, nameOf: (String) -> String?) {
    val joined = s.attendees.values.mapNotNull { it.person }.toSet() + s.attendees.keys
    s.rung.filterKeys { it !in joined }.toList().sortedBy { it.first }.forEach { (person, state) ->
        val key = when (state) {
            ai.ciris.mobile.shared.calls.RungState.RINGING -> "mobile.call_rung_ringing"
            ai.ciris.mobile.shared.calls.RungState.NOTIFIED -> "mobile.call_rung_notified"
            ai.ciris.mobile.shared.calls.RungState.BUSY -> "mobile.call_rung_busy"
            ai.ciris.mobile.shared.calls.RungState.DECLINED -> "mobile.call_rung_declined"
            ai.ciris.mobile.shared.calls.RungState.UNREACHABLE -> "mobile.call_rung_unreachable"
            ai.ciris.mobile.shared.calls.RungState.REFUSED -> "mobile.call_rung_refused"
            ai.ciris.mobile.shared.calls.RungState.IN_CALL,
            ai.ciris.mobile.shared.calls.RungState.OTHER -> null
        } ?: return@forEach
        val who = nameOf(person) ?: localizedString("mobile.call_someone")
        Text(
            localizedString(key, "who", who),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testable("row_call_rung_$person", state.wire ?: "other"),
        )
    }
    s.noAnswer?.let { NoAnswerLine(it) }
}

@Composable
private fun NoAnswerLine(why: ai.ciris.mobile.shared.calls.NoAnswer) {
    Text(
        localizedString(
            if (why == ai.ciris.mobile.shared.calls.NoAnswer.TIMEOUT) "mobile.call_no_answer_timeout"
            else "mobile.call_no_answer_declined"
        ),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testable("txt_call_no_answer", why.name.lowercase()),
    )
}
