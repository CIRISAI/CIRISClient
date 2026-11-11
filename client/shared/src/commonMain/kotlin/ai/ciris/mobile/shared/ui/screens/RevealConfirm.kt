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

/**
 * A room moderator flags one message or file (CIRISServer 0.5.246). The row is
 * signed by the moderator's own key and holds the item back from every reader
 * until each one reveals it, so it is confirmed: the sheet says the class, what
 * changes and whose key signs.
 */
@Composable
internal fun FlagConfirm(contentClass: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmSheet(
        title = localizedString("mobile.moderation_flag_title"),
        facts = listOf(
            ConfirmFact(localizedString("mobile.moderation_reveal_fact_flag"), flagWord(contentClass)),
            ConfirmFact(localizedString("mobile.moderation_reveal_fact_effect"), localizedString("mobile.moderation_flag_effect")),
            ConfirmFact(localizedString("mobile.moderation_reveal_fact_signer"), localizedString("mobile.moderation_flag_signer")),
        ),
        confirmLabel = localizedString("mobile.moderation_flag_action_flag"),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        tagPrefix = "moderation_flag",
    )
}

/** The grounds an appeal may give and the outcomes a ruling may reach (CC 3.3, CIRISServer 0.5.246), in menu order. */
internal val APPEAL_GROUNDS = listOf("new_evidence", "procedural_error", "quorum_compromise")
internal val APPEAL_OUTCOMES = listOf("upheld", "partial", "reversed")

/**
 * The author of a flagged item, or a reviewer of the room, appeals the flag.
 * It is their own signed row and asks a reviewer to rule; the flag stays until
 * one does.
 */
@Composable
internal fun AppealConfirm(grounds: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmSheet(
        title = localizedString("mobile.moderation_appeal_title"),
        facts = listOf(
            ConfirmFact(localizedString("mobile.moderation_appeal_fact_grounds"), localizedString("mobile.moderation_grounds_$grounds")),
            ConfirmFact(localizedString("mobile.moderation_reveal_fact_effect"), localizedString("mobile.moderation_appeal_effect")),
            ConfirmFact(localizedString("mobile.moderation_reveal_fact_signer"), localizedString("mobile.moderation_appeal_signer")),
        ),
        confirmLabel = localizedString("mobile.moderation_appeal_send"),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        tagPrefix = "moderation_appeal",
    )
}

/**
 * A reviewer rules on a filed appeal. [alsoModerates] is true when this viewer
 * holds `moderate` here too: in a founder-moderated room one person holds both
 * duties, so the ruling may be on their own flag, and the sheet says so. The
 * Constitution promises recused reviewers and has not yet defined them for a room.
 */
@Composable
internal fun RuleConfirm(outcome: String, alsoModerates: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmSheet(
        title = localizedString("mobile.moderation_rule_title"),
        facts = listOf(
            ConfirmFact(localizedString("mobile.moderation_rule_fact_outcome"), localizedString("mobile.moderation_outcome_$outcome")),
            ConfirmFact(localizedString("mobile.moderation_reveal_fact_effect"), localizedString("mobile.moderation_rule_effect")),
            ConfirmFact(localizedString("mobile.moderation_reveal_fact_signer"), localizedString("mobile.moderation_rule_signer")),
        ),
        confirmLabel = localizedString("mobile.moderation_rule_send"),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        tagPrefix = "moderation_rule",
        note = if (alsoModerates) localizedString("mobile.moderation_rule_same_person") else null,
    )
}

/** The menu label for flagging as one class: "Flag as reported…". */
@Composable
internal fun flagAsLabel(contentClass: String): String =
    localizedString("mobile.moderation_flag_as", "flag", flagWord(contentClass))

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
