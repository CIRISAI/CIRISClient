package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * **The second-device calls, on the wire** (CSD-093 / CSD-094, CIRISServer 0.5.218).
 *
 * The real client against a real socket shaped as the 0.5.218 node answers
 * (`src/auth/bootstrap.rs` `claimed_session`, `src/claim_remote.rs`):
 *
 *  * the new device's wizard collects its session from ITS node, with the PIN
 *    in the body and no bearer (it has none — that is the point);
 *  * a 0.5.217 node, where the route does not exist, is a version fact;
 *  * the approving device names the target's address, which 0.5.218 needs
 *    for a code with no transport hint (`claim.no_route`).
 */
class ClaimedSessionWireTest {

    private class Seen(val method: String, val path: String, val body: String, val auth: String?)

    private class Node(routes: Map<String, Pair<Int, String>>) {
        val seen: MutableList<Seen> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.use { it.readBytes().decodeToString() }
                seen += Seen(ex.requestMethod, ex.requestURI.path, body, ex.requestHeaders.getFirst("Authorization"))
                // Unrouted is axum's bare 404: empty body, no id.
                val (status, out) = routes[ex.requestURI.path] ?: (404 to "")
                val bytes = out.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private var savedLocal = CIRISApiClient.LOCAL_NODE_URL

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        savedLocal = CIRISApiClient.LOCAL_NODE_URL
    }

    @AfterTest fun tearDown() {
        CIRISApiClient.setLocalNodeUrl(savedLocal)
        Dispatchers.resetMain()
    }

    // `SessionGrant`, flattened — the same shape `/v1/auth/login` returns.
    private val grant =
        """{"access_token":"tok-owner","token_type":"Bearer","expires_in":86400,"role":"SYSTEM_ADMIN","user_id":"wa-root-1"}"""

    private suspend fun SetupViewModel.settled(): ClaimedSessionState =
        withContext(Dispatchers.Default) {
            withTimeout(15_000) {
                claimedSession.first { it != ClaimedSessionState.Idle && it != ClaimedSessionState.Collecting }
            }
        }

    @Test
    fun theWizardCollectsItsSessionFromItsOwnNodeWithThePin() = runBlocking {
        val node = Node(mapOf("/v1/setup/claimed-session" to (200 to grant)))
        try {
            // The wizard's node is the LOCAL node; the default collector reads it.
            CIRISApiClient.setLocalNodeUrl(node.url)
            val api = CIRISApiClient(node.url, null)
            val vm = SetupViewModel(api)

            vm.collectClaimedSession(claimPinProvider = { "7F3K-Q9MZ" })

            assertEquals(ClaimedSessionState.Collected("SYSTEM_ADMIN"), vm.settled())
            val call = node.seen.single()
            assertEquals("POST", call.method)
            assertEquals("/v1/setup/claimed-session", call.path)
            val body = Json.parseToJsonElement(call.body) as JsonObject
            assertEquals("7F3K-Q9MZ", body["claim_pin"]?.jsonPrimitive?.content)
            assertEquals(setOf("claim_pin"), body.keys, "the PIN and nothing else")
            assertNull(call.auth, "a device with no session yet sends no bearer")
            assertEquals("tok-owner", api.getAccessToken())
        } finally {
            node.server.stop(0)
        }
    }

    @Test
    fun theNodesRefusalsArriveByIdOverTheWire() = runBlocking {
        val node = Node(
            mapOf(
                "/v1/setup/claimed-session" to (401 to """{"error":"invalid one-time claim PIN","reason_id":"auth.claim.pin_invalid"}"""),
            ),
        )
        try {
            CIRISApiClient.setLocalNodeUrl(node.url)
            val vm = SetupViewModel(CIRISApiClient(node.url, null))

            vm.collectClaimedSession(claimPinProvider = { "AAAA-BBBB" })

            assertEquals(
                ClaimedSessionState.Refused("auth.claim.pin_invalid", "invalid one-time claim PIN", 401),
                vm.settled(),
            )
        } finally {
            node.server.stop(0)
        }
    }

    @Test
    fun aReleased0_5_217NodeAnswersBare404AndTheOldEndingStands() = runBlocking {
        val node = Node(emptyMap())
        try {
            CIRISApiClient.setLocalNodeUrl(node.url)
            val api = CIRISApiClient(node.url, null)
            val vm = SetupViewModel(api)

            vm.collectClaimedSession(claimPinProvider = { "AAAA-BBBB" })

            assertEquals(ClaimedSessionState.NodeTooOld, vm.settled())
            assertNull(api.getAccessToken())
        } finally {
            node.server.stop(0)
        }
    }

    @Test
    fun theApprovingDeviceNamesTheTargetsAddress() = runBlocking {
        val node = Node(
            mapOf("/v1/setup/claim-remote" to (201 to """{"wa_id":"wa-root-2","role":"SYSTEM_ADMIN","session_pickup":"local"}""")),
        )
        try {
            val api = CIRISApiClient(node.url, "tok-approver")
            val resp = api.claimRemote(
                nodeCode = "CIRIS-V1-TEST",
                claimPin = "AAAA-BBBB",
                cohortScope = "self",
                localNodeUrl = node.url,
                targetUrl = "http://192.168.1.20:4243/",
            )

            assertEquals("SYSTEM_ADMIN", resp.role)
            // 0.5.218 keeps the new device's session on the new device.
            assertNull(resp.accessToken)
            val call = node.seen.single()
            assertEquals("/v1/setup/claim-remote", call.path)
            assertEquals("Bearer tok-approver", call.auth)
            val body = Json.parseToJsonElement(call.body) as JsonObject
            assertEquals("http://192.168.1.20:4243", body["target_url"]?.jsonPrimitive?.content)
            assertFalse("owner_password" in body, "no password is sent toward another device")
        } finally {
            node.server.stop(0)
        }
    }
}
