package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ContactsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.ClientMode
import ai.ciris.mobile.shared.models.federation.AddContactResponse
import ai.ciris.mobile.shared.models.federation.AnnounceOwnershipResponse
import ai.ciris.mobile.shared.models.federation.ContactCodeIncludedNode
import ai.ciris.mobile.shared.models.federation.ContactCodeNode
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AGENT_URL = "http://agent.invalid:8080"
private const val NODE_URL = "http://node.invalid:4243"
private const val PHONE = "node-phone"
private const val LAPTOP = "node-laptop"

/** A node that answers what it is told to, and records where and what it was asked. */
private class FakeContacts(
    /** The devices the person announced. The code carries whatever `nodes` selects of them. */
    var announced: List<String> = listOf(PHONE, LAPTOP),
    var codeError: (nodes: String?) -> NodeRefusal? = { null },
    var addError: NodeRefusal? = null,
    var freshlyEmitted: Boolean = true,
) : ContactsApi {
    val codeCalls = mutableListOf<Pair<String, String?>>()
    val addCalls = mutableListOf<Pair<String, String>>()
    val announceCalls = mutableListOf<String>()

    override suspend fun listContacts() = ContactListResponse()
    override suspend fun listPeers() = FederationPeerListResponse()

    override suspend fun addContact(nodeUrl: String, keyId: String): AddContactResponse {
        addCalls += nodeUrl to keyId
        addError?.let { throw it }
        return AddContactResponse(keyId = "person-1", freshlyEmitted = freshlyEmitted, chatCommunityId = "pair-1")
    }

    override suspend fun contactCode(nodeUrl: String, nodes: String?): ContactCodeResponse {
        codeCalls += nodeUrl to nodes
        codeError(nodes)?.let { throw it }
        val named = when (nodes) {
            null -> announced
            "none" -> emptyList()
            else -> nodes.split(',')
        }
        return ContactCodeResponse(
            keyId = "person-me",
            code = "CIRIS-V3-AAAA-${named.size}",
            qrPayload = "CIRIS-V3-AAAA${named.size}",
            availableNodes = announced.map { ContactCodeNode(it, label = it.removePrefix("node-")) },
            includedNodes = named.map { ContactCodeIncludedNode(it) },
        )
    }

    override suspend fun announceThisDevice(nodeUrl: String): AnnounceOwnershipResponse {
        announceCalls += nodeUrl
        announced = announced + PHONE
        return AnnounceOwnershipResponse(announceOwnership = true, announceTakesEffect = "next_boot")
    }
}

/** The real client, its api base the AGENT (as on a with-AI install). The fake must answer every call. */
private fun agentFrontedClient() = CIRISApiClient(baseUrl = AGENT_URL)

class ContactCodeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm(api: FakeContacts, client: CIRISApiClient = agentFrontedClient()) =
        ContactsViewModel(client, NODE_URL, api)

    // ── The picker ────────────────────────────────────────────────────────

    @Test
    fun thePickerDefaultsToEveryAnnouncedDeviceWithAllTicked() {
        val api = FakeContacts()
        val vm = vm(api)
        vm.openContactCode()
        assertEquals(ContactCodeNodes.ALL, vm.contactCodeNodes.value)
        assertEquals(listOf<String?>(null), api.codeCalls.map { it.second }, "the default leaves `nodes` absent")
        assertEquals(setOf(PHONE, LAPTOP), vm.contactCodeTicked.value, "every device starts ticked")
        val ready = assertIs<ContactCodeState.Ready>(vm.contactCode.value)
        assertEquals(2, ready.code.includedNodes.size)
    }

    @Test
    fun theNodesQueryIsBuiltFromThePicker() {
        val api = FakeContacts()
        val vm = vm(api)
        vm.openContactCode()
        vm.toggleContactCodeNode(LAPTOP) // from "all": untick one → the list of the rest
        assertEquals(ContactCodeNodes.LIST, vm.contactCodeNodes.value)
        vm.setContactCodeNodes(ContactCodeNodes.NONE)
        vm.setContactCodeNodes(ContactCodeNodes.ALL)
        vm.setContactCodeNodes(ContactCodeNodes.LIST)
        vm.toggleContactCodeNode(LAPTOP) // tick it back: both, sorted
        vm.toggleContactCodeNode(PHONE)
        vm.toggleContactCodeNode(LAPTOP) // nothing ticked is a code with no devices
        assertEquals(
            listOf(null, PHONE, "none", null, PHONE, "$LAPTOP,$PHONE", LAPTOP, "none"),
            api.codeCalls.map { it.second },
        )
    }

    @Test
    fun theQueryNeverSendsAnEmptyListAndSortsWhatItSends() {
        assertNull(contactCodeNodesQuery(ContactCodeNodes.ALL, setOf(PHONE)))
        assertEquals("none", contactCodeNodesQuery(ContactCodeNodes.NONE, setOf(PHONE)))
        assertEquals("none", contactCodeNodesQuery(ContactCodeNodes.LIST, emptySet()))
        assertEquals("$LAPTOP,$PHONE", contactCodeNodesQuery(ContactCodeNodes.LIST, setOf(PHONE, LAPTOP)))
        assertEquals(
            "$NODE_URL/v1/self/contact-code?nodes=$LAPTOP%2C$PHONE",
            CIRISApiClient.contactCodeUrl("$NODE_URL/", "$LAPTOP,$PHONE"),
        )
        assertEquals("$NODE_URL/v1/self/contact-code", CIRISApiClient.contactCodeUrl(NODE_URL, null))
    }

    // ── The honesty rule ──────────────────────────────────────────────────

    @Test
    fun withNothingAnnouncedNoCodeIsOfferedAndTheWayOutIsTheAnnounce() {
        val api = FakeContacts(announced = emptyList())
        val vm = vm(api)
        vm.openContactCode()
        assertEquals(ContactCodeState.Unreachable, vm.contactCode.value, "a code that reaches no one is not shown")
        vm.setContactCodeNodes(ContactCodeNodes.NONE)
        assertEquals(ContactCodeState.Unreachable, vm.contactCode.value, "nor is the no-device one")

        vm.makeThisDeviceReachable()
        assertEquals(listOf(NODE_URL), api.announceCalls)
        assertEquals(MakeReachableState.Done("next_boot"), vm.makeReachable.value)
        assertIs<ContactCodeState.Ready>(vm.contactCode.value, "once a device is announced the code is re-read and shown")
    }

    @Test
    fun aNoDeviceCodeIsShownWhenSomethingIsAnnounced() {
        val vm = vm(FakeContacts())
        vm.openContactCode()
        vm.setContactCodeNodes(ContactCodeNodes.NONE)
        val ready = assertIs<ContactCodeState.Ready>(vm.contactCode.value, "the directory knows this person")
        assertTrue(ready.code.includedNodes.isEmpty())
    }

    // ── Where the calls go ────────────────────────────────────────────────

    @Test
    fun withAnAgentInFrontTheCallsGoToTheNodeNotTheApiBase() {
        val api = FakeContacts()
        val vm = vm(api)
        vm.openContactCode()
        vm.addContact("CIRIS-V3-THEIR-CODE")
        vm.makeThisDeviceReachable()
        assertEquals(listOf(NODE_URL), api.codeCalls.map { it.first }.distinct())
        assertEquals(listOf(NODE_URL to "CIRIS-V3-THEIR-CODE"), api.addCalls)
        assertEquals(listOf(NODE_URL), api.announceCalls)
    }

    @Test
    fun onANodeClientTheCallsFollowTheActiveNode() {
        val api = FakeContacts()
        val client = CIRISApiClient(baseUrl = "http://switched.invalid:4243").apply { setClientMode(ClientMode.NODE) }
        val vm = vm(api, client)
        vm.addContact("CIRIS-V3-THEIR-CODE")
        assertEquals("http://switched.invalid:4243", api.addCalls.single().first, "a grant goes to the node on screen")
    }

    // ── Refusals ──────────────────────────────────────────────────────────

    @Test
    fun aNodeWithoutTheRouteIsTooOldNotEmpty() {
        val vm = vm(FakeContacts(codeError = { NodeRefusal(null, null, 404) }))
        vm.openContactCode()
        assertEquals(ContactCodeState.NodeTooOld, vm.contactCode.value)
    }

    @Test
    fun a404WithAnIdIsARefusalNotAnOldNode() {
        val vm = vm(FakeContacts(codeError = { NodeRefusal("self.owner_session_required", "sign in", 404) }))
        vm.openContactCode()
        assertEquals(ContactCodeState.Failed("self.owner_session_required", "sign in"), vm.contactCode.value)
    }

    @Test
    fun aStalePickerIsRefusedByNameAndReloaded() {
        val api = FakeContacts()
        val vm = vm(api)
        vm.openContactCode()
        // LAPTOP is made private elsewhere; the picker still offers it.
        api.announced = listOf(PHONE)
        api.codeError = { nodes ->
            if (nodes != null && LAPTOP in nodes.split(',')) {
                NodeRefusal(ContactsViewModel.NODE_NOT_ANNOUNCED, "not announced", 400)
            } else {
                null
            }
        }
        vm.setContactCodeNodes(ContactCodeNodes.LIST)
        assertEquals(ContactsViewModel.NODE_NOT_ANNOUNCED, vm.contactCodeRefusal.value?.reasonId, "said, by name")
        assertEquals(setOf(PHONE), vm.contactCodeTicked.value, "the private device is dropped")
        val ready = assertIs<ContactCodeState.Ready>(vm.contactCode.value, "and the code for what is left is shown")
        assertEquals(listOf(PHONE), ready.code.includedNodes.map { it.keyId })
        assertEquals(listOf(null, "$LAPTOP,$PHONE", null, PHONE), api.codeCalls.map { it.second })
    }

    // ── Adding someone ────────────────────────────────────────────────────

    @Test
    fun aNodeCodeIsRefusedAsUnresolvableAndNothingIsAdded() {
        val vm = vm(FakeContacts(addError = NodeRefusal("contacts.unresolvable", "does not resolve", 400)))
        vm.addContact("CIRIS-V1-A-NODE-CODE")
        assertEquals("contacts.unresolvable", vm.addRefusalReasonId.value)
        assertNull(vm.justAdded.value)
        assertNull(vm.addOutcome.value)
    }

    @Test
    fun addingTheSamePersonAgainIsAlreadyAddedNotAnError() {
        val api = FakeContacts(freshlyEmitted = false)
        val vm = vm(api)
        vm.addContact("CIRIS-V3-THEIR-CODE")
        assertEquals(AddContactOutcome.ALREADY, vm.addOutcome.value)
        assertNull(vm.addRefusalReasonId.value)
        assertNull(vm.addError.value)
        assertNotNull(vm.justAdded.value, "the chat is still offered")

        api.freshlyEmitted = true
        vm.addContact("CIRIS-V3-SOMEONE-NEW")
        assertEquals(AddContactOutcome.ADDED, vm.addOutcome.value)
    }

    // ── Session ───────────────────────────────────────────────────────────

    @Test
    fun signingOutClosesTheCardAndForgetsThePicker() {
        val vm = vm(FakeContacts())
        vm.openContactCode()
        vm.toggleContactCodeNode(PHONE)
        vm.clearSessionState()
        assertEquals(ContactCodeState.Closed, vm.contactCode.value)
        assertEquals(ContactCodeNodes.ALL, vm.contactCodeNodes.value)
        assertTrue(vm.contactCodeTicked.value.isEmpty())
    }
}
