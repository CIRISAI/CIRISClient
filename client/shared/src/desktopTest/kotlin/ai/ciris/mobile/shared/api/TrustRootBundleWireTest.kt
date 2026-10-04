package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.servedBundleView
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `GET /v1/trust-root/bundle` (ciris-server 0.5.220, CIRISServer#726): public,
 * so the client sends no bearer; a node without the route answers a bare 404,
 * which the view model reads as "no card", never as a failed listing.
 */
class TrustRootBundleWireTest {

    private class Node(val routes: Map<String, Pair<Int, String>>) {
        val seen: MutableList<String> = CopyOnWriteArrayList()
        val auth: MutableList<String?> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += "${ex.requestMethod} ${ex.requestURI.rawPath}"
                auth += ex.requestHeaders.getFirst("Authorization")
                val (status, body) = routes[ex.requestURI.path] ?: (404 to "")
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    @Test
    fun theServedBundleIsReadPubliclyAndAnOlderNodeIsABare404() {
        val newer = Node(mapOf("/v1/trust-root/bundle" to (200 to """{"bundle":{"version":3,"attestations":[{"x":1}]},"community":null,"bundle_fingerprint":"sha256:9f00","charter_root_key_id":"humanity-accord","served_by":"node-1"}""")))
        val older = Node(emptyMap())
        val notEntrenched = Node(mapOf("/v1/trust-root/bundle" to (409 to """{"error":"this node is not entrenched on the bundle it carries, so it serves none: ","reason_id":"trust_root.bundle_not_in_force"}""")))
        try {
            runBlocking {
                val view = servedBundleView(CIRISApiClient(newer.url).getTrustRootBundle(nodeUrl = newer.url))
                assertEquals("sha256:9f00", view?.fingerprint)
                assertEquals("humanity-accord", view?.charterRootKeyId)
                assertEquals(false, view?.carriesCommunity)
                val refusal = assertFailsWith<NodeRefusal> { CIRISApiClient(older.url).getTrustRootBundle(nodeUrl = older.url) }
                assertEquals(404, refusal.statusCode)
                assertEquals(null, refusal.reasonId)
                val conflict = assertFailsWith<NodeRefusal> { CIRISApiClient(notEntrenched.url).getTrustRootBundle(nodeUrl = notEntrenched.url) }
                assertEquals(409, conflict.statusCode)
                assertEquals("trust_root.bundle_not_in_force", conflict.reasonId)
                kotlin.test.assertIs<ai.ciris.mobile.shared.models.federation.ServedBundleRead.NotInForce>(
                    ai.ciris.mobile.shared.models.federation.servedBundleRead(conflict),
                )
            }
            assertTrue(newer.seen.contains("GET /v1/trust-root/bundle"), newer.seen.toString())
            assertEquals(listOf<String?>(null), newer.auth, "a public read carries no bearer")
        } finally {
            newer.server.stop(0)
            older.server.stop(0)
            notEntrenched.server.stop(0)
        }
    }
}
