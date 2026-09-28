package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.FederationPeerSASResponse
import ai.ciris.mobile.shared.models.federation.FederationPeerSASUpdateResponse
import ai.ciris.mobile.shared.models.federation.PeerTrustState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSD-104 — the SAS ceremony on the peer detail, pinned as rules.
 *
 * The screen used to show the code and a Close button, and nothing was ever
 * recorded. These tests pin the four things that make it a ceremony: what each
 * answer writes, that a mismatch is not a withdrawal (or a cancel), that a
 * refused write is reported rather than shown as done, and that "this key is
 * not in the directory" is not "this node has no such route".
 */
class NetworkPeerDetailSasTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ─── What each answer writes ────────────────────────────────────────────

    @Test
    fun aMatchRecordsVerifiedAndTouchesNothingElse() {
        assertEquals(listOf<PeerWrite>(PeerWrite.Sas(verified = true)), SasOutcome.MATCH.writes())
    }

    @Test
    fun aMismatchStopsTrustingTheKeyFirstThenClearsTheCheck() {
        assertEquals(
            listOf(PeerWrite.Trust(PeerTrustState.UNTRUSTED), PeerWrite.Sas(verified = false)),
            SasOutcome.MISMATCH.writes(),
        )
    }

    @Test
    fun aMismatchIsNotAWithdrawal() {
        assertNotEquals(SasOutcome.WITHDRAW.writes(), SasOutcome.MISMATCH.writes())
        assertEquals(listOf<PeerWrite>(PeerWrite.Sas(verified = false)), SasOutcome.WITHDRAW.writes())
        assertFalse(SasOutcome.WITHDRAW.writes().any { it is PeerWrite.Trust }, "withdrawing a check must not untrust")
    }

    // ─── Applying them ──────────────────────────────────────────────────────

    @Test
    fun allWritesLandedIsRecorded() = runTest {
        val seen = mutableListOf<PeerWrite>()
        val r = recordSasOutcome(SasOutcome.MISMATCH) { seen += it }
        assertEquals(SasOutcomeResult.Recorded(SasOutcome.MISMATCH), r)
        assertEquals(SasOutcome.MISMATCH.writes(), seen)
    }

    @Test
    fun aRefusedWriteIsReportedNeverShownAsRecorded() = runTest {
        val r = recordSasOutcome(SasOutcome.MATCH) {
            throw NodeRefusal(null, "federation peer sideband writes require the owner (SYSTEM_ADMIN) role", 403)
        }
        val refused = assertIs<SasOutcomeResult.Refused>(r)
        assertEquals(SasWriteRefusal.NOT_OWNER, refused.reason)
        assertTrue(refused.applied.isEmpty())
    }

    @Test
    fun aMismatchWhoseRecordFailsStillSaysTheUntrustLanded() = runTest {
        val r = recordSasOutcome(SasOutcome.MISMATCH) { w ->
            if (w is PeerWrite.Sas) throw RuntimeException("store: timeout")
        }
        val refused = assertIs<SasOutcomeResult.Refused>(r)
        assertEquals(listOf<PeerWrite>(PeerWrite.Trust(PeerTrustState.UNTRUSTED)), refused.applied)
        assertEquals(PeerWrite.Sas(verified = false), refused.failedAt)
        assertEquals(SasWriteRefusal.FAILED, refused.reason)
    }

    @Test
    fun theFirstRefusalStopsTheRun() = runTest {
        val seen = mutableListOf<PeerWrite>()
        recordSasOutcome(SasOutcome.MISMATCH) { w ->
            seen += w
            throw NodeRefusal(null, "PEER_NOT_FOUND", 404)
        }
        assertEquals(1, seen.size, "a refused untrust must not be followed by clearing the check")
    }

    // ─── Reading the code: three different absences ────────────────────────

    @Test
    fun aKeyNotInTheDirectoryIsNotAMissingRoute() {
        assertEquals(SasRead.NotInDirectory, sasReadFailureOf(NodeRefusal.fromBody(404,
            """{"error":"PEER_SAS_UNAVAILABLE","key_id":"k","detail":"peer key_id not found in federation directory: \"k\""}""")))
        assertIs<SasRead.RouteAbsent>(sasReadFailureOf(NodeRefusal.fromBody(404, "")))
        assertIs<SasRead.Failed>(sasReadFailureOf(NodeRefusal.fromBody(503, """{"error":"store: down"}""")))
        assertIs<SasRead.Failed>(sasReadFailureOf(RuntimeException("connection refused")))
    }

    @Test
    fun writeRefusalsAreClassifiedByRemedy() {
        assertEquals(SasWriteRefusal.NOT_OWNER, sasWriteRefusalOf(NodeRefusal(null, "missing bearer session token", 401)))
        assertEquals(SasWriteRefusal.NOT_OWNER, sasWriteRefusalOf(NodeRefusal(null, "this node has no responsible party", 403)))
        assertEquals(SasWriteRefusal.NOT_IN_DIRECTORY, sasWriteRefusalOf(NodeRefusal.fromBody(404,
            """{"error":"PEER_NOT_FOUND","key_id":"k","detail":"peer \"k\" is not in the federation directory"}""")))
        assertEquals(SasWriteRefusal.ROUTE_ABSENT, sasWriteRefusalOf(NodeRefusal.fromBody(404, "")))
    }

    @Test
    fun aFailedPeerReadIsNotNotFound() {
        assertEquals(PeerDetailFailure.NotFound, peerDetailFailureOf(RuntimeException("Federation peer fetch failed: 404 Not Found for k")))
        assertIs<PeerDetailFailure.Failed>(peerDetailFailureOf(RuntimeException("Federation peer fetch failed: 503 Service Unavailable for k")))
    }

    // ─── The wire ───────────────────────────────────────────────────────────

    /** The node's real `GET …/sas` data object (`src/federation_peers.rs:925-933`). */
    @Test
    fun theRecordedOutcomeIsReadOffTheWire() {
        val sas = json.decodeFromString(FederationPeerSASResponse.serializer(),
            """{"key_id":"k","words":["abandon","ribbon","gravity","orbit","velvet"],"digits":"482915","verified":true,"verified_at":"2026-09-25T18:04:11Z"}""")
        assertEquals(true, sas.verified)
        assertEquals("2026-09-25T18:04:11Z", sas.verifiedAt)
        assertTrue(SasRead.Ready(sas).verified)
    }

    @Test
    fun neverRecordedIsNotVerified() {
        val sas = json.decodeFromString(FederationPeerSASResponse.serializer(),
            """{"key_id":"k","words":["a","b","c","d","e"],"digits":"000001","verified":null,"verified_at":null}""")
        assertNull(sas.verified)
        assertFalse(SasRead.Ready(sas).verified)
    }

    @Test
    fun theWriteResponseDecodes() {
        val r = json.decodeFromString(FederationPeerSASUpdateResponse.serializer(),
            """{"key_id":"k","verified":false,"verified_at":null}""")
        assertEquals(false, r.verified)
        assertNull(r.verifiedAt)
    }

    @Test
    fun digitsAreReadAloudInTwoHalves() {
        assertEquals("482 915", sasDigitsGrouped("482915"))
        assertEquals("12345", sasDigitsGrouped("12345"))
    }
}
