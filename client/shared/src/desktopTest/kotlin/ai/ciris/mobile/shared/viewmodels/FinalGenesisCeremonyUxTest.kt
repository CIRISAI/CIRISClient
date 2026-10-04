package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.genesisItemLabel
import ai.ciris.mobile.shared.models.federation.pinTriesWarning
import ai.ciris.mobile.shared.models.federation.seedBlobName
import ai.ciris.mobile.shared.models.federation.seedBlobPath
import ai.ciris.mobile.shared.platform.getFileSize
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Eric hit at the console running the ceremony on 0.5.227 (Linux), one
 * test per gap: the USB folder and its seed blob, the token hints, the grid's
 * plain words, and a first load that waits for the node instead of stopping on
 * "connection refused".
 */
class FinalGenesisCeremonyUxTest {

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain(); servers.forEach { it.stop(0) } }
    private val servers = mutableListOf<HttpServer>()

    // ── 1. The USB key folder ────────────────────────────────────────────────

    @Test
    fun thePickedFolderIsCheckedForTheHoldersSeedBlob() {
        val dir = Files.createTempDirectory("usb-a1").toFile()
        try {
            assertEquals("A1.mldsa65.seed.blob", seedBlobName("A1"))
            assertEquals("${dir.path}/A1.mldsa65.seed.blob", seedBlobPath(dir.path + "/", "A1"), "a trailing slash is not doubled")
            assertEquals(0L, getFileSize(seedBlobPath(dir.path, "A1")), "missing before the blob is there")
            java.io.File(dir, "A1.mldsa65.seed.blob").writeBytes(ByteArray(64) { 1 })
            assertTrue(getFileSize(seedBlobPath(dir.path, "A1")) > 0L, "found once it is")
            assertEquals(0L, getFileSize(seedBlobPath(dir.path, "B1")), "another holder's blob is not this one")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── 2. The token hints: PIN tries, PIV slot ──────────────────────────────

    @Test
    fun thePinTriesTheNodeReportsAreLiftedOutOfTheRefusal() {
        // CIRISServer accord_provision.rs's three spellings.
        assertEquals(
            "WARNING: 1 of 3 PIN attempts left — one more wrong PIN LOCKS this token and it will need the PUK.",
            pinTriesWarning("couldn't open your YubiKey's slot-9c key: CKR_PIN_INCORRECT — is the YubiKey inserted and the PIN correct? " +
                "WARNING: 1 of 3 PIN attempts left — one more wrong PIN LOCKS this token and it will need the PUK."),
        )
        assertEquals("2 of 3 PIN attempts remain.", pinTriesWarning("couldn't open … the PIN correct? 2 of 3 PIN attempts remain."))
        assertEquals(
            "This token's PIN is now LOCKED — unlock it with the PUK (`ykman piv access unblock-pin`) before retrying.",
            pinTriesWarning("x? This token's PIN is now LOCKED — unlock it with the PUK (`ykman piv access unblock-pin`) before retrying."),
        )
        assertNull(pinTriesWarning("the specified USB path has no seed blob"))
        assertNull(pinTriesWarning(null))
    }

    @Test
    fun thePivSlotReachesTheWireOnSignAndVerify() = runBlocking<Unit> {
        val bodies = CopyOnWriteArrayList<Pair<String, String>>()
        val url = serve(bodies, mapOf(
            "POST /v1/accord/final-genesis/sign" to (200 to """{"signed":[],"owed":{},"complete":false}"""),
            "POST /v1/accord/final-genesis/recovery-key" to (200 to
                """{"holder_key_id":"A1","recovery_key":{"key_id":"A2","pubkey_ed25519_base64":"AA==","pubkey_ml_dsa_65_base64":"z"},"recorded":["A1"]}"""),
        ))
        val c = client()
        c.signFinalGenesis("A1", "/media/eric/A1KEY", "1", nodeUrl = url, pivSlot = "9c")
        c.verifyFinalGenesisRecoveryKey("A1", "A2", "/media/eric/A2KEY", "1", nodeUrl = url, pivSlot = "9a")
        val sign = Json.parseToJsonElement(bodies.first { it.first.endsWith("/sign") }.second) as JsonObject
        val verify = Json.parseToJsonElement(bodies.first { it.first.endsWith("/recovery-key") }.second) as JsonObject
        assertEquals("9c", (sign["pkcs11"] as JsonObject)["piv_slot"]!!.jsonPrimitive.content)
        assertEquals("9a", (verify["pkcs11"] as JsonObject)["piv_slot"]!!.jsonPrimitive.content)
    }

    // ── 3. The grid in plain words ───────────────────────────────────────────

    @Test
    fun everyItemTheCeremonyEmitsHasPlainWordsAndAnUnknownOneStaysRaw() {
        assertEquals("mobile.final_genesis_item_authz" to emptyMap(), genesisItemLabel("authz"))
        assertEquals("mobile.final_genesis_item_record" to mapOf("node" to "ciris-canonical-1-d7bdeu223k"), genesisItemLabel("record:ciris-canonical-1-d7bdeu223k"))
        assertEquals("mobile.final_genesis_item_charter" to emptyMap(), genesisItemLabel("row:genesis-charter"))
        assertEquals("mobile.final_genesis_item_grant" to mapOf("node" to "ciris-canonical-1"), genesisItemLabel("row:genesis-grant:ciris-canonical-1"))
        assertEquals("mobile.final_genesis_item_lifecycle" to emptyMap(), genesisItemLabel("row:genesis-lifecycle"))
        assertEquals("mobile.final_genesis_item_family" to emptyMap(), genesisItemLabel("family:humanity-accord"))
        assertEquals("mobile.final_genesis_item_community" to emptyMap(), genesisItemLabel("community:ciris-canonical"))
        assertNull(genesisItemLabel("row:something-new"))
        assertNull(genesisItemLabel("family:another-family"))
    }

    // ── 4. A first load that waits for the node ──────────────────────────────

    @Test
    fun aRefusedFirstReadIsWaitedOutThenTimesOutWithRetryAndAnAnswerIsNotRetried() = runBlocking<Unit> {
        var clock = 0L
        val waits = mutableListOf<NodeWait>()
        var calls = 0
        val v = awaitNodeFirstRead("http://127.0.0.1:4243", onWait = { waits += it }, deadlineSeconds = 90,
            now = { clock }, pause = { clock += it }) {
            calls++
            if (calls < 3) throw java.net.ConnectException("Connection refused") else "up"
        }
        assertEquals("up", v)
        assertEquals(listOf(NodeWait.Waiting(0, 1), NodeWait.Waiting(0, 2), NodeWait.Idle), waits)

        clock = 0
        waits.clear()
        val none = awaitNodeFirstRead("http://127.0.0.1:4243", onWait = { waits += it }, deadlineSeconds = 5,
            now = { clock }, pause = { clock += it }) { throw java.net.ConnectException("Connection refused") }
        assertNull(none)
        val last = assertIs<NodeWait.TimedOut>(waits.last())
        assertEquals("http://127.0.0.1:4243", last.nodeUrl)

        var answered = 0
        assertFailsWith<NodeRefusal> {
            awaitNodeFirstRead("u", onWait = {}, now = { 0 }, pause = {}) { answered++; throw NodeRefusal("x.y", "no", 403) }
        }
        assertEquals(1, answered, "an answer is never waited out")
    }

    @Test
    fun theSheetsFirstProbeWaitsForTheNodeAndThenOffersRetry() = runBlocking<Unit> {
        // A port nothing listens on, then the node.
        val dead = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}" }
        val live = serve(CopyOnWriteArrayList(), mapOf(
            "GET /v1/accord/final-genesis" to (404 to """{"error":"final_genesis.not_planned","reason_id":"final_genesis.not_planned","detail":"no ceremony"}"""),
        ))
        var reads = 0
        val vm = FinalGenesisViewModel(client(), nodeUrl = { if (reads++ < 2) dead else live })
        vm.open().join()
        assertEquals(FinalGenesisPhase.NotPlanned, vm.phase.value, "the refused reads were waited out")

        val never = FinalGenesisViewModel(client(), nodeUrl = { dead }, nodeWaitDeadlineSeconds = 0)
        never.open().join()
        val p = assertIs<FinalGenesisPhase.Unavailable>(never.phase.value)
        assertEquals("mobile.final_genesis_node_unreachable", p.refusal.reasonId, "an error the sheet's Retry answers")
    }

    private fun client() = CIRISApiClient(baseUrl = "http://127.0.0.1:9").also { it.setAccessToken("owner") }

    private fun serve(bodies: MutableList<Pair<String, String>>, routes: Map<String, Pair<Int, String>>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val key = "${ex.requestMethod} ${ex.requestURI.rawPath}"
                bodies += key to ex.requestBody.readBytes().decodeToString()
                val (status, body) = routes[key] ?: (404 to "")
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        servers += s
        return "http://127.0.0.1:${s.address.port}"
    }
}
