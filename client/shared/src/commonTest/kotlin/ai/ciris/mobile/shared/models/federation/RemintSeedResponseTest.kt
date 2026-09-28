package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The re-mint (portable seed) ceremony's done state — what the node says about
 * the seed it just minted, read from where the node puts it.
 *
 * The body below is the shape `propose_genesis`/`cosign_genesis` return
 * (CIRISServer `src/accord_provision.rs:2579-2600`): the fingerprint and this
 * node's acceptance of the new root are TOP-LEVEL fields beside the bundle.
 * The bundle is persist's `GenesisBundle`, which has no fingerprint field
 * (CIRISPersist v48.0.0 `genesis/bundle.rs:121-130`). Reading the fingerprint
 * from the bundle meant the CC 3.2 T5 out-of-band comparison never rendered.
 */
class RemintSeedResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val bundle = """
        {"version":1,"family_key_id":"humanity-accord","holders":[{},{},{}],
         "serve_nodes":[{}],"consensus_protocol":"quorum:2/3","attestations":[],
         "authorizations":[{"holder_key_id":"wa-a1","signature_classical":"x","signature_pqc":"y"},
                           {"holder_key_id":"wa-b1","signature_classical":"x","signature_pqc":"y"}],
         "produced_at":"2026-09-25T00:00:00Z"}
    """.trimIndent()

    private fun body(
        complete: Boolean = true,
        fingerprint: String = "\"3f9a0c1d2e4b5a67\"",
        trustsRoot: String = "\"humanity-accord\"",
        trustEdgeError: String = "null",
    ) = """
        {"bundle":$bundle,
         "seed_path":${if (complete) "\"/home/node/.ciris/mesh-genesis.json\"" else "null"},
         "seed_filename":"mesh-genesis.json",
         "seed_save_error":null,
         "authorizations_have":2,"authorizations_needed":2,"complete":$complete,
         "blocked_by":null,
         "fingerprint":$fingerprint,
         "node_trusts_root":$trustsRoot,
         "trust_edge_error":$trustEdgeError,
         "serve_node_reblessed":false}
    """.trimIndent()

    private fun parse(raw: String) = json.decodeFromString(GenesisSeedResponse.serializer(), raw)

    private fun state(res: GenesisSeedResponse) = GenesisSeedState(
        bundle = res.bundle,
        prettyJson = "",
        authorizationsHave = res.authorizationsHave,
        authorizationsNeeded = res.authorizationsNeeded,
        complete = res.complete,
        fingerprint = res.fingerprint,
        nodeTrustsRoot = res.nodeTrustsRoot,
        trustEdgeError = res.trustEdgeError,
        seedPath = res.seedPath,
        seedSaveError = res.seedSaveError,
    )

    @Test
    fun theFingerprintIsReadFromTheResponseNotTheBundle() {
        val res = parse(body())
        assertEquals("3f9a0c1d2e4b5a67", res.fingerprint)
        val d = genesisSeedDisplay(res.bundle, res.fingerprint)
        assertEquals("3f9a0c1d2e4b5a67", d.fingerprint)
        // The bundle's own facts still come from the bundle.
        assertEquals("humanity-accord", d.familyKeyId)
        assertEquals(3, d.holderCount)
        assertEquals(1, d.serveNodeCount)
        assertEquals(listOf("wa-a1", "wa-b1"), d.authorizedKeyIds)
    }

    @Test
    fun anEmptyFingerprintIsAbsenceNotAFingerprint() {
        // The node sends `unwrap_or_default()` — "" — when it could not compute one.
        val res = parse(body(fingerprint = "\"\""))
        assertNull(genesisSeedDisplay(res.bundle, res.fingerprint).fingerprint)
        assertNull(genesisSeedDisplay(res.bundle, null).fingerprint)
    }

    @Test
    fun doneOnlyWhenThisNodeTrustsTheRoot() {
        val res = parse(body())
        assertEquals("/home/node/.ciris/mesh-genesis.json", res.seedPath)
        val outcome = remintOutcome(state(res))
        assertIs<RemintOutcome.Trusted>(outcome)
        assertEquals("humanity-accord", outcome.rootKeyId)
    }

    @Test
    fun mintedButNotTrustedCarriesTheNodesReason() {
        val res = parse(body(trustsRoot = "\"\"", trustEdgeError = "\"directory: write refused\""))
        val outcome = remintOutcome(state(res))
        assertIs<RemintOutcome.MintedNotTrusted>(outcome)
        assertEquals("directory: write refused", outcome.error)
    }

    @Test
    fun mintedButNotTrustedWithNoReasonSaysSo() {
        // An older node that sends neither field must not read as "this node trusts it".
        val res = parse(body(trustsRoot = "null"))
        val outcome = remintOutcome(state(res))
        assertIs<RemintOutcome.MintedNotTrusted>(outcome)
        assertNull(outcome.error)
    }

    @Test
    fun anIncompleteCeremonyHasNoOutcome() {
        val res = parse(body(complete = false, trustsRoot = "\"\""))
        assertNull(remintOutcome(state(res)))
    }
}
