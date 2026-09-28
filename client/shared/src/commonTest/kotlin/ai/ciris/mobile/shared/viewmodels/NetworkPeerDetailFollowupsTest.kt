package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.FederationPeerDetailResponse
import ai.ciris.mobile.shared.models.federation.FederationPeerSASResponse
import ai.ciris.mobile.shared.models.federation.FederationPeerSASUpdateResponse
import ai.ciris.mobile.shared.models.federation.LocalPeerState
import ai.ciris.mobile.shared.models.federation.PeerAppearance
import ai.ciris.mobile.shared.models.federation.PeerTrustState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSD-104 follow-ups (Codex review of #117), each pinned:
 *  1. a mismatch on a BLOCKED peer never weakens it to UNTRUSTED;
 *  2. a refused trust step is classified by remedy, not as a generic failure;
 *  3. a recorded mismatch's warning survives a later refused outcome and is
 *     cleared only by a superseding match;
 *  4. SAS and manual trust writes never overlap;
 *  5. a refresh clears a stale error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkPeerDetailFollowupsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun peer(trust: PeerTrustState) = LocalPeerState(
        keyId = "k", pubkeyEd25519Base64 = "AA==", canonical = false, trust = trust,
        firstSeen = Instant.fromEpochSeconds(0),
    )

    private class FakePeers(var trust: PeerTrustState = PeerTrustState.TRUSTED) : PeerSidebandApi {
        val writes = mutableListOf<PeerWrite>()
        var peerThrows: Exception? = null
        var sasWriteThrows: Exception? = null
        var trustThrows: Exception? = null
        /** When set, the trust write suspends until completed — to hold a write in flight. */
        var trustGate: CompletableDeferred<Unit>? = null
        override suspend fun getFederationPeer(keyId: String): FederationPeerDetailResponse {
            peerThrows?.let { throw it }
            return FederationPeerDetailResponse(peer = LocalPeerState(
                keyId = keyId, pubkeyEd25519Base64 = "AA==", canonical = false, trust = trust,
                firstSeen = Instant.fromEpochSeconds(0),
            ))
        }
        override suspend fun getFederationPeerSAS(keyId: String) =
            FederationPeerSASResponse(words = listOf("a", "b", "c", "d", "e"), digits = "123456", keyId = keyId)
        override suspend fun setFederationPeerSASVerified(keyId: String, verified: Boolean): FederationPeerSASUpdateResponse {
            sasWriteThrows?.let { throw it }
            writes += PeerWrite.Sas(verified)
            return FederationPeerSASUpdateResponse(keyId = keyId, verified = verified)
        }
        override suspend fun setFederationPeerTrust(keyId: String, trust: PeerTrustState): LocalPeerState {
            trustGate?.await()
            trustThrows?.let { throw it }
            writes += PeerWrite.Trust(trust)
            this.trust = trust
            return LocalPeerState(keyId = keyId, pubkeyEd25519Base64 = "AA==", canonical = false, trust = trust,
                firstSeen = Instant.fromEpochSeconds(0))
        }
        override suspend fun setFederationPeerAppearance(keyId: String, appearance: PeerAppearance) =
            setFederationPeerTrust(keyId, trust)
    }

    private fun vm(peers: FakePeers) = NetworkPeerDetailViewModel(CIRISApiClient("http://127.0.0.1:1", null), "k", peers)

    // ── 1. BLOCKED is stricter than UNTRUSTED ──────────────────────────────

    @Test
    fun aMismatchOnABlockedPeerDoesNotWeakenItToUntrusted() {
        assertEquals(listOf<PeerWrite>(PeerWrite.Sas(verified = false)), SasOutcome.MISMATCH.writes(PeerTrustState.BLOCKED))
        // Every other standing still gets the protective write first.
        for (t in listOf(PeerTrustState.TRUSTED, PeerTrustState.UNKNOWN, PeerTrustState.UNTRUSTED, null)) {
            assertEquals(PeerWrite.Trust(PeerTrustState.UNTRUSTED), SasOutcome.MISMATCH.writes(t).first(), "trust=$t")
        }
    }

    @Test
    fun aMismatchOnABlockedPeerLeavesItBlocked() = runTest(dispatcher) {
        val peers = FakePeers(trust = PeerTrustState.BLOCKED)
        val vm = vm(peers)
        vm.load(); advanceUntilIdle()
        vm.requestOutcome(SasOutcome.MISMATCH); vm.confirmOutcome(); advanceUntilIdle()
        assertEquals(listOf<PeerWrite>(PeerWrite.Sas(verified = false)), peers.writes)
        assertEquals(PeerTrustState.BLOCKED, vm.detail.value?.peer?.trust)
        assertTrue(vm.recordedMismatch.value)
    }

    // ── 2. The trust step's refusal keeps its remedy ───────────────────────

    @Test
    fun aRefusedTrustStepIsClassifiedByRemedy() = runTest(dispatcher) {
        val peers = FakePeers().apply { trustThrows = NodeRefusal(null, "requires the owner", 403) }
        val vm = vm(peers)
        vm.load(); advanceUntilIdle()
        vm.requestOutcome(SasOutcome.MISMATCH); vm.confirmOutcome(); advanceUntilIdle()
        val r = assertIs<SasOutcomeResult.Refused>(vm.lastOutcome.value)
        assertEquals(SasWriteRefusal.NOT_OWNER, r.reason)
        assertEquals(PeerWrite.Trust(PeerTrustState.UNTRUSTED), r.failedAt)
    }

    // ── 3. The mismatch warning outlives a later refusal ───────────────────

    @Test
    fun theMismatchWarningIsKeptSeparately() {
        assertTrue(mismatchWarningAfter(false, SasOutcomeResult.Recorded(SasOutcome.MISMATCH)))
        val refusedMatch = SasOutcomeResult.Refused(SasOutcome.MATCH, emptyList(), PeerWrite.Sas(true), SasWriteRefusal.NOT_OWNER, null)
        assertTrue(mismatchWarningAfter(true, refusedMatch), "a refused match landed nothing; the danger stands")
        assertTrue(mismatchWarningAfter(true, SasOutcomeResult.Recorded(SasOutcome.WITHDRAW)), "withdrawing a check says nothing about the key")
        assertFalse(mismatchWarningAfter(true, SasOutcomeResult.Recorded(SasOutcome.MATCH)), "a recorded match supersedes")
        assertFalse(mismatchWarningAfter(false, refusedMatch))
    }

    @Test
    fun aRefusedMatchAfterAMismatchKeepsTheWarningOnScreen() = runTest(dispatcher) {
        val peers = FakePeers()
        val vm = vm(peers)
        vm.load(); advanceUntilIdle()
        vm.requestOutcome(SasOutcome.MISMATCH); vm.confirmOutcome(); advanceUntilIdle()
        assertTrue(vm.recordedMismatch.value)
        peers.sasWriteThrows = NodeRefusal(null, "requires the owner", 403)
        vm.requestOutcome(SasOutcome.MATCH); vm.confirmOutcome(); advanceUntilIdle()
        assertIs<SasOutcomeResult.Refused>(vm.lastOutcome.value)
        assertTrue(vm.recordedMismatch.value, "nothing corrective landed, so the warning must stay")
        peers.sasWriteThrows = null
        vm.requestOutcome(SasOutcome.MATCH); vm.confirmOutcome(); advanceUntilIdle()
        assertFalse(vm.recordedMismatch.value, "a recorded match supersedes the mismatch")
    }

    // ── 4. One write at a time ─────────────────────────────────────────────

    @Test
    fun aManualTrustWriteCannotRaceAMismatch() = runTest(dispatcher) {
        val peers = FakePeers().apply { trustGate = CompletableDeferred() }
        val vm = vm(peers)
        vm.load(); advanceUntilIdle()
        vm.requestOutcome(SasOutcome.MISMATCH); vm.confirmOutcome(); advanceUntilIdle()
        assertTrue(vm.writeInFlight.value, "the mismatch's untrust is held in flight")
        vm.requestTrust(PeerTrustState.TRUSTED); advanceUntilIdle()
        peers.trustGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(PeerWrite.Trust(PeerTrustState.UNTRUSTED), PeerWrite.Sas(verified = false)), peers.writes)
        assertEquals(PeerTrustState.UNTRUSTED, vm.detail.value?.peer?.trust)
        assertFalse(vm.writeInFlight.value)
    }

    @Test
    fun anOutcomeCannotStartWhileAManualTrustWriteIsInFlight() = runTest(dispatcher) {
        val peers = FakePeers().apply { trustGate = CompletableDeferred() }
        val vm = vm(peers)
        vm.load(); advanceUntilIdle()
        vm.requestTrust(PeerTrustState.UNKNOWN); advanceUntilIdle()
        assertTrue(vm.writeInFlight.value)
        vm.requestOutcome(SasOutcome.MATCH)
        assertNull(vm.pendingOutcome.value, "no sheet opens over a write in flight")
        peers.trustGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf<PeerWrite>(PeerWrite.Trust(PeerTrustState.UNKNOWN)), peers.writes)
    }

    // ── 5. A refresh clears a stale error ──────────────────────────────────

    @Test
    fun aSuccessfulRefreshClearsTheStaleError() = runTest(dispatcher) {
        val peers = FakePeers()
        val vm = vm(peers)
        vm.load(); advanceUntilIdle()
        peers.peerThrows = RuntimeException("store: timeout")
        vm.refresh(); advanceUntilIdle()
        assertEquals("store: timeout", vm.error.value)
        peers.peerThrows = null
        vm.refresh(); advanceUntilIdle()
        assertNull(vm.error.value, "the retry succeeded; the old error is stale")
    }
}
