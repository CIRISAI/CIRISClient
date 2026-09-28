package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.DeletionProof
import ai.ciris.mobile.shared.models.DeletionSigningKey
import ai.ciris.mobile.shared.models.DeletionVerification
import ai.ciris.mobile.shared.models.ErasureFailure
import ai.ciris.mobile.shared.models.ErasureOutcome
import ai.ciris.mobile.shared.models.TraceErasureResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Data card's erasure state (CSD-039 §3): what the person sees after an
 * erasure is what the node reported, and nothing short of that reads as done.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataErasureControllerTest {

    private class FakeBackend(
        var erase: suspend (String, String) -> TraceErasureResult = { h, _ ->
            TraceErasureResult(erased = true, agentIdHash = h, traceEvents = 3, erasedAt = "2026-09-25T18:00:00+00:00")
        },
        var key: suspend () -> DeletionSigningKey = { DeletionSigningKey("agent-k2", "/v1/verification/keys/agent-k2.pub", "Ed25519") },
        var pub: suspend (String) -> String = { "AAAA" },
        var verify: suspend (DeletionProof) -> DeletionVerification = { p ->
            DeletionVerification(valid = true, deletionId = p.deletionId, totalRecords = 15, verifiedAt = "2026-09-25T18:01:00+00:00")
        },
    ) : DataErasureBackend {
        val erased = mutableListOf<Pair<String, String>>()
        var verifyCalls = 0
        override suspend fun eraseAgentTraces(agentIdHash: String, reason: String): TraceErasureResult {
            erased += agentIdHash to reason
            return erase(agentIdHash, reason)
        }
        override suspend fun deletionSigningKey() = key()
        override suspend fun deletionPublicKey(keyId: String) = pub(keyId)
        override suspend fun verifyDeletionProof(proof: DeletionProof): DeletionVerification {
            verifyCalls++
            return verify(proof)
        }
    }

    private val proof = """
        {"deletion_id": "del-7", "user_identifier": "ada@example.org",
         "sources_deleted": {"sql": {"total_records_deleted": 15}},
         "deleted_at": "2026-09-01T10:00:00+00:00", "signature": "c2ln", "public_key_id": "agent-k1"}
    """.trimIndent()

    @Test
    fun anErasureWithNoReasonNeverReachesTheNode() = runTest {
        val be = FakeBackend()
        val c = DataErasureController(be, this)
        assertFalse(c.canErase("a4f1", " "))
        c.eraseAgentTraces("a4f1", " ")
        c.eraseAgentTraces("", "asked by the person")
        advanceUntilIdle()
        assertTrue(be.erased.isEmpty(), "the node requires both; the card must not send a request it will refuse")
        assertEquals(TraceErasureState.Idle, c.traceErasure.value)
    }

    @Test
    fun theNodesCountsAreWhatThePersonSees() = runTest {
        val be = FakeBackend()
        val c = DataErasureController(be, this)
        c.eraseAgentTraces("  a4f1  ", " asked by the person ")
        advanceUntilIdle()
        assertEquals(listOf("a4f1" to "asked by the person"), be.erased)
        val s = c.traceErasure.value
        assertIs<TraceErasureState.Answered>(s)
        assertEquals(ErasureOutcome.Erased, s.outcome)
        assertEquals(3L, s.result.traceEvents)
    }

    @Test
    fun aRefusalIsNeverAnAnswer() = runTest {
        val be = FakeBackend(erase = { _, _ ->
            throw NodeRefusal.fromBody(403, """{"error":"owner session required"}""")
        })
        val c = DataErasureController(be, this)
        c.eraseAgentTraces("a4f1", "asked")
        advanceUntilIdle()
        val s = c.traceErasure.value
        assertIs<TraceErasureState.Failed>(s)
        assertIs<ErasureFailure.Refused>(s.failure)
    }

    @Test
    fun aNodeWithoutTheRouteSaysSoRatherThanFailing() = runTest {
        val be = FakeBackend(erase = { _, _ -> throw NodeRefusal.fromBody(404, "") })
        val c = DataErasureController(be, this)
        c.eraseAgentTraces("a4f1", "asked")
        advanceUntilIdle()
        val s = c.traceErasure.value
        assertIs<TraceErasureState.Failed>(s)
        assertIs<ErasureFailure.NotOnThisHost>(s.failure)
    }

    @Test
    fun anAllZeroAnswerIsNothingThereNotDone() = runTest {
        val be = FakeBackend(erase = { h, _ -> TraceErasureResult(erased = true, agentIdHash = h) })
        val c = DataErasureController(be, this)
        c.eraseAgentTraces("a4f1", "asked")
        advanceUntilIdle()
        assertEquals(ErasureOutcome.NothingThere, (c.traceErasure.value as TraceErasureState.Answered).outcome)
    }

    @Test
    fun anUnreadableReceiptIsRefusedWithoutCallingTheAgent() = runTest {
        val be = FakeBackend()
        val c = DataErasureController(be, this)
        c.checkReceipt("""{"deletion_id": "del-7"}""")
        advanceUntilIdle()
        assertIs<ReceiptCheckState.Unreadable>(c.receiptCheck.value)
        assertEquals(0, be.verifyCalls)
    }

    @Test
    fun aReceiptSignedByAnOlderKeyIsFlaggedNotCalledForged() = runTest {
        val be = FakeBackend(verify = { p -> DeletionVerification(valid = false, deletionId = p.deletionId) })
        val c = DataErasureController(be, this)
        c.loadReceiptKey()
        advanceUntilIdle()
        val key = c.receiptKey.value
        assertIs<ReceiptKeyState.Loaded>(key)
        assertEquals("AAAA", key.publicKeyB64)
        c.checkReceipt(proof)
        advanceUntilIdle()
        val s = c.receiptCheck.value
        assertIs<ReceiptCheckState.Checked>(s)
        assertFalse(s.verdict.valid)
        assertTrue(s.keyNotCurrent, "proof names agent-k1; the agent checks only agent-k2")
    }

    @Test
    fun aKeyWhosePublicHalfWillNotDownloadIsStillAKey() = runTest {
        val be = FakeBackend(pub = { throw NodeRefusal.fromBody(404, """{"detail":"Public key agent-k2 not found"}""") })
        val c = DataErasureController(be, this)
        c.loadReceiptKey()
        advanceUntilIdle()
        val key = c.receiptKey.value
        assertIs<ReceiptKeyState.Loaded>(key)
        assertNull(key.publicKeyB64)
    }

    @Test
    fun anAgentWithoutVerificationSaysSo() = runTest {
        val be = FakeBackend(key = { throw NodeRefusal.fromBody(404, """{"detail":"Not Found"}""") })
        val c = DataErasureController(be, this)
        c.loadReceiptKey()
        advanceUntilIdle()
        val key = c.receiptKey.value
        assertIs<ReceiptKeyState.Failed>(key)
        assertIs<ErasureFailure.NotOnThisHost>(key.failure)
    }
}
