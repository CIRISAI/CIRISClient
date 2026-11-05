package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.chat.RoomModeration
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import androidx.compose.runtime.Composable

/** The words for a moderator's flag class; an unknown class falls to the general word. */
@Composable
private fun flagWord(flag: String?): String = localizedString(
    if (flag == "infohazard") "mobile.moderation_flag_infohazard" else "mobile.moderation_flag_reported",
)

/** One line on how a room's moderators stand on an item, or null when there is nothing to say. */
@Composable
internal fun moderationNote(m: RoomModeration): String? {
    val stance = when {
        m.flag == null -> localizedString("mobile.moderation_note_cleared")
        m.revealed -> localizedString("mobile.moderation_note_revealed", "flag", flagWord(m.flag))
        m.withheld -> localizedString("mobile.moderation_note_flagged", "flag", flagWord(m.flag))
        else -> null
    }
    val appeal = when (m.reconsideration) {
        "filed" -> localizedString("mobile.moderation_appeal_filed")
        "upheld" -> localizedString("mobile.moderation_appeal_upheld")
        "partial" -> localizedString("mobile.moderation_appeal_partial")
        "reversed" -> localizedString("mobile.moderation_appeal_reversed")
        else -> null
    }
    return listOfNotNull(stance, appeal).joinToString(" ").ifEmpty { null }
}

/** See one flagged item. It is this viewer's own signed consent, so it says what it does and does not change. */
@Composable
internal fun RevealConfirm(moderation: RoomModeration, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmSheet(
        title = localizedString("mobile.moderation_reveal_title"),
        facts = listOf(
            ConfirmFact(localizedString("mobile.moderation_reveal_fact_flag"), flagWord(moderation.flag)),
            ConfirmFact(localizedString("mobile.moderation_reveal_fact_effect"), localizedString("mobile.moderation_reveal_effect")),
            ConfirmFact(localizedString("mobile.moderation_reveal_fact_signer"), localizedString("mobile.moderation_reveal_signer")),
        ),
        confirmLabel = localizedString("mobile.moderation_reveal"),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        tagPrefix = "moderation_reveal",
    )
}
