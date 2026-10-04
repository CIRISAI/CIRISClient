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
        // The two charter legs of CC 3.2 T3, previously decoded and dropped.
        assertEquals(true, root.rootSelfDeclares)
        assertEquals(true, root.charterHasRecovery)
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

    @Test
    fun anUnlabelledBundleIsANamedRefusalWithGuidanceNotTheNodesRawText() {
        // ciris-server 0.5.220 (persist v53, CC 3.2 T4a): the import route's own
        // body, verbatim shape (`trust_root_api.rs::err`: the English rides
        // `error`, the id rides `reason_id`), 422.
        val wire = """{"error":"this bundle was minted before the trust-root rows carried their job labels (trust:charter:v1); on this node it would install as no charter at all. Import a bundle from the final genesis (FSD/FINAL_GENESIS.md) instead. Nothing on this node changed.","reason_id":"trust_root.bundle_unlabelled"}"""
        val refused = trustRootFailure(NodeRefusal.fromBody(422, wire))
        assertIs<TrustRootFailure.Refused>(refused)
        assertEquals(TRUST_ROOT_BUNDLE_UNLABELLED, refused.reasonId)
        // A title in the reader's language, and guidance under it — never the node's English as the body.
        assertEquals("mobile.trust_root_refused_bundle_unlabelled", trustRootRefusalKey(refused.reasonId))
        assertEquals("mobile.trust_root_refused_bundle_unlabelled_body", trustRootRefusalBodyKey(refused.reasonId))
        // Other refusals keep the node's own words under their title.
        assertNull(trustRootRefusalBodyKey("trust_root.bad_bundle"))
        assertNull(trustRootRefusalBodyKey(null))
    }

    @Test
    fun aLabelledCharterWithAnUnlabelledGrantIsTheSameRefusalAndItsDetailNamesTheRow() {
        // CIRISServer#726 (4da726e8): grants are checked too; same id, and the
        // English names the row — that detail is what the block shows beneath the guidance.
        val wire = """{"error":"this bundle was minted before the trust-root rows carried their job labels (trust:charter:v1 / trust:confers:v1 — genesis-grant:srv-1 carries none); on this node it would install as no charter or no grant. Import a bundle from the final genesis (FSD/FINAL_GENESIS.md) instead. Nothing on this node changed.","reason_id":"trust_root.bundle_unlabelled"}"""
        val refused = assertIs<TrustRootFailure.Refused>(trustRootFailure(NodeRefusal.fromBody(422, wire)))
        assertEquals(TRUST_ROOT_BUNDLE_UNLABELLED, refused.reasonId)
        assertTrue(refused.detail.orEmpty().contains("genesis-grant:srv-1"), refused.detail)
    }

    @Test
    fun aRootNotAdoptedWithThePreviousInForceIsNotUnrooted() {
        // persist v53 (#973): pre_genesis with reason.kind = bake_not_adopted.
        val held = listing(
            """{"posture":{"state":"pre_genesis","leg":"anchor","detail":"newer bake refused","reason":{"kind":"bake_not_adopted","why":{"cause":"refused","refusal":"x"},"held_root_in_force":true}},"banner":"ROOT NOT ADOPTED: this node still holds its previous constitutional trust root …","entrenched":false,"roots":[]}""",
        ).trustPosture()
        assertEquals(GenesisState.PRE_GENESIS, held.state)
        assertTrue(held.bakeNotAdopted)
        assertTrue(held.heldRootInForce)
        assertEquals("ROOT NOT ADOPTED: this node still holds its previous constitutional trust root …", held.banner, "the banner is the node's, as given")
        val none = listing(
            """{"posture":{"state":"pre_genesis","leg":"anchor","detail":"d","reason":{"kind":"bake_not_adopted","why":{"cause":"refused"},"held_root_in_force":false}},"banner":"NO TRUST ROOT","entrenched":false,"roots":[]}""",
        ).trustPosture()
        assertTrue(none.bakeNotAdopted)
        assertEquals(false, none.heldRootInForce)
        // An older node: no `reason` at all reads as persist's default, not_seeded.
        val old = listing("""{"posture":{"state":"pre_genesis","leg":"canonical","detail":"no row"},"banner":"PRE-GENESIS: …","entrenched":false,"roots":[]}""").trustPosture()
        assertEquals(false, old.bakeNotAdopted)
        assertEquals(false, old.heldRootInForce)
        val seeded = listing("""{"posture":{"state":"pre_genesis","leg":"canonical","detail":"no row","reason":{"kind":"not_seeded"}},"entrenched":false,"roots":[]}""").trustPosture()
        assertEquals(false, seeded.bakeNotAdopted)
    }

    @Test
    fun anImportInstalledButNotAcceptedIsNeverASuccess() {
        // 0.5.220: a deferred acceptance reports `accepted: false` (it read true before).
        val json = Json { ignoreUnknownKeys = true }
        val deferred = json.decodeFromString(TrustRootImportResult.serializer(), """{"installed":true,"accepted":false,"entrenched":true,"banner":null}""")
        assertTrue(importNotYetAccepted(deferred))
        val whole = json.decodeFromString(TrustRootImportResult.serializer(), """{"installed":true,"accepted":true,"entrenched":true}""")
        assertEquals(false, importNotYetAccepted(whole))
    }

    @Test
    fun theServedBundleShowsItsFingerprintAndCharterRoot() {
        val json = Json { ignoreUnknownKeys = true }
        val v3 = servedBundleView(json.decodeFromString(TrustRootBundleDto.serializer(),
            """{"bundle":{"version":3,"attestations":[]},"community":{"key_id":"ciris-canonical"},"bundle_fingerprint":"sha256:ab12","charter_root_key_id":"humanity-accord","served_by":"node-1"}"""))
        assertEquals(ServedBundleView("sha256:ab12", "humanity-accord", carriesCommunity = true), v3)
        val v2 = servedBundleView(json.decodeFromString(TrustRootBundleDto.serializer(),
            """{"bundle":{},"community":null,"bundle_fingerprint":"sha256:cd34","charter_root_key_id":null,"served_by":"node-1"}"""))
        assertEquals(ServedBundleView("sha256:cd34", null, carriesCommunity = false), v2)
        assertNull(servedBundleView(TrustRootBundleDto()), "nothing comparable, no card")
    }

    @Test
    fun aNodeNotOnItsBundleIsANamedStateAndAnOlderNodeShowsNoCard() {
        // CIRISServer#726 (814dd7c6): 409 trust_root.bundle_not_in_force when not entrenched on the bake.
        val notInForce = servedBundleRead(NodeRefusal.fromBody(409,
            """{"error":"this node is not entrenched on the bundle it carries, so it serves none: ROOT NOT ADOPTED …","reason_id":"trust_root.bundle_not_in_force"}"""))
        val named = assertIs<ServedBundleRead.NotInForce>(notInForce)
        assertTrue(named.detail.orEmpty().contains("ROOT NOT ADOPTED"))
        // A node without the route, a transport failure, another refusal: no card, never an error.
        assertEquals(ServedBundleRead.Absent, servedBundleRead(NodeRefusal.fromBody(404, "")))
        assertEquals(ServedBundleRead.Absent, servedBundleRead(RuntimeException("connect refused")))
        assertEquals(ServedBundleRead.Absent, servedBundleRead(TrustRootBundleDto()))
        assertIs<ServedBundleRead.Shown>(servedBundleRead(TrustRootBundleDto(bundleFingerprint = "sha256:1")))
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
