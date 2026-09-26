package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientContactsApi
import ai.ciris.mobile.shared.api.ContactsApi
import ai.ciris.mobile.shared.models.federation.FederationPeerListResponse
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **People's 0.5.218 calls reach the node, over the wire** (CSD-092, CSD-005).
 *
 * The real client against two real sockets: an "agent" at the api base that
 * does not serve the contact surface (CIRISAgent#1213: it 404s it), and the
 * node. The view-model tests next door prove which URL the view model picks;
 * this proves the real client sends to it, with the `nodes` query on the
 * line, and that the served bytes decode into a code the card shows.
 */
class ContactCodeWireTest {

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private class Recorder(routes: Map<String, Pair<Int, String>>, fallback: Pair<Int, String>) {
        val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val uri = exchange.requestURI
                seen += "${exchange.requestMethod} ${uri.rawPath}${uri.rawQuery?.let { "?$it" } ?: ""}"
                val (status, body) = routes[uri.path] ?: fallback
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    /**
     * The real client for every call but the peer list, which reads the
     * process-wide LOCAL_NODE_URL: a test must not knock on whatever node this
     * machine happens to run.
     */
    private fun vmOver(client: CIRISApiClient, nodeUrl: String): ContactsViewModel {
        val real = ClientContactsApi(client)
        val api = object : ContactsApi by real {
            override suspend fun listPeers() = FederationPeerListResponse()
        }
        return ContactsViewModel(client, nodeUrl, api)
    }

    private suspend fun awaitThat(what: String, condition: () -> Boolean) {
        try {
            withTimeout(15_000) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            fail("timed out waiting for: $what")
        }
    }

    // Shaped as the 0.5.218 `contact_code` handler answers (CIRISServer src/self_devices.rs).
    private val codeBody = """{"key_id":"alice-abc","code":"CIRIS-V3-AB12-CD34","qr_payload":"CIRIS-V3-AB12CD34",
        |"format":"fedcode-v3","ml_dsa_65_pubkey_sha256":"00",
        |"available_nodes":[{"node_key_id":"node-a","announced":true,"has_transport":true,"this_node":true,"label":"Phone"},
        |{"node_key_id":"node-b","announced":true,"has_transport":false,"this_node":false}],
        |"included_nodes":[{"key_id":"node-a","transport_pubkey_ed25519_base64":"AAAA"}],
        |"nodes_without_transport":[],"reachable_without_directory":true}""".trimMargin()

    @Test
    fun theCodeAndTheAddGoToTheNodeWhileTheAgentSeesNeither() {
        val agent = Recorder(emptyMap(), 404 to """{"detail":"Not Found"}""")
        val node = Recorder(
            mapOf(
                "/v1/self/contact-code" to (200 to codeBody),
                "/v1/contacts" to (200 to """{"key_id":"bob-xyz","freshly_emitted":false,"chat_community_id":"pair"}"""),
            ),
            404 to "",
        )
        try {
            runBlocking {
                // The contact list keeps following the api base; only the 0.5.218 calls move.
                val vm = vmOver(CIRISApiClient(agent.url), node.url)
                vm.openContactCode()
                awaitThat("the contact code") { vm.contactCode.value is ContactCodeState.Ready }
                val ready = assertIs<ContactCodeState.Ready>(vm.contactCode.value)
                assertEquals("CIRIS-V3-AB12-CD34", ready.code.code)
                assertEquals("CIRIS-V3-AB12CD34", ready.code.qrValue, "the QR carries the ungrouped form")
                assertEquals(listOf("Phone", null), ready.code.availableNodes.map { it.label })

                vm.toggleContactCodeNode("node-b")
                awaitThat("the list code") { node.seen.any { "nodes=" in it } }

                vm.addContact("CIRIS-V3-BOB")
                awaitThat("the add") { vm.addOutcome.value != null }
                assertEquals(AddContactOutcome.ALREADY, vm.addOutcome.value)

                assertEquals(
                    listOf(
                        "GET /v1/self/contact-code",
                        "GET /v1/self/contact-code?nodes=node-a",
                        "POST /v1/contacts",
                    ),
                    node.seen.filter { "contact-code" in it || it.startsWith("POST") },
                )
                assertTrue(
                    agent.seen.none { "contact-code" in it || it.startsWith("POST") },
                    "the agent must see no contact-code or add call: ${agent.seen}",
                )
            }
        } finally {
            agent.server.stop(0)
            node.server.stop(0)
        }
    }

    @Test
    fun aNodeOlderThan0_5_218SaysSoOverTheWire() {
        val node = Recorder(emptyMap(), 404 to "")
        try {
            runBlocking {
                val vm = vmOver(CIRISApiClient(node.url), node.url)
                vm.openContactCode()
                awaitThat("a verdict") { vm.contactCode.value !is ContactCodeState.Loading }
                assertEquals(ContactCodeState.NodeTooOld, vm.contactCode.value)
            }
        } finally {
            node.server.stop(0)
        }
    }

    // ── The copy the refusals render (CSD-005, CSD-092) ─────────────────────

    private fun en(): JsonObject {
        val f = listOf(
            File("src/desktopMain/resources/localization/en.json"),
            File("shared/src/desktopMain/resources/localization/en.json"),
            File("client/shared/src/desktopMain/resources/localization/en.json"),
        ).firstOrNull { it.exists() } ?: error("en.json not found")
        return Json.parseToJsonElement(f.readText()) as JsonObject
    }

    private fun JsonObject.at(path: String): String =
        path.split('.').fold(this as Any) { node, part -> (node as JsonObject)[part]!! }
            .let { (it as kotlinx.serialization.json.JsonElement).jsonPrimitive.content }

    @Test
    fun unresolvableSaysItIsNotAPersonsCodeAndPointsAtTheContactCode() {
        val copy = en().at("contacts.unresolvable")
        assertTrue("isn't a person's code" in copy, copy)
        assertTrue("contact code" in copy, "it points at the contact code: $copy")
    }

    @Test
    fun theRefusalsTheCardRendersHaveEnglish() {
        val en = en()
        assertTrue("isn't reachable" in en.at("self.node_not_announced"))
        assertTrue("none of your devices is reachable" in en.at("mobile.contact_code_unreachable"))
        assertTrue("0.5.218" in en.at("mobile.contact_code_node_too_old"))
        assertEquals("Already in your contacts.", en.at("mobile.contacts_add_already"))
    }
}
