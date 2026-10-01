package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientHouseholds
import ai.ciris.mobile.shared.api.ClientMembershipInvites
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.CommunityChangeOutcome
import ai.ciris.mobile.shared.ui.screens.HouseholdAct
import ai.ciris.mobile.shared.ui.screens.InviteSupport
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Invitations over a real socket** (CSD-106, CIRISServer 0.5.218
 * `src/membership_invites.rs`, `family_api.rs::invite`, `communities.rs::invite`).
 *
 * The bodies below are the handlers' own `json!` shapes. What can go wrong
 * lives in how they travel: the path each call takes, the 202 an invitation
 * answers with (which must never decode as an applied add), the id a refusal
 * carries, a bare 404 read as "an older node" rather than "you have none",
 * and every call reaching the NODE, never the agent's base URL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MembershipInvitesWireTest {

    @BeforeTest
    fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }

    @AfterTest
    fun tearDown() { Dispatchers.resetMain() }

    private class Served(val method: String, val path: String, val body: String)

    private fun serving(
        routes: Map<String, Pair<Int, String>>,
        fallback: Pair<Int, String> = 404 to "",
    ): Pair<HttpServer, MutableList<Served>> {
        val log: MutableList<Served> = CopyOnWriteArrayList()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                log += Served(ex.requestMethod, ex.requestURI.rawPath, body)
                val (status, out) = routes["${ex.requestMethod} ${ex.requestURI.rawPath}"] ?: fallback
                val bytes = out.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        return server to log
    }

    private fun HttpServer.url() = "http://127.0.0.1:${address.port}"

    private suspend fun awaitThat(what: String, condition: () -> Boolean) {
        try {
            withTimeout(15_000) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            fail("timed out waiting for: $what")
        }
    }

    private val fam = "family:v1:aa"
    private val room = "chat:room:v1:abc"
    private val invited = """{"state":"invited","proposal_id":"att-1","group_kind":"family","group_id":"$fam",
        "invitee_key_id":"k-cy","role":"member","expires_at":"2026-10-15T00:00:00+00:00"}"""
    private val familyList = """{"families":[{"family_id":"$fam","name":"Okafors","consensus_protocol":"founder_only",
        "members":[{"key_id":"k-me","role":"founder"}],"my_role":"founder"}],"resume":null}"""
    private val groupInvites = """{"family_id":"$fam","invites":[
        {"proposal_id":"att-1","invitee_key_id":"k-cy","role":"member","proposer_key_id":"k-me",
         "proposed_at":"2026-10-01T00:00:00+00:00","expires_at":"2026-10-15T00:00:00+00:00","state":"pending","reply_id":null},
        {"proposal_id":"att-0","invitee_key_id":"k-bo","role":"member","proposer_key_id":"k-me",
         "proposed_at":"2026-09-01T00:00:00+00:00","expires_at":"2026-09-15T00:00:00+00:00","state":"joined","reply_id":"r-0"}],
        "seated_now":[],"dek_rewrap":{}}"""
    private val inbox = """{"invitee_key_id":"k-me","invites":[
        {"proposal_id":"att-9","group_kind":"family","group_id":"$fam","group_name":"Okafors","is_pair_room":false,
         "role":"member","proposer_key_id":"k-ada","proposed_at":"2026-10-01T00:00:00+00:00","expires_at":"2026-10-15T00:00:00+00:00"},
        {"proposal_id":"att-8","group_kind":"community","group_id":"chat:pair:v1:x","group_name":null,"is_pair_room":true,
         "role":"founder","proposer_key_id":"k-bo","proposed_at":"2026-10-01T00:00:00+00:00","expires_at":"2026-10-15T00:00:00+00:00"}]}"""

    // ── The wire, call by call ────────────────────────────────────────────

    @Test
    fun the_household_invite_routes_carry_their_paths_bodies_and_answers() {
        val (node, log) = serving(mapOf(
            "POST /v1/families/$fam/invites" to (202 to invited),
            "GET /v1/families/$fam/invites" to (200 to groupInvites),
            "DELETE /v1/families/$fam/invites/att-1" to (200 to """{"state":"withdrawn","proposal_id":"att-1","withdrawal_id":"w-1"}"""),
        ))
        val (agent, agentLog) = serving(emptyMap())
        try {
            runBlocking {
                val api = ClientHouseholds(CIRISApiClient(agent.url()), node.url())
                val sent = api.inviteMember(fam, "k-cy", null)
                assertEquals("invited", sent.state)
                assertEquals("att-1", sent.proposalId)
                assertEquals("2026-10-15T00:00:00+00:00", sent.expiresAt)
                assertEquals("""{"key_id":"k-cy"}""", log.last().body)
                val list = api.listInvites(fam)
                assertEquals(listOf("pending", "joined"), list.invites.map { it.state })
                assertEquals("k-me", list.invites.first().proposerKeyId)
                assertEquals("withdrawn", api.withdrawInvite(fam, "att-1").state)
                assertEquals(
                    listOf("POST /v1/families/$fam/invites", "GET /v1/families/$fam/invites", "DELETE /v1/families/$fam/invites/att-1"),
                    log.map { "${it.method} ${it.path}" },
                )
                assertTrue(agentLog.isEmpty(), "an invitation call reached the agent's base URL")
            }
        } finally { node.stop(0); agent.stop(0) }
    }

    @Test
    fun the_inbox_routes_carry_their_paths_and_answers() {
        val (node, log) = serving(mapOf(
            "GET /v1/self/invites" to (200 to inbox),
            "POST /v1/self/invites/att-9/accept" to (200 to """{"state":"accepted","proposal_id":"att-9","reply_id":"r-9",
                "group_kind":"family","group_id":"$fam","awaiting":"the group's widening"}"""),
            "POST /v1/self/invites/att-9/decline" to (200 to """{"state":"declined","proposal_id":"att-9","reply_id":"r-9",
                "group_kind":"family","group_id":"$fam","awaiting":null}"""),
        ))
        try {
            runBlocking {
                val api = ClientMembershipInvites(CIRISApiClient("http://127.0.0.1:1"), node.url())
                val rows = api.myInvites().invites
                assertEquals(2, rows.size)
                assertTrue(rows[1].isPairRoom)
                assertNull(rows[1].groupName)
                val accepted = api.accept("att-9")
                assertEquals("accepted", accepted.state)
                assertEquals("the group's widening", accepted.awaiting)
                assertNull(api.decline("att-9").awaiting)
                assertEquals(
                    listOf("GET /v1/self/invites", "POST /v1/self/invites/att-9/accept", "POST /v1/self/invites/att-9/decline"),
                    log.map { "${it.method} ${it.path}" },
                )
            }
        } finally { node.stop(0) }
    }

    @Test
    fun the_community_invite_routes_carry_their_paths() {
        val (node, log) = serving(mapOf(
            "POST /v1/communities/$room/invites" to (202 to invited.replace("\"family\"", "\"community\"")),
            "GET /v1/communities/$room/invites" to (200 to """{"community_id":"$room","invites":[],"seated_now":[]}"""),
            "DELETE /v1/communities/$room/invites/att-1" to (200 to """{"state":"withdrawn","proposal_id":"att-1","withdrawal_id":"w"}"""),
        ))
        try {
            runBlocking {
                val c = CIRISApiClient("http://127.0.0.1:1")
                assertEquals("att-1", c.inviteCommunityMember(room, "k-cy", null, node.url()).proposalId)
                assertTrue(c.listCommunityInvites(room, node.url()).invites.isEmpty())
                assertEquals("withdrawn", c.withdrawCommunityInvite(room, "att-1", node.url()).state)
                assertEquals(3, log.size)
            }
        } finally { node.stop(0) }
    }

    @Test
    fun a_202_invited_from_the_members_alias_is_never_an_applied_add() {
        val (node, _) = serving(mapOf(
            "POST /v1/families/$fam/members" to (202 to invited),
            "POST /v1/communities/$room/members" to (202 to invited),
        ))
        try {
            runBlocking {
                val c = CIRISApiClient("http://127.0.0.1:1")
                assertEquals("att-1", c.addFamilyMember(fam, "k-cy", null, node.url())?.proposalId)
                val out = c.addCommunityMember(room, "k-cy", null, node.url())
                assertEquals("att-1", assertIs<CommunityChangeOutcome.Invited>(out).invite.proposalId)
                // And an old node's applied add is still an applied add.
                assertIs<CommunityChangeOutcome.Applied>(c.communityChangeOutcome(200, """{"community_id":"c","op":"add","applied":true}"""))
            }
        } finally { node.stop(0) }
    }

    @Test
    fun every_refusal_id_the_handlers_emit_arrives_with_its_id() {
        // Status per `membership_invites.rs`: the 404, 403, 400, 401, 410 and 503 ones, the rest 409.
        val status = mapOf(
            "membership.invite_not_found" to 404, "membership.not_the_invitee" to 403,
            "membership.not_the_proposer" to 403, "membership.acceptance_mismatch" to 403,
            "membership.delegate_may_not_answer" to 403, "membership.signer_unavailable" to 403,
            "membership.bad_expiry" to 400, "membership.owner_session_required" to 401,
            "membership.invite_expired" to 410, "membership.store_unavailable" to 503,
        )
        for (id in MEMBERSHIP_IDS) {
            val code = status[id] ?: 409
            val (node, _) = serving(mapOf(
                "POST /v1/self/invites/att-9/accept" to (code to """{"error":"English.","reason_id":"$id","detail":"persist"}"""),
            ))
            try {
                runBlocking {
                    val api = ClientMembershipInvites(CIRISApiClient("http://127.0.0.1:1"), node.url())
                    val e = assertFailsWith<NodeRefusal> { api.accept("att-9") }
                    assertEquals(id, e.reasonId)
                    assertEquals(code, e.statusCode)
                }
            } finally { node.stop(0) }
        }
    }

    // ── The view models over the socket ───────────────────────────────────

    @Test
    fun a_node_without_the_inbox_route_is_an_older_node_not_an_empty_inbox() {
        val (node, _) = serving(emptyMap())
        try {
            runBlocking {
                val vm = InvitationsViewModel(ClientMembershipInvites(CIRISApiClient("http://127.0.0.1:1"), node.url()))
                vm.load()
                awaitThat("the inbox settles") { vm.inbox.value != InboxRead.Loading && vm.inbox.value != InboxRead.NotAsked }
                assertEquals(InboxRead.NotOnThisNode, vm.inbox.value)
                assertEquals(InviteSupport.LEGACY, vm.support.value)
            }
        } finally { node.stop(0) }
    }

    @Test
    fun accepting_over_the_socket_is_accepted_awaiting_the_group() {
        val (node, log) = serving(mapOf(
            "GET /v1/self/invites" to (200 to inbox),
            "POST /v1/self/invites/att-9/accept" to (200 to """{"state":"accepted","proposal_id":"att-9","reply_id":"r",
                "group_kind":"family","group_id":"$fam","awaiting":"the group's widening"}"""),
        ))
        try {
            runBlocking {
                val vm = InvitationsViewModel(ClientMembershipInvites(CIRISApiClient("http://127.0.0.1:1"), node.url()))
                vm.load()
                awaitThat("the inbox loads") { vm.inbox.value is InboxRead.Loaded }
                val row = (vm.inbox.value as InboxRead.Loaded).invites.first()
                vm.request(row, accept = true)
                vm.confirm()
                awaitThat("the answer settles") { vm.answered.value != null || vm.refusal.value != null }
                assertEquals("the group's widening", vm.answered.value?.awaiting)
                assertTrue(log.any { it.method == "POST" && it.path == "/v1/self/invites/att-9/accept" })
            }
        } finally { node.stop(0) }
    }

    @Test
    fun the_household_roster_detects_an_older_node_by_the_route_and_keeps_the_add() {
        val (node, log) = serving(mapOf(
            "GET /v1/families" to (200 to familyList),
            "POST /v1/families/$fam/members" to (200 to """{"family_id":"$fam"}"""),
        ))
        try {
            runBlocking {
                val vm = HouseholdsViewModel(ClientHouseholds(CIRISApiClient("http://127.0.0.1:1"), node.url()))
                vm.load()
                awaitThat("the households load") { vm.selected() != null }
                vm.loadInvites()
                awaitThat("the invites route is asked") { vm.inviteSupport.value != InviteSupport.UNKNOWN }
                assertEquals(InviteSupport.LEGACY, vm.inviteSupport.value, "a bare 404 on GET …/invites is 0.5.216–0.5.217")
                vm.request(vm.pickAct("k-cy", "Cy"))
                vm.confirmMemberAct()
                awaitThat("the add settles") { vm.notice.value != null || vm.refusal.value != null }
                assertEquals(HouseholdNotice.ADDED, vm.notice.value)
                assertTrue(log.none { it.method == "POST" && it.path.endsWith("/invites") })
            }
        } finally { node.stop(0) }
    }

    @Test
    fun the_household_roster_on_a_218_node_invites() {
        val (node, log) = serving(mapOf(
            "GET /v1/families" to (200 to familyList),
            "GET /v1/families/$fam/invites" to (200 to groupInvites),
            "POST /v1/families/$fam/invites" to (202 to invited),
        ))
        try {
            runBlocking {
                val vm = HouseholdsViewModel(ClientHouseholds(CIRISApiClient("http://127.0.0.1:1"), node.url()))
                vm.load()
                awaitThat("the households load") { vm.selected() != null }
                vm.loadInvites()
                awaitThat("the invites load") { vm.invites.value is GroupInvitesRead.Loaded }
                assertEquals(InviteSupport.INVITES, vm.inviteSupport.value)
                val act = vm.pickAct("k-cy", "Cy")
                assertIs<HouseholdAct.Invite>(act)
                vm.request(act)
                vm.confirmMemberAct()
                awaitThat("the invite settles") { vm.notice.value != null || vm.refusal.value != null }
                assertEquals(HouseholdNotice.INVITED, vm.notice.value)
                assertTrue(log.none { it.path.endsWith("/members") }, "no direct add on a node that invites")
            }
        } finally { node.stop(0) }
    }

    @Test
    fun a_community_room_on_an_older_node_keeps_the_add_and_an_invite_sends_nothing_else() {
        val (node, log) = serving(emptyMap())
        try {
            runBlocking {
                val vm = CommunitiesViewModel(CIRISApiClient("http://127.0.0.1:1"), "community") { node.url() }
                vm.loadInvites(room)
                awaitThat("the room's invites settle") { vm.inviteSupport.value != InviteSupport.UNKNOWN }
                assertEquals(InviteSupport.LEGACY, vm.inviteSupport.value)
                vm.invite(room, "k-cy")
                awaitThat("the invite settles") { vm.refusal.value != null }
                assertEquals(INVITES_NOT_ON_THIS_NODE, vm.refusal.value?.reasonId)
                assertTrue(log.none { it.path.endsWith("/members") }, "a confirmed invitation is never turned into an add")
            }
        } finally { node.stop(0) }
    }

    @Test
    fun consent_required_on_a_community_add_rereads_the_invites_route() {
        val (node, log) = serving(mapOf(
            "POST /v1/communities/$room/members" to (409 to """{"error":"x","reason_id":"membership.consent_required"}"""),
            "GET /v1/communities/$room/invites" to (200 to """{"community_id":"$room","invites":[],"seated_now":[]}"""),
        ))
        try {
            runBlocking {
                val vm = CommunitiesViewModel(CIRISApiClient("http://127.0.0.1:1"), "community") { node.url() }
                vm.addMember(room, "k-cy")
                awaitThat("the add settles and the route is re-read") {
                    vm.refusal.value != null && vm.inviteSupport.value == InviteSupport.INVITES
                }
                assertEquals("membership.consent_required", vm.refusal.value?.reasonId)
                assertTrue(log.any { it.method == "GET" && it.path == "/v1/communities/$room/invites" })
            }
        } finally { node.stop(0) }
    }

    @Test
    fun a_community_invite_on_a_218_node_is_an_invitation() {
        val (node, _) = serving(mapOf(
            "POST /v1/communities/$room/invites" to (202 to invited),
            "GET /v1/communities/$room/invites" to (200 to """{"community_id":"$room","invites":[
                {"proposal_id":"att-1","invitee_key_id":"k-cy","proposer_key_id":"k-me","state":"pending"}],"seated_now":[]}"""),
        ))
        try {
            runBlocking {
                val vm = CommunitiesViewModel(CIRISApiClient("http://127.0.0.1:1"), "community") { node.url() }
                vm.invite(room, "k-cy")
                awaitThat("the invite lands") { vm.invites.value[room] is GroupInvitesRead.Loaded }
                assertEquals(CommunitiesViewModel.NOTICE_INVITED, vm.inviteNotice.value)
                assertNull(vm.applied.value, "an invitation is never an applied add")
                assertEquals(InviteSupport.INVITES, vm.inviteSupport.value)
            }
        } finally { node.stop(0) }
    }
}
