package ai.ciris.mobile.shared.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * One poll of `GET /v1/auth/oauth/handoff`, as the node answers it (CSD-081).
 * The node parks a failed flow as a 200 carrying `status: "failed"` and the
 * reason, hands it over once, and answers 410 `auth.oauth.flow_expired` after.
 * Reading that 200 as "not yet" is how the person ended up seeing "expired".
 */
class OAuthHandoffPollTest {
    @Test
    fun aFailedFlowParkedAsA200IsTheNodesReasonNotAWait() {
        val poll = OAuthHandoffPoll.classify(
            200,
            """{"status":"failed","error":"no identity","reason_id":"auth.oauth.no_local_identity","provider":"google"}""",
        )
        assertEquals(OAuthHandoffPoll.Failed("auth.oauth.no_local_identity", "failed"), poll)
    }

    @Test
    fun aCompletedFlowIsASession() {
        val poll = OAuthHandoffPoll.classify(
            200,
            """{"status":"complete","access_token":"sess:x","provider":"google","external_id":"1"}""",
        )
        assertIs<OAuthHandoffPoll.Ready>(poll)
        assertEquals("sess:x", poll.handoff.accessToken)
    }

    @Test
    fun notYetAndBlipsKeepWaitingAndAnExpiryStops() {
        assertEquals(OAuthHandoffPoll.Pending, OAuthHandoffPoll.classify(204, ""))
        assertEquals(OAuthHandoffPoll.Pending, OAuthHandoffPoll.classify(502, "<html>bad gateway</html>"))
        assertEquals(
            OAuthHandoffPoll.Failed("auth.oauth.flow_expired", null),
            OAuthHandoffPoll.classify(410, """{"reason_id":"auth.oauth.flow_expired"}"""),
        )
    }
}
