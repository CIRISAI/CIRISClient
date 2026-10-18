package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientContactsApi
import ai.ciris.mobile.shared.api.ContactsApi
import ai.ciris.mobile.shared.models.federation.FederationPeerListResponse
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **A 202 `contacts.awaiting_key_body` is pending, never "added"** (CIRISClient#188),
 * over the wire.
 *
 * `POST /v1/contacts` with a fedcode v3 code whose key body the node does not
 * hold yet answers **202** `{reason_id: contacts.awaiting_key_body,
 * converges_on_its_own: true}` and writes nothing (CIRISServer
 * `contacts_chat.rs`, `refuse_with(StatusCode::ACCEPTED, …)` @ f2bbb63). The
 * client read any 2xx as a contact: the card said "added", the list stayed
 * empty, and the first message failed 403 `chat.not_a_contact`.
 *
 * The real client against a real socket that answers the server's exact body
 * shape: the add is PENDING, the same code is POSTed again on a bounded
 * backoff, and only a 2xx WITHOUT a `reason_id` completes it.
 */
class PendingContactWireTest {

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    /** A node whose `POST /v1/contacts` answers a SCRIPT, one entry per call, the last one repeating. */
    private class ScriptedNode(script: List<Pair<Int, String>>, listBody: String = """{"contacts":[],"total":0}""") {
        val seen: MutableList<String> = CopyOnWriteArrayList()
        val posts = AtomicInteger(0)
        var list: String = listBody
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val uri = exchange.requestURI
                seen += "${exchange.requestMethod} ${uri.rawPath}"
                val (status, body) = when {
                    uri.path == "/v1/contacts" && exchange.requestMethod == "POST" ->
                        script[minOf(posts.getAndIncrement(), script.size - 1)]
                    uri.path == "/v1/contacts" -> 200 to list
                    else -> 404 to ""
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private fun vmOver(
        client: CIRISApiClient,
        nodeUrl: String,
        holdFirstWait: CompletableDeferred<Unit>? = null,
    ): ContactsViewModel {
        val real = ClientContactsApi(client)
        val api = object : ContactsApi by real {
            override suspend fun listPeers() = FederationPeerListResponse()
        }
        // The real backoff is seconds; the schedule is pinned in
        // PendingContactTest. Here the waits are shortened so the loop runs
        // over the socket in test time, and recorded so the test sees them.
        // [holdFirstWait] parks the first backoff until the test has looked:
        // at 10 ms a step, 202 -> 202 -> 201 can finish between two 20 ms
        // polls, the pending row comes and goes unseen, and the wait for it
        // times out (PR #200's gradle job, PendingContactWireTest.kt:89).
        return ContactsViewModel(client, { nodeUrl }, api, pendingWait = { ms ->
            waits += ms
            holdFirstWait?.await()
            delay(10)
        })
    }

    private val waits = CopyOnWriteArrayList<Long>()

    private suspend fun awaitThat(what: String, condition: () -> Boolean) {
        try {
            withTimeout(15_000) { while (!condition()) delay(20) }
        } catch (e: TimeoutCancellationException) {
            fail("timed out waiting for: $what")
        }
    }

    // The server's 202, byte-shaped as `refuse_with(StatusCode::ACCEPTED, "contacts.awaiting_key_body", …, extra)` builds it.
    private val awaiting = 202 to """{"error":"the key this code names is being fetched from the mesh; the contact completes on its own once it arrives and is verified against the code",
        |"reason_id":"contacts.awaiting_key_body","key_id":"ml-dsa-bob-7f3a","fed_id":"bob-x",
        |"ml_dsa_65_pubkey_sha256":"00ff","source":"direct","converges_on_its_own":true}""".trimMargin()
    private val added = 201 to """{"key_id":"bob-x","consent_attestation_id":"att-1","freshly_emitted":true,
        |"consent_prefixes":["chat:"],"chat_community_id":"pair-bob","source":"direct","reachable":true,"reachable_nodes":1}""".trimMargin()

    @Test
    fun a202AwaitingKeyBodyIsNeverReportedAsAdded() {
        val node = ScriptedNode(listOf(awaiting))
        try {
            runBlocking {
                val vm = vmOver(CIRISApiClient(node.url), node.url)
                vm.addContact("CIRIS-V3-BOB-CODE")
                awaitThat("the add to settle") { node.posts.get() >= 1 && !vm.addBusy.value }
                assertNull(vm.justAdded.value, "a 202 wrote no row, so there is no contact to offer a chat with")
                assertNotEquals(AddContactOutcome.ADDED, vm.addOutcome.value)
                assertNotEquals(AddContactOutcome.ALREADY, vm.addOutcome.value)
                assertNull(vm.addRefusalReasonId.value, "a 202 is not a refusal either")
                assertEquals(AddContactOutcome.PENDING, vm.addOutcome.value)
                val row = vm.pendingContacts.value.single()
                assertEquals("CIRIS-V3-BOB-CODE", row.code, "the code is kept, exactly as pasted")
                assertEquals("ml-dsa-bob-7f3a", row.keyId)
                assertEquals("bob-x", row.fedId)
                assertEquals("contacts.awaiting_key_body", row.reasonId)
                assertTrue(row.convergesOnItsOwn)
                assertTrue(row.detail!!.startsWith("the key this code names"), "the server's sentence rides along as the detail")
            }
        } finally {
            node.server.stop(0)
        }
    }

    @Test
    fun thePendingCodeIsPostedAgainUntilTheNodeAnswers201AndOnlyThenIsItAdded() {
        val node = ScriptedNode(listOf(awaiting, awaiting, added))
        val looked = CompletableDeferred<Unit>()
        try {
            runBlocking {
                val vm = vmOver(CIRISApiClient(node.url), node.url, holdFirstWait = looked)
                vm.addContact("CIRIS-V3-BOB-CODE")
                awaitThat("the pending row") { vm.pendingContacts.value.isNotEmpty() }
                assertNull(vm.justAdded.value)
                looked.complete(Unit)

                awaitThat("the re-POSTs to reach the 201") { vm.addOutcome.value == AddContactOutcome.ADDED }
                assertEquals(3, node.posts.get(), "202, 202, 201: three POSTs of the same code")
                assertEquals(listOf(2_000L, 4_000L), waits.toList(), "each re-POST waited its backoff step")
                assertTrue(vm.pendingContacts.value.isEmpty(), "the row leaves on the 201")
                assertEquals("bob-x", vm.justAdded.value?.keyId)
                assertEquals(
                    listOf("POST /v1/contacts", "POST /v1/contacts", "POST /v1/contacts"),
                    node.seen.filter { it.startsWith("POST") },
                )
                awaitThat("the list re-read after the add") { node.seen.count { it == "GET /v1/contacts" } >= 2 }
            }
        } finally {
            node.server.stop(0)
        }
    }

    @Test
    fun aRefusalOnTheRePostEndsTheLoopAndIsSaidOnTheRow() {
        val refused = 409 to """{"error":"This code doesn't match the person it names","reason_id":"contacts.code_commitment_mismatch"}"""
        val node = ScriptedNode(listOf(awaiting, refused, added))
        try {
            runBlocking {
                val vm = vmOver(CIRISApiClient(node.url), node.url)
                vm.addContact("CIRIS-V3-BOB-CODE")
                awaitThat("the refusal on the row") { vm.pendingContacts.value.singleOrNull()?.refusal != null }
                assertEquals("contacts.code_commitment_mismatch", vm.pendingContacts.value.single().refusal?.reasonId)
                delay(200)
                assertEquals(2, node.posts.get(), "a hard refusal is final for the loop: no third POST")
                assertNull(vm.justAdded.value)
            }
        } finally {
            node.server.stop(0)
        }
    }
}
