package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.federation.CommunityChangeOutcome
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Communities and affiliations against a real socket** (CSD-102, CSD-103).
 *
 * The defects this card can have live in how a served body travels into the
 * view model, so these drive the REAL client over HTTP, as HonestStatesTest
 * does: a bare 404 must read as "this node doesn't serve communities", not as
 * "you are in none"; a `community.quorum_pending` must be HELD as a pending
 * change with its envelope, not shown as a refusal that drops the only copy;
 * and every call must go to the NODE URL, never the client's base URL (on a
 * with-AI install that is the agent, which does not proxy these —
 * CIRISAgent#1213).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunitiesViewModelTest {

    @BeforeTest
    fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }

    @AfterTest
    fun tearDown() { Dispatchers.resetMain() }

    private class Served(val method: String, val path: String, val body: String)

    /** Serves "METHOD /path" → status to body; anything else gets [fallback]. Records every request. */
    private fun serving(
        routes: Map<String, Pair<Int, String>>,
        fallback: Pair<Int, String> = 404 to "",
        log: MutableList<Served> = CopyOnWriteArrayList(),
    ): Pair<HttpServer, MutableList<Served>> {
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

    private val room = """
        {"community_id":"chat:room:v1:abc","name":"Allotment","kind":"room","tier":"community",
         "consensus_protocol":"quorum:2/3","founded_at":"2026-09-01T00:00:00Z","member_count":3,
         "my_role":"founder","members":[
           {"key_id":"k-me","role":"founder","joined_at":"2026-09-01T00:00:00Z"},
           {"key_id":"k-ann","role":"member","joined_at":"2026-09-01T00:00:00Z"},
           {"key_id":"k-bo","role":"member","joined_at":"2026-09-02T00:00:00Z"}],
         "roles":{"founder":["k-me"],"member":["k-ann","k-bo"]}}
    """.trimIndent()

    private val pendingBody = """
        {"error":"1 of 2 required signature(s) under quorum:2/3","reason_id":"community.quorum_pending",
         "change_envelope":{"op":"add","key_id":"k-cy","prior_persist_row_hash":"h1"},
         "signing_bytes_base64":"AA==","signatures":[{"signer":"k-me","sig":"s1"}],
         "valid":1,"required":2,"eligible_signers":["k-ann","k-bo","k-me"],"consensus_protocol":"quorum:2/3"}
    """.trimIndent()

    @Test
    fun a_node_without_the_route_is_not_a_person_in_no_communities() {
        val (node, _) = serving(emptyMap())
        try {
            runBlocking {
                val vm = CommunitiesViewModel(CIRISApiClient(node.url()), "community") { node.url() }
                vm.refresh()
                awaitThat("the list read settles") { vm.rooms.value is CommunityListRead.Failed }
                val failed = assertIs<CommunityListRead.Failed>(vm.rooms.value)
                assertEquals(CommunityReadFailure.NotOnThisNode, failed.failure)
            }
        } finally { node.stop(0) }
    }

    @Test
    fun a_refusal_with_an_id_is_a_refusal_not_a_missing_route() {
        val (node, _) = serving(mapOf(
            "GET /v1/communities" to (503 to """{"error":"down","reason_id":"community.store_unavailable"}"""),
        ))
        try {
            runBlocking {
                val vm = CommunitiesViewModel(CIRISApiClient(node.url()), "community") { node.url() }
                vm.refresh()
                awaitThat("the list read settles") { vm.rooms.value is CommunityListRead.Failed }
                val refused = assertIs<CommunityReadFailure.Refused>((vm.rooms.value as CommunityListRead.Failed).failure)
                assertEquals("community.store_unavailable", refused.reasonId)
            }
        } finally { node.stop(0) }
    }

    @Test
    fun every_call_goes_to_the_node_url_not_the_base_url() {
        val (node, nodeLog) = serving(mapOf("GET /v1/communities" to (200 to """{"communities":[$room],"total":1}""")))
        val (agent, agentLog) = serving(emptyMap())
        try {
            runBlocking {
                // The client's base URL is the AGENT; the view model is told the node.
                val vm = CommunitiesViewModel(CIRISApiClient(agent.url()), "community") { node.url() }
                vm.refresh()
                awaitThat("rooms load") { vm.rooms.value is CommunityListRead.Loaded }
                assertTrue(nodeLog.any { it.path == "/v1/communities" })
                assertTrue(agentLog.none { it.path.startsWith("/v1/communities") }, "a /v1/communities call reached the agent")
            }
        } finally { node.stop(0); agent.stop(0) }
    }

    @Test
    fun a_quorum_pending_add_is_held_then_assembled() {
        val applied = """{"community_id":"chat:room:v1:abc","op":"add","applied":true,"members":[]}"""
        val (node, log) = serving(mapOf(
            "GET /v1/communities" to (200 to """{"communities":[$room],"total":1}"""),
            "POST /v1/communities/chat:room:v1:abc/members" to (409 to pendingBody),
            "POST /v1/communities/chat:room:v1:abc/changes/assemble" to (200 to applied),
        ))
        try {
            runBlocking {
                val vm = CommunitiesViewModel(CIRISApiClient(node.url()), "community") { node.url() }
                vm.addMember("chat:room:v1:abc", "k-cy")
                awaitThat("the add settles") { !vm.busy.value && (vm.pending.value.isNotEmpty() || vm.refusal.value != null) }
                assertNull(vm.refusal.value, "quorum_pending is not a refusal")
                val p = vm.pending.value["chat:room:v1:abc"] ?: fail("the pending change was dropped")
                assertEquals(1, p.signatures.size)
                assertEquals(2, p.required)
                assertEquals(listOf("k-ann", "k-bo", "k-me"), p.eligibleSigners)

                // Ann's signature comes back as her node's cosign response.
                assertTrue(vm.addSignature("chat:room:v1:abc", """{"community_id":"chat:room:v1:abc","op":"add","signature":{"signer":"k-ann","sig":"s2"}}"""))
                assertEquals(2, vm.pending.value.getValue("chat:room:v1:abc").signatures.size)

                vm.assemble("chat:room:v1:abc")
                awaitThat("the assemble applies") { vm.applied.value != null || vm.refusal.value != null }
                assertEquals("add", vm.applied.value)
                assertTrue(vm.pending.value.isEmpty(), "an applied change is no longer pending")
                val sent = log.last { it.path.endsWith("/changes/assemble") }.body
                assertTrue("\"k-ann\"" in sent && "\"k-me\"" in sent, "assemble must carry both signatures: $sent")
                assertTrue("\"prior_persist_row_hash\":\"h1\"" in sent, "assemble must carry the envelope unchanged: $sent")
            }
        } finally { node.stop(0) }
    }

    @Test
    fun a_room_you_are_not_in_is_its_own_state() {
        val (node, _) = serving(mapOf(
            "GET /v1/communities/chat:room:v1:gone" to (404 to """{"error":"no community","reason_id":"community.not_found"}"""),
        ))
        try {
            runBlocking {
                val vm = CommunitiesViewModel(CIRISApiClient(node.url()), "community") { node.url() }
                vm.select("chat:room:v1:gone")
                awaitThat("the detail settles") { vm.detail.value !is CommunityDetailRead.Loading && vm.detail.value !is CommunityDetailRead.NotAsked }
                assertEquals(CommunityDetailRead.NotFound, vm.detail.value)
            }
        } finally { node.stop(0) }
    }

    @Test
    fun the_split_between_pending_and_refused_is_by_id() {
        val api = CIRISApiClient("http://127.0.0.1:1")
        assertIs<CommunityChangeOutcome.Pending>(api.communityChangeOutcome(409, pendingBody))
        assertNull(api.communityChangeOutcome(409, """{"error":"x","reason_id":"community.change_stale"}"""))
        assertIs<CommunityChangeOutcome.Applied>(api.communityChangeOutcome(200, """{"community_id":"c","op":"remove","applied":true}"""))
    }
}
