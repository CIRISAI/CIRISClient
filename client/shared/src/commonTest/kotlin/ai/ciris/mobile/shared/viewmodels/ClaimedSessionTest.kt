package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.ClaimedSessionGrant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The new device collects the session its approval left on it** (CSD-093,
 * CIRISServer 0.5.218 `POST /v1/setup/claimed-session`).
 *
 * Every answer the route gives, and the one a 0.5.217 node gives instead,
 * lands in a state the approval card can act on. The wire half — that the
 * real client sends this to the node with the PIN in the body — is
 * `ClaimedSessionWireTest` (desktopTest).
 */
class ClaimedSessionTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    /** Port 1 on loopback: never a real node. The collector is injected, so nothing is sent. */
    private fun client() = CIRISApiClient("http://127.0.0.1:1", null)

    private suspend fun SetupViewModel.settled(): ClaimedSessionState =
        withContext(Dispatchers.Default) {
            withTimeout(15_000) {
                claimedSession.first { it != ClaimedSessionState.Idle && it != ClaimedSessionState.Collecting }
            }
        }

    @Test
    fun aCollectedSessionIsAppliedAndHandedToTheHost() = runBlocking {
        val api = client()
        val vm = SetupViewModel(api)
        var pinSent: String? = null
        var persisted: String? = null

        vm.collectClaimedSession(
            claimPinProvider = { " 7F3K-Q9MZ " },
            collector = { pin ->
                pinSent = pin
                ClaimedSessionGrant(accessToken = "tok-owner", tokenType = "Bearer", expiresIn = 86_400, role = "SYSTEM_ADMIN", userId = "wa-root")
            },
            onSession = { persisted = it.accessToken },
        )

        assertEquals(ClaimedSessionState.Collected("SYSTEM_ADMIN"), vm.settled())
        assertEquals("7F3K-Q9MZ", pinSent, "the PIN is sent trimmed")
        assertEquals("tok-owner", api.getAccessToken(), "the session is applied as the self-claim applies its own")
        assertEquals("tok-owner", persisted, "the host is handed the session to persist")
    }

    @Test
    fun aWrongPinIsARefusalByIdAndKeepsTheCardOpen() = runBlocking {
        val api = client()
        val vm = SetupViewModel(api)

        vm.collectClaimedSession(
            claimPinProvider = { "AAAA-BBBB" },
            collector = {
                throw NodeRefusal.fromBody(401, """{"error":"invalid one-time claim PIN","reason_id":"auth.claim.pin_invalid"}""")
            },
        )

        val s = vm.settled()
        assertIs<ClaimedSessionState.Refused>(s)
        assertEquals(ClaimedSessionState.PIN_INVALID, s.reasonId)
        assertEquals(401, s.status)
        assertFalse(s.goesToLogin)
        assertNull(api.getAccessToken(), "a refusal applies no session")
    }

    @Test
    fun nothingWaitingIsARefusalThatSendsThePersonToSignIn() = runBlocking {
        val vm = SetupViewModel(client())

        vm.collectClaimedSession(
            claimPinProvider = { "AAAA-BBBB" },
            collector = {
                throw NodeRefusal.fromBody(
                    404,
                    """{"error":"no session is waiting","reason_id":"auth.claim.no_pending_session"}""",
                )
            },
        )

        val s = vm.settled()
        assertIs<ClaimedSessionState.Refused>(s)
        assertEquals(ClaimedSessionState.NO_PENDING_SESSION, s.reasonId)
        assertTrue(s.goesToLogin, "a 404 WITH an id is the route answering — not a node too old for it")
    }

    @Test
    fun aBare404IsANodeOlderThan0_5_218() = runBlocking {
        val api = client()
        val vm = SetupViewModel(api)

        vm.collectClaimedSession(
            claimPinProvider = { "AAAA-BBBB" },
            collector = { throw NodeRefusal.fromBody(404, "") },
        )

        assertEquals(ClaimedSessionState.NodeTooOld, vm.settled())
        assertNull(api.getAccessToken())
    }

    @Test
    fun noPinMeansNothingIsAsked() = runBlocking {
        val vm = SetupViewModel(client())
        var asked = false

        vm.collectClaimedSession(claimPinProvider = { null }, collector = { asked = true; error("unreachable") })

        assertEquals(ClaimedSessionState.NoPin, vm.settled())
        assertFalse(asked)
    }

    @Test
    fun aNodeThatDoesNotAnswerIsAFailureNotARefusal() = runBlocking {
        val vm = SetupViewModel(client())

        vm.collectClaimedSession(
            claimPinProvider = { "AAAA-BBBB" },
            collector = { throw RuntimeException("Connection refused") },
        )

        assertEquals(ClaimedSessionState.Failed("Connection refused"), vm.settled())
    }
}
