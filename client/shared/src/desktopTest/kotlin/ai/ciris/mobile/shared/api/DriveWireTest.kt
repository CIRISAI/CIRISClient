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
                assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", drive.readFile("att-1", "self", "owner-room").contentDigest)
                assertEquals(0, drive.readNotes().notes.size)
                val policy = drive.readMediaPolicy()
                assertEquals(1_000_000L, policy.tierA["text/plain"])
                assertEquals(false, policy.renditions)
            }
            assertTrue(node.seen.contains("GET /v1/drive?cohort=family&room_id=fam%201&limit=100"), "the family room is on the line: ${node.seen}")
            assertTrue(node.seen.contains("GET /v1/files/att-1?cohort=self"), "a self file names its cohort and no room: ${node.seen}")
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

    /**
     * A node that answers the per-file routes the way CIRISServer's
     * `room_from_query` + `find_file` do (`src/drive.rs`, the same `FileQuery`
     * on v0.5.217 and v0.5.218): a missing cohort is `self`
     * (`Cohort::parse`); family and community need `room_id` or are refused
     * by name; a row asked in the wrong room is `404 drive.not_in_room`. Only
     * 0.5.218 mounts `/custody`; 0.5.217 answers it a bare 404.
     */
    private class ContractNode(val version: String) {
        /** attestation id -> (cohort, room) it lives in. A self row's room is the owner. */
        val rows = mapOf("att-s" to ("self" to OWNER), "att-f" to ("family" to "fam 1"), "att-c" to ("community" to "room-9"))
        val seen: MutableList<String> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val uri = ex.requestURI
                seen += "${ex.requestMethod} ${uri.rawPath}${uri.rawQuery?.let { "?$it" } ?: ""}"
                val q = (uri.query ?: "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
                val parts = uri.path.removePrefix("/v1/files/").split('/')
                val custody = parts.getOrNull(1) == "custody"
                val (status, body) = when {
                    !uri.path.startsWith("/v1/files/") -> 404 to ""
                    custody && version == "0.5.217" -> 404 to ""
                    else -> answer(parts[0], q["cohort"], q["room_id"], custody)
                }
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"

        private fun answer(id: String, cohortParam: String?, roomParam: String?, custody: Boolean): Pair<Int, String> {
            val cohort = cohortParam ?: "self" // Cohort::parse: None | Some("self") => SelfCollective
            val room = when (cohort) {
                "self" -> OWNER
                "family" -> roomParam ?: return 400 to refusal("drive.family_id_required")
                "community" -> roomParam ?: return 400 to refusal("drive.community_id_required")
                else -> return 400 to refusal("drive.unknown_cohort")
            }
            if (rows[id] != (cohort to room)) return 404 to refusal("drive.not_in_room")
            return 200 to if (custody) {
                """{"devices_total":1,"devices":[{"node_key_id":"D1","this_device":true,"holds":"here"}],"receipts_supported":true}"""
            } else {
                """{"attestation_id":"$id","media_type":"text/plain","bytes_base64":"aGVsbG8="}"""
            }
        }

        private fun refusal(id: String) = """{"error":"$id","detail":"$id"}"""

        companion object { const val OWNER = "owner-key" }
    }

    @Test
    fun everyCohortIsAskedInItsOwnRoomOnBothServerVersions() {
        for (version in listOf("0.5.217", "0.5.218")) {
            val node = ContractNode(version)
            try {
                val drive = ClientDrive(CIRISApiClient(node.url)) { node.url }
                runBlocking {
                    // The rows exactly as `GET /v1/drive` lists them: cohort + room_id.
                    assertEquals("att-s", drive.readFile("att-s", "self", ContractNode.OWNER).attestationId)
                    assertEquals("att-f", drive.readFile("att-f", "family", "fam 1").attestationId)
                    assertEquals("att-c", drive.readFile("att-c", "community", "room-9").attestationId)
                    if (version == "0.5.218") {
                        assertEquals(1, drive.readCustody("att-f", "family", "fam 1").devicesTotal)
                    } else {
                        val old = assertFailsWith<NodeRefusal> { drive.readCustody("att-f", "family", "fam 1") }
                        assertEquals(404 to null, old.statusCode to old.reasonId, "0.5.217 has no custody route: a bare 404")
                    }
                    // A family file with no household is the node's refusal by name, not a self lookup.
                    val noRoom = assertFailsWith<NodeRefusal> { drive.readFile("att-f", "family", null) }
                    assertEquals("drive.family_id_required", noRoom.reasonId)
                }
                assertEquals(
                    listOf(
                        "GET /v1/files/att-s?cohort=self",
                        "GET /v1/files/att-f?cohort=family&room_id=fam%201",
                        "GET /v1/files/att-c?cohort=community&room_id=room-9",
                        "GET /v1/files/att-f/custody?cohort=family&room_id=fam%201",
                        "GET /v1/files/att-f?cohort=family",
                    ),
                    node.seen.toList(),
                    "$version: the exact per-file queries",
                )
            } finally {
                node.server.stop(0)
            }
        }
    }

    @Test
    fun theOldQueryIsWhatTheNodeRefused() {
        // What the client sent before this fix: the room with NO cohort. The
        // node reads that as `self` and does not find a family row there.
        val node = ContractNode("0.5.218")
        try {
            val conn = java.net.URL("${node.url}/v1/files/att-f?room_id=fam%201").openConnection() as java.net.HttpURLConnection
            assertEquals(404, conn.responseCode)
            val body = conn.errorStream.bufferedReader().readText()
            assertTrue(body.contains("drive.not_in_room"), body)
        } finally {
            node.server.stop(0)
        }
    }

    @Test
    fun aRoomWithoutItsCohortIsRefusedBeforeTheNodeCanIgnoreIt() {
        val node = Recorder(mapOf("/v1/drive" to (200 to """{"rooms":[],"entries":[],"resume":"c2"}""")), 404 to "")
        try {
            val drive = ClientDrive(CIRISApiClient(node.url)) { node.url }
            assertFailsWith<IllegalArgumentException> { runBlocking { drive.readDrive(cohort = null, roomId = "room-9") } }
            assertFailsWith<IllegalArgumentException> { runBlocking { drive.readDrive(cohort = "self", roomId = "room-9") } }
            val page = runBlocking { drive.readDrive(limit = 500, after = "c 1") }
            assertEquals("c2", page.resume)
            assertEquals(listOf("GET /v1/drive?limit=500&after=c%201"), node.seen.toList(), "nothing reached the node for the refused asks")
        } finally {
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

    /**
     * ciris-server 0.5.220 (persist v53 #969): a file whose bytes arrived before
     * this device's per-epoch key. The listing row says `bytes: "awaiting_key"`;
     * the byte read and the custody route answer `409` with the drive's
     * refusal body (`src/drive.rs::refuse_state`, @ e357f6bf). The client must
     * carry the id through, not just the status — 409 is also `not_fetched`.
     */
    @Test
    fun aKeyNotYetHereComesBackAsTheAwaitingKeyIdNotABare409() {
        val refusalBody = """{"error":"drive.awaiting_key","reason_id":"drive.awaiting_key","detail":"the bytes are here, but this device's key for them has not arrived yet — it follows on its own; ask again shortly"}"""
        val node = Recorder(
            mapOf(
                "/v1/drive" to (200 to """{"rooms":[{"cohort":"self","room":"me"}],"entries":[{"cohort":"self","room_id":"me","attestation_id":"att-k","author_key_id":"me","asserted_at":"2026-10-03T00:00:00Z","filename":"k.pdf","media_type":"application/pdf","bytes":"awaiting_key","detail":"the bytes are here, but this device's key for them has not arrived yet — it follows on its own; ask again shortly","withdrawn":false,"custody":null}]}"""),
                "/v1/files/att-k" to (409 to refusalBody),
                "/v1/files/att-k/custody" to (409 to refusalBody),
            ),
            404 to "",
        )
        try {
            val drive = ClientDrive(CIRISApiClient(node.url)) { node.url }
            runBlocking {
                val row = drive.readDrive(cohort = "self").entries.single()
                assertEquals(ai.ciris.mobile.shared.models.drive.ByteState.AWAITING_KEY, row.byteState)
                val read = assertFailsWith<NodeRefusal> { drive.readFile("att-k", "self", null) }
                assertEquals(409, read.statusCode)
                assertEquals("drive.awaiting_key", read.reasonId)
                val custody = assertFailsWith<NodeRefusal> { drive.readCustody("att-k", "self", null) }
                assertEquals(409, custody.statusCode)
                assertEquals("drive.awaiting_key", custody.reasonId)
            }
        } finally {
            node.server.stop(0)
        }
    }
}
