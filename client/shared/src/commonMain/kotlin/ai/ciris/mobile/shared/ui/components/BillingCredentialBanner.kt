package ai.ciris.mobile.shared.ui.components

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.hairlineStroke
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * "Credits need a fresh Google sign-in" — a CREDITS state with the one action
 * that fixes it, and nothing about the session (CIRISClient#171, #172).
 *
 * The billing credential is the provider ID token the agent spends at the
 * hosted proxy; it lasts an hour, and on desktop nothing renewed it. When it
 * lapsed the client showed [SessionExpiredBanner] — "your session is no
 * longer valid" — and that banner's button signed the person out of an agent
 * that was answering them. This one is non-blocking: it sits where credits
 * are shown, says what expired, and its button runs the refresh (a browser
 * sign-in on desktop, the native picker on Android/iOS) while the session
 * stays exactly where it is.
 *
 * Brand tone, not danger: nothing is broken, a clock ran out.
 *
 * @param tag differs per surface (`btn_billing_refresh_signin` on Billing,
 *   `btn_interact_refresh_billing_signin` on Interact) so a harness can tell
 *   which one it drove; the text tag is `<tag>_text`.
 */
@Composable
fun BillingCredentialBanner(
    onRefresh: () -> Unit,
    refreshing: Boolean,
    tag: String,
    provider: String,
    modifier: Modifier = Modifier,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val sentence = localizedString("mobile.billing_credential_stale", "provider", provider)
    val waiting = localizedString("auth.browser_signin.waiting")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CirisShape.card)
            .background(t.brand.copy(alpha = 0.06f))
            .border(hairlineStroke(), CirisShape.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = sentence,
            style = type.body,
            color = t.ink,
            modifier = Modifier.testable("${tag}_text", sentence),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CirisButton(
                label = localizedString("mobile.billing_credential_refresh", "provider", provider),
                tag = tag,
                onClick = onRefresh,
                enabled = !refreshing,
            )
            if (refreshing) {
                CircularProgressIndicator(color = t.brand, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(waiting, style = type.signed, color = t.dim, modifier = Modifier.testable("${tag}_waiting", waiting))
            }
        }
    }
}
