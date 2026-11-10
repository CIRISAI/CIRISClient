package ai.ciris.mobile.shared.models.federation

import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CSD-005 at CIRISServer 0.5.245: `reach`, `last_heard_at` and `stale` on each
 * `GET /v1/contacts` row (`contacts_chat.rs::contact_reach`, `last_heard_from`).
 */
class ContactReachTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun row(extra: String) =
        json.decodeFromString(Contact.serializer(), """{"key_id":"k1","canonical":false,"trust":"unknown"$extra}""")

    @Test
    fun eachReachWordReadsAsItsState() {
        assertEquals(ContactReach.CONNECTED, row(""","reach":"connected"""").reachState)
        assertEquals(ContactReach.SENT, row(""","reach":"sent"""").reachState)
        assertEquals(ContactReach.WAITING_TO_REACH, row(""","reach":"waiting_to_reach"""").reachState)
    }

    /** A word this client does not know must never read as delivered (CSD-005). */
    @Test
    fun anUnknownReachWordReadsAsWaiting() {
        assertEquals(ContactReach.WAITING_TO_REACH, row(""","reach":"relayed"""").reachState)
    }

    /** An older node sends no `reach`: the row says nothing, never a guess from `mutual`. */
    @Test
    fun anOlderNodeSaysNothing() {
        val c = row(""","mutual":false""")
        assertNull(c.reachState)
        assertNull(c.staleSince)
    }

    @Test
    fun staleWithADateSaysSince() {
        val c = row(""","stale":true,"last_heard_at":"2026-10-20T08:15:00Z"""")
        assertEquals(Instant.parse("2026-10-20T08:15:00Z"), c.staleSince)
    }

    @Test
    fun aNullDateIsNotStaleWhateverTheFlag() {
        assertNull(row(""","stale":true,"last_heard_at":null""").staleSince)
        assertNull(row(""","stale":false,"last_heard_at":"2026-10-20T08:15:00Z"""").staleSince)
    }

    /** A date this client cannot read loses the label, never the list. */
    @Test
    fun anUnreadableDateLosesOnlyTheLabel() {
        val list = json.decodeFromString(
            ListSerializer(Contact.serializer()),
            """[{"key_id":"a","stale":true,"last_heard_at":"last tuesday"},{"key_id":"b","reach":"sent"}]""",
        )
        assertEquals(2, list.size)
        assertNull(list[0].staleSince)
        assertEquals(ContactReach.SENT, list[1].reachState)
    }
}
