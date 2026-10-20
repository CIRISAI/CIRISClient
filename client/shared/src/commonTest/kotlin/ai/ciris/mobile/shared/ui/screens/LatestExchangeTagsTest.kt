package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.models.ChatMessage
import ai.ciris.mobile.shared.models.MessageType
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `msg_user_latest` / `msg_reply_latest` (CIRISClient#217): a flow names the
 * message IT sent and the reply to THAT message, whatever ran before it on the
 * same agent — no ordinal to guess. The reply is matched by the agent's own id
 * convention, `<message_id>_response` (routes/agent.py history; the view model
 * mints the same id), never by position.
 */
class LatestExchangeTagsTest {

    private var t = 0L
    private fun m(id: String, type: MessageType) =
        ChatMessage(id, "x", type, Instant.fromEpochMilliseconds(1_700_000_000_000 + t++))

    @Test
    fun theLatestUserMessageAndItsReplyAreNamed() {
        val messages = listOf(
            m("a", MessageType.USER), m("a_response", MessageType.AGENT),
            m("b", MessageType.USER), m("b_response", MessageType.AGENT),
        )
        assertEquals(mapOf("b" to "msg_user_latest", "b_response" to "msg_reply_latest"), latestExchangeTags(messages))
    }

    @Test
    fun anEarlierReplyIsNeverTheLatestReply() {
        // Our message is sent, its reply has not landed: the previous flow's
        // reply is still the newest agent row, and it must NOT answer for ours.
        val messages = listOf(m("a", MessageType.USER), m("a_response", MessageType.AGENT), m("b", MessageType.USER))
        val tags = latestExchangeTags(messages)
        assertEquals("msg_user_latest", tags["b"])
        assertNull(tags["a_response"])
    }

    @Test
    fun aSystemRowAfterTheSendDoesNotMoveTheReply() {
        val messages = listOf(m("b", MessageType.USER), m("sys", MessageType.SYSTEM), m("b_response", MessageType.AGENT))
        assertEquals("msg_reply_latest", latestExchangeTags(messages)["b_response"])
    }

    @Test
    fun noUserMessageNamesNothing() {
        assertEquals(emptyMap(), latestExchangeTags(listOf(m("x", MessageType.AGENT))))
    }
}
