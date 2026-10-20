package ai.ciris.mobile.shared.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * **The 2-of-3 trust-root AMEND on the wire** (CC 5.3.4; the steward's ruling
 * on CIRISConstitution#172), against a socket answering what CIRISServer#798
 * (0.5.232, head `98cee69e`) `src/final_genesis.rs` writes:
 *
 *  - `AmendPlanRequest` (`:1310-1324`): `pinned_bundle?`, `serve_transport_destinations`
 *    (each a SIGNED transport_destination row, an object), `replace?`;
 *  - `amend_status_body` (`:1285-1306`): `{mode, needed, signed, status, complete, serve_transport_destinations}`;
 *  - `amend_sign` reads the final-genesis `SignRequest` (`:864`);
 *  - `amend_finish` (`:1542-1600`): `{mode, complete, version, bundle_path, bundle_sha256, bundle_json, serve_transport_destinations, verified}`;
 *  - nothing planned: 409 `final_genesis.amend_not_planned` (`amend_load`, `:1235`);
 *    a node older than the routes: axum's bare 404.
 */
class FinalGenesisAmendWireTest {

    private class Node(private val status: Int, private val body: String) {
        val seen: MutableList<String> = CopyOnWriteArrayList()
        val bodies: MutableList<String> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += "${ex.requestMethod} ${ex.requestURI.rawPath}"
                bodies += ex.requestBody.readBytes().decodeToString()
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        fun json(i: Int = 0): JsonObject = Json.parseToJsonElement(bodies[i]) as JsonObject
    }

    private val nodes = mutableListOf<Node>()
    private fun node(status: Int, body: String) = Node(status, body).also { nodes += it }
    @AfterTest fun stop() { nodes.forEach { it.server.stop(0) } }
    private val client = CIRISApiClient(baseUrl = "http://127.0.0.1:9").also { it.setAccessToken("owner") }
    private fun parse(s: String) = Json.parseToJsonElement(s)

    /** A signed transport_destination row as persist serializes it (the fields the server reads are nested). */
    private val row = """{"transport_destination":{"occurrence_key_id":"ciris-canonical-1","epoch":2,"transit_kex":{"x25519":"AA==","mlkem768":"AQ=="}},"signature":"c2ln"}"""

    private val planned = """{"mode":"amend","needed":2,"signed":[],"status":"0 of 2","complete":false,"serve_transport_destinations":["ciris-canonical-1"]}"""

    @Test
    fun statusIsABodylessGetAndDecodesTheAmendBody() = runBlocking<Unit> {
        val n = node(200, """{"mode":"amend","needed":2,"signed":["A1"],"status":"1 of 2","complete":false,"serve_transport_destinations":["ciris-canonical-1"]}""")
        val s = client.getFinalGenesisAmendStatus(n.url)
        assertEquals(listOf("GET /v1/accord/final-genesis/amend"), n.seen)
        assertEquals("", n.bodies.single())
        assertEquals("amend", s.mode)
        assertEquals(2, s.needed)
        assertEquals(listOf("A1"), s.signed)
        assertEquals("1 of 2", s.status)
        assertEquals(false, s.complete)
        assertEquals(listOf("ciris-canonical-1"), s.serveTransportDestinations)
    }

    @Test
    fun notPlannedIsA409WithItsIdAndAnOlderNodeIsABare404() = runBlocking<Unit> {
        val unplanned = node(409, """{"error":"final_genesis.amend_not_planned","reason_id":"final_genesis.amend_not_planned","detail":"no amend is planned on this node — POST /v1/accord/final-genesis/amend/plan"}""")
        val e = assertFailsWith<NodeRefusal> { client.getFinalGenesisAmendStatus(unplanned.url) }
        assertEquals(409, e.statusCode)
        assertEquals("final_genesis.amend_not_planned", e.reasonId)
        val old = node(404, "")
        val o = assertFailsWith<NodeRefusal> { client.getFinalGenesisAmendStatus(old.url) }
        assertEquals(404, o.statusCode)
        assertNull(o.reasonId)
    }

    @Test
    fun planWithNothingPastedIsAnEmptyObjectSoTheNodeReadsItsOwnBundleAndRows() = runBlocking<Unit> {
        val n = node(200, planned)
        val s = client.planFinalGenesisAmend(emptyList(), replace = false, nodeUrl = n.url)
        assertEquals(listOf("POST /v1/accord/final-genesis/amend/plan"), n.seen)
        // pinned_bundle omitted = the bundle this node runs on; rows omitted = read from its directory.
        assertEquals(parse("{}"), n.json())
        assertEquals(listOf("ciris-canonical-1"), s.serveTransportDestinations)
    }

    @Test
    fun planCarriesEachPastedRowAsAnObjectAndReplaceOnlyWhenConfirmed() = runBlocking<Unit> {
        val n = node(200, planned)
        val rows = listOf(parse(row) as JsonObject)
        client.planFinalGenesisAmend(rows, replace = false, nodeUrl = n.url)
        client.planFinalGenesisAmend(rows, replace = true, nodeUrl = n.url)
        assertEquals(parse("""{"serve_transport_destinations":[$row]}"""), n.json(0))
        assertEquals(parse("""{"serve_transport_destinations":[$row],"replace":true}"""), n.json(1))
    }

    @Test
    fun signIsTheFinalGenesisSignRequestAndAnswersTheStatusBody() = runBlocking<Unit> {
        val n = node(200, """{"mode":"amend","needed":2,"signed":["B1"],"status":"1 of 2","complete":false,"serve_transport_destinations":["ciris-canonical-1"]}""")
        val s = client.signFinalGenesisAmend("B1", " /media/b1 ", "123456", modulePath = "/usr/lib/libykcs11.so", nodeUrl = n.url, pivSlot = "9c")
        assertEquals(listOf("POST /v1/accord/final-genesis/amend/sign"), n.seen)
        assertEquals(
            parse("""{"key_id":"B1","mldsa_usb_path":"/media/b1","pkcs11":{"user_pin":"123456","piv_slot":"9c","module_path":"/usr/lib/libykcs11.so"}}"""),
            n.json(),
        )
        assertEquals(listOf("B1"), s.signed)
    }

    @Test
    fun finishDecodesVersionFourTheSeededCanonicalsAndTheBundleBytesVerbatim() = runBlocking<Unit> {
        val file = "{\n  \"version\": 4,\n  \"serve_transport_destinations\": []\n}"
        val sha = ai.ciris.mobile.shared.platform.util.Sha256.hex(file.encodeToByteArray())
        val asString = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(file))
        val n = node(200, """{"mode":"amend","complete":true,"version":4,"bundle_path":"/h/final-genesis/canonical_seed.json",""" +
            """"bundle_sha256":"sha256:$sha","bundle_json":$asString,"serve_transport_destinations":["ciris-canonical-1"],""" +
            """"verified":{"quorum_verified":2,"serve_nodes":["ciris-canonical-1"],"attestations":["genesis-charter"],"community_key_id":"ciris-canonical","founders":3}}""")
        val r = client.finishFinalGenesisAmend(n.url)
        assertEquals(listOf("POST /v1/accord/final-genesis/amend/finish"), n.seen)
        assertEquals(parse("{}"), n.json())
        assertEquals("amend", r.mode)
        assertEquals(4, r.version)
        assertEquals(listOf("ciris-canonical-1"), r.serveTransportDestinations)
        assertEquals(file, r.bundleText)
        assertEquals(true, r.bundleMatchesFingerprint)
        assertEquals(2, r.verified.quorumVerified)
    }

    @Test
    fun aRefusalKeepsItsIdAndTheNodesSentence() = runBlocking<Unit> {
        val n = node(400, """{"error":"final_genesis.serve_node_without_transit_pair","reason_id":"final_genesis.serve_node_without_transit_pair","detail":"no signed transport_destination row with a transit KEX pair for [\"ciris-canonical-1\"]"}""")
        val e = assertFailsWith<NodeRefusal> { client.planFinalGenesisAmend(emptyList(), replace = false, nodeUrl = n.url) }
        assertEquals("final_genesis.serve_node_without_transit_pair", e.reasonId)
        assertEquals(400, e.statusCode)
    }

    // ── the canonical's transit row (CIRISServer#798 @ 76e16ccf, `src/trust_root_api.rs:615-676`) ──

    /** What `serve_transit_row` answers: the SignedTransportDestination, its pair in `signed_envelope.transit_kex_pubkeys`. */
    private val fetchedRow = """{"transport_destination":{"occurrence_key_id":"ciris-canonical-1","epoch":3,"asserted_at":"2026-10-19T00:00:00Z"},""" +
        """"attesting_key_id":"ciris-canonical-1","signed_envelope":{"transit_kex_pubkeys":{"x25519_base64":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","ml_kem_768_base64":"AQID"}},""" +
        """"signature":"c2ln"}"""

    @Test
    fun theTransitRowIsAPublicGetOnTheCanonicalWithNoBearerAndComesBackVerbatim() = runBlocking<Unit> {
        val seenAuth = CopyOnWriteArrayList<String?>()
        val seen = CopyOnWriteArrayList<String>()
        val canonical = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seenAuth += ex.requestHeaders.getFirst("Authorization")
                seen += "${ex.requestMethod} ${ex.requestURI.rawPath}"
                val bytes = fetchedRow.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val r = client.fetchCanonicalTransitRow("http://127.0.0.1:${canonical.address.port}/")
            assertEquals(listOf("GET /v1/trust-root/transit-row"), seen.toList())
            assertEquals(listOf<String?>(null), seenAuth.toList(), "a different host: the local node's bearer is never sent")
            assertEquals(parse(fetchedRow), r.row)
            assertEquals("ciris-canonical-1", r.keyId)
            assertEquals(16, r.pairFingerprint?.replace(" ", "")?.length)
        } finally {
            canonical.stop(0)
        }
    }

    @Test
    fun eachTransitRowRefusalKeepsItsIdAndAnOlderCanonicalIsABare404() = runBlocking<Unit> {
        for ((status, id) in listOf(404 to "trust_root.not_a_serve_node", 404 to "trust_root.no_transit_row", 500 to "trust_root.store_unavailable")) {
            val n = node(status, """{"error":"english for $id","reason_id":"$id"}""")
            val e = assertFailsWith<NodeRefusal> { client.fetchCanonicalTransitRow(n.url) }
            assertEquals(id, e.reasonId)
            assertEquals(status, e.statusCode)
        }
        val old = node(404, "")
        val e = assertFailsWith<NodeRefusal> { client.fetchCanonicalTransitRow(old.url) }
        assertEquals(404, e.statusCode)
        assertNull(e.reasonId)
    }
}
