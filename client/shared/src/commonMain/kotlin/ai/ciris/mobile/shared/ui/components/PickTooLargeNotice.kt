package ai.ciris.mobile.shared.ui.components

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.PickTooLarge
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.screens.files.humanBytes
import ai.ciris.mobile.shared.ui.theme.CirisTheme
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
 * The picker's refusal, said where the pick was made: the file, its size and
 * the limit (`mobile.files_too_large`, the same words as the Files cap). Every
 * platform picker used to drop such a file without a word. Not a card, so it
 * can sit inside one; [tag] names it and `<tag>_dismiss` closes it.
 */
@Composable
fun PickTooLargeNotice(refusal: PickTooLarge, tag: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val t = CirisTheme.tokens
    val text = localizedString(
        "mobile.files_too_large",
        mapOf("name" to refusal.name, "size" to humanBytes(refusal.sizeBytes), "limit" to humanBytes(refusal.limitBytes)),
    )
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testable(tag, text),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = CirisTheme.type.body, color = t.danger, modifier = Modifier.weight(1f))
        CirisTextButton(label = localizedString("mobile.common_dismiss"), tag = "${tag}_dismiss", onClick = onDismiss)
    }
}
