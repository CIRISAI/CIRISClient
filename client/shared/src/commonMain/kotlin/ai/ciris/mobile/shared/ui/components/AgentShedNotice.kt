package ai.ciris.mobile.shared.ui.components

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.viewmodels.ChatShedState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The agent is shedding new work (CIRISClient#173), said above the composer:
 * a calm countdown while the resend waits, then — if it gave up — that the
 * message was kept, with Retry. Runtime self-protection, never DEFER/REJECT
 * wording. `chat_shed_notice`, `chat_shed_retry`, `chat_shed_dismiss`.
 */
@Composable
fun AgentShedNotice(state: ChatShedState, onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val t = CirisTheme.tokens
    val text = when (state) {
        ChatShedState.None -> return
        is ChatShedState.Waiting -> localizedString("mobile.interact_shed_retrying", mapOf("seconds" to state.secondsRemaining.toString()))
        is ChatShedState.GaveUp -> localizedString("mobile.interact_shed_gave_up")
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testable("chat_shed_notice", text),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = CirisTheme.type.body, color = t.dim, modifier = Modifier.weight(1f))
        if (state is ChatShedState.GaveUp) {
            CirisTextButton(label = localizedString("mobile.common_retry"), tag = "chat_shed_retry", onClick = onRetry)
            CirisTextButton(label = localizedString("mobile.common_dismiss"), tag = "chat_shed_dismiss", onClick = onDismiss)
        }
    }
}
