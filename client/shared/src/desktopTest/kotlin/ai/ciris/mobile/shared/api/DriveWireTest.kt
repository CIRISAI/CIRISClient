package ai.ciris.mobile.shared.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **Every drive call reaches the NODE, over the wire** (CSD-007 §3). The real
 * client against two real sockets: an "agent" at the api base that does not
 * serve the drive (CIRISAgent#1213 is closed, but an older agent 404s it),
 * and the node. The view-model tests prove what is asked; this proves the
 * bytes leave for the node's URL and the agent sees none of it.
 */
class DriveWireTest {

    private class Recorder(routes: Map<String, Pair<Int, String>>, fallback: Pair<Int, String>) {
        val seen: MutableList<String> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val uri = exchange.requestURI
                seen += "${exchange.requestMethod} ${uri.rawPath}${uri.rawQuery?.let { "?$it" } ?: ""}"
                val (status, body) = routes[uri.path] ?: fallback
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private val policyBody = """{"policy_version":1,"tier_a":{"text/plain":{"max_bytes":1000000}},"inline_max_bytes":1048576,"renditions":false}"""

    @Test
    fun theDriveTheFileTheNotesAndThePolicyGoToTheNodeWhileTheAgentSeesNothing() {
        val agent = Recorder(emptyMap(), 404 to """{"detail":"Not Found"}""")
        val node = Recorder(
            mapOf(
                "/v1/drive" to (200 to """{"rooms":[],"entries":[]}"""),
                "/v1/files/att-1" to (200 to """{"attestation_id":"att-1","media_type":"text/plain","filename":"a.txt","size":5,"content_digest":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","content_digest_alg":"sha-256","bytes_base64":"aGVsbG8="}"""),
                "/v1/notes" to (200 to """{"room":"self","notes":[]}"""),
                "/v1/media/policy" to (200 to policyBody),
            ),
            404 to "",
        )
        try {
            val drive = ClientDrive(CIRISApiClient(agent.url)) { node.url }
            runBlocking {
                assertEquals(0, drive.readDrive(cohort = "family", roomId = "fam 1").entries.size)
                assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", drive.readFile("att-1", "self").contentDigest)
                assertEquals(0, drive.readNotes().notes.size)
                val policy = drive.readMediaPolicy()
                assertEquals(1_000_000L, policy.tierA["text/plain"])
                assertEquals(false, policy.renditions)
            }
            assertTrue(node.seen.contains("GET /v1/drive?cohort=family&room_id=fam%201&limit=100"), "the family room is on the line: ${node.seen}")
            assertTrue(node.seen.contains("GET /v1/files/att-1?room_id=self"), node.seen.toString())
            assertTrue(node.seen.contains("GET /v1/notes"), node.seen.toString())
            assertTrue(node.seen.contains("GET /v1/media/policy"), node.seen.toString())
            assertTrue(agent.seen.isEmpty(), "a drive call reached the api base: ${agent.seen}")
        } finally {
            agent.server.stop(0)
            node.server.stop(0)
        }
    }

    @Test
    fun custodyGoesToTheNodeWithTheCohortAndRoomAndABare404IsNotAnId() {
        // CSD-107: the per-file query every drive route takes, at the NODE; a
        // released node has no such route and answers a bare 404.
        val agent = Recorder(emptyMap(), 404 to """{"detail":"Not Found"}""")
        val node = Recorder(
            mapOf("/v1/files/att-1/custody" to (200 to """{"devices_total":2,"devices":[{"node_key_id":"D1","this_device":true,"holds":"here"}],"receipts_supported":false}""")),
            404 to "",
        )
        try {
            val drive = ClientDrive(CIRISApiClient(agent.url)) { node.url }
            runBlocking {
                val c = drive.readCustody("att-1", "family", "fam 1")
                assertEquals(2, c.devicesTotal)
                assertEquals(false, c.receiptsSupported)
                drive.readCustody("att-1", "self", null)
                val refusal = assertFailsWith<NodeRefusal> { drive.readCustody("att-2", "self", null) }
                assertEquals(404, refusal.statusCode)
                assertEquals(null, refusal.reasonId)
            }
            assertTrue(node.seen.contains("GET /v1/files/att-1/custody?cohort=family&room_id=fam%201"), node.seen.toString())
            assertTrue(node.seen.contains("GET /v1/files/att-1/custody?cohort=self"), node.seen.toString())
            assertTrue(agent.seen.isEmpty(), "a custody call reached the api base: ${agent.seen}")
        } finally {
            agent.server.stop(0)
            node.server.stop(0)
        }
    }

    @Test
    fun aNodeWithoutThePolicyRouteRefusesByStatusNotById() {
        val node = Recorder(emptyMap(), 404 to """{"detail":"Not Found"}""")
        try {
            val drive = ClientDrive(CIRISApiClient(node.url)) { node.url }
            val refusal = assertFailsWith<NodeRefusal> { runBlocking { drive.readMediaPolicy() } }
            assertEquals(404, refusal.statusCode)
            assertEquals(null, refusal.reasonId, "a bare 404 is 'not on this node', and the view model reads it as such")
        } finally {
            node.server.stop(0)
        }
    }
}
