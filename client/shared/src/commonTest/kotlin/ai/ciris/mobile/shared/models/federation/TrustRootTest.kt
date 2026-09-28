package ai.ciris.mobile.shared.models.federation

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.viewmodels.ImportStage
import ai.ciris.mobile.shared.viewmodels.TrustRootViewModel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This node's trust root, read the way CIRISServer `src/trust_root_api.rs`
 * serves it (persist v48.0.0 shapes: `GenesisPosture` is `{"state": …}`,
 * `RootKind` and `DrillFreshness` serialize PascalCase).
 */
class TrustRootTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun listing(raw: String) = json.decodeFromString(TrustRootListingDto.serializer(), raw)

    /** A real-shaped listing: the accord family, entrenched, `accepted` as the server fills it today. */
    private val entrenchedListing = """
        {"posture":{"state":"entrenched"},"banner":null,"entrenched":true,
         "roots":[{"root_key_id":"humanity-accord","root_kind":"Family","accepted":false,
           "verdict":{"edge_exists":true,"root_self_declares":true,"charter_has_recovery":true,
             "last_drill_at":"2026-09-01T12:00:00Z","drill_freshness":"Green","halt_latched":false,
             "valid":true,"root_kind":"Family",
             "charter_quorum":{"distinct_holders":2,"required":2,"roster_size":3},
             "holders_hardware":[
               {"key_id":"wa-a1","class":"ExternalSecureElement","layer_a":true,"layer_b":true,"refusal":null},
               {"key_id":"wa-b1","class":null,"layer_a":false,"layer_b":null,"refusal":"no attestation evidence"}],
             "holders_hardware_attested":false}}]}
    """.trimIndent()

    @Test
    fun acceptanceIsReadFromEdgeExistsNotTheBrokenAcceptedField() {
        // CIRISServer#681: `accepted` is false for every root on the wire.
        val root = trustRootView(listing(entrenchedListing).roots.single())
        assertEquals(true, root.acceptedByThisNode)
    }

    @Test
    fun theVerdictIsReadWhole() {
        val root = trustRootView(listing(entrenchedListing).roots.single())
        assertEquals(RootKindView.FAMILY, root.kind)
        assertEquals(true, root.valid)
        assertEquals(CharterQuorumView(2, 2, 3), root.quorum)
        assertEquals(DrillBand.GREEN, root.drillBand)
        assertEquals("2026-09-01T12:00:00Z", root.lastDrillAt)
        assertEquals(false, root.haltLatched)
        assertNull(root.boundedUntil)
        assertEquals(2, root.holders.size)
        assertEquals("ExternalSecureElement", root.holders[0].hardwareClass)
        assertEquals(true, root.holders[0].layerB)
        assertNull(root.holders[1].hardwareClass)
        assertNull(root.holders[1].layerB)
        assertEquals("no attestation evidence", root.holders[1].refusal)
    }

    @Test
    fun anUnreadableRootIsUnknownNotUntrusted() {
        val l = listing(
            """{"posture":{"state":"entrenched"},"entrenched":true,
               "roots":[{"root_key_id":"solo-1","root_kind":"unreadable","accepted":false,
                         "verdict":{"error":"directory: timeout"}}]}""",
        )
        val root = trustRootView(l.roots.single())
        assertEquals(RootKindView.UNREADABLE, root.kind)
        assertEquals("directory: timeout", root.evaluationError)
        assertNull(root.acceptedByThisNode)
        assertNull(root.valid)
    }

    @Test
    fun eachGenesisStateIsItsOwnState() {
        fun p(state: String, entrenched: Boolean = false) =
            listing("""{"posture":{"state":"$state","leg":"family","detail":"d"},"banner":"b","entrenched":$entrenched,"roots":[]}""")
                .trustPosture().state
        assertEquals(GenesisState.ENTRENCHED, p("entrenched", entrenched = true))
        assertEquals(GenesisState.PRE_GENESIS, p("pre_genesis"))
        assertEquals(GenesisState.DIVERGENT, p("divergent"))
        assertEquals(GenesisState.UNREADABLE, p("unreadable"))
        assertEquals(GenesisState.UNKNOWN, p("something_new"))
        // A token that says entrenched while persist's own bool says not is never green.
        assertEquals(GenesisState.UNKNOWN, p("entrenched", entrenched = false))
        val pre = listing("""{"posture":{"state":"pre_genesis","leg":"canonical","detail":"no row"},"banner":"PRE-GENESIS: …","entrenched":false,"roots":[]}""").trustPosture()
        assertEquals("canonical", pre.leg)
        assertEquals("PRE-GENESIS: …", pre.banner)
    }

    @Test
    fun unTrustingTheLastAcceptedRootSaysTraceStops() {
        val accord = trustRootView(listing(entrenchedListing).roots.single())
        assertEquals(UntrustConsequence.LAST_ROOT, untrustConsequence(listOf(accord), accord.rootKeyId))
        val other = accord.copy(rootKeyId = "mesh-b")
        assertEquals(UntrustConsequence.OTHERS_REMAIN, untrustConsequence(listOf(accord, other), accord.rootKeyId))
        // A root whose acceptance is unknown does not count as "another remains".
        val unknown = other.copy(acceptedByThisNode = null)
        assertEquals(UntrustConsequence.LAST_ROOT, untrustConsequence(listOf(accord, unknown), accord.rootKeyId))
    }

    @Test
    fun theLoopbackRefusalIsItsOwnFailure() {
        // `require_loopback` answers a bare 403 with a text body (src/auth/loopback.rs:52-55).
        val bare = NodeRefusal.fromBody(403, "setup routes are localhost-only (run the wizard on the node's own host)")
        assertIs<TrustRootFailure.LoopbackOnly>(trustRootFailure(bare))
        // …and the id #652 asks for reads as the same fact.
        val typed = NodeRefusal.fromBody(403, """{"error":"x","reason_id":"trust_root.loopback_required"}""")
        assertIs<TrustRootFailure.LoopbackOnly>(trustRootFailure(typed))
        assertIs<TrustRootFailure.NotOnThisNode>(trustRootFailure(NodeRefusal.fromBody(404, "")))
        val refused = trustRootFailure(
            NodeRefusal.fromBody(400, """{"error":"not a genesis bundle: missing field","reason_id":"trust_root.bad_bundle"}"""),
        )
        assertIs<TrustRootFailure.Refused>(refused)
        assertEquals("trust_root.bad_bundle", refused.reasonId)
        assertEquals("mobile.trust_root_refused_bad_bundle", trustRootRefusalKey(refused.reasonId))
        assertIs<TrustRootFailure.Failed>(trustRootFailure(RuntimeException("connect refused")))
    }

    private val seed = """
        {"version":1,"family_key_id":"humanity-accord","holders":[],"serve_nodes":[],
         "consensus_protocol":"quorum:2/3",
         "attestations":[
           {"attestation":{"attestation_type":"delegates_to","attestation_id":"genesis-grant:srv-1","attested_key_id":"srv-1"}},
           {"attestation":{"attestation_type":"delegates_to","attestation_id":"genesis-charter","attested_key_id":"humanity-accord"}}],
         "authorizations":[],"produced_at":"2026-09-25T00:00:00Z"}
    """.trimIndent()

    @Test
    fun theSeedNamesItsCharterRoot() {
        val stage = TrustRootViewModel.prepareImport(seed)
        assertIs<ImportStage.Confirming>(stage)
        assertEquals("humanity-accord", stage.charterRoot)
        assertEquals("humanity-accord", stage.familyKeyId)
    }

    @Test
    fun notJsonIsRefusedBeforeAnythingIsSent() {
        assertIs<ImportStage.NotJson>(TrustRootViewModel.prepareImport("not a seed"))
        assertIs<ImportStage.NotJson>(TrustRootViewModel.prepareImport("[1,2]"))
        val noCharter = TrustRootViewModel.prepareImport("""{"family_key_id":"x","attestations":[]}""")
        assertIs<ImportStage.Confirming>(noCharter)
        assertNull(noCharter.charterRoot)
    }

    @Test
    fun theImportResultKeepsItsTwoActsApart() {
        val r = json.decodeFromString(
            TrustRootImportResult.serializer(),
            """{"installed":true,"accepted":false,"posture":{"state":"pre_genesis","leg":"canonical","detail":"d"},"entrenched":false,"banner":"b"}""",
        )
        assertTrue(r.installed)
        assertEquals(false, r.accepted)
    }

    @Test
    fun familyVersionsReadTheSnapshot() {
        val h = json.decodeFromString(
            FamilyHistoryResponse.serializer(),
            """{"versions":[
                 {"cohort":"family","group_key_id":"humanity-accord","version":1,
                  "snapshot":{"family_key_id":"humanity-accord","members":[{"key_id":"wa-a1"},{"key_id":"wa-b1"},{"key_id":"wa-c1"}],
                              "consensus_protocol":"quorum:2/3","founded_at":"2026-08-14T00:00:00Z"},
                  "is_current":true}]}""",
        )
        val v = familyVersionView(h.versions.single())
        assertEquals(1, v.version)
        assertTrue(v.isCurrent)
        assertEquals(listOf("wa-a1", "wa-b1", "wa-c1"), v.members)
        assertEquals("quorum:2/3", v.consensusProtocol)
        assertEquals(false, v.byQuorum)
    }
}
