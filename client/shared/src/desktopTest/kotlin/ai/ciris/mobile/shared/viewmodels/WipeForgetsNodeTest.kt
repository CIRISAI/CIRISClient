package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ResetAccountResult
import ai.ciris.mobile.shared.api.WipeSigningKeyResult
import ai.ciris.mobile.shared.auth.WipedNode
import ai.ciris.mobile.shared.auth.forgetWipedNode
import ai.ciris.mobile.shared.auth.profilesAfterWipe
import ai.ciris.mobile.shared.models.NodeProfile
import ai.ciris.mobile.shared.platform.EnvFileUpdater
import ai.ciris.mobile.shared.platform.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CIRISClient#87: a wipe destroyed the node's home, but the client kept the
 * session token and the switcher's profile for the node it had just wiped, and
 * merged both back. After a successful wipe the client forgets that node — by
 * IDENTITY, not by URL alone — and nothing else.
 */
class WipeForgetsNodeTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private var savedLocalUrl = CIRISApiClient.LOCAL_NODE_URL
    private var savedExplicit = CIRISApiClient.localNodeUrlIsExplicit

    @BeforeTest fun setup() {
        Dispatchers.setMain(dispatcher)
        savedLocalUrl = CIRISApiClient.LOCAL_NODE_URL
        savedExplicit = CIRISApiClient.localNodeUrlIsExplicit
        // Nothing listens on :9 — the switcher's owned-nodes read fails fast and
        // never reaches a real node on this machine.
        CIRISApiClient.setLocalNodeUrl(DEAD_LOCAL)
    }

    @AfterTest fun tearDown() {
        CIRISApiClient.setLocalNodeUrl(savedLocalUrl, explicit = savedExplicit)
        Dispatchers.resetMain()
    }

    // ── the rule ────────────────────────────────────────────────────────────

    @Test
    fun aDestroyedIdentityDropsEveryProfilePinnedToItAndNoOther() {
        val local = NodeProfile(id = "local", name = "This device", baseUrl = WIPED_URL, pinnedKeyId = WIPED_KEY, isLocal = true, sessionToken = "t-local")
        val ownedRemote = NodeProfile(id = WIPED_KEY, name = WIPED_KEY, baseUrl = "", pinnedKeyId = WIPED_KEY, isOwned = true)
        val other = NodeProfile(id = "other", name = "Work", baseUrl = "http://10.0.0.2:8080", pinnedKeyId = "k-other", sessionToken = "t-other")
        val after = profilesAfterWipe(listOf(local, ownedRemote, other), WipedNode(WIPED_URL, WIPED_KEY, identityDestroyed = true))
        assertEquals(listOf(other), after, "only the node that was wiped is forgotten; the other keeps its token")
    }

    @Test
    fun aDifferentNodeAtTheSameUrlIsNotTheWipedNode() {
        val sameUrlOtherKey = NodeProfile(id = "x", name = "Next home", baseUrl = "$WIPED_URL/", pinnedKeyId = "k-next", sessionToken = "t-next")
        val after = profilesAfterWipe(listOf(sameUrlOtherKey), WipedNode(WIPED_URL, WIPED_KEY, identityDestroyed = true))
        assertEquals(listOf(sameUrlOtherKey), after, "a URL is where a node answers, not which node it is")
    }

    @Test
    fun anUnpinnedProfileAtTheWipedEndpointKeepsItsRowButLosesItsToken() {
        val unpinned = NodeProfile(id = "u", name = "Typed in", baseUrl = WIPED_URL.uppercase() + "/", sessionToken = "t-dead")
        val after = profilesAfterWipe(listOf(unpinned), WipedNode(WIPED_URL, WIPED_KEY, identityDestroyed = true))
        assertEquals(listOf(unpinned.copy(sessionToken = null)), after, "identity unproven: keep the row; the auth DB that minted the token is gone")
    }

    @Test
    fun aResetKeepsTheNodeButNotItsSessions() {
        val pinned = NodeProfile(id = "p", name = "Home", baseUrl = WIPED_URL, pinnedKeyId = WIPED_KEY, sessionToken = "t-dead")
        val after = profilesAfterWipe(listOf(pinned), WipedNode(WIPED_URL, WIPED_KEY, identityDestroyed = false))
        assertEquals(listOf(pinned.copy(sessionToken = null)), after, "reset-account keeps the key: same node, no live session")
    }

    @Test
    fun anUnknownIdentityMatchesNothingByKey() {
        val pinned = NodeProfile(id = "p", name = "Home", baseUrl = "http://10.0.0.3:8080", pinnedKeyId = WIPED_KEY, sessionToken = "t")
        val after = profilesAfterWipe(listOf(pinned), WipedNode(WIPED_URL, keyId = null, identityDestroyed = true))
        assertEquals(listOf(pinned), after)
    }

    // ── the one shared path ─────────────────────────────────────────────────

    @Test
    fun everyStepRunsEvenWhenAnEarlierOneFails() = runBlocking {
        val ran = mutableListOf<String>()
        val failed = forgetWipedNode(
            WipedNode(WIPED_URL, WIPED_KEY, identityDestroyed = true),
            deleteStoredToken = { ran += "stored"; Result.failure(IllegalStateException("keychain locked")) },
            clearLiveToken = { ran += "live" },
            forgetProfiles = { ran += "profiles"; throw IllegalStateException("boom") },
        )
        assertEquals(listOf("stored", "live", "profiles"), ran)
        assertEquals(listOf("stored_token", "profiles"), failed)
    }

    // ── Data Management, as every platform runs it ──────────────────────────

    @Test
    fun aSuccessfulWipeClearsTheTokenAndForgetsTheNodeByTheKeyReadBeforehand() {
        val order = mutableListOf<String>()
        val api = CIRISApiClient(baseUrl = WIPED_URL).apply { setAccessToken("t-dead") }
        var forgotten: WipedNode? = null
        var storedDeleted = false
        val vm = DataManagementViewModel(
            api, SecureStorage(), EnvFileUpdater(),
            forgetNodeProfiles = { forgotten = it },
            readNodeKeyId = { order += "identity"; WIPED_KEY },
            deleteStoredToken = { storedDeleted = true; Result.success(Unit) },
            callWipeSigningKey = { order += "wipe"; WipeSigningKeyResult(true, "wiped", walletAccessDestroyed = true) },
        )
        vm.wipeSigningKey()
        assertEquals(listOf("identity", "wipe"), order, "the key_id is read BEFORE the wipe destroys it")
        assertTrue(storedDeleted, "the persisted session token must not survive the wipe")
        assertNull(api.getAccessToken(), "nor the in-memory one")
        assertEquals(WipedNode(WIPED_URL, WIPED_KEY, identityDestroyed = true), forgotten)
        assertTrue(vm.wipeSigningKeySuccess.value)
    }

    @Test
    fun aRefusedWipeForgetsNothing() {
        val api = CIRISApiClient(baseUrl = WIPED_URL).apply { setAccessToken("t-live") }
        var forgotten: WipedNode? = null
        var storedDeleted = false
        val vm = DataManagementViewModel(
            api, SecureStorage(), EnvFileUpdater(),
            forgetNodeProfiles = { forgotten = it },
            readNodeKeyId = { WIPED_KEY },
            deleteStoredToken = { storedDeleted = true; Result.success(Unit) },
            callWipeSigningKey = { WipeSigningKeyResult(false, "API error: HTTP 403", walletAccessDestroyed = false) },
        )
        vm.wipeSigningKey()
        assertNull(forgotten)
        assertEquals(false, storedDeleted)
        assertEquals("t-live", api.getAccessToken())
    }

    @Test
    fun aResetForgetsTheSessionsButKeepsTheIdentity() {
        val api = CIRISApiClient(baseUrl = WIPED_URL).apply { setAccessToken("t-dead") }
        var forgotten: WipedNode? = null
        val vm = DataManagementViewModel(
            api, SecureStorage(), EnvFileUpdater(),
            forgetNodeProfiles = { forgotten = it },
            readNodeKeyId = { WIPED_KEY },
            deleteStoredToken = { Result.success(Unit) },
            callResetAccount = { ResetAccountResult(true, "reset", signingKeyPreserved = true) },
        )
        vm.factoryReset()
        assertEquals(WipedNode(WIPED_URL, WIPED_KEY, identityDestroyed = false), forgotten)
        assertNull(api.getAccessToken())
    }

    // ── the switcher ────────────────────────────────────────────────────────

    @Test
    fun theSwitcherForgetsTheWipedNodeAndAReloadDoesNotMergeItBack() = runBlocking {
        val vm = NodeSwitcherViewModel(CIRISApiClient(baseUrl = DEAD_LOCAL))
        vm.importNodeListJson(
            """{"nodes":[
                {"keyId":"$WIPED_KEY","name":"Node A","baseUrl":"$WIPED_URL"},
                {"keyId":"k-other","name":"Node B","baseUrl":"http://10.0.0.2:8080"}
            ]}""",
        )
        vm.saveProfile("Typed", "http://10.0.0.4:8080", sessionToken = "t-typed")
        assertNotNull(vm.profiles.value.firstOrNull { it.pinnedKeyId == WIPED_KEY }, "precondition: Node A is listed")

        vm.forgetWipedNode(WipedNode(WIPED_URL, WIPED_KEY, identityDestroyed = true))
        vm.reload()

        val names = vm.profiles.value.map { it.name }
        assertTrue("Node A" !in names, "the wiped node came back: $names")
        assertTrue("Node B" in names && "Typed" in names, "other nodes stay: $names")
        assertEquals("t-typed", vm.profiles.value.first { it.name == "Typed" }.sessionToken)
        assertTrue(vm.profiles.value.any { it.isLocal }, "the local node is always listed")
    }

    private companion object {
        const val WIPED_URL = "http://10.0.0.1:8080"
        const val WIPED_KEY = "ciris-canonical-1-wiped"
        const val DEAD_LOCAL = "http://127.0.0.1:9"
    }
}
