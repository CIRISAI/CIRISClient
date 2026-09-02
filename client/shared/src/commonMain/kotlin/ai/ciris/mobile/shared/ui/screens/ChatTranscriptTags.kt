package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.models.ChatMessage
import ai.ciris.mobile.shared.models.MessageType

/**
 * The role segment of a transcript row's test tag.
 *
 * Exhaustive with no `else`. A sixth [MessageType] is a COMPILE ERROR here
 * rather than a row that quietly renders with no tag and is therefore absent
 * from `/tree` — which is precisely the failure this file exists to close.
 */
internal fun transcriptRole(type: MessageType): String = when (type) {
    MessageType.USER -> "user"
    MessageType.AGENT -> "agent"
    MessageType.SYSTEM -> "system"
    MessageType.ERROR -> "error"
    MessageType.ACTION -> "action"
}

/**
 * One test tag per Interact transcript row, in the order the messages were
 * given — i.e. CHRONOLOGICAL, oldest first, regardless of how the list is
 * later reversed for display.
 *
 * WHY THIS EXISTS
 *
 * `/tree` reports only composables that carry a `testTag`, and the chat
 * transcript carried none. So the client's own documented test interface could
 * not see a single message bubble: CIRISAgent's five-platform QA gate watched a
 * reply render on screen, asked `/tree` for every string it knew about, and got
 * nothing back. The giveaway was that the user's OWN echo was missing too — no
 * agent-side or transport explanation produces that (CIRISClient#27). The core
 * feature of the product was the one thing automation could not assert, and the
 * standing workaround — asserting the agent's `/v1/agent/history` instead —
 * proves the agent answered, not that this client drew anything.
 *
 * THE SCHEME: `msg_<role>_<n>`, n COUNTED PER ROLE FROM THE OLDEST
 *
 * Role comes first because the one question a gate asks is "did the AGENT
 * reply, or am I looking at my own echo?", and `msg_agent_0` answers it from
 * the tag alone, before anything reads the text.
 *
 * The ordinal counts that role's OWN rows. Counting per role rather than over
 * the whole list keeps the first reply addressable as `msg_agent_0` however
 * many ACTION or SYSTEM rows the agent interleaved ahead of it — a number the
 * caller cannot predict and should not have to.
 *
 * Counting from the OLDEST rather than the newest is what makes a tag stable,
 * and that is a correctness argument, not a taste one. The tag IS the registry
 * key: `testable()` registers the element on layout and unregisters it on
 * dispose, both keyed on the tag. Were `msg_agent_0` to mean "newest", every
 * arriving reply would renumber every row behind it, and the new row's
 * registration would race the previous row's dispose-time unregistration of the
 * same key — an interleaving whose losing outcome is the newest reply missing
 * from `/tree`, which is the bug this file closes, reintroduced by its own fix.
 * Appending renumbers nothing, so no such race can arise.
 *
 * What the tag does NOT encode is the message id: it is minted by the agent, so
 * automation cannot predict it and cannot address a row by it. (`ChatScreen`
 * keys its rows on an attestation id, which is a different bargain — there the
 * caller already holds the id it is looking for.)
 *
 * `msg_` is a DISPLAY prefix. These rows are read, never driven, so they carry
 * `.testable()` and satisfy the drivability invariant (CIRISClient#28,
 * `client/tools/check_ui_drivable.py`) by construction rather than by exemption.
 *
 * Returning a tag per message rather than a function to call per row is
 * deliberate: the caller zips this against the same list it renders, so a row
 * drawn with no tag is not an omission anyone can make.
 */
internal fun transcriptTestTags(messages: List<ChatMessage>): List<String> {
    val seenPerRole = mutableMapOf<String, Int>()
    return messages.map { message ->
        val role = transcriptRole(message.type)
        val ordinal = seenPerRole.getOrElse(role) { 0 }
        seenPerRole[role] = ordinal + 1
        "msg_${role}_$ordinal"
    }
}
