package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.chat.ChatCommunity
import ai.ciris.mobile.shared.models.chat.PairPhase
import ai.ciris.mobile.shared.models.chat.pairPhase
import ai.ciris.mobile.shared.models.chat.pairRooms
import ai.ciris.mobile.shared.models.federation.EvictionReport
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The three 0.5.218 changes, over the wire** (CIRISServer#706, #700, and the
 * drive's whole-read cap): the real client against a real socket answering
 * with the bytes `src/contacts_chat.rs`, `src/membership_invites.rs`,
 * `src/self_devices.rs` and `src/drive.rs` produce at 53d1ffb5. The view-model
 * tests next door drive the states; this proves the client sends what the
 * server reads and decodes what it sends.
 */
class Server218ChatEvictWireTest {

    /** method + path → (status, body). Records every request line and body. */
    private class Node(private val routes: Map<String, Pair<Int, String>>) {
        val seen: MutableList<String> = CopyOnWriteArrayList()
        val bodies: MutableList<String> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val key = "${exchange.requestMethod} ${exchange.requestURI.rawPath}"
                seen += key
                bodies += exchange.requestBody.readBytes().decodeToString()
                val (status, body) = routes[key] ?: (404 to "")
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private val nodes = mutableListOf<Node>()
    private fun node(routes: Map<String, Pair<Int, String>>) = Node(routes).also { nodes += it }
    @AfterTest fun stop() { nodes.forEach { it.server.stop(0) } }

    private fun client(n: Node) = CIRISApiClient(baseUrl = "http://127.0.0.1:9").also { it.setAccessToken("owner-session") }

    @Test
    fun anInvitedPairRoomDecodesWithItsStateAndProposal() = runBlocking {
        // `pair_room_response` at 53d1ffb5: StartChatResponse + state + proposal_id.
        val n = node(mapOf("POST /v1/chat" to (200 to
            """{"community_id":"chat:pair:v1:ab","community_name":"a <-> b","member_key_ids":["a"],""" +
            """"cohort_scope":"community","freshly_created":true,"state":"invited","proposal_id":"prop-1"}""")))
        val room = client(n).startChat("b", nodeUrl = n.url)
        assertEquals("invited", room.state)
        assertEquals("prop-1", room.proposalId)
        assertEquals(PairPhase.WAITING_FOR_THEM, room.pairPhase())
        assertTrue(n.bodies.single().contains("\"key_id\""))
    }

    @Test
    fun a0_5_217PairRoomAnswerHasNoStateAndIsOpen() = runBlocking {
        val n = node(mapOf("POST /v1/chat" to (200 to
            """{"community_id":"chat:pair:v1:ab","community_name":"a <-> b","member_key_ids":["a","b"],"cohort_scope":"community","freshly_created":false}""")))
        val room: ChatCommunity = client(n).startChat("b", nodeUrl = n.url)
        assertNull(room.state)
        assertNull(room.proposalId)
        assertEquals(PairPhase.OPEN, room.pairPhase())
    }

    @Test
    fun theInboxIsReadAndADeclineIsPostedToTheInvitationsOwnRoute() = runBlocking {
        val n = node(mapOf(
            "GET /v1/self/invites" to (200 to
                """{"invitee_key_id":"me","invites":[""" +
                """{"proposal_id":"prop-pair","group_kind":"community","group_id":"chat:pair:v1:ab","group_name":null,"is_pair_room":true,"role":"founder","proposer_key_id":"node-x","proposed_at":"t","expires_at":"t2"},""" +
                """{"proposal_id":"prop-fam","group_kind":"family","group_id":"family:1","group_name":"Home","is_pair_room":false,"role":"member","proposer_key_id":"p","proposed_at":"t","expires_at":"t2"}]}"""),
            "POST /v1/self/invites/prop-pair/decline" to (200 to
                """{"state":"declined","proposal_id":"prop-pair","reply_id":"r1","group_kind":"community","group_id":"chat:pair:v1:ab","awaiting":null}"""),
        ))
        val c = client(n)
        val inbox = c.listMyInvites(n.url)
        assertEquals(listOf("prop-pair"), inbox.pairRooms.map { it.proposalId })
        val answer = c.declineInvite("prop-pair", n.url)
        assertEquals("declined", answer.state)
        assertEquals(listOf("GET /v1/self/invites", "POST /v1/self/invites/prop-pair/decline"), n.seen)
    }

    @Test
    fun aNodeWithoutTheInboxAnswersABare404() = runBlocking {
        val n = node(emptyMap())
        val e = assertFailsWith<NodeRefusal> { client(n).listMyInvites(n.url) }
        assertEquals(404, e.statusCode)
        assertNull(e.reasonId)
    }

    @Test
    fun forceSelfIsOnTheWireOnlyWhenAsked() = runBlocking {
        val ok = """{"identity_key_id":"o","occurrence_key_id":"d","revoked":true,"revoked_by":"o","released_self":true,""" +
            """"history":"Already-shared history stays readable by the evicted device.","failed":[],"withdrawn":[],"occurrences_revoked":[]}"""
        val n = node(mapOf("POST /v1/self/occurrence/revoke" to (200 to ok)))
        val c = client(n)
        c.revokeOccurrence("o", "d", nodeUrl = n.url)
        val forced = c.revokeOccurrence("o", "d", nodeUrl = n.url, forceSelf = true)
        assertFalse(n.bodies[0].contains("force_self"), "a first attempt sends the body a 0.5.217 node always read")
        assertTrue(n.bodies[1].replace(" ", "").contains("\"force_self\":true"), n.bodies[1])
        assertTrue(forced.releasedSelf)
        assertEquals("Already-shared history stays readable by the evicted device.", forced.history)
    }

    @Test
    fun anIncompleteEvictionReachesTheScreenWithItsReport() = runBlocking {
        val body = """{"error":"Part of removing that device did not complete. What was done stays done; the answer names what was not.",""" +
            """"reason_id":"self.evict_incomplete","detail":"occurrence_revocation d: put_identity_occurrence_revocation: store down",""" +
            """"nodes":[],"withdrawn":[],"occurrences_revoked":[],"occurrences_already_revoked":[],""" +
            """"failed":[{"part":"occurrence_revocation","target":"d","error":"put_identity_occurrence_revocation: store down"}],""" +
            """"replication_kicked":true,"nodes_owned_by":[],"history":"Already-shared history stays readable by the evicted device.",""" +
            """"identity_key_id":"o","occurrence_key_id":"d","revoked":false,"revoked_by":"o","released_self":false}"""
        val n = node(mapOf("POST /v1/self/occurrence/revoke" to (500 to body)))
        val e = assertFailsWith<NodeRefusal> { client(n).revokeOccurrence("o", "d", nodeUrl = n.url) }
        assertEquals("self.evict_incomplete", e.reasonId)
        val report = assertNotNull(EvictionReport.fromBody(e.body), "the report rides the refusal, not just its headline")
        assertEquals(listOf("occurrence_revocation" to false), report.parts.map { it.part to it.done })
    }

    @Test
    fun aWholeReadAboveTheCapIsRefusedByName() = runBlocking {
        val n = node(mapOf("GET /v1/files/att-big" to (413 to
            """{"error":"drive.too_large_for_whole_read","reason_id":"drive.too_large_for_whole_read","detail":"this file is 70000000 bytes, above the 67108864-byte whole-read cap"}""")))
        val e = assertFailsWith<NodeRefusal> { client(n).readFile("att-big", "self", n.url) }
        assertEquals("drive.too_large_for_whole_read", e.reasonId)
        assertEquals(413, e.statusCode)
    }
}
