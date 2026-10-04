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
 * **What the client puts on the wire for each final-genesis route**, read by a
 * real socket and compared with what CIRISServer 0.5.220's handlers deserialize
 * (`src/final_genesis.rs` at e357f6bf: `RecoveryKeyRequest`, `PlanRequest`,
 * `SignRequest`, and `ProvisionPkcs11` in `src/accord_provision.rs`). serde
 * ignores unknown fields there, so the check is that each field the handler
 * reads is spelled as it reads it, and that nothing the operator did not
 * confirm (`clock_checked`, `replace`) is sent as true.
 */
class FinalGenesisWireTest {

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

    @Test
    fun statusIsABodylessGet() = runBlocking<Unit> {
        val n = node(200, """{"complete":false,"signable_now":["row:genesis-charter"],"owed":{"row:genesis-charter":["A1","B1","C1"]}}""")
        val s = client.getFinalGenesisStatus(n.url)
        assertEquals(listOf("GET /v1/accord/final-genesis"), n.seen)
        assertEquals("", n.bodies.single())
        assertEquals(listOf("A1", "B1", "C1"), s.owed["row:genesis-charter"])
    }

    @Test
    fun unplannedAndOldNodesAreTwoDifferent404s() = runBlocking<Unit> {
        val newNode = node(404, """{"error":"final_genesis.not_planned","reason_id":"final_genesis.not_planned","detail":"no ceremony is planned on this node"}""")
        val e = assertFailsWith<NodeRefusal> { client.getFinalGenesisStatus(newNode.url) }
        assertEquals("final_genesis.not_planned", e.reasonId)
        val oldNode = node(404, "")
        val old = assertFailsWith<NodeRefusal> { client.getFinalGenesisStatus(oldNode.url) }
        assertEquals(404, old.statusCode)
        assertNull(old.reasonId)
    }

    @Test
    fun recoveryKeyNamesTheHolderTheSpareTheUsbAndThePin() = runBlocking<Unit> {
        val n = node(200, """{"holder_key_id":"A1","recovery_key":{"key_id":"A2","pubkey_ed25519_base64":"AA==","pubkey_ml_dsa_65_base64":"AQ=="},"recorded":["A1"]}""")
        val r = client.verifyFinalGenesisRecoveryKey("A1", "A2", " /media/a2 ", "123456", nodeUrl = n.url)
        assertEquals(listOf("POST /v1/accord/final-genesis/recovery-key"), n.seen)
        assertEquals(
            parse("""{"holder_key_id":"A1","recovery_key_id":"A2","mldsa_usb_path":"/media/a2","pkcs11":{"user_pin":"123456"}}"""),
            n.json(),
        )
        assertEquals("A2", r.recoveryKey.keyId)
        assertEquals(listOf("A1"), r.recorded)
    }

    @Test
    fun planSendsServeNodesAndTheClockAnswerAndReplaceOnlyWhenConfirmed() = runBlocking<Unit> {
        val ok = """{"complete":false,"signable_now":[],"owed":{}}"""
        val n = node(200, ok)
        client.planFinalGenesis(listOf("ciris-canonical-1"), clockChecked = false, replace = false, nodeUrl = n.url)
        client.planFinalGenesis(listOf("ciris-canonical-1", "ciris-canonical-2"), clockChecked = true, replace = true, nodeUrl = n.url)
        assertEquals(List(2) { "POST /v1/accord/final-genesis/plan" }, n.seen)
        // successor_keys / recovery_keys are omitted: the node defaults both to the spares on record.
        assertEquals(parse("""{"serve_nodes":["ciris-canonical-1"],"clock_checked":false}"""), n.json(0))
        assertEquals(
            parse("""{"serve_nodes":["ciris-canonical-1","ciris-canonical-2"],"clock_checked":true,"replace":true}"""),
            n.json(1),
        )
    }

    @Test
    fun signNamesTheHolderAsKeyIdAndOmitsAnEmptyPin() = runBlocking<Unit> {
        val n = node(200, """{"signed":["row:genesis-charter"],"owed":{"row:genesis-charter":["B1","C1"]},"complete":false}""")
        val r = client.signFinalGenesis("A1", "/media/a1", "654321", modulePath = "/usr/lib/libykcs11.so", nodeUrl = n.url)
        client.signFinalGenesis("B1", "/media/b1", "", nodeUrl = n.url)
        assertEquals(
            parse("""{"key_id":"A1","mldsa_usb_path":"/media/a1","pkcs11":{"user_pin":"654321","module_path":"/usr/lib/libykcs11.so"}}"""),
            n.json(0),
        )
        assertEquals(parse("""{"key_id":"B1","mldsa_usb_path":"/media/b1","pkcs11":{}}"""), n.json(1))
        assertEquals(listOf("row:genesis-charter"), r.signed)
        assertEquals(false, r.complete)
    }

    @Test
    fun theDryRunsSignAnswerHasNoCompleteAndStillDecodes() = runBlocking<Unit> {
        val n = node(200, """{"signed":["authz"],"owed":{}}""")
        val r = client.signFinalGenesis("C1", "/media/c1", "1", nodeUrl = n.url)
        assertNull(r.complete)
    }

    @Test
    fun finishPostsAnEmptyObjectAndDecodesTheVerifiedFacts() = runBlocking<Unit> {
        val n = node(200, """{"complete":true,"bundle_path":"/h/final-genesis/canonical_seed.json","bundle_sha256":"sha256:ab",""" +
            """"verified":{"quorum_verified":3,"serve_nodes":["ciris-canonical-1"],"attestations":["genesis-charter"],"community_key_id":"ciris-canonical","founders":3}}""")
        val r = client.finishFinalGenesis(n.url)
        assertEquals(listOf("POST /v1/accord/final-genesis/finish"), n.seen)
        assertEquals(parse("{}"), n.json())
        assertEquals("sha256:ab", r.bundleSha256)
        assertEquals(3, r.verified.founders)
    }

    @Test
    fun aPassedThroughCeremonyRefusalKeepsItsIdAndItsSentence() = runBlocking<Unit> {
        // persist's ids carry no dot; `refuse` puts the id in BOTH `error` and `reason_id`.
        val n = node(409, """{"error":"ceremony_incomplete","reason_id":"ceremony_incomplete","detail":"authz is owed by A1, B1, C1"}""")
        val e = assertFailsWith<NodeRefusal> { client.finishFinalGenesis(n.url) }
        assertEquals(409, e.statusCode)
        assertEquals("ceremony_incomplete", e.reasonId)
        assertEquals("authz is owed by A1, B1, C1", e.detail)
    }
}
