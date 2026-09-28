package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.TicketData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSD-013: the Tickets list's empty state never shows over an error or a
 * spinner, and an opened ticket is the re-read copy, not the listed one.
 */
class TicketsStateTest {
    private fun ticket(id: String, status: String) = TicketData(
        ticketId = id, sop = "DSAR_DELETE", ticketType = "dsar", status = status, priority = 5,
        email = "user@example.com", userIdentifier = null, submittedAt = "2026-09-27T00:00:00Z",
        deadline = null, lastUpdated = "2026-09-27T00:00:00Z", completedAt = null, notes = null,
        automated = false, metadata = emptyMap(),
    )

    @Test
    fun emptyNeverShowsOverAnError() {
        assertTrue(TicketsScreenState().showsEmpty())
        assertFalse(TicketsScreenState(error = "Failed to refresh").showsEmpty(), "error and empty must not both render")
        assertFalse(TicketsScreenState(isLoading = true).showsEmpty())
        assertFalse(TicketsScreenState(isRefreshing = true).showsEmpty())
        assertFalse(TicketsScreenState(tickets = listOf(ticket("t1", "pending"))).showsEmpty())
    }

    @Test
    fun anOpenedTicketIsReplacedByItsReRead() {
        val listed = TicketsScreenState(
            tickets = listOf(ticket("t1", "pending"), ticket("t2", "pending")),
            selectedTicket = ticket("t1", "pending"),
        )
        val fresh = ticket("t1", "completed")
        val after = listed.withTicket(fresh)
        assertEquals("completed", after.tickets.first { it.ticketId == "t1" }.status)
        assertEquals("pending", after.tickets.first { it.ticketId == "t2" }.status)
        assertEquals(fresh, after.selectedTicket)
    }

    /**
     * A slow re-read of A landing after the person opened B (or closed the
     * detail) updates A's row and nothing else: the detail shows what they
     * chose, not what the network answered last.
     */
    @Test
    fun aLateReReadOfAnotherTicketDoesNotHijackTheSelection() {
        val rows = listOf(ticket("t1", "pending"), ticket("t2", "pending"))
        val fresh = ticket("t1", "completed")

        val openedB = TicketsScreenState(tickets = rows, selectedTicket = ticket("t2", "pending")).withTicket(fresh)
        assertEquals("t2", openedB.selectedTicket?.ticketId, "B was open; A's late re-read must not replace it")
        assertEquals("completed", openedB.tickets.first { it.ticketId == "t1" }.status, "A's row is still refreshed")

        val closed = TicketsScreenState(tickets = rows, selectedTicket = null).withTicket(fresh)
        assertNull(closed.selectedTicket, "the detail was closed; a late re-read must not reopen it")
    }
}
