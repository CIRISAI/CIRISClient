package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.OwnedNodeDto
import ai.ciris.mobile.shared.models.federation.OwnedNodesDto
import ai.ciris.mobile.shared.models.safety.AgeAssurance
import ai.ciris.mobile.shared.models.safety.AgeBand
import ai.ciris.mobile.shared.models.safety.AssuranceLevel
import ai.ciris.mobile.shared.models.safety.SafetyStatusResponse
import ai.ciris.mobile.shared.models.safety.WatchlistClass
import ai.ciris.mobile.shared.models.safety.WatchlistEnable
import ai.ciris.mobile.shared.models.safety.WatchlistListResponse
import ai.ciris.mobile.shared.models.safety.WatchlistMode
import ai.ciris.mobile.shared.models.safety.WatchlistResponse
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OWNER = "eric-moore-v1"
private const val NODE_KEY = "ciris-client-node"
private const val GROUP = "wa-grp-4f19c2"

/** A local node that answers what it is told to and records whose posture was asked for. */
private class FakeSafety(
    var owner: String? = OWNER,
    var ownedNodesFails: Boolean = false,
    var status: (String) -> SafetyStatusResponse = { SafetyStatusResponse(it, null, null) },
    var enables: () -> WatchlistListResponse = { WatchlistListResponse(GROUP) },
    var write: () -> WatchlistResponse = { WatchlistResponse("att-1", true, "watchlist:x") },
) : SafetyApi {
    val statusAskedFor = mutableListOf<String>()
    val bandsRecorded = mutableListOf<AgeBand>()

    override suspend fun ownedNodes(): OwnedNodesDto {
        if (ownedNodesFails) throw RuntimeException("owned-nodes: 503")
        return OwnedNodesDto(owner = owner, nodes = listOf(OwnedNodeDto(NODE_KEY, isSelf = true)))
    }
    override suspend fun selfKeyId(): String = NODE_KEY
    override suspend fun safetyStatus(keyId: String): SafetyStatusResponse {
        statusAskedFor += keyId
        return status(keyId)
    }
    override suspend fun watchlist(groupKeyId: String): WatchlistListResponse = enables()
    override suspend fun setWatchlist(
        signerKeyId: String,
        groupKeyId: String,
        watchlistId: String,
        watchlistClass: WatchlistClass,
        enabled: Boolean,
        mode: WatchlistMode,
        routeToModerator: String?,
    ): WatchlistResponse = write()
    override suspend fun setAgeSelf(band: AgeBand): String {
        bandsRecorded += band
        return """{"attestation_id":"att-age-1"}"""
    }
}

/**
 * The Safety card (CSD-066): whose posture it shows, and the four states of a
 * group's watchlist. Driven through [SafetyApi] by a fake; no network.
 */
class SafetyCardTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm(fake: FakeSafety) = SafetyViewModel(CIRISApiClient(baseUrl = "http://127.0.0.1:9"), api = fake)

    // ── The posture is the PERSON's, not the node's ────────────────────────

    @Test
    fun thePostureIsTheOwnersNotTheNodes() {
        val fake = FakeSafety(status = { key ->
            // The node has a band on record for the OWNER and none for its own key.
            if (key == OWNER) SafetyStatusResponse(key, AgeAssurance(AgeBand.ADULT, AssuranceLevel.SELF_DECLARED))
            else SafetyStatusResponse(key, null)
        })
        val vm = vm(fake)
        vm.probeIdentityAndStatus()
        val s = vm.state.value
        assertEquals(listOf(OWNER), fake.statusAskedFor, "the posture read names the owner's fed-ID, not the node's signer")
        assertEquals(OWNER, s.subjectKeyId)
        assertTrue(s.subjectIsOwner)
        assertEquals(AgeBand.ADULT, s.ageAssurance?.band, "the band the person declared, not the node's unknown")
        // The node key stays the signer for moderation and watchlist actions.
        assertEquals(NODE_KEY, s.selfKeyId)
    }

    @Test
    fun anUnclaimedNodeFallsBackToItsOwnKeyAndSaysSo() {
        val fake = FakeSafety(owner = null)
        val vm = vm(fake)
        vm.probeIdentityAndStatus()
        assertEquals(NODE_KEY, vm.state.value.subjectKeyId)
        assertEquals(false, vm.state.value.subjectIsOwner)
    }

    @Test
    fun aFailedOwnerLookupIsNotAnUnclaimedNode() {
        // Codex, PR #126: owned-nodes failing is "we could not ask", never
        // "no owner" — asking about the node key drew the wrong posture and
        // hid the owner's restate controls.
        val fake = FakeSafety(ownedNodesFails = true)
        val vm = vm(fake)
        vm.probeIdentityAndStatus()
        val s = vm.state.value
        assertTrue(fake.statusAskedFor.isEmpty(), "no posture is read when whose posture it is could not be resolved")
        assertNull(s.subjectKeyId)
        assertIs<ReadFailure.Failed>(s.subjectFailure)
        assertEquals(NODE_KEY, s.selfKeyId, "the node key stays the signer")
    }

    @Test
    fun aFailedPostureReadIsNotNoBandOnRecord() {
        val fake = FakeSafety(status = { throw RuntimeException("status: 503") })
        val vm = vm(fake)
        vm.probeIdentityAndStatus()
        val s = vm.state.value
        assertNull(s.ageAssurance)
        assertIs<ReadFailure.Failed>(s.statusFailure, "a read that failed is a failure, never the protective default drawn as a fact")
    }

    // ── The watchlist has four states, and none of them is blank ───────────

    @Test
    fun aGroupNeverAskedAboutIsNotAsked() {
        assertEquals(WatchlistRead.NotAsked, vm(FakeSafety()).state.value.watchlistRead)
    }

    @Test
    fun anEmptyListIsTheTrueEmptyAndAFailedReadIsNot() {
        val fake = FakeSafety()
        val vm = vm(fake)
        vm.setWatchlistGroupKeyId(GROUP)
        vm.loadWatchlist()
        val loaded = assertIs<WatchlistRead.Loaded>(vm.state.value.watchlistRead)
        assertTrue(loaded.enables.isEmpty())

        fake.enables = { throw RuntimeException("watchlist fetch failed: 503 for $GROUP") }
        vm.loadWatchlist()
        assertIs<WatchlistRead.Failed>(vm.state.value.watchlistRead, "a failed read must not render as 'nothing is watched'")
        assertTrue(vm.state.value.watchlistEnables.isEmpty())
    }

    @Test
    fun aNodeWithoutTheRouteIsSaidInWords() {
        val fake = FakeSafety(enables = { throw RuntimeException("watchlist fetch failed: 404 Not Found for $GROUP") })
        val vm = vm(fake)
        vm.setWatchlistGroupKeyId(GROUP)
        vm.loadWatchlist()
        val failed = assertIs<WatchlistRead.Failed>(vm.state.value.watchlistRead)
        assertIs<ReadFailure.NotOnThisNode>(failed.failure)
    }

    @Test
    fun aListedEnableIsRendered() {
        val e = WatchlistEnable(GROUP, "iwf-2026q3", WatchlistClass.CSAM, true, WatchlistMode.ALERT_ONLY)
        val vm = vm(FakeSafety(enables = { WatchlistListResponse(GROUP, listOf(e)) }))
        vm.setWatchlistGroupKeyId(GROUP)
        vm.loadWatchlist()
        assertEquals(listOf(e), assertIs<WatchlistRead.Loaded>(vm.state.value.watchlistRead).enables)
    }

    // ── A write the node will not take says why ────────────────────────────

    @Test
    fun aFourOhOneIsTheSigningGapNotAWrongPassword() {
        val fake = FakeSafety(write = { throw NodeRefusal(null, "missing x-ciris-key-id", 401) })
        val vm = vm(fake)
        vm.probeIdentityAndStatus()
        vm.setWatchlistGroupKeyId(GROUP)
        vm.setWatchlistId("iwf-2026q3")
        vm.setWatchlistEnabled(true)
        assertEquals(WatchlistWriteRefusal.Unsigned, vm.state.value.watchlistWriteRefusal)
        assertNull(vm.state.value.error, "the refusal is typed, not a sentence in the shared error slot")
    }

    @Test
    fun aFourOhThreeIsNotAHolder() {
        val fake = FakeSafety(write = { throw NodeRefusal(null, "signer is neither the acting key nor an admitted occurrence of it", 403) })
        val vm = vm(fake)
        vm.probeIdentityAndStatus()
        vm.setWatchlistGroupKeyId(GROUP)
        vm.setWatchlistId("iwf-2026q3")
        vm.setWatchlistEnabled(false)
        assertEquals(WatchlistWriteRefusal.NotAHolder, vm.state.value.watchlistWriteRefusal)
    }

    @Test
    fun aTakenWriteReReadsTheGroupAndTheNodesListIsWhatShows() {
        var reads = 0
        val fake = FakeSafety(enables = { reads += 1; WatchlistListResponse(GROUP) })
        val vm = vm(fake)
        vm.probeIdentityAndStatus()
        vm.setWatchlistGroupKeyId(GROUP)
        vm.setWatchlistId("iwf-2026q3")
        vm.setWatchlistEnabled(true)
        assertEquals(1, reads, "what is on is what the node lists after the write, not what was asked for")
        assertNull(vm.state.value.watchlistWriteRefusal)
    }

    // ── Restating the band goes to the owner-session route and re-reads ────

    @Test
    fun restatingTheBandRecordsItAndReReadsThePosture() {
        var band: AgeBand? = null
        val fake = FakeSafety(status = { key -> SafetyStatusResponse(key, band?.let { AgeAssurance(it, AssuranceLevel.SELF_DECLARED) }) })
        val vm = vm(fake)
        vm.probeIdentityAndStatus()
        assertNull(vm.state.value.ageAssurance)
        band = AgeBand.MINOR
        vm.restateAgeBand(AgeBand.MINOR)
        assertEquals(listOf(AgeBand.MINOR), fake.bandsRecorded)
        assertEquals(AgeRestate.Recorded(AgeBand.MINOR), vm.state.value.ageRestate)
        assertEquals(listOf(OWNER, OWNER), fake.statusAskedFor, "the posture is re-read from the node after the write")
        assertEquals(AgeBand.MINOR, vm.state.value.ageAssurance?.band)
    }
}
