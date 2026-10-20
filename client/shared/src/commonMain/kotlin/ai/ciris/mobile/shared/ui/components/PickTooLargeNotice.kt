package ai.ciris.mobile.shared.ui.components

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.AttachmentRefusal
import ai.ciris.mobile.shared.platform.AttachmentTypes
import ai.ciris.mobile.shared.platform.PickTooLarge
import ai.ciris.mobile.shared.platform.PickedFile
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
    val text = localizedString(
        "mobile.files_too_large",
        mapOf("name" to refusal.name, "size" to humanBytes(refusal.sizeBytes), "limit" to humanBytes(refusal.limitBytes)),
    )
    RefusalNoticeRow(text, tag, onDismiss, modifier)
}

/**
 * The chat paperclip's refusal (CIRISClient#217), one named notice per cause:
 * `<prefix>_too_large`, `<prefix>_unsupported_type` (a type outside
 * [PickedFile.ALLOWED_MIME_TYPES], HEIC among them) and `<prefix>_too_many`
 * (past [PickedFile.MAX_ATTACHMENTS]); `<tag>_dismiss` closes each.
 */
@Composable
fun AttachmentRefusalNotice(
    refusal: AttachmentRefusal,
    tagPrefix: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The types the paperclip takes now — the agent's policy, or the client's own. */
    allowedTypes: List<String> = PickedFile.ALLOWED_MIME_TYPES,
) {
    when (refusal) {
        is AttachmentRefusal.TooLarge -> PickTooLargeNotice(refusal.pick, "${tagPrefix}_too_large", onDismiss, modifier)
        is AttachmentRefusal.UnsupportedType -> RefusalNoticeRow(
            localizedString(
                "mobile.interact_attachment_unsupported_type",
                mapOf(
                    "name" to refusal.name,
                    "types" to AttachmentTypes.extensionsFor(allowedTypes).joinToString(", ") { ".$it" },
                ),
            ),
            "${tagPrefix}_unsupported_type", onDismiss, modifier,
        )
        is AttachmentRefusal.TooMany -> RefusalNoticeRow(
            localizedString(
                "mobile.interact_attachment_too_many",
                mapOf("name" to refusal.name, "limit" to refusal.limit.toString()),
            ),
            "${tagPrefix}_too_many", onDismiss, modifier,
        )
    }
}

@Composable
private fun RefusalNoticeRow(text: String, tag: String, onDismiss: () -> Unit, modifier: Modifier) {
    val t = CirisTheme.tokens
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testable(tag, text),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = CirisTheme.type.body, color = t.danger, modifier = Modifier.weight(1f))
        CirisTextButton(label = localizedString("mobile.common_dismiss"), tag = "${tag}_dismiss", onClick = onDismiss)
    }
}
