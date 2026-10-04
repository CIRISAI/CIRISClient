package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.federation.FinalGenesisItems
import ai.ciris.mobile.shared.models.federation.GenesisCell
import ai.ciris.mobile.shared.models.federation.HolderGenesisState
import ai.ciris.mobile.shared.models.federation.PlanConfirm
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
 * **The final-genesis sheet's states, against a socket** that answers with the
 * bytes CIRISServer 0.5.220 produces (`src/final_genesis.rs` at e357f6bf; items
 * from persist v53.0.1 `genesis/ceremony.rs`). Every state the sheet draws is
 * reached here through the view model, over the real client.
 */
class FinalGenesisViewModelTest {

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        nodes.forEach { it.server.stop(0) }
    }

    /** "METHOD /path" → answers, served in order; the last one repeats. */
    private class Node(routes: Map<String, List<Pair<Int, String>>>) {
        private val queues = routes.mapValues { CopyOnWriteArrayList(it.value) }
        val seen: MutableList<String> = CopyOnWriteArrayList()
        val bodies: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val key = "${ex.requestMethod} ${ex.requestURI.rawPath}"
                seen += key
                bodies += key to ex.requestBody.readBytes().decodeToString()
                val q = queues[key]
                val (status, body) = when {
                    q == null || q.isEmpty() -> 404 to ""
                    q.size > 1 -> q.removeAt(0)
                    else -> q[0]
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
    )

    // ── the bytes ────────────────────────────────────────────────────────────

    private fun refusal(id: String, detail: String) = """{"error":"$id","reason_id":"$id","detail":"$detail"}"""
    private val notPlanned = 404 to refusal("final_genesis.not_planned", "no ceremony is planned on this node")

    private val source = 200 to """{"holders":[""" +
        """{"key_id":"A1","identity_type":"accord_holder","pubkey_ed25519_base64":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","pubkey_ml_dsa_65_base64":"x"},""" +
        """{"key_id":"B1","identity_type":"accord_holder","pubkey_ed25519_base64":"AQ==","pubkey_ml_dsa_65_base64":"x"},""" +
        """{"key_id":"C1","identity_type":"accord_holder","pubkey_ed25519_base64":"Ag==","pubkey_ml_dsa_65_base64":"x"}],""" +
        """"canonicals":[{"key_id":"ciris-canonical-1","identity_type":"canonical,node","pubkey_ed25519_base64":"Aw==",""" +
        """"pubkey_ml_dsa_65_base64":"y","scrub_key_id":"A1","transport_hints":null,"confers_infra_serve":true}],""" +
        """"quorum":"2/3","quorum_m":2,"quorum_n":3,"note":"all three sign"}"""

    private val r1 = listOf("record:ciris-canonical-1", "row:genesis-charter", "row:genesis-grant:ciris-canonical-1", "row:genesis-lifecycle")
    private val r2 = listOf("authz", "community:ciris-canonical", "family:humanity-accord")
    private val all = listOf("A1", "B1", "C1")

    /** The status body: [owedBy] item → holders still owed; signable = owed and not waiting. */
    private fun status(owedBy: Map<String, List<String>>, complete: Boolean = false): String {
        val charterDone = owedBy["row:genesis-charter"].isNullOrEmpty()
        val owed = owedBy.filterValues { it.isNotEmpty() }
        val ready = owed.keys.filter { it in r1 || charterDone }
        val owedJson = owed.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
            "\"$k\":[${v.joinToString(",") { "\"$it\"" }}]"
        }
        return """{"complete":$complete,"signable_now":[${ready.joinToString(",") { "\"$it\"" }}],"owed":{$owedJson}}"""
    }
    private val freshPlan = status((r1 + r2).associateWith { all })

    // ── old node / new node ─────────────────────────────────────────────────

    @Test
    fun aBare404IsAnOldNodeAndKeepsTheTwoOfThreeSheet() = runBlocking<Unit> {
        val n = node(mapOf("GET /v1/accord/genesis/remint-source" to listOf(source)))
        val vm = vm(n)
        vm.open().join()
        assertEquals(FinalGenesisPhase.Legacy, vm.phase.value)
        // The legacy sheet loads its own source; this one does not ask.
        assertEquals(listOf("GET /v1/accord/final-genesis"), n.seen)
    }

    @Test
    fun notPlannedOnANewNodePrefillsTheRecoveryKeysFromTheRecord() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
        ))
        val vm = vm(n)
        vm.open().join()
        assertEquals(FinalGenesisPhase.NotPlanned, vm.phase.value)
        assertEquals(
            mapOf("A1" to RecoveryRow.OnRecord("A2"), "B1" to RecoveryRow.OnRecord("B2"), "C1" to RecoveryRow.OnRecord("C2")),
            vm.recovery.value,
        )
        assertEquals(setOf("ciris-canonical-1"), vm.serveNodes.value, "the first canonical is seated by default")
    }

    @Test
    fun aRefusalOffTheNodesMachineIsShownNotTakenForAnOldNode() = runBlocking<Unit> {
        val n = node(mapOf("GET /v1/accord/final-genesis" to listOf(403 to "setup routes are localhost-only (run the wizard on the node's own host)")))
        val vm = vm(n)
        vm.open().join()
        val p = assertIs<FinalGenesisPhase.Unavailable>(vm.phase.value)
        assertEquals(403, p.refusal.statusCode)
    }

    // ── recovery keys (optional) ────────────────────────────────────────────

    @Test
    fun verifyingASpareThatMatchesTheRecordMarksItVerified() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/recovery-key" to listOf(200 to
                """{"holder_key_id":"A1","recovery_key":{"key_id":"A2","pubkey_ed25519_base64":"AAECAw==","pubkey_ml_dsa_65_base64":"z"},"recorded":["A1"]}"""),
        ))
        val vm = vm(n)
        vm.open().join()
        vm.verifyRecovery("A1", "/media/a2", "123456").join()
        val row = assertIs<RecoveryRow.Verified>(vm.recovery.value["A1"])
        assertEquals("A2", row.key.keyId)
        val body = n.bodiesOf("POST /v1/accord/final-genesis/recovery-key").single()
        assertEquals("A2", body["recovery_key_id"]!!.jsonPrimitive.content, "A1's spare is A2")
        assertTrue(vm.phase.value == FinalGenesisPhase.NotPlanned, "verifying never gates or changes the phase")
    }

    @Test
    fun aMismatchedSpareIsARefusalByIdAndPlanStaysAvailable() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/recovery-key" to listOf(409 to refusal(
                "final_genesis.recovery_key_mismatch",
                "the token opened as B2 does not hold the key the accord ceremony recorded for B2 — wrong YubiKey or USB",
            )),
            "POST /v1/accord/final-genesis/plan" to listOf(200 to freshPlan),
        ))
        val vm = vm(n)
        vm.open().join()
        vm.verifyRecovery("B1", "/media/b2", "123456").join()
        val row = assertIs<RecoveryRow.Refused>(vm.recovery.value["B1"])
        assertEquals("final_genesis.recovery_key_mismatch", row.refusal.reasonId)
        assertTrue(row.refusal.detail!!.contains("wrong YubiKey"))
        vm.plan().join()
        assertIs<FinalGenesisPhase.Planned>(vm.phase.value)
    }

    // ── plan ─────────────────────────────────────────────────────────────────

    @Test
    fun clockUnverifiedAsksForTheConfirmAndOnlyThenSendsClockChecked() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/plan" to listOf(
                412 to refusal("final_genesis.clock_unverified", "this server cannot read the host's clock-sync state"),
                200 to freshPlan,
            ),
        ))
        val vm = vm(n)
        vm.open().join()
        vm.plan().join()
        assertEquals(PlanConfirm.CLOCK, vm.confirm.value)
        assertNull(vm.refusal.value, "a question, not an error")
        assertEquals(FinalGenesisPhase.NotPlanned, vm.phase.value)
        vm.confirmClock().join()
        assertNull(vm.confirm.value)
        val plans = n.bodiesOf("POST /v1/accord/final-genesis/plan")
        assertEquals(listOf(false, true), plans.map { it["clock_checked"]!!.jsonPrimitive.boolean })
        assertEquals(listOf("ciris-canonical-1"), plans[1]["serve_nodes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertIs<FinalGenesisPhase.Planned>(vm.phase.value)
    }

    @Test
    fun anUnsynchronizedClockIsARefusalNoConfirmCanOverride() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(notPlanned),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/plan" to listOf(412 to refusal("final_genesis.clock_not_synchronized", "not NTP-synchronized")),
        ))
        val vm = vm(n)
        vm.open().join()
        vm.plan().join()
        assertNull(vm.confirm.value)
        assertEquals("final_genesis.clock_not_synchronized", vm.refusal.value?.reasonId)
    }

    @Test
    fun alreadyPlannedAsksBeforeReplacingAndKeepsTheClockConfirm() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(200 to freshPlan),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/plan" to listOf(
                412 to refusal("final_genesis.clock_unverified", "confirm the clock"),
                409 to refusal("final_genesis.already_planned", "a ceremony is already planned on this node; pass replace to discard it"),
                200 to freshPlan,
            ),
        ))
        val vm = vm(n)
        vm.open().join()
        assertIs<FinalGenesisPhase.Planned>(vm.phase.value)
        vm.startReplan()
        vm.plan().join()
        vm.confirmClock().join()
        assertEquals(PlanConfirm.REPLACE, vm.confirm.value)
        vm.confirmReplace().join()
        val plans = n.bodiesOf("POST /v1/accord/final-genesis/plan")
        assertEquals(3, plans.size)
        assertFalse("replace" in plans[0] || "replace" in plans[1], "replace is sent only after its confirm")
        assertEquals(true, plans[2]["replace"]!!.jsonPrimitive.boolean)
        assertEquals(true, plans[2]["clock_checked"]!!.jsonPrimitive.boolean)
        assertFalse(vm.replanning.value)
    }

    @Test
    fun otherPlanRefusalsAreShownById() = runBlocking<Unit> {
        for ((id, code) in listOf("final_genesis.serve_node_unknown" to 400, "final_genesis.no_serve_nodes" to 400, "ceremony_inputs_invalid" to 400)) {
            val n = node(mapOf(
                "GET /v1/accord/final-genesis" to listOf(notPlanned),
                "GET /v1/accord/genesis/remint-source" to listOf(source),
                "POST /v1/accord/final-genesis/plan" to listOf(code to refusal(id, "the node's own words")),
            ))
            val vm = vm(n)
            vm.open().join()
            vm.plan().join()
            assertEquals(id, vm.refusal.value?.reasonId)
            assertEquals("the node's own words", vm.refusal.value?.detail, "the English is the detail, never the id")
            assertNull(vm.confirm.value)
        }
    }

    // ── the grid ─────────────────────────────────────────────────────────────

    @Test
    fun roundOneIsSignableAndRoundTwoWaitsOnTheCharter() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(200 to freshPlan),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
        ))
        val vm = vm(n)
        vm.open().join()
        val g = vm.grid()!!
        assertEquals(listOf("record:ciris-canonical-1", "row:genesis-charter", "row:genesis-grant:ciris-canonical-1",
            "row:genesis-lifecycle", "family:humanity-accord", "community:ciris-canonical", "authz"), g.items)
        assertEquals(all, g.holders)
        for (h in all) {
            r1.forEach { assertEquals(GenesisCell.SIGN_NOW, g.cell(h, it)) }
            r2.forEach { assertEquals(GenesisCell.WAITING, g.cell(h, it)) }
            assertEquals(HolderGenesisState.SignNow, g.holderState(h))
        }
        assertFalse(g.roundTwoOpen)
        assertEquals(listOf("record:ciris-canonical-1", "row:genesis-charter", "row:genesis-grant:ciris-canonical-1", "row:genesis-lifecycle"),
            vm.itemsOfRound(1))
    }

    @Test
    fun aHolderWhoSignedRoundOneWaitsAndNothingToSignNamesWhoFor() = runBlocking<Unit> {
        // A1 has signed round one; B1 and C1 have not.
        val afterA1 = status(r1.associateWith { listOf("B1", "C1") } + r2.associateWith { all })
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(200 to freshPlan, 200 to afterA1),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/sign" to listOf(
                200 to """{"signed":${r1.joinToString(",", "[", "]") { "\"$it\"" }},"owed":{},"complete":false}""",
                409 to refusal("final_genesis.nothing_to_sign", "A1 owes nothing signable right now"),
            ),
        ))
        val vm = vm(n)
        vm.open().join()
        vm.sign("A1", "/media/a1", "123456").join()
        assertEquals(HolderSignNote.Signed(r1), vm.notes.value["A1"])
        val g = vm.grid()!!
        assertEquals(HolderGenesisState.Waiting(listOf("B1", "C1")), g.holderState("A1"))
        assertEquals(GenesisCell.SIGNED, g.cell("A1", "row:genesis-charter"))
        assertEquals(GenesisCell.SIGN_NOW, g.cell("B1", "row:genesis-charter"))

        vm.sign("A1", "/media/a1", "123456").join()
        assertEquals(HolderSignNote.NothingYet(listOf("B1", "C1")), vm.notes.value["A1"], "not an error: waiting for B1 and C1")
        val sign = n.bodiesOf("POST /v1/accord/final-genesis/sign").first()
        assertEquals("A1", sign["key_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun roundTwoOpensOnlyOnceAllThreeHaveSignedTheCharter() = runBlocking<Unit> {
        val twoOfThree = status(r1.associateWith { listOf("C1") } + r2.associateWith { all })
        val threeOfThree = status(r2.associateWith { all })
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(200 to twoOfThree, 200 to threeOfThree),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
        ))
        val vm = vm(n)
        vm.open().join()
        var g = vm.grid()!!
        assertFalse(g.roundTwoOpen, "two charters are not three")
        assertEquals(listOf("C1"), g.charterOwedBy)
        assertEquals(GenesisCell.WAITING, g.cell("A1", "authz"))
        vm.refresh().join()
        g = vm.grid()!!
        assertTrue(g.roundTwoOpen)
        for (h in all) {
            r2.forEach { assertEquals(GenesisCell.SIGN_NOW, g.cell(h, it)) }
            // Round one left `owed` once complete; the grid still shows it, signed.
            r1.forEach { assertEquals(GenesisCell.SIGNED, g.cell(h, it)) }
        }
        assertEquals(7, g.items.size)
    }

    @Test
    fun aSignerFailureIsARefusalOnThatHoldersRow() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(200 to freshPlan),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/sign" to listOf(502 to refusal("final_genesis.signer_unavailable", "the specified PIN is incorrect")),
        ))
        val vm = vm(n)
        vm.open().join()
        vm.sign("B1", "/media/b1", "000000").join()
        val note = assertIs<HolderSignNote.Refused>(vm.notes.value["B1"])
        assertEquals("final_genesis.signer_unavailable", note.refusal.reasonId)
        assertNull(vm.notes.value["A1"])
    }

    // ── finish ───────────────────────────────────────────────────────────────

    @Test
    fun finishBeforeEveryoneSignedIs409IncompleteAndRereadsTheStatus() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(200 to freshPlan),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/finish" to listOf(409 to refusal("ceremony_incomplete", "still owed: authz by A1, B1, C1")),
        ))
        val vm = vm(n)
        vm.open().join()
        vm.finish().join()
        assertEquals("ceremony_incomplete", vm.refusal.value?.reasonId)
        assertEquals("still owed: authz by A1, B1, C1", vm.refusal.value?.detail)
        assertIs<FinalGenesisPhase.Planned>(vm.phase.value)
        assertEquals(2, n.seen.count { it == "GET /v1/accord/final-genesis" })
    }

    @Test
    fun finishReturnsTheBundleFingerprintPathAndVerifiedFacts() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/accord/final-genesis" to listOf(200 to status(emptyMap(), complete = true)),
            "GET /v1/accord/genesis/remint-source" to listOf(source),
            "POST /v1/accord/final-genesis/finish" to listOf(200 to
                """{"complete":true,"bundle_path":"/var/lib/ciris/final-genesis/canonical_seed.json",""" +
                """"bundle_sha256":"sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",""" +
                """"verified":{"quorum_verified":3,"serve_nodes":["ciris-canonical-1"],""" +
                """"attestations":["genesis-charter","genesis-grant:ciris-canonical-1","genesis-lifecycle"],""" +
                """"community_key_id":"ciris-canonical","founders":3}}"""),
        ))
        val vm = vm(n)
        vm.open().join()
        assertTrue(vm.grid()!!.complete)
        vm.finish().join()
        val done = assertIs<FinalGenesisPhase.Finished>(vm.phase.value).result
        assertEquals("sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", done.bundleSha256)
        assertEquals("/var/lib/ciris/final-genesis/canonical_seed.json", done.bundlePath)
        assertEquals(3, done.verified.quorumVerified)
        assertEquals(listOf("ciris-canonical-1"), done.verified.serveNodes)
        assertEquals(3, done.verified.attestations.size)
        assertEquals("ciris-canonical", done.verified.communityKeyId)
        assertEquals(3, done.verified.founders)
        // A re-read after finishing does not take the result off the screen.
        vm.refresh().join()
        assertIs<FinalGenesisPhase.Finished>(vm.phase.value)
        assertEquals(1, FinalGenesisItems.round("record:x"))
    }
}
