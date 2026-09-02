package ai.ciris.mobile.shared.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four claim outcomes an operator has to tell apart, and the one rule that
 * keeps them apart: the server's CODES beat its prose.
 */
class ClaimFailureTest {

    @Test
    fun codes_win_outright() {
        assertEquals(
            ClaimFailure.PIN_REJECTED,
            classifyClaimFailure("target rejected the claim (HTTP 401): auth.claim.pin_invalid"),
        )
        assertEquals(
            ClaimFailure.PIN_REJECTED,
            classifyClaimFailure("target rejected the claim (HTTP 400): auth.claim.pin_missing"),
        )
        assertEquals(
            ClaimFailure.ALREADY_CLAIMED,
            classifyClaimFailure("target rejected the claim (HTTP 409): auth.claim.not_armed"),
        )
    }

    @Test
    fun a_reworded_server_message_still_classifies_by_code() {
        // The whole reason to match the code: this sentence is not the sentence
        // the server ships today, and the verdict must not depend on that.
        assertEquals(
            ClaimFailure.ALREADY_CLAIMED,
            classifyClaimFailure("HTTP 409: auth.claim.not_armed — totally different wording here"),
        )
    }

    @Test
    fun prose_fallback_covers_older_nodes() {
        assertEquals(
            ClaimFailure.ALREADY_CLAIMED,
            classifyClaimFailure(
                "this node is not armed for a first-run claim (no one-time PIN) — " +
                    "ownership may already be claimed",
            ),
        )
        assertEquals(ClaimFailure.PIN_REJECTED, classifyClaimFailure("invalid claim pin"))
        assertEquals(
            ClaimFailure.UNREACHABLE,
            classifyClaimFailure(
                "target NodeCode carries no transport_hint — cannot reach the node to claim it",
            ),
        )
        assertEquals(
            ClaimFailure.UNREACHABLE,
            classifyClaimFailure("reach target node: connection refused"),
        )
        assertEquals(
            ClaimFailure.BAD_NODE_CODE,
            classifyClaimFailure("decode target NodeCode: bad checksum"),
        )
    }

    @Test
    fun already_claimed_is_not_offered_as_a_retry() {
        // Retrying a node that already has an owner cannot succeed, and telling
        // the operator to try again sends them back to the console for a PIN
        // that was never minted.
        assertFalse(ClaimFailure.ALREADY_CLAIMED.isRetryable)
        assertTrue(ClaimFailure.PIN_REJECTED.isRetryable)
        assertTrue(ClaimFailure.UNREACHABLE.isRetryable)
        assertTrue(ClaimFailure.BAD_NODE_CODE.isRetryable)
    }

    @Test
    fun an_already_claimed_body_is_not_read_as_a_pin_problem() {
        // The not_armed sentence contains the word "PIN". Ordering the prose
        // fallbacks wrongly would send the operator to hunt for a PIN that the
        // node never printed, because it has an owner.
        val body = "this node is not armed for a first-run claim (no one-time PIN)"
        assertEquals(ClaimFailure.ALREADY_CLAIMED, classifyClaimFailure(body))
    }

    @Test
    fun nothing_useful_stays_unknown() {
        assertEquals(ClaimFailure.UNKNOWN, classifyClaimFailure(null))
        assertEquals(ClaimFailure.UNKNOWN, classifyClaimFailure(""))
        assertEquals(ClaimFailure.UNKNOWN, classifyClaimFailure("HTTP 500 internal error"))
    }
}
