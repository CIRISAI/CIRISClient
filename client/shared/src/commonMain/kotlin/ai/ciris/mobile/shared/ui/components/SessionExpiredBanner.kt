package ai.ciris.mobile.shared.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.ciris.mobile.shared.localization.LocalizationHelper
import ai.ciris.mobile.shared.platform.testableClickable

/**
 * "Your session is no longer valid" — with the one action that fixes it.
 *
 * The state behind this (`authExpired`) is TokenManager's own conclusion that a
 * person has to sign in again, which for 25 hours reached a WARN line and no
 * screen (CIRISClient#59). Rendering the sentence made that honest; this makes
 * it actionable. On desktop there is no silent path — the token came from a
 * browser sign-in and only another browser sign-in renews it — so the button
 * goes to Login, where that flow starts.
 *
 * Shared by Interact and Billing so the two surfaces the person is stuck on
 * say the same thing and offer the same door. [tag] differs per surface so a
 * harness can tell which one it drove.
 */
@Composable
fun SessionExpiredBanner(
    onSignInAgain: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.errorContainer,
    foreground: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    Surface(
        color = background,
        contentColor = foreground,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = LocalizationHelper.getString("auth.session_expired"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onSignInAgain,
                modifier = Modifier.testableClickable(tag) { onSignInAgain() },
            ) {
                // REUSED, NOT ADDED. A new key here is English-only until someone
                // dispatches the `i18n translate` workflow for it, and the
                // localization gate fails on the 28 languages that lack it in the
                // meantime — correctly; that is how the debt is kept from
                // growing. `mobile.adapter_sign_in` is the imperative "Sign In"
                // already translated into all 29 locales. Its name is adapter-
                // flavoured; its value is exactly the word this button needs.
                Text(LocalizationHelper.getString("mobile.adapter_sign_in"))
            }
        }
    }
}
