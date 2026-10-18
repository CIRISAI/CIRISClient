package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientDeviceRequests
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Add a device, on the wire** (CSD-093 / CSD-094, CIRISServer v0.5.231).
 *
 * The real client against a real socket shaped as `src/add_device.rs` answers:
 * the new device's three loopback calls with no bearer, the refusal body
 * `{error, reason_id}` (`src/auth/refusal.rs`), the bare 404 an older node
 * gives, the end-of-first-run order (complete → claimed-session → the agent's
 * setup-complete), and the approving device's owner routes WITH its bearer.
 */
class AddDeviceWireTest {

    private class Seen(val method: String, val path: String, val body: String, val auth: String?)

    /** A node whose answer for each "METHOD path" is a function, so a test can change it mid-run. */
    private class Node(val routes: MutableMap<String, () -> Pair<Int, String>>) {
        val seen: MutableList<Seen> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.use { it.readBytes().decodeToString() }
                seen += Seen(ex.requestMethod, ex.requestURI.rawPath, body, ex.requestHeaders.getFirst("Authorization"))
                // Unrouted is axum's bare 404: empty body, no id.
                val (status, out) = routes["${ex.requestMethod} ${ex.requestURI.rawPath}"]?.invoke() ?: (404 to "")
                val bytes = out.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        fun paths() = seen.map { "${it.method} ${it.path}" }
    }

    private var savedLocal = CIRISApiClient.LOCAL_NODE_URL

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        savedLocal = CIRISApiClient.LOCAL_NODE_URL
    }

    /** Every wizard a test made: its poll is stopped before Main is reset, or it leaks into the next test. */
    private val made = mutableListOf<SetupViewModel>()

    @AfterTest fun tearDown() {
        made.forEach { it.resetState() }
        CIRISApiClient.setLocalNodeUrl(savedLocal)
        Dispatchers.resetMain()
    }

    private val grant =
        """{"access_token":"tok-owner","token_type":"Bearer","expires_in":86400,"role":"SYSTEM_ADMIN","user_id":"wa-root-1"}"""

    private fun refusal(id: String, english: String) = """{"error":"$english","reason_id":"$id"}"""

    private suspend fun SetupViewModel.until(what: String, pred: (SetupFormState) -> Boolean): SetupFormState =
        withContext(Dispatchers.Default) {
            try {
                withTimeout(15_000) { state.first(pred) }
            } catch (e: Exception) {
                throw AssertionError("never reached: $what (${state.value.addDevice} ${state.value.ownershipClaim})", e)
            }
        }

    private fun wizard(node: Node, hasAgent: Boolean = false): Pair<CIRISApiClient, SetupViewModel> {
        CIRISApiClient.setLocalNodeUrl(node.url)
        val api = CIRISApiClient(node.url, null)
        return api to SetupViewModel(api, hasAgent = hasAgent, addDevicePollMs = 20).also { made += it }
    }

    @Test
    fun theRequestGoesToThisDevicesNodeWithTheContactAndLabelAndNoBearer() = runBlocking {
        val node = Node(
            mutableMapOf(
                "POST /v1/setup/add-device" to {
                    202 to """{"state":"requested","match_code":"482913","target_node":"ciris-node-a","occurrence_key_id":"device-abc"}"""
                },
                "GET /v1/setup/add-device" to { 200 to """{"state":"requested","match_code":"482913"}""" },
            ),
        )
        try {
            val (_, vm) = wizard(node)
            vm.chooseIdentityPath(IdentityPath.EXISTING)
            vm.setDeviceName("S23 Phone")
            vm.setAddDeviceContact(" CIRIS-V3-LONG ")
            vm.sendAddDeviceRequest()

            val s = vm.until("requested") { it.addDevice.phase == AddDevicePhase.REQUESTED }
            assertEquals("482913", s.addDevice.matchCode)
            val call = node.seen.first { it.method == "POST" }
            assertEquals("/v1/setup/add-device", call.path)
            val body = Json.parseToJsonElement(call.body) as JsonObject
            assertEquals("CIRIS-V3-LONG", body["contact"]?.jsonPrimitive?.content)
            assertEquals("S23 Phone", body["label"]?.jsonPrimitive?.content)
            assertNull(call.auth, "first run: no session to send")
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while (node.seen.count { it.method == "GET" } < 2) kotlinx.coroutines.delay(10) }
            }
            assertTrue(node.seen.filter { it.method == "GET" }.all { it.path == "/v1/setup/add-device" && it.auth == null })
        } finally {
            node.server.stop(0)
        }
    }

    @Test
    fun aRefusalArrivesByIdOverTheWire() = runBlocking {
        val node = Node(
            mutableMapOf(
                "POST /v1/setup/add-device" to {
                    409 to refusal("add_device.not_accepting", "your other device is not accepting new devices — open Add a device on it, then try again")
                },
            ),
        )
        try {
            val (_, vm) = wizard(node)
            vm.chooseIdentityPath(IdentityPath.EXISTING)
            vm.setAddDeviceContact("CIRIS-V3-LONG")
            vm.sendAddDeviceRequest()

            val s = vm.until("refused") { it.addDevice.phase == AddDevicePhase.REFUSED }
            assertEquals("add_device.not_accepting", s.addDevice.refusalReasonId)
            assertEquals(409, s.addDevice.refusalStatus)
        } finally {
            node.server.stop(0)
        }
    }

    @Test
    fun aNodeOlderThan0_5_231AnswersBare404AndAddThisDeviceIsOff() = runBlocking {
        val node = Node(mutableMapOf())
        try {
            val (_, vm) = wizard(node)
            vm.probeAddDevice()
            vm.until("too old") { it.addDevice.availability == AddDeviceAvailability.NODE_TOO_OLD }
            vm.chooseIdentityPath(IdentityPath.EXISTING)
            assertNull(vm.state.value.identityPath)
        } finally {
            node.server.stop(0)
        }
    }

    @Test
    fun withAnAgentTheOrderIsCompleteThenClaimedSessionThenTheAgentsSetupComplete() = runBlocking {
        val node = Node(
            mutableMapOf(
                "GET /v1/setup/add-device" to { 200 to """{"state":"approved","owner_key_id":"eric-moore-v1"}""" },
                "POST /v1/setup/add-device/complete" to { 200 to """{"state":"applied","owner_key_id":"eric-moore-v1"}""" },
                "POST /v1/setup/claimed-session" to { 200 to grant },
            ),
        )
        try {
            val (api, vm) = wizard(node, hasAgent = true)
            // A restart with an approval held: the wizard resumes this path.
            vm.probeAddDevice()
            vm.until("resumed") { it.addDevice.phase == AddDevicePhase.APPROVED && it.identityPath == IdentityPath.EXISTING }
            vm.setRunWithoutAi(true)
            vm.setUsername("qaadmin")
            vm.setUserPassword("hunter2hunter2")
            vm.setUserPasswordConfirm("hunter2hunter2")
            vm.setAnnounceOwnership(false)
            assertTrue(vm.nextStep())
            vm.setAccordMetricsConsent(false)
            var seenAtAgentComplete = -1

            vm.finishAgentSetup(
                claimPinProvider = { "7F3K-Q9MZ" },
                submit = {
                    seenAtAgentComplete = node.seen.size
                    SetupCompletionResult(success = true, message = "ok")
                },
            )

            vm.until("completed") { seenAtAgentComplete >= 0 && !it.isSubmitting }
            val order = node.paths().filter { it.startsWith("POST") }
            assertEquals(
                listOf("POST /v1/setup/add-device/complete", "POST /v1/setup/claimed-session"),
                order.take(2),
                "apply the held approval, THEN collect the session",
            )
            assertTrue(
                node.paths().take(seenAtAgentComplete).contains("POST /v1/setup/claimed-session"),
                "the agent's setup-complete (which reloads the runtime) runs only after the session is collected",
            )
            val pinCall = node.seen.first { it.path == "/v1/setup/claimed-session" }
            assertEquals("7F3K-Q9MZ", (Json.parseToJsonElement(pinCall.body) as JsonObject)["claim_pin"]?.jsonPrimitive?.content)
            assertEquals("tok-owner", api.getAccessToken())
            assertTrue(node.paths().none { it.endsWith("/v1/self/identity") || it.endsWith("/v1/setup/claim-remote") },
                "joining an identity neither mints nor self-claims")
        } finally {
            node.server.stop(0)
        }
    }

    @Test
    fun completeBeforeApprovalIs409NotApprovedAndTheWizardWaits() = runBlocking {
        val node = Node(
            mutableMapOf(
                "POST /v1/setup/add-device" to { 202 to """{"state":"requested","match_code":"482913"}""" },
                "GET /v1/setup/add-device" to { 200 to """{"state":"requested","match_code":"482913"}""" },
                "POST /v1/setup/add-device/complete" to {
                    409 to refusal("add_device.not_approved", "your other device has not approved this one yet")
                },
            ),
        )
        try {
            val (_, vm) = wizard(node)
            vm.chooseIdentityPath(IdentityPath.EXISTING)
            vm.setAddDeviceContact("CIRIS-V3-LONG")
            vm.sendAddDeviceRequest()
            vm.until("requested") { it.addDevice.phase == AddDevicePhase.REQUESTED }

            vm.claimLocalNodeOwnership(claimPinProvider = { "7F3K-Q9MZ" })

            val s = vm.until("waiting") { it.ownershipClaim.awaitingApproval }
            assertEquals(false, s.ownershipClaim.claimed)
            assertTrue(node.paths().none { it == "POST /v1/setup/claimed-session" })
        } finally {
            node.server.stop(0)
        }
    }

    @Test
    fun afterANodeSwitchTheApprovalGoesToTheNewNode() = runBlocking {
        // Codex on PR #189: a URL captured at construction sent approve to the
        // PREVIOUS node while the card showed the new one.
        fun owner() = Node(
            mutableMapOf(
                "GET /v1/self/device-requests" to {
                    200 to """{"requests":[{"request_id":"r1","label":"S23 Phone","device_class":"phone","match_code":"482913","node_key_id":"b","requested_secs_ago":5}],"window_secs_left":300}"""
                },
                "POST /v1/self/device-requests/r1/approve" to { 200 to """{"approved":true,"match_code":"482913"}""" },
            ),
        )
        val first = owner()
        val second = owner()
        try {
            val api = CIRISApiClient(first.url, null)
            api.setAccessToken("tok-owner")
            var active = first.url
            val vm = ApproveDeviceViewModel(ClientDeviceRequests(api) { active })
            vm.refreshNow()
            assertEquals(1, first.seen.size)

            active = second.url // the operator switched node profiles
            vm.refreshNow()
            vm.askApprove(vm.state.value.requests.single())
            vm.confirm()
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.outcome != null } } }

            assertTrue(second.paths().contains("POST /v1/self/device-requests/r1/approve"), "approved on the node the card shows")
            assertTrue(first.paths().none { it.endsWith("/approve") }, "nothing reaches the previous node")
        } finally {
            first.server.stop(0)
            second.server.stop(0)
        }
    }

    @Test
    fun theApprovingDevicesCallsCarryItsOwnerSession() = runBlocking {
        val node = Node(
            mutableMapOf(
                "POST /v1/self/device-requests/open" to { 200 to """{"open":true,"seconds":600}""" },
                "GET /v1/self/device-requests" to {
                    200 to """{"requests":[{"request_id":"9f1c-ab","label":"S23 Phone","device_class":"laptop","match_code":"482913","node_key_id":"ciris-node-b","requested_secs_ago":12}],"window_secs_left":null}"""
                },
                "POST /v1/self/device-requests/9f1c-ab/approve" to {
                    200 to """{"approved":true,"match_code":"482913","node_key_id":"ciris-node-b","occurrence_key_id":"device-abc","self_dek_granted":3}"""
                },
                "POST /v1/self/device-requests/9f1c-ab/deny" to {
                    404 to refusal("add_device.request_unknown", "no such pending request")
                },
            ),
        )
        try {
            val api = CIRISApiClient(node.url, null)
            api.setAccessToken("tok-owner")
            val vm = ApproveDeviceViewModel(ClientDeviceRequests(api, node.url))

            vm.open()
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.loaded && it.requests.isNotEmpty() } } }
            val req = vm.state.value.requests.single()
            assertEquals("482913", req.matchCode)
            assertNull(vm.state.value.windowSecsLeft, "closed window, and the request is still listed")

            vm.askApprove(req)
            vm.confirm()
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.outcome != null } } }

            vm.askDeny(req)
            vm.confirm()
            val s = withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.refusal != null } } }
            assertEquals("add_device.request_unknown", s.refusal?.reasonId)

            val owned = node.seen.filter { it.path.startsWith("/v1/self/device-requests") }
            assertTrue(owned.isNotEmpty())
            assertTrue(owned.all { it.auth == "Bearer tok-owner" }, "every owner route carries the owner session")
            assertEquals("{}", node.seen.first { it.path.endsWith("/approve") }.body)
        } finally {
            node.server.stop(0)
        }
    }
}
