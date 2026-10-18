package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ContactsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.AddContactResponse
import ai.ciris.mobile.shared.models.federation.AnnounceOwnershipResponse
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.ContactCodeResponse
import ai.ciris.mobile.shared.models.federation.ContactListResponse
import ai.ciris.mobile.shared.models.federation.FederationPeerListResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NODE = "http://node.invalid:4243"
private const val CODE = "CIRIS-V3-BOB-CODE"

/** The server's 202, as `refuse_with(StatusCode::ACCEPTED, "contacts.awaiting_key_body", …)` shapes it. */
private fun awaitingKeyBody(keyId: String = "ml-dsa-bob", fedId: String? = "bob-x") = AddContactResponse(
    keyId = keyId,
    reasonId = AddContactResponse.AWAITING_KEY_BODY,
    error = "the key this code names is being fetched from the mesh",
    fedId = fedId,
    convergesOnItsOwn = true,
    httpStatus = 202,
)

private fun added(freshly: Boolean = true) = AddContactResponse(
    keyId = "bob-x", freshlyEmitted = freshly, consentPrefixes = listOf("chat:"), chatCommunityId = "pair-bob", httpStatus = 201,
)

/** A node whose `POST /v1/contacts` answers a script, the last entry repeating; the list is whatever the test sets. */
private class ScriptedNode(val script: MutableList<() -> AddContactResponse>) : ContactsApi {
    val posts = mutableListOf<String>()
    var contacts: List<Contact> = emptyList()
    var listReads = 0

    override suspend fun listContacts(nodeUrl: String): ContactListResponse {
        listReads += 1
        return ContactListResponse(contacts = contacts, total = contacts.size)
    }
    override suspend fun listPeers() = FederationPeerListResponse()
    override suspend fun addContact(nodeUrl: String, keyId: String): AddContactResponse {
        posts += keyId
        val step = if (script.size > 1) script.removeAt(0) else script.first()
        return step()
    }
    override suspend fun contactCode(nodeUrl: String, nodes: String?) = ContactCodeResponse(keyId = "me", code = "x", qrPayload = "x")
    override suspend fun announceThisDevice(nodeUrl: String) = AnnounceOwnershipResponse(announceOwnership = true)
}

/**
 * **A 202 `contacts.awaiting_key_body` is pending, never "added"; the code is
 * kept and asked again until the node answers without a `reason_id`**
 * (CIRISClient#188).
 */
class PendingContactTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    /** Every wait the loop asked for, in order; none is actually waited. */
    private val waits = mutableListOf<Long>()

    private fun vm(node: ScriptedNode) = ContactsViewModel(
        CIRISApiClient(baseUrl = NODE), { NODE }, node,
        pendingWait = { ms -> waits += ms },
    )

    @Test
    fun a202IsPendingNotAddedAndOffersNoChat() {
        val node = ScriptedNode(mutableListOf({ awaitingKeyBody() }))
        val vm = vm(node)
        vm.addContact(CODE)
        assertEquals(AddContactOutcome.PENDING, vm.addOutcome.value)
        assertNull(vm.justAdded.value, "nothing was written, so there is no contact to open a chat with")
        assertNull(vm.addRefusalReasonId.value, "a not-yet is not a refusal")
        val row = vm.pendingContacts.value.single()
        assertEquals(CODE, row.code)
        assertEquals("ml-dsa-bob", row.keyId)
        assertEquals("bob-x", row.fedId)
        assertEquals("contacts.awaiting_key_body", row.reasonId)
        assertTrue(row.convergesOnItsOwn)
        assertEquals("bob-x", row.who)
    }

    @Test
    fun theSameCodeIsPostedAgainOnABoundedBackoffUntilTheNodeAnswersWithoutAReasonId() {
        val node = ScriptedNode(mutableListOf(
            { awaitingKeyBody() }, { awaitingKeyBody() }, { awaitingKeyBody() }, { added() },
        ))
        val vm = vm(node)
        vm.addContact(CODE)
        // The loop ran to completion under the unconfined dispatcher: three
        // 202s, each waited out on the schedule, then the 201 that adds.
        assertEquals(listOf(CODE, CODE, CODE, CODE), node.posts, "the SAME code is POSTed again, nothing else")
        assertEquals(listOf(2_000L, 4_000L, 8_000L), waits, "2 s doubling — a backoff, not a hammer")
        assertTrue(vm.pendingContacts.value.isEmpty(), "the row leaves once the node answers without a reason_id")
        assertEquals(AddContactOutcome.ADDED, vm.addOutcome.value)
        assertEquals("bob-x", assertNotNull(vm.justAdded.value).keyId)
        assertTrue(node.listReads >= 2, "the list is re-read after the add, not edited: ${node.listReads}")
    }

    @Test
    fun theBackoffIsCappedAndTheLoopStopsAfterTheBound() {
        assertEquals(2_000L, ContactsViewModel.pendingRetryDelayMs(1))
        assertEquals(32_000L, ContactsViewModel.pendingRetryDelayMs(5))
        assertEquals(60_000L, ContactsViewModel.pendingRetryDelayMs(6))
        assertEquals(60_000L, ContactsViewModel.pendingRetryDelayMs(40), "never overflows, never exceeds the cap")

        val node = ScriptedNode(mutableListOf({ awaitingKeyBody() }))
        val vm = vm(node)
        vm.addContact(CODE)
        assertEquals(ContactsViewModel.PENDING_MAX_ATTEMPTS, node.posts.size, "bounded: it stops asking")
        val row = vm.pendingContacts.value.single()
        assertTrue(row.exhausted, "and the row says so")
        assertTrue(waits.all { it <= ContactsViewModel.PENDING_BACKOFF_CAP_MS })
        assertNull(vm.justAdded.value, "still never 'added'")

        // A refresh is the person's own "ask now": the loop starts over.
        node.script.clear(); node.script += { added() }
        vm.refreshContacts()
        assertTrue(vm.pendingContacts.value.isEmpty())
        assertEquals(AddContactOutcome.ADDED, vm.addOutcome.value)
    }

    @Test
    fun aRefusalDuringTheLoopEndsItAndIsKeptOnTheRow() {
        val node = ScriptedNode(mutableListOf(
            { awaitingKeyBody() },
            { throw NodeRefusal("contacts.code_commitment_mismatch", "does not match", 409) },
            { added() },
        ))
        val vm = vm(node)
        vm.addContact(CODE)
        assertEquals(2, node.posts.size, "a hard refusal ends the loop")
        val row = vm.pendingContacts.value.single()
        assertEquals("contacts.code_commitment_mismatch", assertNotNull(row.refusal).reasonId)
        assertNull(vm.justAdded.value)

        // Try again clears it and asks now.
        vm.retryPending(CODE)
        assertEquals(3, node.posts.size)
        assertTrue(vm.pendingContacts.value.isEmpty())
        assertEquals(AddContactOutcome.ADDED, vm.addOutcome.value)
    }

    @Test
    fun aTransportFailureCountsAsAnAttemptAndTheLoopGoesOn() {
        val node = ScriptedNode(mutableListOf(
            { awaitingKeyBody() },
            { throw IllegalStateException("connection reset") },
            { added() },
        ))
        val vm = vm(node)
        vm.addContact(CODE)
        assertEquals(3, node.posts.size)
        assertEquals(listOf(2_000L, 4_000L), waits)
        assertTrue(vm.pendingContacts.value.isEmpty())
    }

    @Test
    fun aPendingWhoseKeyTurnsUpInTheListWasCompletedByTheNodeAndItsRowLeaves() {
        // ciris-server 0.5.231 completes a pending v3 contact itself when the key
        // body arrives (pending_contacts, CIRISServer#777); the list is the fact.
        val node = ScriptedNode(mutableListOf({ awaitingKeyBody(keyId = "ml-dsa-bob", fedId = "bob-x") }))
        val vm = vm(node)
        vm.addContact(CODE)
        assertEquals(1, vm.pendingContacts.value.size)
        node.contacts = listOf(Contact(keyId = "bob-x", chatCommunityId = "pair-bob"))
        node.script.clear(); node.script += { awaitingKeyBody() } // the node would still say 202 to a re-POST
        val before = node.posts.size
        vm.refreshContacts()
        assertTrue(vm.pendingContacts.value.isEmpty(), "the key is in the list: the row leaves")
        assertEquals(before, node.posts.size, "and nothing is asked again for it")
        assertEquals(1, vm.contacts.value.size)
    }

    @Test
    fun forgettingAPendingDropsTheRowAndStopsAsking() {
        val node = ScriptedNode(mutableListOf({ awaitingKeyBody() }))
        val vm = vm(node)
        vm.addContact(CODE)
        vm.forgetPending(CODE)
        assertTrue(vm.pendingContacts.value.isEmpty())
        assertNull(vm.addOutcome.value, "the card's pending line goes with it")
        val before = node.posts.size
        vm.refreshContacts()
        assertEquals(before, node.posts.size)
    }

    @Test
    fun signingOutDropsPendingCodes() {
        val node = ScriptedNode(mutableListOf({ awaitingKeyBody() }))
        val vm = vm(node)
        vm.addContact(CODE)
        vm.clearSessionState()
        assertTrue(vm.pendingContacts.value.isEmpty())
        assertNull(vm.addOutcome.value)
    }

    @Test
    fun aPendingRowIsSearchedByWhomItNames() {
        val node = ScriptedNode(mutableListOf({ awaitingKeyBody(keyId = "ml-dsa-bob", fedId = "bob-x") }))
        val vm = vm(node)
        vm.addContact(CODE)
        vm.setSearchQuery("bob")
        assertEquals(1, vm.pendingContacts.value.size)
        vm.setSearchQuery("zz-nobody")
        assertTrue(vm.pendingContacts.value.isEmpty())
    }

    @Test
    fun anyTwoHundredWithAReasonIdIsPendingNeverAdded() {
        // The rule is the reason_id, not the number: `contacts.awaiting_consent`
        // and `contacts.unreachable` are 202s on the same route (contacts_chat.rs
        // `stall` → ACCEPTED), and a success body carries no reason_id at all.
        for (reason in listOf("contacts.awaiting_consent", "contacts.unreachable", "contacts.awaiting_key_body")) {
            val node = ScriptedNode(mutableListOf({
                AddContactResponse(keyId = "bob-x", reasonId = reason, error = "not yet", httpStatus = 202)
            }))
            val vm = vm(node)
            vm.addContact(CODE)
            assertEquals(AddContactOutcome.PENDING, vm.addOutcome.value, reason)
            assertNull(vm.justAdded.value, reason)
        }
        assertTrue(AddContactResponse(keyId = "bob-x", freshlyEmitted = false, httpStatus = 200).pending.not())
    }
}
