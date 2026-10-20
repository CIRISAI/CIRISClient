package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientDeviceRequests
import ai.ciris.mobile.shared.api.NodeRefusal
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.ViewModel
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * **What 0.5.232 adds to add-device and eviction, on the wire** (CIRISServer#798,
 * `98cee69e`):
 *
 *  - `src/add_device.rs:1363-1375` — the new device's request waits ≤30 s for the
 *    other device's route, then answers **503 `add_device.owner_route_pending`
 *    with `Retry-After: 10`**: retryable, never a refusal;
 *  - `:1327-1328` — approving a THIRD device from a roster of two is a held
 *    self-roster proposal: **409 `add_device.roster_change_held`**;
 *  - `src/self_devices.rs:880-893` — an eviction the roster's door held answers
 *    **200 `{revoked: false, held: true, occurrences_held: [{occurrence_key_id, door}]}`**.
 */
class RosterHeldWireTest {

    private class Node(val routes: Map<String, () -> Triple<Int, String, Map<String, String>>>) {
        val seen: MutableList<String> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                ex.requestBody.readBytes()
                val key = "${ex.requestMethod} ${ex.requestURI.rawPath}"
                seen += key
                val (status, out, headers) = routes[key]?.invoke() ?: Triple(404, "", emptyMap())
                headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                val bytes = out.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private val made = mutableListOf<ViewModel>()
    private val nodes = mutableListOf<Node>()
    private fun node(routes: Map<String, () -> Triple<Int, String, Map<String, String>>>) = Node(routes).also { nodes += it }
    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() {
        stopViewModels(made)
        Dispatchers.resetMain()
        nodes.forEach { it.server.stop(0) }
    }

    private val pendingText = "your other device's address has not reached this one yet — try again in a moment. " +
        "If your other device is not announced, it cannot be reached this way: use the pairing QR or the same local network"

    @Test
    fun ownerRoutePendingCarriesItsRetryAfter() = runBlocking<Unit> {
        val n = node(mapOf("POST /v1/setup/add-device" to {
            Triple(503, """{"error":"$pendingText","reason_id":"add_device.owner_route_pending"}""", mapOf("Retry-After" to "10"))
        }))
        val e = assertFailsWith<NodeRefusal> { CIRISApiClient(n.url, null).addDeviceStart("CIRIS-V3-LONG", "S23 Phone", n.url) }
        assertEquals(503, e.statusCode)
        assertEquals("add_device.owner_route_pending", e.reasonId)
        assertEquals(10, e.retryAfterSeconds)
    }

    @Test
    fun aRefusalWithoutRetryAfterSaysNothingAboutWhen() = runBlocking<Unit> {
        val n = node(mapOf("POST /v1/setup/add-device" to {
            Triple(409, """{"error":"not accepting","reason_id":"add_device.not_accepting"}""", emptyMap())
        }))
        val e = assertFailsWith<NodeRefusal> { CIRISApiClient(n.url, null).addDeviceStart("CIRIS-V3-LONG", null, n.url) }
        assertNull(e.retryAfterSeconds)
    }

    @Test
    fun aThirdDeviceIsHeldAsAProposalNotRefused() = runBlocking<Unit> {
        val n = node(mapOf(
            "GET /v1/self/device-requests" to {
                Triple(200, """{"requests":[{"request_id":"r3","label":"Tablet","device_class":"phone","match_code":"111222","node_key_id":"ciris-node-c","requested_secs_ago":5}],"window_secs_left":300}""", emptyMap())
            },
            "POST /v1/self/device-requests/r3/approve" to {
                Triple(409, """{"error":"roster change held: a third key from a roster of two is a proposal","reason_id":"add_device.roster_change_held"}""", emptyMap())
            },
        ))
        val api = CIRISApiClient(n.url, null).also { it.setAccessToken("tok-owner") }
        val vm = ApproveDeviceViewModel(ClientDeviceRequests(api) { n.url }).also { made += it }
        vm.refreshNow()
        vm.askApprove(vm.state.value.requests.single())
        vm.confirm()
        val s = withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first { it.outcome != null && it.working == null } } }
        assertEquals(DeviceRequestOutcome.Held("Tablet"), s.outcome)
        assertNull(s.refusal, "held is pending, not a failure")
    }

    @Test
    fun aHeldEvictionDecodesAsHeldNotRevoked() = runBlocking<Unit> {
        val n = node(mapOf("POST /v1/self/occurrence/revoke" to {
            Triple(200, """{"nodes":[],"withdrawn":[],"occurrences_revoked":[],"occurrences_already_revoked":[],""" +
                """"occurrences_held":[{"occurrence_key_id":"laptop-key","door":"self_roster:proposal"}],"failed":[],""" +
                """"replication_kicked":true,"nodes_owned_by":[],"history":"Already-shared history stays readable.",""" +
                """"identity_key_id":"owner","occurrence_key_id":"laptop-key","revoked":false,"held":true,"revoked_by":"owner","released_self":false}""", emptyMap())
        }))
        val r = CIRISApiClient(n.url, null).revokeOccurrence("owner", "laptop-key", nodeUrl = n.url, token = "tok")
        assertEquals(false, r.revoked)
        assertEquals(true, r.held)
        assertEquals(listOf("laptop-key"), r.occurrencesHeld.map { it.occurrenceKeyId })
        assertEquals("self_roster:proposal", r.occurrencesHeld.single().door)
    }

    @Test
    fun aHeldReleaseDecodesAsHeld() = runBlocking<Unit> {
        val n = node(mapOf("POST /v1/self/nodes/node-x/release" to {
            Triple(200, """{"node_key_id":"node-x","released":false,"held":true,"released_self":false,"nodes_owned_by":["node-x"],""" +
                """"occurrences_held":[{"occurrence_key_id":"node-x","door":"self_roster:proposal"}],"failed":[]}""", emptyMap())
        }))
        val r = CIRISApiClient(n.url, null).releaseNode("node-x", forceSelf = false, nodeUrl = n.url, token = "tok")
        assertEquals(false, r.released)
        assertEquals(true, r.held)
        assertIs<List<*>>(r.occurrencesHeld)
    }
}
