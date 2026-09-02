package ai.ciris.mobile.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciris.mobile.shared.backend.BackendAction
import ai.ciris.mobile.shared.backend.BackendStatus
import ai.ciris.mobile.shared.backend.noticeFor
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable

/**
 * What the backend is doing, and what you can do about it.
 *
 * THE SCREEN THIS REPLACES
 *
 * A user on 32-bit Android read "Cannot connect to server. Please check your
 * connection." from an app whose backend runs inside itself. They restarted the
 * phone, then asked why the app kept demanding a login and why their Google
 * account would not work — because an unreachable backend surfaces on the login
 * screen, so that is what it looked like from outside. The app knew the real
 * reason and had put it in a notification.
 *
 * Everything shown here comes from [noticeFor], which the compiler requires to
 * be exhaustive over BackendState. There is no branch in this file that decides
 * what a state means: it renders an answer that was already required to exist.
 *
 * Renders NOTHING when no supervisor is installed, and nothing when the backend
 * is Live. A status bar that is always present is one people stop reading.
 */
@Composable
fun BackendBanner(
    modifier: Modifier = Modifier,
    onChooseNode: (() -> Unit)? = null,
) {
    val state by BackendStatus.state.collectAsState()
    val backend = state ?: return          // nobody watching — say nothing
    val notice = noticeFor(backend)
    if (notice.action == BackendAction.None) return   // Live — stay out of the way

    val bg = if (notice.isError) Color(0x33EF4444) else Color(0x33FFFFFF)
    val fg = if (notice.isError) Color(0xFFFFD7D7) else Color(0xFFF0F0F0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testable("txt_backend_status", notice.headlineKey),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (notice.action == BackendAction.Waiting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = fg,
                )
            }
            Text(
                text = localizedString(notice.headlineKey),
                color = fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // THE REASON THE APP ALREADY HAD.
        //
        // On Android the service caught the runtime's startup exception and
        // wrote it to a notification while the screen said something false.
        // A user forwarding a screenshot should be forwarding a fact.
        notice.detail?.let { detail ->
            Text(
                text = detail,
                color = fg.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier.testable("txt_backend_detail", detail),
            )
        }

        when (notice.action) {
            BackendAction.Retry -> TextButton(
                onClick = { BackendStatus.retry() },
                modifier = Modifier.testableClickable("btn_backend_retry") { BackendStatus.retry() },
            ) {
                Text(localizedString("mobile.backend_retry"), color = fg, fontSize = 13.sp)
            }

            BackendAction.ChooseNode -> onChooseNode?.let { choose ->
                TextButton(
                    onClick = choose,
                    modifier = Modifier.testableClickable("btn_backend_choose_node") { choose() },
                ) {
                    Text(localizedString("mobile.backend_choose_node"), color = fg, fontSize = 13.sp)
                }
            }

            // CheckNetwork is the one case with nothing for the app to do — and
            // the ONLY case where telling someone to check their connection is
            // true. The headline already says it; a button would be theatre.
            BackendAction.CheckNetwork -> Unit
            BackendAction.Waiting -> Unit
            BackendAction.None -> Unit
        }
    }
}

/** Centred variant for the login screen, where the false message used to sit. */
@Composable
fun BackendBannerCentered(modifier: Modifier = Modifier) {
    val state by BackendStatus.state.collectAsState()
    val backend = state ?: return
    val notice = noticeFor(backend)
    if (notice.action == BackendAction.None) return

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = localizedString(notice.headlineKey),
            color = if (notice.isError) Color(0xFFFF6B6B) else Color(0xFFE8E8E8),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testable("txt_backend_status", notice.headlineKey),
        )
        notice.detail?.let {
            Text(
                text = it,
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testable("txt_backend_detail", it),
            )
        }
        if (notice.action == BackendAction.Retry) {
            TextButton(
                onClick = { BackendStatus.retry() },
                modifier = Modifier.testableClickable("btn_backend_retry") { BackendStatus.retry() },
            ) {
                Text(localizedString("mobile.backend_retry"), color = Color(0xFF10B981), fontSize = 14.sp)
            }
        }
    }
}
