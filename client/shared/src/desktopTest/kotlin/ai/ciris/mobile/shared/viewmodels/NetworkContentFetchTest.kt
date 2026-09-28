package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.federation.LocalPeerState
import ai.ciris.mobile.shared.models.federation.PeerTrustState
import ai.ciris.mobile.shared.viewmodels.federation.NetworkContentViewModel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * CSD-047: one tap on Fetch is one `POST /v1/federation/content/{id}`. The
 * button disables itself while a fetch is in flight, but the automation
 * handler beside it did not, and the view model had no guard of its own — so a
 * second tap launched a second POST. Driven against a real socket: the
 * duplicate is only visible as two requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkContentFetchTest {

    private val hits = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        CIRISApiClient.setLocalNodeUrl(CIRISApiClient.DEFAULT_LOCAL_NODE_URL)
    }

    /** Every request is recorded, then answered slowly, so a second tap lands mid-flight. */
    private fun slowNode(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            hits += "${exchange.requestMethod} ${exchange.requestURI.path}"
            Thread.sleep(300)
            val bytes = """{"detail":"peer unreachable"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(503, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private suspend fun awaitThat(what: String, condition: () -> Boolean) {
        try {
            withTimeout(15_000) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            fail("timed out waiting for: $what")
        }
    }

    private val peer = LocalPeerState(
        keyId = "peer-1", pubkeyEd25519Base64 = "AA==", canonical = false, trust = PeerTrustState.TRUSTED,
        firstSeen = Instant.fromEpochSeconds(0),
    )
    private val cid = "a".repeat(64)

    @Test
    fun a_second_tap_while_a_fetch_is_in_flight_sends_no_second_post() {
        val server = slowNode()
        val url = "http://127.0.0.1:${server.address.port}"
        CIRISApiClient.setLocalNodeUrl(url)
        try {
            runBlocking {
                val vm = NetworkContentViewModel(CIRISApiClient(url))
                vm.selectPeer(peer)
                vm.setContentId(cid)
                vm.fetch()
                vm.fetch()
                awaitThat("the fetch to end") { !vm.fetching.value }
                assertEquals(1, hits.count { it == "POST /v1/federation/content/$cid" }, "requests seen: $hits")

                // Once it has ended, a new tap is a new fetch.
                vm.fetch()
                awaitThat("the second fetch to end") { !vm.fetching.value }
                assertEquals(2, hits.count { it == "POST /v1/federation/content/$cid" })
            }
        } finally {
            server.stop(0)
        }
    }
}
