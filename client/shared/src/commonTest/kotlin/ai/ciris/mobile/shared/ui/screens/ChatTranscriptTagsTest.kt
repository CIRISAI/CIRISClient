package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.models.ChatMessage
import ai.ciris.mobile.shared.models.MessageType
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Interact transcript was invisible to `/tree` because its rows carried no
 * `testTag` (CIRISClient#27). These pin the properties a downstream gate is
 * entitled to rely on once they do — the ones that, if they quietly stopped
 * holding, would put us back to a passing suite over an unassertable transcript.
 */
class ChatTranscriptTagsTest {

    private var seq = 0

    private fun msg(type: MessageType, text: String = "t"): ChatMessage = ChatMessage(
        // Real ids come from the agent and are unpredictable — which is why the
        // tag is not built from them.
        id = "id-${seq++}",
        text = text,
        type = type,
        timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000 + seq),
    )

    /** Every row gets one, or a row is missing from `/tree` — the whole bug. */
    @Test
    fun everyMessageGetsATag() {
        val messages = listOf(
            msg(MessageType.USER),
            msg(MessageType.AGENT),
            msg(MessageType.SYSTEM),
            msg(MessageType.ERROR),
            msg(MessageType.ACTION),
        )
        assertEquals(messages.size, transcriptTestTags(messages).size)
    }

    /**
     * The question the gate actually asks: is this the agent, or my own echo?
     * The tag has to answer it without reading the text.
     */
    @Test
    fun roleIsReadableFromTheTagAlone() {
        val tags = transcriptTestTags(listOf(msg(MessageType.USER), msg(MessageType.AGENT)))
        assertEquals(listOf("msg_user_0", "msg_agent_0"), tags)
    }

    /**
     * Ordinals count per role, so ACTION and SYSTEM rows the agent interleaves
     * — a count no caller can predict — cannot push the first reply off
     * `msg_agent_0`.
     */
    @Test
    fun interleavedRowsDoNotShiftTheReplyOrdinal() {
        val tags = transcriptTestTags(
            listOf(
                msg(MessageType.USER),
                msg(MessageType.ACTION),
                msg(MessageType.ACTION),
                msg(MessageType.SYSTEM),
                msg(MessageType.AGENT),
            )
        )
        assertEquals("msg_agent_0", tags.last())
        assertEquals(listOf("msg_action_0", "msg_action_1"), tags.slice(1..2))
    }

    /**
     * Ordinals run oldest-first. If they ran newest-first every arriving reply
     * would renumber the rows behind it, and re-registration under a key another
     * row is disposing of is how the newest reply goes missing from `/tree`.
     */
    @Test
    fun ordinalsCountFromTheOldest() {
        val tags = transcriptTestTags(
            listOf(msg(MessageType.AGENT, "first"), msg(MessageType.AGENT, "second"))
        )
        assertEquals(listOf("msg_agent_0", "msg_agent_1"), tags)
    }

    /** The stability claim, stated as the thing it means: appending renumbers nothing. */
    @Test
    fun appendingAMessageLeavesEveryExistingTagAlone() {
        val before = listOf(msg(MessageType.USER), msg(MessageType.AGENT))
        val after = before + msg(MessageType.AGENT)

        val beforeTags = transcriptTestTags(before)
        val afterTags = transcriptTestTags(after)

        assertEquals(beforeTags, afterTags.take(beforeTags.size))
        assertEquals("msg_agent_1", afterTags.last())
    }

    /** Two rows sharing a tag means one of them silently overwrites the other. */
    @Test
    fun tagsAreUniqueAcrossATranscript() {
        val messages = MessageType.entries.flatMap { type -> List(3) { msg(type) } }
        val tags = transcriptTestTags(messages)
        assertEquals(tags.size, tags.toSet().size, "duplicate tag(s) in $tags")
    }

    /** No two message types may collapse onto the same role segment. */
    @Test
    fun everyMessageTypeHasItsOwnRole() {
        val roles = MessageType.entries.map { transcriptRole(it) }
        assertEquals(roles.size, roles.toSet().size, "roles collide: $roles")
        assertTrue(roles.none { it.isBlank() })
    }

    /**
     * `msg_` must stay a DISPLAY prefix. These rows are read, never clicked, so
     * an interactive prefix would make each one a new offender against the
     * drivability invariant (CIRISClient#28, client/tools/check_ui_drivable.py)
     * — one per message, growing with the conversation.
     */
    @Test
    fun tagsUseADisplayPrefixNotAnInteractiveOne() {
        val interactive = listOf(
            "btn_", "chip_", "menu_", "input_", "field_", "toggle_", "switch_", "tab_"
        )
        val tags = transcriptTestTags(MessageType.entries.map { msg(it) })
        assertTrue(tags.all { it.startsWith("msg_") }, "not display-prefixed: $tags")
        assertFalse(
            tags.any { tag -> interactive.any { tag.startsWith(it) } },
            "interactive prefix on a display-only row: $tags"
        )
    }
}
