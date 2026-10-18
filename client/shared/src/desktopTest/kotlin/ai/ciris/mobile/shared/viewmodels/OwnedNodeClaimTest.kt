package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.OwnedNodeClaim
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertNotNull
import com.sun.net.httpserver.HttpServer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **No claim PIN on a node that already has an owner is not "go read a
 * console"** (CIRISClient#184 item 3).
 *
 * The node consumes its claim_pin file on the first claim, and an upgraded
 * node never armed one, so an absent file on an OWNED node is the expected
 * shape, not a fault. The view model asks the node which ownership state it is
 * in — the same two loopback reads ciris-server 0.5.231 serves for exactly
 * this (`GET /v1/setup/owned-nodes` → `owner`, `src/auth/bootstrap.rs:1619`;
 * `GET /v1/setup/status` → `setup_required`, `src/auth/bootstrap.rs:1653`) —
 * and names the outcome:
 *
 * - owner-binding present → `claim.node_already_claimed`, sign in as the owner;
 * - ROOT present, no binding → `claim.node_owner_upgrade`, sign in as the
 *   owner, then the Add Federation ID catch-up mints (`POST /v1/self/identity`)
 *   and re-roots (`POST /v1/self/upgrade-owner`);
 * - neither → the existing `claim.pin_file_absent`;
 * - `owned-nodes` answers a bare 404 → `claim.node_too_old`.
 *
 * The same reads name a MINT that the node refused: once a ROOT exists,
 * `POST /v1/self/identity` is owner-gated (`src/identity.rs:1815`), so the
 * wizard's setup session is turned away before any PIN is read, and "could
 * not create your federation identity" was the wrong name for an owned node.
 *
 * Each runs against a real socket.
 */
class OwnedNodeClaimTest {

    private var savedNodeUrl = CIRISApiClient.LOCAL_NODE_URL

    // A view model launches on viewModelScope from init and from its acts.
    // Left running past resetMain(), a call that comes back resumes with no
    // test Main and throws outside any test; kotlinx-coroutines-test then pins
    // it on the NEXT runTest (PR #203: PortalConnectTest failed with
    // UncaughtExceptionsBeforeTest). Every view model made here is cancelled first.
    private val viewModels = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        savedNodeUrl = CIRISApiClient.LOCAL_NODE_URL
    }

    @AfterTest fun tearDown() {
        CIRISApiClient.setLocalNodeUrl(savedNodeUrl)
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    /** A node that mints the fed-ID and answers the two ownership reads as given (null = bare 404). */
    private fun claimWithNoPinFile(
        ownedNodes: String?,
        setupStatus: String?,
        mintRefused: Boolean = false,
    ): Pair<NodeOwnershipClaimState, List<String>> = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        val node = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                ex.requestBody.use { it.readBytes() }
                asked += "${ex.requestMethod} ${ex.requestURI.path}"
                val (status, body) = when (ex.requestURI.path) {
                    "/v1/self/identity" -> if (mintRefused) {
                        401 to """{"error":"owner session required"}"""
                    } else {
                        200 to """{"key_id":"owner-abc123","fedcode":"CIRIS-V2-TEST","identity_type":"user"}"""
                    }
                    "/v1/setup/owned-nodes" -> if (ownedNodes != null) 200 to ownedNodes else 404 to ""
                    "/v1/setup/status" -> if (setupStatus != null) 200 to setupStatus else 404 to ""
                    else -> 404 to ""
                }
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${node.address.port}"
            CIRISApiClient.setLocalNodeUrl(url)
            val vm = SetupViewModel(CIRISApiClient(url, "setup-tok"), hasAgent = false).also { viewModels += it }
            vm.claimLocalNodeOwnership(claimPinProvider = { null })
            withContext(Dispatchers.Default) {
                withTimeout(15_000) { vm.state.first { !it.ownershipClaim.inProgress } }
            }
            vm.state.value.ownershipClaim to asked.toList()
        } finally {
            node.stop(0)
        }
    }

    private fun assertNoConsole(claim: NodeOwnershipClaimState) {
        assertFalse((claim.error ?: "").contains("console", ignoreCase = true), "no console carries the PIN: ${claim.error}")
    }

    @Test
    fun anAlreadyClaimedNodeIsNamedAndRoutesToOwnerSignIn() {
        val (claim, asked) = claimWithNoPinFile(
            ownedNodes = """{"owner":"owner-xyz789","nodes":[{"key_id":"node-1","is_self":true}]}""",
            setupStatus = """{"data":{"is_first_run":false,"setup_required":false,"config_exists":true}}""",
        )
        assertEquals("claim.node_already_claimed", claim.errorId, "asked: $asked")
        assertTrue(claim.ownerSignIn, "an owned node is entered by signing in as its owner")
        assertFalse(claim.claimed)
        assertFalse(claim.errorRecoverable, "retrying a claim on a claimed node repeats a request that cannot succeed")
        assertFalse(asked.any { it.endsWith("/v1/setup/claim-remote") }, "no claim is attempted without a PIN: $asked")
        assertNoConsole(claim)
    }

    @Test
    fun aLegacyOwnedNodeRoutesToOwnerSignInForTheUpgrade() {
        val (claim, asked) = claimWithNoPinFile(
            ownedNodes = """{"owner":null,"nodes":[]}""",
            setupStatus = """{"data":{"is_first_run":false,"setup_required":false,"config_exists":true}}""",
        )
        assertEquals("claim.node_owner_upgrade", claim.errorId, "asked: $asked")
        assertTrue(claim.ownerSignIn, "the upgrade is owner-gated: sign in first")
        assertFalse(claim.claimed)
        assertNoConsole(claim)
    }

    @Test
    fun anUnclaimedNodeKeepsThePinFileAbsentOutcome() {
        val (claim, asked) = claimWithNoPinFile(
            ownedNodes = """{"owner":null,"nodes":[]}""",
            setupStatus = """{"data":{"is_first_run":true,"setup_required":true,"config_exists":false}}""",
        )
        assertEquals("claim.pin_file_absent", claim.errorId, "asked: $asked")
        assertFalse(claim.ownerSignIn, "nobody owns this node yet; there is no owner to sign in as")
        assertTrue(claim.errorRecoverable)
        assertTrue(asked.contains("GET /v1/setup/owned-nodes"), "the node was asked, not guessed: $asked")
        assertNoConsole(claim)
    }

    @Test
    fun aNodeOlderThanTheOwnershipRouteIsNamedTooOld() {
        val (claim, asked) = claimWithNoPinFile(ownedNodes = null, setupStatus = null)
        assertEquals("claim.node_too_old", claim.errorId, "asked: $asked")
        assertFalse(claim.ownerSignIn)
        assertNoConsole(claim)
    }

    /**
     * The ids are looked up through [OwnedNodeClaim.messageKey], not a literal
     * `localizedString("…")`, so check_localization_sync cannot see them. This
     * does: every outcome's key resolves in en.json, and none says "console".
     */
    @Test
    fun everyOutcomeHasEnglishThatSendsNobodyToAConsole() {
        val en = kotlinx.serialization.json.Json.parseToJsonElement(
            java.io.File("src/desktopMain/resources/localization/en.json").readText(),
        ).jsonObject
        val ids = listOf(OwnedNodeClaim.ALREADY_CLAIMED, OwnedNodeClaim.OWNER_UPGRADE, OwnedNodeClaim.NODE_TOO_OLD)
        val keys = ids.map { assertNotNull(OwnedNodeClaim.messageKey(it), "no message key for $it") } +
            listOf("mobile.setup_claim_owned_title", "mobile.setup_owner_sign_in")
        for (key in keys) {
            val text = key.split('.').fold<String, kotlinx.serialization.json.JsonElement?>(en) { o, part ->
                (o as? kotlinx.serialization.json.JsonObject)?.get(part)
            }?.jsonPrimitive?.content
            assertNotNull(text, "$key does not resolve in en.json")
            assertFalse(text.contains("console", ignoreCase = true), "$key sends someone to a console: $text")
        }
    }

    @Test
    fun aMintRefusedByAnOwnedNodeIsNamedNotAFedIdFailure() {
        val (claim, asked) = claimWithNoPinFile(
            ownedNodes = """{"owner":"owner-xyz789","nodes":[{"key_id":"node-1","is_self":true}]}""",
            setupStatus = """{"data":{"is_first_run":false,"setup_required":false,"config_exists":true}}""",
            mintRefused = true,
        )
        assertEquals("claim.node_already_claimed", claim.errorId, "asked: $asked")
        assertTrue(claim.ownerSignIn, "an owned node is entered by signing in as its owner")
        assertNoConsole(claim)
    }
}
