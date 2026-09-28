package ai.ciris.mobile.shared.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /v1/auth/signin-state` as CIRISServer `src/auth/oauth.rs` builds it
 * (#439). The Login screen reads `new_identity` to say, before anyone tries,
 * what a never-seen account would get (CSD-081, CIRISClient#110).
 */
class SigninStateWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun anOwnedPersonalNodeSaysANewAccountWouldBeRefused() {
        val s = json.decodeFromString(
            SigninState.serializer(),
            """{"claimed":true,"managed":false,"web_signin":true,"callback_base":"http://127.0.0.1:4243",
               "exchange_query_key":"ciris_code","providers":["google"],"session_delivery":"loopback_handoff",
               "caller_is_loopback":true,
               "new_identity":{"outcome":"refused","reason_id":"auth.oauth.no_local_identity","remedy":"Sign in with the owner's account."}}""",
        )
        assertTrue(s.claimed)
        assertEquals("loopback_handoff", s.sessionDelivery)
        assertTrue(s.newIdentity!!.isRefused)
        assertEquals("auth.oauth.no_local_identity", s.newIdentity!!.reasonId)
    }

    @Test
    fun aFreshNodeSaysTheFirstAccountClaimsIt() {
        val s = json.decodeFromString(
            SigninState.serializer(),
            """{"claimed":false,"providers":[],"new_identity":{"outcome":"claims_this_node","reason_id":null,"remedy":"x"}}""",
        )
        assertFalse(s.newIdentity!!.isRefused)
        assertEquals("claims_this_node", s.newIdentity!!.outcome)
    }
}
