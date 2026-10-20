package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.federation.TransitFetch
import ai.ciris.mobile.shared.models.federation.TransitRows
import ai.ciris.mobile.shared.models.federation.parseTransitRows
import androidx.lifecycle.ViewModel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Re-scrub: the 2-of-3 trust-root amend, through the view model** (CC 5.3.4,
 * the steward's ruling on CIRISConstitution#172), against a socket answering
 * the bytes CIRISServer#798 (`98cee69e`) `src/final_genesis.rs:1187-1600`
 * writes. PLAN shows mode `amend`, the canonicals whose transit rows are
 * seeded, and how many holders are needed; SIGN is any holder, one session,
 * until complete — no recovery keys, no second round; FINISH is version 4.
 */
class FinalGenesisAmendViewModelTest {

    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() {
        stopViewModels(made)
        Dispatchers.resetMain()
        nodes.forEach { it.server.stop(0) }
    }

    /** "METHOD /path" → answers, served in order; the last one repeats. Unrouted is axum's bare 404. */
    private class Node(routes: Map<String, List<Pair<Int, String>>>) {
        private val queues = routes.mapValues { CopyOnWriteArrayList(it.value) }
        val seen: MutableList<String> = CopyOnWriteArrayList()
        val bodies: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val key = "${ex.requestMethod} ${ex.requestURI.rawPath}"
                seen += key
                bodies += key to ex.requestBody.readBytes().decodeToString()
                val (status, body) = synchronized(this@Node) {
                    val q = queues[key]
                    when {
                        q == null || q.isEmpty() -> 404 to ""
                        q.size > 1 -> q.removeAt(0)
                        else -> q[0]
                    }
                }
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        fun bodiesOf(key: String) = bodies.filter { it.first == key }.map { Json.parseToJsonElement(it.second) as JsonObject }
    }

    private val nodes = mutableListOf<Node>()
    private fun node(routes: Map<String, List<Pair<Int, String>>>) = Node(routes).also { nodes += it }
    private fun vm(n: Node) = FinalGenesisViewModel(
        CIRISApiClient(baseUrl = "http://127.0.0.1:9").also { it.setAccessToken("owner") },
        nodeUrl = { n.url },
        nodeWaitDeadlineSeconds = 0,
    ).also { made += it }

    private fun refusal(id: String, detail: String) = """{"error":"$id","reason_id":"$id","detail":"$detail"}"""
    private val notPlanned = 409 to refusal("final_genesis.amend_not_planned", "no amend is planned on this node — POST /v1/accord/final-genesis/amend/plan")

    private val source = 200 to """{"holders":[""" +
        """{"key_id":"A1","identity_type":"accord_holder","pubkey_ed25519_base64":"AA==","pubkey_ml_dsa_65_base64":"x"},""" +
        """{"key_id":"B1","identity_type":"accord_holder","pubkey_ed25519_base64":"AQ==","pubkey_ml_dsa_65_base64":"x"},""" +
        """{"key_id":"C1","identity_type":"accord_holder","pubkey_ed25519_base64":"Ag==","pubkey_ml_dsa_65_base64":"x"}],""" +
        """"canonicals":[{"key_id":"ciris-canonical-1","identity_type":"canonical,node","pubkey_ed25519_base64":"Aw==",""" +
        """"pubkey_ml_dsa_65_base64":"y","scrub_key_id":"A1","transport_hints":[{"kind":"ip","destination":"203.0.113.7:4242"}],"confers_infra_serve":true}],""" +
        """"quorum":"2/3","quorum_m":2,"quorum_n":3,"note":"all three sign"}"""

    private fun amendStatus(signed: List<String>, needed: Int = 2) =
        200 to """{"mode":"amend","needed":$needed,"signed":[${signed.joinToString(",") { "\"$it\"" }}],""" +
            """"status":"${signed.size} of $needed","complete":${signed.size >= needed},"serve_transport_destinations":["ciris-canonical-1"]}"""

    private val row = """{"transport_destination":{"occurrence_key_id":"ciris-canonical-1","epoch":2},"signature":"c2ln"}"""

    private val finished = 200 to """{"mode":"amend","complete":true,"version":4,"bundle_path":"/h/final-genesis/canonical_seed.json",""" +
        """"bundle_sha256":"sha256:ab","bundle_json":"{}","serve_transport_destinations":["ciris-canonical-1"],""" +
        """"verified":{"quorum_verified":2,"serve_nodes":["ciris-canonical-1"],"attestations":[],"community_key_id":"ciris-canonical","founders":3}}"""

    // ── which node ──────────────────────────────────────────────────────────

    @Test
    fun aBare404IsANodeTooOldForTheAmendNotAnError() = runBlocking<Unit> {
        val n = node(mapOf("GET /v1/accord/genesis/remint-source" to listOf(source)))
        val vm = vm(n)
        vm.openAmend().join()
        assertEquals(AmendPhase.TooOld, vm.amendPhase.value)
        assertFalse("POST /v1/accord/final-genesis/amend/plan" in n.seen)
    }

    @Test
    fun nothingPlannedIsTheNamed409AndReadsTheRosterButNoRecoveryKeys() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis/amend" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
        ))
        val vm = vm(n)
        vm.openAmend().join()
        assertEquals(AmendPhase.NotPlanned, vm.amendPhase.value)
        assertEquals(listOf("A1", "B1", "C1"), vm.amendHolders())
        assertFalse(n.seen.any { it.contains("recovery-key") }, "the amend carries the commitments: no recovery-key reads")
    }

    // ── plan ────────────────────────────────────────────────────────────────

    @Test
    fun planWithNoRowPastedSendsAnEmptyObjectAndShowsModeSeededAndNeeded() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis/amend" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/amend/plan" to listOf(amendStatus(emptyList())),
        ))
        val vm = vm(n)
        vm.openAmend().join()
        vm.planAmend().join()
        assertEquals(emptyMap(), n.bodiesOf("POST /v1/accord/final-genesis/amend/plan").single())
        val p = assertIs<AmendPhase.Planned>(vm.amendPhase.value)
        assertEquals("amend", p.status.mode)
        assertEquals(listOf("ciris-canonical-1"), p.status.serveTransportDestinations)
        assertEquals(2, p.status.needed)
        assertEquals(3, vm.amendHolders().size, "2 of 3 needed")
    }

    @Test
    fun aPastedTransitRowIsSentAsTheRowItself() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis/amend" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/amend/plan" to listOf(amendStatus(emptyList())),
        ))
        val vm = vm(n)
        vm.openAmend().join()
        vm.setAmendRowsText("  $row \n")
        vm.planAmend().join()
        val sent = n.bodiesOf("POST /v1/accord/final-genesis/amend/plan").single()
        assertEquals(Json.parseToJsonElement("[$row]"), sent["serve_transport_destinations"])
        assertNull(sent["replace"], "replace only after the confirm")
    }

    @Test
    fun rowsThatAreNotJsonObjectsSendNothing() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis/amend" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/amend/plan" to listOf(amendStatus(emptyList())),
        ))
        val vm = vm(n)
        vm.openAmend().join()
        vm.setAmendRowsText("ciris-canonical-1")
        assertIs<TransitRows.Invalid>(vm.amendRows())
        vm.planAmend().join()
        assertFalse("POST /v1/accord/final-genesis/amend/plan" in n.seen)
    }

    @Test
    fun alreadyPlannedAsksAndReplaceIsSentOnlyOnTheConfirmedRequest() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis/amend" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/amend/plan" to listOf(
                409 to refusal("final_genesis.amend_already_planned", "an amend is already planned on this node; pass \\\"replace\\\": true to discard it"),
                amendStatus(emptyList()),
            ),
        ))
        val vm = vm(n)
        vm.openAmend().join()
        vm.planAmend().join()
        assertTrue(vm.amendConfirmReplace.value, "the node's 409 opens the replace confirm")
        assertNull(vm.amendRefusal.value)
        vm.confirmAmendReplace().join()
        val bodies = n.bodiesOf("POST /v1/accord/final-genesis/amend/plan")
        assertNull(bodies[0]["replace"])
        assertEquals(true, bodies[1]["replace"]!!.jsonPrimitive.boolean)
        assertIs<AmendPhase.Planned>(vm.amendPhase.value)
    }

    @Test
    fun aCanonicalWithoutItsTransitPairIsARefusalByIdAndNothingIsPlanned() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis/amend" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/amend/plan" to listOf(400 to refusal("final_genesis.serve_node_without_transit_pair", "no signed transport_destination row")),
        ))
        val vm = vm(n)
        vm.openAmend().join()
        vm.planAmend().join()
        assertEquals("final_genesis.serve_node_without_transit_pair", vm.amendRefusal.value?.reasonId)
        assertEquals(AmendPhase.NotPlanned, vm.amendPhase.value)
    }

    // ── sign, finish ────────────────────────────────────────────────────────

    @Test
    fun anyTwoHoldersSignOnceEachThenFinishIsVersionFour() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis/amend" to listOf(amendStatus(emptyList())),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/amend/sign" to listOf(amendStatus(listOf("C1")), amendStatus(listOf("A1", "C1"))),
            "POST /v1/accord/final-genesis/amend/finish" to listOf(finished),
        ))
        val vm = vm(n)
        vm.openAmend().join()
        assertIs<AmendPhase.Planned>(vm.amendPhase.value)
        vm.signAmend("C1", "/media/c1", "123456").join()
        assertEquals(listOf("C1"), assertIs<AmendPhase.Planned>(vm.amendPhase.value).status.signed)
        assertIs<HolderSignNote.Signed>(vm.amendNotes.value["C1"])
        vm.signAmend("A1", "/media/a1", "654321").join()
        val done = assertIs<AmendPhase.Planned>(vm.amendPhase.value).status
        assertTrue(done.complete)
        // Complete: no third holder is asked (the quorum is met).
        vm.signAmend("B1", "/media/b1", "1").join()
        assertEquals(2, n.seen.count { it == "POST /v1/accord/final-genesis/amend/sign" })
        val signs = n.bodiesOf("POST /v1/accord/final-genesis/amend/sign")
        assertEquals(listOf("C1", "A1"), signs.map { it["key_id"]!!.jsonPrimitive.content })
        assertFalse(n.seen.any { it.contains("recovery-key") || it == "POST /v1/accord/final-genesis/sign" })
        vm.finishAmend().join()
        val f = assertIs<AmendPhase.Finished>(vm.amendPhase.value).result
        assertEquals(4, f.version)
        assertEquals(listOf("ciris-canonical-1"), f.serveTransportDestinations)
        assertEquals("sha256:ab", f.bundleSha256)
        assertEquals("{}", f.bundleText)
    }

    @Test
    fun aRefusedAuthorizationSitsBesideThatHolder() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis/amend" to listOf(amendStatus(emptyList())),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/amend/sign" to listOf(400 to refusal("final_genesis.amend_input_refused", "D9 is not a holder of this charter")),
        ))
        val vm = vm(n)
        vm.openAmend().join()
        vm.signAmend("B1", "/media/b1", "1").join()
        val note = assertIs<HolderSignNote.Refused>(vm.amendNotes.value["B1"])
        assertEquals("final_genesis.amend_input_refused", note.refusal.reasonId)
        assertIs<AmendPhase.Planned>(vm.amendPhase.value)
    }

    @Test
    fun finishBelowQuorumIsARefusalByIdAndReReadsTheStatus() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis/amend" to listOf(amendStatus(listOf("A1"))),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/amend/finish" to listOf(409 to refusal("final_genesis.amend_quorum_not_met", "1 of 2 authorizations")),
        ))
        val vm = vm(n)
        vm.openAmend().join()
        vm.finishAmend().join()
        assertEquals("final_genesis.amend_quorum_not_met", vm.amendRefusal.value?.reasonId)
        assertIs<AmendPhase.Planned>(vm.amendPhase.value)
        assertEquals(2, n.seen.count { it == "GET /v1/accord/final-genesis/amend" })
    }

    // ── the pasted rows ─────────────────────────────────────────────────────

    @Test
    fun pastedRowsAreAnObjectAnArrayOrSeveralAndNameTheirCanonical() {
        assertEquals(TransitRows.Empty, parseTransitRows("  \n "))
        val one = assertIs<TransitRows.Rows>(parseTransitRows(row))
        assertEquals(listOf("ciris-canonical-1"), one.subjects)
        val arr = assertIs<TransitRows.Rows>(parseTransitRows("[$row]"))
        assertEquals(1, arr.rows.size)
        val two = row.replace("ciris-canonical-1", "ciris-canonical-2")
        val attached = assertIs<TransitRows.Rows>(parseTransitRows("$row\n$two"))
        assertEquals(listOf("ciris-canonical-1", "ciris-canonical-2"), attached.subjects)
        assertIs<TransitRows.Invalid>(parseTransitRows("[1, 2]"))
        assertIs<TransitRows.Invalid>(parseTransitRows("{\"transport_destination\":"))
        assertTrue(Json.parseToJsonElement("[$row]").jsonArray.size == 1)
    }

    // ── fetch the row from the canonical (the expected path) ────────────────

    private fun canonical(status: Int, body: String) = node(mapOf("GET /v1/trust-root/transit-row" to listOf(status to body)))
    private val fetchedRow = """{"transport_destination":{"occurrence_key_id":"ciris-canonical-1","epoch":3},"attesting_key_id":"ciris-canonical-1",""" +
        """"signed_envelope":{"transit_kex_pubkeys":{"x25519_base64":"AAEC","ml_kem_768_base64":"AQID"}},"signature":"c2ln"}"""

    @Test
    fun aFetchedRowIsShownThenPlannedVerbatim() = runBlocking<Unit> {
        val c = canonical(200, fetchedRow)
        val n = node(mapOf(
            "GET /v1/accord/final-genesis/amend" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/amend/plan" to listOf(amendStatus(emptyList())),
        ))
        val vm = vm(n)
        vm.openAmend().join()
        vm.setCanonicalUrl(c.url)
        vm.fetchTransitRow().join()
        val f = assertIs<TransitFetch.Fetched>(vm.transitFetch.value)
        assertEquals("ciris-canonical-1", f.row.keyId)
        assertTrue(f.row.pairFingerprint != null)
        assertFalse(n.seen.any { it.contains("amend/plan") }, "fetching shows the row; it plans nothing")
        vm.planAmend().join()
        val sent = n.bodiesOf("POST /v1/accord/final-genesis/amend/plan").single()
        assertEquals(Json.parseToJsonElement("[$fetchedRow]"), sent["serve_transport_destinations"])
    }

    @Test
    fun aCanonicalOlderThanTheRouteIsNamedAndEachRefusalIsById() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis/amend" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
        ))
        val vm = vm(n)
        vm.openAmend().join()
        vm.setCanonicalUrl(canonical(404, "").url)
        vm.fetchTransitRow().join()
        assertEquals(TransitFetch.TooOld, vm.transitFetch.value)
        vm.setCanonicalUrl(canonical(404, refusal("trust_root.no_transit_row", "x")).url)
        vm.fetchTransitRow().join()
        assertEquals("trust_root.no_transit_row", assertIs<TransitFetch.Refused>(vm.transitFetch.value).refusal.reasonId)
    }
}
