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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onBack: () -> Unit,
) {
    val media = rememberCallMediaPlatform()
    var state by remember { mutableStateOf<CallPermissionState?>(null) }

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
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).testable("call_screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                null -> CallChoice(onChoose = { advance(it, emptySet()) })
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
                    Text(
                        localizedString("mobile.call_not_connecting"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testable("call_not_connecting"),
                    )
                }
            }
            CallSaveState()
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
