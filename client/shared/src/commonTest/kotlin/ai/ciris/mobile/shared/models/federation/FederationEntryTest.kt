package ai.ciris.mobile.shared.models.federation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The federation-identity door, and the one thing that opens it.
 *
 * This surface did not exist in this tree until 0.5.190 — the login screen
 * carried a note saying there was no fedID sign-in option *by design*. The
 * reasoning was right; the conclusion was to leave the door out rather than shut
 * it, which cost the consumers a feature they have. The door is here now, so the
 * rule that keeps it shut is worth more than a comment.
 *
 * Every assertion below is about NOT admitting someone. The opposite mistake is
 * cheap — a founder who has to sign in with their account is one tap from what
 * they wanted, and the node would refuse to sign with the fedID without that
 * session anyway.
 */
class FederationEntryTest {

    @Test
    fun a_live_owner_session_opens_it() {
        assertTrue(mayEnterWithFederationIdentity("eyJhbGciOi.session.token"))
    }

    @Test
    fun no_session_at_all_does_not() {
        // THE CASE THIS EXISTS FOR. A long-lived identity sitting on the device
        // is not proof of who is holding the device, and entering on its presence
        // alone is a second, credential-less door beside the account login.
        assertFalse(
            mayEnterWithFederationIdentity(null),
            "the identity's presence on the device is not a credential",
        )
    }

    @Test
    fun an_empty_or_blank_token_is_not_a_session() {
        // A token cleared to "" rather than null is the shape a refactor
        // produces, and `!= null` would have admitted it.
        assertFalse(mayEnterWithFederationIdentity(""))
        assertFalse(mayEnterWithFederationIdentity("   "))
        assertFalse(mayEnterWithFederationIdentity("\t\n"))
    }

    @Test
    fun the_rule_reads_only_the_session() {
        // No fedID, key_id, device state or probe result is an input. If any of
        // them ever becomes one, this stops compiling — which is the point of
        // the rule being a function with one parameter rather than a condition
        // inlined in a composable, where "and the identity is present" could be
        // added to it without anyone noticing.
        assertTrue(mayEnterWithFederationIdentity("t"))
        assertFalse(mayEnterWithFederationIdentity(null))
    }
}
