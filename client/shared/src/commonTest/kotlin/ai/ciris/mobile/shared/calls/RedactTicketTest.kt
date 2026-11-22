package ai.ciris.mobile.shared.calls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The frames-socket failure is logged (2026-11-21: a call that failed at the socket
 * left nothing in the app's log), and the socket URL carries a single-use ticket.
 */
class RedactTicketTest {
    @Test
    fun a_ticket_in_a_quoted_url_is_removed_and_the_status_kept() {
        val msg = "Handshake exception, expected status code 101 but was 403 " +
            "(ws://localhost:8080/v1/chat/room/call/frames?ticket=abcDEF-123_x&v=1)"
        val out = redactTicket(msg)!!
        assertFalse("abcDEF-123_x" in out)
        assertEquals(
            "Handshake exception, expected status code 101 but was 403 " +
                "(ws://localhost:8080/v1/chat/room/call/frames?ticket=<redacted>&v=1)",
            out,
        )
    }

    @Test
    fun a_message_without_a_ticket_is_unchanged() {
        assertEquals("Connection refused", redactTicket("Connection refused"))
        assertNull(redactTicket(null))
    }
}
