package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.calls.DeclineReason
import ai.ciris.mobile.shared.calls.IncomingRing
import ai.ciris.mobile.shared.calls.RingTone
import ai.ciris.mobile.shared.calls.RingWatcher
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Someone in a room started a call and this device is ringing (CSD-134; CIRISServer
 * 0.5.255). A banner over whatever screen is open, never a dialog: a ring must not
 * put a modal on top of a modal (plan item 2.1).
 *
 * Answer opens the call and joins it, which stops the ring here and on the person's
 * other devices. Decline tells the caller; while this device is already in another
 * call it says "busy" instead, and answering leaves that call.
 */
@Composable
fun IncomingCallBanner(
    watcher: RingWatcher,
    /** The room of the call this device is in, if any. */
    inCallRoom: String?,
    nameOf: (String) -> String?,
    onAnswer: (IncomingRing) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ringing by watcher.ringing.collectAsState()
    // A ring for the call already open here is not a ring: the node does not send one,
    // and if two people joined at once it would only say the obvious.
    val shown = ringing.filter { it.room != inCallRoom }
    val scope = rememberCoroutineScope()

    // The sound, only while something rings and this device is not already in a call
    // (there the banner alone says it). The test runner has no speaker to spare.
    val sounding = shown.isNotEmpty() && inCallRoom == null &&
        !ai.ciris.mobile.shared.platform.TestAutomation.isEnabled()
    val tone = remember { RingTone({ ai.ciris.mobile.shared.calls.media.platformCallMediaDevices().speaker() }, scope) }
    DisposableEffect(sounding) {
        if (sounding) tone.start() else tone.stop()
        onDispose { tone.stop() }
    }

    if (shown.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(12.dp).widthIn(max = 520.dp).testable("card_call_incoming", shown.size.toString()),
    ) {
        shown.forEach { ring ->
            val who = ring.byPerson?.let(nameOf) ?: localizedString("mobile.call_someone")
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().testable("row_call_incoming_${ring.room}", if (ring.video) "video" else "voice"),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        localizedString(if (ring.video) "mobile.call_incoming_video" else "mobile.call_incoming_voice", "who", who),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (inCallRoom != null) {
                        Text(
                            localizedString("mobile.call_incoming_leaves_current"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.testable("txt_call_incoming_leaves_current"),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val answer = {
                            watcher.answered(ring.room)
                            onAnswer(ring)
                        }
                        val decline: () -> Unit = {
                            val reason = if (inCallRoom != null) DeclineReason.BUSY else DeclineReason.DECLINED
                            scope.launch { watcher.decline(ring.room, reason) }
                        }
                        Button(
                            onClick = answer,
                            modifier = Modifier.testableClickable("btn_call_answer_${ring.room}") { answer() },
                        ) { Text(localizedString("mobile.call_answer")) }
                        OutlinedButton(
                            onClick = decline,
                            modifier = Modifier.testableClickable("btn_call_decline_${ring.room}") { decline() },
                        ) { Text(localizedString("mobile.call_decline")) }
                    }
                }
            }
        }
    }
}
