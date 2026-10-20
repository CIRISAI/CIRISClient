package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientSelfRoster
import ai.ciris.mobile.shared.api.NodeRefusal
import androidx.lifecycle.ViewModel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The self roster's held changes, on the wire** (CSD-095; CIRISServer#794 as
 * pushed in CIRISServer#802 @ `e1201b69`, `src/self_devices.rs:1405-1541`, routes
 * `:1572-1583`). The in-test node answers the handlers' shapes: the list
 * (`roster_proposals`, `:1428-1451`, each row `standing_view` `:1417-1424` over
 * persist v59's `Standing`), the two answers (`answer_proposal`, `:1478-1541`),
 * their two refusals in the server's own English, and a node without the
 * route (bare 404).
 */
class SelfRosterWireTest {

    private class Node(val routes: Map<String, () -> Pair<Int, String>>) {
        val seen: MutableList<String> = CopyOnWriteArrayList()
        val auth: MutableList<String?> = CopyOnWriteArrayList()
        val bodies: MutableList<String> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                val key = "${ex.requestMethod} ${ex.requestURI.rawPath}"
                seen += key
                auth += ex.requestHeaders.getFirst("Authorization")
                if (ex.requestMethod == "POST") bodies += body
                val (status, out) = routes[key]?.invoke() ?: (404 to "")
                val bytes = out.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private val made = mutableListOf<ViewModel>()
    private val nodes = mutableListOf<Node>()
    private fun node(routes: Map<String, () -> Pair<Int, String>>) = Node(routes).also { nodes += it }
    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() {
        stopViewModels(made)
        Dispatchers.resetMain()
        nodes.forEach { it.server.stop(0) }
    }

    private fun client(n: Node) = CIRISApiClient(n.url, null).also { it.setAccessToken("tok-owner") }

    /** A pending add from the laptop, one approval short, lone path open. */
    private val pendingAdd = """{"proposal":{"id":"prop-add-c","kind":"add","proposer":"fed-owner-a1a1","target":"fed-owner-c3c3",""" +
        """"asserted_at":"2026-10-20T15:00:00Z","received_at":"2026-10-20T15:00:01Z"},""" +
        """"decision":{"outcome":"pending","earliest_lone_at":"2026-10-21T15:00:01Z","needs":1},""" +
        """"decided_at":"2026-10-20T15:00:05Z","approvers":[],"objectors":[],"outcome":"pending","lands":false}"""

    /** A pending evict whose lone path is closed (`earliest_lone_at: null`), one objector. */
    private val pendingEvict = """{"proposal":{"id":"prop-evict-b","kind":"evict","proposer":"fed-owner","target":"fed-owner-b2b2",""" +
        """"asserted_at":"2026-10-20T15:01:00Z","received_at":"2026-10-20T15:01:00Z"},""" +
        """"decision":{"outcome":"pending","earliest_lone_at":null,"needs":2},""" +
        """"decided_at":"2026-10-20T15:01:05Z","approvers":[],"objectors":["fed-owner-b2b2"],"outcome":"pending","lands":false}"""

    /** A refused add: the decision's tag is `refused`, the top-level outcome is the reason's token. */
    private val refusedAdd = """{"proposal":{"id":"prop-dup","kind":"add","proposer":"fed-owner-b2b2","target":"fed-owner-c3c3",""" +
        """"asserted_at":"2026-10-20T15:02:00Z","received_at":"2026-10-20T15:02:00Z"},""" +
        """"decision":{"outcome":"refused","reason":"target_has_open_proposal"},""" +
        """"decided_at":"2026-10-20T15:02:05Z","approvers":[],"objectors":[],"outcome":"target_has_open_proposal","lands":false}"""

    private val listing = """{"identity_key_id":"fed-owner","roster":["fed-owner","fed-owner-a1a1","fed-owner-b2b2"],""" +
        """"w_self_secs":86400,"proposals":[$pendingAdd,$pendingEvict,$refusedAdd]}"""

    @Test
    fun theListDecodesEveryDecisionShapeWithTheOwnerSession() = runBlocking<Unit> {
        val n = node(mapOf("GET /v1/self/roster/proposals" to { 200 to listing }))
        val r = client(n).getRosterProposals(n.url)
        assertEquals("tok-owner".let { "Bearer $it" }, n.auth.single())
        assertEquals("fed-owner", r.identityKeyId)
        assertEquals(3, r.roster.size)
        assertEquals(86_400, r.wSelfSecs)
        val (add, evict, dup) = r.proposals
        assertEquals("add", add.proposal.kind)
        assertEquals("fed-owner-a1a1", add.proposal.proposer)
        assertEquals("pending", add.decision.outcome)
        assertEquals(1, add.decision.needs)
        assertEquals("2026-10-21T15:00:01Z", add.decision.earliestLoneAt)
        assertEquals(1, add.signaturesSoFar())
        assertEquals(2, add.signaturesNeeded(r.roster.size))
        assertNull(evict.decision.earliestLoneAt, "null = the lone path is closed")
        assertEquals(listOf("fed-owner-b2b2"), evict.objectors)
        assertEquals("refused", dup.decision.outcome)
        assertEquals("target_has_open_proposal", dup.decision.reason)
        assertEquals("target_has_open_proposal", dup.outcome)
    }

    @Test
    fun assentAndObjectPostAnEmptyBodyToTheirOwnRoutes() = runBlocking<Unit> {
        val n = node(mapOf(
            "POST /v1/self/roster/proposals/prop-add-c/assent" to {
                200 to """{"proposal_id":"prop-add-c","answer":"assent","answer_attestation_id":"att-assent-1","settled":true,"proposal":null}"""
            },
            "POST /v1/self/roster/proposals/prop-evict-b/object" to {
                200 to """{"proposal_id":"prop-evict-b","answer":"object","answer_attestation_id":"att-object-1","settled":false,"proposal":$pendingEvict}"""
            },
        ))
        val api = ClientSelfRoster(client(n)) { n.url }
        val a = api.assent("prop-add-c")
        assertEquals("assent", a.answer)
        assertTrue(a.settled)
        assertNull(a.proposal)
        assertEquals("att-assent-1", a.answerAttestationId)
        val o = api.objectTo("prop-evict-b")
        assertEquals("object", o.answer)
        assertFalse(o.settled)
        assertEquals("prop-evict-b", assertNotNull(o.proposal).proposal.id)
        assertEquals(listOf("{}", "{}"), n.bodies)
        assertTrue(n.auth.all { it == "Bearer tok-owner" })
    }

    @Test
    fun anUnknownProposalIsRefusedByItsId() = runBlocking<Unit> {
        val n = node(mapOf("POST /v1/self/roster/proposals/gone/assent" to {
            404 to """{"error":"There is no open change to your devices with that id.","reason_id":"self.roster_proposal_unknown","detail":"gone"}"""
        }))
        val e = assertFailsWith<NodeRefusal> { ClientSelfRoster(client(n)) { n.url }.assent("gone") }
        assertEquals(404, e.statusCode)
        assertEquals(ROSTER_PROPOSAL_UNKNOWN, e.reasonId)
    }

    @Test
    fun theProposersOwnAnswerIsRefusedAndTheCardStopsOfferingIt() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/self/roster/proposals" to { 200 to listing },
            "POST /v1/self/roster/proposals/prop-add-c/object" to {
                409 to """{"error":"This device proposed that change; another of your devices answers it.","reason_id":"self.roster_own_proposal","detail":"prop-add-c"}"""
            },
        ))
        val vm = SelfRosterViewModel(ClientSelfRoster(client(n)) { n.url }).also { made += it }
        vm.refreshNow()
        val add = vm.state.value.proposals.first { it.proposal.id == "prop-add-c" }
        assertTrue(vm.state.value.canAnswer(add))
        vm.askObject(add)
        vm.confirm().join()
        val s = vm.state.value
        assertEquals(ROSTER_OWN_PROPOSAL, s.answerRefusal?.reasonId)
        assertEquals(409, s.answerRefusal?.status)
        assertTrue("prop-add-c" in s.ownProposals)
        assertFalse(s.canAnswer(s.proposals.first { it.proposal.id == "prop-add-c" }))
    }

    @Test
    fun aNodeWithoutTheRouteIsTooOldNotAnError() = runBlocking<Unit> {
        val n = node(emptyMap())
        val vm = SelfRosterViewModel(ClientSelfRoster(client(n)) { n.url }).also { made += it }
        vm.refreshNow()
        assertTrue(vm.state.value.nodeTooOld)
        assertNull(vm.state.value.refusal)
        assertEquals(listOf("GET /v1/self/roster/proposals"), n.seen.toList())
    }
}
