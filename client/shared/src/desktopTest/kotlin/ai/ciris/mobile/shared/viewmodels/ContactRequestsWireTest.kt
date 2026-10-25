package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientContactRequestsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Contact requests over a real socket** (CSD-109; CIRISServer 0.5.237, #819 and
 * #820 @ b784f8f9). The bodies are the server's §8 / §8.1 shapes. What is
 * checked is how they travel: every path and verb at the NODE URL, the accept
 * sent as the entry's own `POST /v1/contacts {key_id}`, the label read as the
 * requester's own, and each refusal id with its status.
 */
class ContactRequestsWireTest {

    private class Served(val method: String, val path: String, val body: String)

    private val servers = mutableListOf<HttpServer>()
    @AfterTest fun tearDown() { servers.forEach { it.stop(0) } }

    private fun serving(routes: Map<String, Pair<Int, String>>): Pair<String, MutableList<Served>> {
        val log: MutableList<Served> = CopyOnWriteArrayList()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                log += Served(ex.requestMethod, ex.requestURI.rawPath, body)
                val (status, out) = routes["${ex.requestMethod} ${ex.requestURI.rawPath}"] ?: (404 to "")
                val bytes = out.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        servers += server
        return "http://127.0.0.1:${server.address.port}" to log
    }

    private fun entry(id: String, key: String, label: String?) = """{"kind":"contact","requester_key_id":"$key",
        "requester_label":${label?.let { "\"$it\"" } ?: "null"},"grant_attestation_id":"$id",
        "granted_by_key_id":"$key","granted_to_key_id":"k-me","requested_at":"2026-10-25T10:00:00+00:00",
        "grant":{"valid_until":null},
        "accept":{"method":"POST","path":"/v1/contacts","body":{"key_id":"$key"}},
        "ignore":{"method":"POST","path":"/v1/contacts/requests/$id/ignore"}}"""

    private val list = """{"requests":[${entry("g1", "ada-9f3a", "ada")}],"total":1,
        "ignored":[${entry("g2", "bob-77aa", null).dropLast(1)},"ignore_id":"ig-2"}]}"""

    /** The agent's base URL: nothing may go here. */
    private val agent = "http://127.0.0.1:9"

    @Test
    fun the_list_is_read_at_the_node_with_labels_and_ignored() = runBlocking {
        val (node, log) = serving(mapOf("GET /v1/contacts/requests" to (200 to list)))
        val got = ClientContactRequestsApi(CIRISApiClient(agent, "tok")).list(node)
        assertEquals(listOf("GET /v1/contacts/requests"), log.map { "${it.method} ${it.path}" })
        assertEquals("ada", got.requests.single().requesterLabel)
        assertEquals("ada-9f3a", got.requests.single().requesterKeyId)
        assertEquals("ig-2", got.ignored.single().ignoreId)
        assertNull(got.ignored.single().requesterLabel)
    }

    @Test
    fun accept_is_the_entrys_own_add_at_the_node() = runBlocking {
        val (node, log) = serving(mapOf(
            "GET /v1/contacts/requests" to (200 to list),
            "POST /v1/contacts" to (201 to """{"key_id":"ada-9f3a","consent_attestation_id":"att-9","freshly_emitted":true}"""),
        ))
        val api = ClientContactRequestsApi(CIRISApiClient(agent, "tok"))
        val request = api.list(node).requests.single()
        val added = api.accept(node, request)
        val post = log.single { it.method == "POST" }
        assertEquals("/v1/contacts", post.path)
        assertTrue(post.body.contains("\"key_id\":\"ada-9f3a\""), post.body)
        assertNull(added.reasonId)
    }

    @Test
    fun ignore_and_unignore_hit_the_ignore_path_with_post_and_delete() = runBlocking {
        val (node, log) = serving(mapOf(
            "POST /v1/contacts/requests/g1/ignore" to (200 to """{"state":"ignored","grant_attestation_id":"g1","ignore_id":"ig-1"}"""),
            "DELETE /v1/contacts/requests/g1/ignore" to (200 to """{"state":"pending","grant_attestation_id":"g1","withdrawal_id":"w-1"}"""),
        ))
        val api = ClientContactRequestsApi(CIRISApiClient(agent, "tok"))
        assertEquals("ig-1", api.ignore(node, "g1").ignoreId)
        assertEquals("w-1", api.unignore(node, "g1").withdrawalId)
        assertEquals(
            listOf("POST /v1/contacts/requests/g1/ignore", "DELETE /v1/contacts/requests/g1/ignore"),
            log.map { "${it.method} ${it.path}" },
        )
    }

    @Test
    fun each_refusal_arrives_with_its_id_and_status() = runBlocking {
        val (node, _) = serving(mapOf(
            "POST /v1/contacts/requests/gone/ignore" to (404 to """{"reason_id":"contacts.request_not_found","error":"not a pending request"}"""),
            "DELETE /v1/contacts/requests/g1/ignore" to (404 to """{"reason_id":"contacts.request_not_ignored","error":"not ignored"}"""),
            "POST /v1/contacts/requests/g1/ignore" to (403 to """{"reason_id":"contacts.delegate_may_not_answer","error":"delegate"}"""),
        ))
        val api = ClientContactRequestsApi(CIRISApiClient(agent, "tok"))
        for ((call, id, status) in listOf(
            Triple(suspend { api.ignore(node, "gone") }, "contacts.request_not_found", 404),
            Triple(suspend { api.unignore(node, "g1") }, "contacts.request_not_ignored", 404),
            Triple(suspend { api.ignore(node, "g1") }, "contacts.delegate_may_not_answer", 403),
        )) {
            val e = assertFailsWith<NodeRefusal> { call() }
            assertEquals(id, e.reasonId)
            assertEquals(status, e.statusCode)
        }
    }

    @Test
    fun a_node_without_the_route_is_a_bare_404() = runBlocking {
        val (node, _) = serving(emptyMap())
        val e = assertFailsWith<NodeRefusal> { ClientContactRequestsApi(CIRISApiClient(agent, "tok")).list(node) }
        assertEquals(404, e.statusCode)
        assertNull(e.reasonId)
    }
}
