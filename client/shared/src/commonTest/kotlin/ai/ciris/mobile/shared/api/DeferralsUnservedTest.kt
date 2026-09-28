package ai.ciris.mobile.shared.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CSD-041: which statuses on `GET /v1/wa/deferrals` may read as "nothing
 * waiting". A 503 is the agent saying its WA service is missing
 * (`routes/wa.py:41`); CC 4.3 freezes an agent without one, so that answer
 * must surface as a failed read and never as an empty list.
 */
class DeferralsUnservedTest {
    @Test
    fun aRouterThatNeverHadTheRouteReadsAsEmpty() {
        for (s in listOf(404, 405, 501)) assertTrue(s in DEFERRALS_UNSERVED, "$s")
    }

    @Test
    fun aMissingWaServiceIsAFailedReadNotAnEmptyList() {
        assertFalse(503 in DEFERRALS_UNSERVED, "503 = WA service missing; not 'nothing waiting'")
        assertFalse(502 in DEFERRALS_UNSERVED)
    }

    @Test
    fun anExpiredTokenIsNeverEmpty() {
        assertFalse(401 in DEFERRALS_UNSERVED)
        assertFalse(403 in DEFERRALS_UNSERVED)
    }
}
