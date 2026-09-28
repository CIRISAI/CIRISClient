package ai.ciris.mobile.shared.models

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.RouteNotOnThisHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Data card's erasure contract (CSD-039 §3): every host answer decodes from
 * the shape the host's source writes, and no answer that is not a full erasure
 * is ever classified as one.
 */
class ErasureWireTest {

    // CIRISServer src/federation_admin.rs erase_agent_traces, the 200 body verbatim.
    private val nodeErased = """
        {"erased": true, "agent_id_hash": "a4f19c02deadbeef", "trace_events": 412,
         "trace_llm_calls": 1830, "detection_events_tombstoned": 7,
         "erased_at": "2026-09-25T18:02:11+00:00",
         "scope_note": "traces only — payloads in attestation/registration envelopes, attestation_evidence, policy_blob or hard_case detail are NOT reached by any erasure primitive (CIRISPersist#573)"}
    """.trimIndent()

    private fun traces(raw: String) = decodeErasure(TraceErasureResult.serializer(), decodeErasure(raw)!!)

    @Test
    fun theNodesTraceErasureBodyDecodesFieldForField() {
        val r = traces(nodeErased)
        assertTrue(r.erased)
        assertEquals("a4f19c02deadbeef", r.agentIdHash)
        assertEquals(412L, r.traceEvents)
        assertEquals(1830L, r.traceLlmCalls)
        assertEquals(7L, r.detectionEventsTombstoned)
        assertEquals("2026-09-25T18:02:11+00:00", r.erasedAt)
        assertTrue(r.scopeNote!!.startsWith("traces only"))
        assertEquals(ErasureOutcome.Erased, r.outcome())
    }

    @Test
    fun aRepeatThatFindsNothingIsNothingThereNotErased() {
        // Idempotent: a second call returns all-zero counts, not an error.
        val r = traces("""{"erased": true, "agent_id_hash": "x", "trace_events": 0, "trace_llm_calls": 0, "detection_events_tombstoned": 0, "erased_at": "2026-09-25T18:03:00+00:00"}""")
        assertEquals(ErasureOutcome.NothingThere, r.outcome())
    }

    @Test
    fun aBodyThatDoesNotSayErasedIsNeverErased() {
        val r = traces("""{"agent_id_hash": "x", "trace_events": 5}""")
        val o = r.outcome()
        assertIs<ErasureOutcome.Partial>(o)
        assertTrue(o.unconfirmed)
    }

    // CIRISServer src/auth/erasure.rs ErasureResponse — reachable only after CIRISServer#677.
    private fun actor(raw: String) = decodeErasure(ActorErasureReport.serializer(), decodeErasure(raw)!!)

    @Test
    fun aFailedWithdrawIsPartialEvenWhenBlobsWereEvicted() {
        val r = actor("""{"blobs_evicted": 3, "withdraws_emitted": 2, "withdraws_failed": 1, "incomplete": true}""")
        val o = r.outcome()
        assertIs<ErasureOutcome.Partial>(o)
        assertEquals(1, o.withdrawsFailed)
        assertNotEquals<ErasureOutcome>(ErasureOutcome.Erased, o)
    }

    @Test
    fun incompleteIsPartialEvenWithNoFailures() {
        // erasure.rs: incomplete = blobs_evicted > 0, "re-invoke until zero".
        val o = actor("""{"blobs_evicted": 4, "withdraws_emitted": 4, "withdraws_failed": 0, "incomplete": true}""").outcome()
        assertIs<ErasureOutcome.Partial>(o)
        assertTrue(o.moreMayRemain)
    }

    @Test
    fun aFailedWithdrawOnAFinalPassIsStillPartial() {
        val o = actor("""{"blobs_evicted": 0, "withdraws_emitted": 0, "withdraws_failed": 2, "incomplete": false}""").outcome()
        assertIs<ErasureOutcome.Partial>(o)
        assertEquals(2, o.withdrawsFailed)
    }

    @Test
    fun aCleanFinalPassIsNothingThere() {
        assertEquals(
            ErasureOutcome.NothingThere,
            actor("""{"blobs_evicted": 0, "withdraws_emitted": 0, "withdraws_failed": 0, "incomplete": false}""").outcome(),
        )
    }

    // ── Why a call produced no answer ──

    @Test
    fun anUnmatchedRouteIsNotOnThisHostOnBothFrameworks() {
        // axum: empty 404. FastAPI: {"detail": "Not Found"}.
        assertIs<ErasureFailure.NotOnThisHost>(ErasureFailure.of(NodeRefusal.fromBody(404, "")))
        assertIs<ErasureFailure.NotOnThisHost>(ErasureFailure.of(NodeRefusal.fromBody(404, """{"detail":"Not Found"}""")))
        assertIs<ErasureFailure.NotOnThisHost>(ErasureFailure.of(RouteNotOnThisHost("/v1/federation/erase-agent-traces")))
    }

    @Test
    fun aFourOhFourThatNamesSomethingIsTheHostAnswering() {
        val f = ErasureFailure.of(
            NodeRefusal.fromBody(404, """{"detail":"Public key k1 not found. Current key: k2"}"""),
        )
        assertIs<ErasureFailure.Refused>(f)
        assertEquals(404, f.status)
    }

    @Test
    fun theOwnerGateIsARefusalNotAnAbsence() {
        val f = ErasureFailure.of(NodeRefusal.fromBody(403, """{"error":"this node has no responsible party (owner-binding) — erasure refused"}"""))
        assertIs<ErasureFailure.Refused>(f)
        assertTrue(f.detail!!.contains("owner-binding"))
        assertIs<ErasureFailure.Failed>(ErasureFailure.of(RuntimeException("connection refused")))
    }

    // ── Receipts: CIRISAgent routes/verification.py ──

    private val proofJson = """
        {"deletion_id": "del-7", "user_identifier": "ada@example.org",
         "sources_deleted": {"ciris_internal": {"total_records_deleted": 12, "weight": 1.0},
                             "sql": {"total_records_deleted": 3}},
         "deleted_at": "2026-09-01T10:00:00+00:00",
         "signature": "c2lnbmF0dXJl", "public_key_id": "agent-k1"}
    """.trimIndent()

    @Test
    fun aBareProofAndAWrappedProofReadTheSame() {
        val bare = parseDeletionProof(proofJson)
        val wrapped = parseDeletionProof("""{"deletion_proof": $proofJson}""")
        assertIs<ProofParse.Read>(bare)
        assertIs<ProofParse.Read>(wrapped)
        assertEquals(bare.proof, wrapped.proof)
        assertEquals(15, bare.proof.claimedRecords)
    }

    @Test
    fun theSignedValuesAreSentBackAsTheyWereRead() {
        // The agent re-canonicalizes sources_deleted (RFC 8785); rewriting 1.0 as 1
        // would change the bytes the signature covers.
        val proof = (parseDeletionProof(proofJson) as ProofParse.Read).proof
        val out = Json.encodeToString(DeletionProof.serializer(), proof)
        assertTrue("\"weight\":1.0" in out, out)
        val back = Json.parseToJsonElement(out) as JsonObject
        assertEquals(Json.parseToJsonElement(proofJson), back)
    }

    @Test
    fun aProofMissingAFieldIsRefusedBeforeAnyCall() {
        val r = parseDeletionProof(proofJson.replace("\"signature\": \"c2lnbmF0dXJl\",", ""))
        assertIs<ProofParse.Unreadable>(r)
        assertTrue("signature" in r.why)
        assertIs<ProofParse.Unreadable>(parseDeletionProof("not json"))
        assertIs<ProofParse.Unreadable>(parseDeletionProof("   "))
    }

    @Test
    fun theVerdictAndTheKeyDecode() {
        val v = decodeErasure(
            DeletionVerification.serializer(),
            standardData("""{"success": false, "data": {"valid": false, "deletion_id": "del-7", "user_identifier": "ada@example.org", "deleted_at": "2026-09-01T10:00:00+00:00", "sources_count": 2, "total_records": 0, "message": "Invalid signature - deletion proof cannot be verified", "verified_at": "2026-09-25T18:00:00+00:00"}, "message": "Invalid signature - deletion proof cannot be verified"}""")!!,
        )
        assertFalse(v.valid)
        assertEquals(0, v.totalRecords)
        val k = decodeErasure(
            DeletionSigningKey.serializer(),
            standardData("""{"success": true, "data": {"public_key_id": "agent-k2", "download_url": "/v1/verification/keys/agent-k2.pub", "algorithm": "Ed25519 over RFC 8785 (JCS) canonical bytes"}}""")!!,
        )
        assertEquals("agent-k2", k.publicKeyId)
        val proof = (parseDeletionProof(proofJson) as ProofParse.Read).proof
        assertTrue(proofKeyIsNotCurrent(proof, k), "a proof naming agent-k1 is not checked by agent-k2")
        assertFalse(proofKeyIsNotCurrent(proof, k.copy(publicKeyId = "agent-k1")))
        assertFalse(proofKeyIsNotCurrent(proof, null), "an unread key is not evidence of a rotation")
    }
}
