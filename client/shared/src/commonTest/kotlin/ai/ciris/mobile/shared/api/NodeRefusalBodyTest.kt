package ai.ciris.mobile.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A REFUSAL KEEPS ITS REASON (CIRISClient#70).
 *
 * Login used to bind a refusal body as a LoginResponse, throw a parse
 * exception, and show "Token exchange failed" — while the node had answered
 * `auth.login.ambiguous_name`, which every bundle translates. The id has to
 * survive whichever process answered: the node bare, or a FastAPI front door
 * that nests it under `detail`.
 */
class NodeRefusalBodyTest {

    @Test
    fun theNodesBareShape() {
        val r = NodeRefusal.fromBody(401, """{"error":"that name belongs to two accounts","reason_id":"auth.login.ambiguous_name"}""")
        assertEquals("auth.login.ambiguous_name", r.reasonId)
        assertEquals("that name belongs to two accounts", r.detail)
        assertEquals(401, r.statusCode)
    }

    @Test
    fun aFastApiFrontDoorNestsItUnderDetail() {
        val r = NodeRefusal.fromBody(403, """{"detail":{"code":"auth_personal_install_observer_blocked","message":"observers cannot sign in here"}}""")
        assertEquals("auth_personal_install_observer_blocked", r.reasonId)
        assertEquals("observers cannot sign in here", r.detail)
    }

    @Test
    fun aPlainStringDetailIsTheEnglishWithNoId() {
        val r = NodeRefusal.fromBody(401, """{"detail":"Invalid credentials"}""")
        assertNull(r.reasonId)
        assertEquals("Invalid credentials", r.detail)
    }

    @Test
    fun aBodyThatIsNotJsonIsStillARefusalNotACrash() {
        // A proxy's HTML 502 must not turn into a parse exception — that is the
        // exception the person used to see instead of the reason.
        val r = NodeRefusal.fromBody(502, "<html>bad gateway</html>")
        assertNull(r.reasonId)
        assertNull(r.detail)
        assertEquals(502, r.statusCode)
    }
}
