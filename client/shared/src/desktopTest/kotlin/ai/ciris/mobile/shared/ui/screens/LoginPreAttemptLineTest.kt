package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.models.NewIdentityOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CIRISClient#174: before any attempt, a claimed node's `refused` outcome is
 * about a NEW identity. Its `reason_id` (auth.oauth.no_local_identity) is the
 * sentence for a sign-in that already failed; up front it told the node's own
 * owner their account was refused.
 */
class LoginPreAttemptLineTest {
    @Test
    fun a_refused_new_identity_is_said_hypothetically_never_with_the_post_attempt_reason() {
        val o = NewIdentityOutcome(
            outcome = "refused",
            reasonId = "auth.oauth.no_local_identity",
            remedy = "Claim this node with this account, or ask the node's owner to link it.",
        )
        assertEquals("mobile.login_new_account_refused", preAttemptLineKey(o))
    }

    @Test
    fun the_other_outcomes_keep_their_lines() {
        assertEquals("mobile.login_new_account_observer", preAttemptLineKey(NewIdentityOutcome("admitted_as_observer")))
        assertEquals("mobile.login_new_account_claims", preAttemptLineKey(NewIdentityOutcome("claims_this_node")))
        assertNull(preAttemptLineKey(NewIdentityOutcome("admitted")))
    }
}
