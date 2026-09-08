package ai.ciris.mobile.shared.viewmodels

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The post-setup hold ends when a session exists (CIRISClient#46).
 *
 * The hold runs inside `LaunchedEffect(phase)` and clears its own latch on the
 * way out. Any `setPhase()` cancels it mid-poll, so the latch survives with
 * nothing routed — and the next phase change re-enters the hold. When that
 * lands after the owner has signed back in, the client holds "Restarting your
 * node…" over a working session: the report has a SYSTEM_ADMIN login succeed
 * against an agent alive on :8080 in `work`, and the app sat on Login for the
 * rest of the budget with the credentials still filled.
 *
 * Passing on Windows and failing on macOS in the SAME run is the signature of
 * a race, which is why the guard is a predicate on state rather than an
 * ordering assumption.
 */
class ReconfigureHoldTest {

    @Test
    fun a_session_ends_the_hold() {
        // THE DEFECT. The latch is still set — it always is after a cancelled
        // hold — but the login already proved the backend is back and the owner
        // can reach it, which is all the hold was polling to establish.
        assertFalse(shouldHoldForReconfigure(reconfiguring = true, hasSession = true))
    }

    @Test
    fun without_a_session_the_hold_still_does_its_job() {
        // The reason the hold exists: after /v1/setup/complete the runtime
        // reloads, the node-fold rebinds, and the stateless client has no token.
        assertTrue(shouldHoldForReconfigure(reconfiguring = true, hasSession = false))
    }

    @Test
    fun no_latch_means_no_hold_either_way() {
        assertFalse(shouldHoldForReconfigure(reconfiguring = false, hasSession = false))
        assertFalse(shouldHoldForReconfigure(reconfiguring = false, hasSession = true))
    }

    @Test
    fun the_session_is_what_decides_it_not_the_latch() {
        // Stated as a property so a future edit cannot quietly make the latch
        // sufficient again: for any latch value, having a session means no hold.
        for (latched in listOf(true, false)) {
            assertFalse(
                shouldHoldForReconfigure(reconfiguring = latched, hasSession = true),
                "a live session must never be held behind the post-setup wait (latched=$latched)",
            )
        }
    }
}
