package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.ContactRequestsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.acceptKeyOf
import ai.ciris.mobile.shared.models.federation.AddContactResponse
import ai.ciris.mobile.shared.models.federation.ContactRequest
import ai.ciris.mobile.shared.models.federation.ContactRequestIgnoreAnswer
import ai.ciris.mobile.shared.models.federation.ContactRequestList
import ai.ciris.mobile.shared.models.federation.RequestRoute
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun req(id: String, by: String = "ada-1234", at: String = "2026-10-25T10:00:00Z", label: String? = null) =
    ContactRequest(
        grantAttestationId = id, requesterKeyId = by, requesterLabel = label, requestedAt = at,
        accept = RequestRoute("POST", "/v1/contacts", buildJsonObject { put("key_id", JsonPrimitive(by)) }),
        ignore = RequestRoute("POST", "/v1/contacts/requests/$id/ignore"),
    )

/** The owner's node: answers what it is told to and records every call, with the node it went to. */
private class FakeRequests(
    var requests: List<ContactRequest> = listOf(req("g1")),
    var ignored: List<ContactRequest> = emptyList(),
    var readError: Exception? = null,
    var answerError: NodeRefusal? = null,
    var acceptReason: String? = null,
) : ContactRequestsApi {
    val calls = mutableListOf<String>()
    var reads = 0
    override suspend fun list(nodeUrl: String): ContactRequestList {
        reads++
        readError?.let { throw it }
        return ContactRequestList(requests, requests.size, ignored)
    }
    override suspend fun accept(nodeUrl: String, request: ContactRequest): AddContactResponse {
        calls += "accept ${acceptKeyOf(request)} @$nodeUrl"
        answerError?.let { throw it }
        if (acceptReason == null) requests = requests.filterNot { it.grantAttestationId == request.grantAttestationId }
        return AddContactResponse(keyId = request.requesterKeyId, reasonId = acceptReason)
    }
    override suspend fun ignore(nodeUrl: String, grantAttestationId: String): ContactRequestIgnoreAnswer {
        calls += "ignore $grantAttestationId @$nodeUrl"
        answerError?.let { throw it }
        val r = requests.first { it.grantAttestationId == grantAttestationId }
        requests = requests - r; ignored = ignored + r.copy(ignoreId = "ig-$grantAttestationId")
        return ContactRequestIgnoreAnswer("ignored", grantAttestationId, ignoreId = "ig-$grantAttestationId")
    }
    override suspend fun unignore(nodeUrl: String, grantAttestationId: String): ContactRequestIgnoreAnswer {
        calls += "unignore $grantAttestationId @$nodeUrl"
        answerError?.let { throw it }
        val r = ignored.first { it.grantAttestationId == grantAttestationId }
        ignored = ignored - r; requests = requests + r.copy(ignoreId = null)
        return ContactRequestIgnoreAnswer("pending", grantAttestationId, withdrawalId = "w-$grantAttestationId")
    }
}

class ContactRequestsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain() }

    private val node = "http://node.test:4243"
    private var accepted = 0
    private val ticks = Channel<Unit>(Channel.UNLIMITED)

    private fun vm(api: FakeRequests) = ContactRequestsViewModel(
        api, nodeUrl = { node }, onAccepted = { accepted++ }, wait = { ticks.receive() },
    ).also { made += it }

    private fun loaded(api: FakeRequests) = vm(api).also { it.load() }

    // ── the read ─────────────────────────────────────────────────────────────

    @Test
    fun requests_are_read_newest_first() {
        val api = FakeRequests(listOf(req("old", at = "2026-10-20T00:00:00Z"), req("new", at = "2026-10-25T00:00:00Z")))
        val read = assertIs<ContactRequestsRead.Loaded>(loaded(api).read.value)
        assertEquals(listOf("new", "old"), read.requests.map { it.grantAttestationId })
    }

    @Test
    fun a_bare_404_is_a_node_without_requests_never_none() {
        val vm = loaded(FakeRequests(readError = NodeRefusal(null, "Not Found", 404)))
        assertEquals(ContactRequestsRead.NotOnThisNode, vm.read.value)
    }

    @Test
    fun a_refusal_with_an_id_is_a_failure_not_an_old_node() {
        val vm = loaded(FakeRequests(readError = NodeRefusal("auth.owner_session_required", "owner only", 403)))
        val failed = assertIs<ContactRequestsRead.Failed>(vm.read.value)
        assertEquals("auth.owner_session_required", failed.refusal?.reasonId)
    }

    @Test
    fun a_failed_poll_keeps_the_rows_it_has() {
        val api = FakeRequests()
        val vm = loaded(api)
        api.readError = NodeRefusal(null, "socket closed", 0)
        vm.load()
        val read = assertIs<ContactRequestsRead.Loaded>(vm.read.value)
        assertEquals(listOf("g1"), read.requests.map { it.grantAttestationId })
    }

    // ── accept ───────────────────────────────────────────────────────────────

    @Test
    fun accept_sends_nothing_until_the_confirm() {
        val api = FakeRequests()
        val vm = loaded(api)
        vm.requestAccept(req("g1"))
        assertTrue(api.calls.isEmpty(), "an accept was sent before its confirm: ${api.calls}")
        vm.cancelConfirm()
        assertTrue(api.calls.isEmpty())
        assertNull(vm.confirming.value)
    }

    @Test
    fun a_confirmed_accept_adds_the_requester_at_the_node_and_rereads() {
        val api = FakeRequests()
        val vm = loaded(api)
        vm.requestAccept(req("g1"))
        vm.confirmAccept()
        assertEquals(listOf("accept ada-1234 @$node"), api.calls)
        assertEquals(ContactRequestNotice.ACCEPTED, vm.notice.value)
        assertEquals(1, accepted, "People's own list must re-read after an accept")
        assertTrue(assertIs<ContactRequestsRead.Loaded>(vm.read.value).requests.isEmpty())
    }

    @Test
    fun an_accept_answered_with_a_reason_id_is_pending_not_contacts() {
        val api = FakeRequests(acceptReason = "contacts.awaiting_key_body")
        val vm = loaded(api)
        vm.requestAccept(req("g1"))
        vm.confirmAccept()
        assertEquals(ContactRequestNotice.ACCEPT_PENDING, vm.notice.value)
        assertEquals(0, accepted)
    }

    // ── ignore / un-ignore ───────────────────────────────────────────────────

    @Test
    fun ignore_is_one_tap_and_moves_the_row_to_ignored() {
        val api = FakeRequests()
        val vm = loaded(api)
        vm.ignore(req("g1"))
        assertEquals(listOf("ignore g1 @$node"), api.calls)
        assertEquals(ContactRequestNotice.IGNORED, vm.notice.value)
        val read = assertIs<ContactRequestsRead.Loaded>(vm.read.value)
        assertTrue(read.requests.isEmpty())
        assertEquals(listOf("g1"), read.ignored.map { it.grantAttestationId })
    }

    @Test
    fun unignore_puts_it_back() {
        val api = FakeRequests(requests = emptyList(), ignored = listOf(req("g1").copy(ignoreId = "ig-g1")))
        val vm = loaded(api)
        vm.unignore(req("g1"))
        assertEquals(ContactRequestNotice.UNIGNORED, vm.notice.value)
        assertEquals(listOf("g1"), assertIs<ContactRequestsRead.Loaded>(vm.read.value).requests.map { it.grantAttestationId })
    }

    @Test
    fun a_refused_answer_is_shown_by_id_and_rereads() {
        val api = FakeRequests(answerError = NodeRefusal("contacts.request_not_found", "not a pending request", 404))
        val vm = loaded(api)
        val before = api.reads
        vm.ignore(req("g1"))
        assertEquals("contacts.request_not_found", vm.refusal.value?.reasonId)
        assertTrue(api.reads > before, "a refused answer must re-read the list")
    }

    @Test
    fun a_delegate_refusal_keeps_the_row() {
        val api = FakeRequests(answerError = NodeRefusal("contacts.delegate_may_not_answer", "delegate", 403))
        val vm = loaded(api)
        vm.ignore(req("g1"))
        assertEquals("contacts.delegate_may_not_answer", vm.refusal.value?.reasonId)
        assertEquals(listOf("g1"), assertIs<ContactRequestsRead.Loaded>(vm.read.value).requests.map { it.grantAttestationId })
    }

    // ── polling ──────────────────────────────────────────────────────────────

    @Test
    fun polling_reads_on_each_tick_and_stops_when_the_screen_leaves() = runTest {
        val api = FakeRequests()
        val vm = vm(api)
        vm.startPolling()
        assertEquals(1, api.reads, "polling reads at once")
        ticks.send(Unit); ticks.send(Unit)
        assertEquals(3, api.reads)
        vm.startPolling()
        assertEquals(3, api.reads, "a second start must not run a second loop")
        vm.stopPolling()
        ticks.send(Unit)
        assertEquals(3, api.reads, "a stopped poll read again")
    }

    @Test
    fun the_session_ending_stops_polling_and_forgets_the_rows() = runTest {
        val api = FakeRequests()
        val vm = vm(api)
        vm.startPolling()
        vm.clearSessionState()
        ticks.send(Unit)
        assertEquals(1, api.reads)
        assertEquals(ContactRequestsRead.NotAsked, vm.read.value)
    }

    @Test
    fun a_node_change_keeps_a_shown_screen_polling() = runTest {
        val api = FakeRequests()
        val vm = vm(api)
        vm.startPolling()
        vm.nodeChanged()
        assertEquals(2, api.reads, "the new node is read at once")
        assertIs<ContactRequestsRead.Loaded>(vm.read.value)
    }

    // ── the accept route ─────────────────────────────────────────────────────

    @Test
    fun only_post_v1_contacts_is_an_accept_route() {
        assertEquals("ada-1234", acceptKeyOf(req("g1")))
        val elsewhere = req("g1").copy(accept = RequestRoute("POST", "/v1/federation/peering", null))
        assertNull(acceptKeyOf(elsewhere), "an accept naming another route must not be sent")
        val wrongVerb = req("g1").copy(accept = RequestRoute("DELETE", "/v1/contacts", null))
        assertNull(acceptKeyOf(wrongVerb))
        val noBody = req("g1").copy(accept = RequestRoute("POST", "/v1/contacts", null))
        assertEquals("ada-1234", acceptKeyOf(noBody), "without a body, the requester is the key")
    }
}
