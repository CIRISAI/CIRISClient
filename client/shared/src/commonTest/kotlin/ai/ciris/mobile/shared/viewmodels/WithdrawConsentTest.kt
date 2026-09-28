package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ConsentWithdrawApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.NodeProfile
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.ContactListResponse
import ai.ciris.mobile.shared.models.federation.OwnedNodeDto
import ai.ciris.mobile.shared.models.federation.OwnedNodesDto
import ai.ciris.mobile.shared.models.federation.PeeringResponse
import ai.ciris.mobile.shared.models.federation.RemoveContactResponse
import ai.ciris.mobile.shared.models.federation.RevokeGrantResponse
import ai.ciris.mobile.shared.models.federation.WithdrawnGrant
import ai.ciris.mobile.shared.ui.screens.revokeNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AGENT_URL = "http://127.0.0.1:9"
private const val NODE_URL = "http://127.0.0.1:19"
private const val PERSON = "person-fed-id"
private const val GRANT = "grant-a-to-b"

/** A node that answers what it is told to, and records every call with the URL it went to. */
private class FakeWithdraw(
    var remove: (keyId: String) -> RemoveContactResponse = { RemoveContactResponse(it, listOf(WithdrawnGrant("g1", "w1"))) },
    var revoke: (id: String) -> RevokeGrantResponse = { RevokeGrantResponse(it, withdraws = "w-$it") },
    var mounted: Boolean? = true,
) : ConsentWithdrawApi {
    val removeCalls = mutableListOf<Pair<String, String>>()
    val revokeCalls = mutableListOf<Pair<String, String>>()
    override suspend fun removeContact(nodeUrl: String, keyId: String): RemoveContactResponse {
        removeCalls += nodeUrl to keyId
        return remove(keyId)
    }
    override suspend fun revokeGrant(nodeUrl: String, attestationId: String): RevokeGrantResponse {
        revokeCalls += nodeUrl to attestationId
        return revoke(attestationId)
    }
    override suspend fun revokeRouteMounted(nodeUrl: String): Boolean? = mounted
}

/** The real client, pointed at a port nothing listens on, standing in for the AGENT. */
private fun agentClient() = CIRISApiClient(baseUrl = AGENT_URL)

class WithdrawConsentTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    // ── People: remove a contact ──────────────────────────────────────────

    private var listReads = 0
    private fun contactsVm(fake: FakeWithdraw) = ContactsViewModel(
        agentClient(),
        nodeUrl = { NODE_URL },
        withdraw = fake,
        readContacts = { listReads += 1; ContactListResponse(listOf(Contact(PERSON)), 1) },
    )

    @Test
    fun remainingGrantsAreNeverReadAsRemoved() {
        // The node withdrew one grant and three it wrote itself stay live.
        val partial = RemoveContactResponse(PERSON, listOf(WithdrawnGrant("g1", "w1")), listOf("n1", "n2", "n3"), contact = true)
        val r = contactRemovalOf(partial)
        assertIs<ContactRemoval.StillActive>(r)
        assertEquals(listOf("n1", "n2", "n3"), r.remainingGrants)
        // Even if the node's `contact` flag disagreed, a remaining grant still is not a removal.
        assertIs<ContactRemoval.StillActive>(contactRemovalOf(partial.copy(contact = false)))
        // And the node calling them a contact with nothing listed is not a removal either.
        assertIs<ContactRemoval.StillActive>(contactRemovalOf(RemoveContactResponse(PERSON, contact = true)))
        // Only nothing-remains-and-not-a-contact reads as removed.
        assertIs<ContactRemoval.Removed>(contactRemovalOf(RemoveContactResponse(PERSON, listOf(WithdrawnGrant("g1")))))
    }

    @Test
    fun theServersWireShapeDecodesToStillActive() {
        val json = Json { ignoreUnknownKeys = true }
        val wire = """{"key_id":"$PERSON","withdrawn":[{"grant":"g1","withdraws":"w1","cohort_scope":"federation"}],
            |"remaining_grants":["n1"],"contact":true}""".trimMargin()
        val resp = json.decodeFromString(RemoveContactResponse.serializer(), wire)
        assertFalse(resp.complete)
        assertIs<ContactRemoval.StillActive>(contactRemovalOf(resp))
    }

    @Test
    fun aRemovalWithGrantsRemainingRendersStillActiveThroughTheModel() {
        val fake = FakeWithdraw(remove = { RemoveContactResponse(it, emptyList(), listOf("n1"), contact = true) })
        val vm = contactsVm(fake)
        vm.removeContact(PERSON)
        val r = vm.removal.value
        assertIs<ContactRemoval.StillActive>(r)
        assertEquals(listOf("n1"), r.remainingGrants)
    }

    @Test
    fun aRemovalReReadsTheList() {
        val vm = contactsVm(FakeWithdraw())
        val before = listReads
        vm.removeContact(PERSON)
        assertIs<ContactRemoval.Removed>(vm.removal.value)
        assertTrue(listReads > before, "the list is re-read after a removal, never edited in place")
    }

    @Test
    fun aStillActiveRemovalAlsoReReadsTheList() {
        val vm = contactsVm(FakeWithdraw(remove = { RemoveContactResponse(it, listOf(WithdrawnGrant("g1")), listOf("n1"), true) }))
        val before = listReads
        vm.removeContact(PERSON)
        assertTrue(listReads > before)
    }

    @Test
    fun aRemovalGoesToTheNodeNotTheAgent() {
        val fake = FakeWithdraw()
        val vm = contactsVm(fake)
        vm.removeContact(PERSON)
        assertEquals(listOf(NODE_URL to PERSON), fake.removeCalls)
        assertNotEquals(AGENT_URL, fake.removeCalls.single().first)
    }

    @Test
    fun aBare404IsANodeTooOldNotARefusal() {
        val vm = contactsVm(FakeWithdraw(remove = { throw NodeRefusal(null, null, 404) }))
        vm.removeContact(PERSON)
        assertIs<ContactRemoval.Unsupported>(vm.removal.value)
    }

    @Test
    fun a404WithAnIdIsARealRefusal() {
        val vm = contactsVm(FakeWithdraw(remove = { throw NodeRefusal("contacts.not_a_contact", "no grant", 404) }))
        vm.removeContact(PERSON)
        val r = vm.removal.value
        assertIs<ContactRemoval.Refused>(r)
        assertEquals("contacts.not_a_contact", r.reasonId)
    }

    // ── Manage Consent: revoke ────────────────────────────────────────────

    private var ownedReads = 0
    private val nodeA = NodeProfile(
        id = NodeProfile.idFor(CIRISApiClient.LOCAL_NODE_URL),
        name = "This device",
        baseUrl = CIRISApiClient.LOCAL_NODE_URL,
        isLocal = true,
        isOwned = true,
    )

    private fun consentVm(fake: FakeWithdraw, granted: Boolean = true) = ConsentObjectsViewModel(
        agentClient(),
        withdraw = fake,
        readOwnedNodes = { ownedReads += 1; OwnedNodesDto(owner = PERSON, nodes = listOf(OwnedNodeDto("node-a", isSelf = true), OwnedNodeDto("node-b"))) },
        initialState = ConsentObjectsState(
            aToB = if (granted) GrantDirectionState.GRANTED else GrantDirectionState.IDLE,
            aToBGrantId = if (granted) GRANT else null,
        ),
    )

    @Test
    fun aNodeWithoutTheRouteKeepsRevokeDisabledWithTheSentence() {
        val vm = consentVm(FakeWithdraw(mounted = false))
        val s = vm.state.value
        assertEquals(RevokeRoute.MISSING, s.revokeRoute)
        assertFalse(s.canRevoke, "a grant id does not make a node without the route able to withdraw")
        assertEquals("mobile.manage_consent_revoke_unsupported" to "text_consent_revoke_unsupported", revokeNote(s))
    }

    @Test
    fun aBare404OnThePostDisablesRevokeWithTheSentence() {
        // The probe could not tell (an SPA fallback, say); the POST itself decides.
        val fake = FakeWithdraw(mounted = null, revoke = { throw NodeRefusal(null, null, 404) })
        val vm = consentVm(fake)
        assertTrue(vm.state.value.canRevoke)
        vm.revokeAToB()
        val s = vm.state.value
        assertEquals(RevokeRoute.MISSING, s.revokeRoute)
        assertFalse(s.canRevoke)
        assertEquals("mobile.manage_consent_revoke_unsupported", revokeNote(s)?.first)
        assertEquals(GrantDirectionState.GRANTED, s.aToB, "nothing was withdrawn")
    }

    @Test
    fun a404WithAnIdDoesNotDisableTheRoute() {
        val vm = consentVm(FakeWithdraw(revoke = { throw NodeRefusal("consent.grant_not_live", "gone", 404) }))
        vm.revokeAToB()
        val s = vm.state.value
        assertNotEquals(RevokeRoute.MISSING, s.revokeRoute)
        assertEquals("consent.grant_not_live", s.revokeRefusalId)
    }

    @Test
    fun aRevokeReReadsAndOnlyTheNodesAnswerMovesTheRow() {
        val fake = FakeWithdraw()
        val vm = consentVm(fake)
        val before = ownedReads
        vm.revokeAToB()
        val s = vm.state.value
        assertTrue(ownedReads > before, "the screen re-reads after a revoke")
        assertEquals(GrantDirectionState.IDLE, s.aToB)
        assertNull(s.aToBGrantId)
        assertEquals("w-$GRANT", s.withdrawnBy)
        assertEquals(listOf(CIRISApiClient.LOCAL_NODE_URL to GRANT), fake.revokeCalls)
    }

    @Test
    fun aNodeAuthoredGrantStaysActiveAndIsNeverReportedDone() {
        val vm = consentVm(FakeWithdraw(revoke = { RevokeGrantResponse(it, remainingGrants = listOf(it)) }))
        val before = ownedReads
        vm.revokeAToB()
        val s = vm.state.value
        assertEquals(listOf(GRANT), s.remainingGrants)
        assertEquals(GrantDirectionState.GRANTED, s.aToB, "a grant that stays live stays GRANTED")
        assertNull(s.withdrawnBy, "no success line while a grant remains")
        assertTrue(ownedReads > before)
    }

    @Test
    fun theRevokeGoesToNodeANotTheAgent() {
        val fake = FakeWithdraw()
        consentVm(fake).revokeAToB()
        assertEquals(nodeA.baseUrl, fake.revokeCalls.single().first)
        assertNotEquals(AGENT_URL, fake.revokeCalls.single().first)
    }

    @Test
    fun withNoNamedGrantThereIsNothingToRevoke() {
        val fake = FakeWithdraw()
        val vm = consentVm(fake, granted = false)
        assertFalse(vm.state.value.canRevoke)
        vm.revokeAToB()
        assertTrue(fake.revokeCalls.isEmpty())
    }

    @Test
    fun thePeeringResponseTheNodeSendsReadsAsGrantedAndNamesTheRow() {
        // The node sends grant_attestation_id and no `granted` member at all.
        val json = Json { ignoreUnknownKeys = true }
        val resp = json.decodeFromString(
            PeeringResponse.serializer(),
            """{"peer_key_id":"b","grant_attestation_id":"$GRANT","freshly_emitted":true,"attestation_prefixes":["capacity:"]}""",
        )
        assertTrue(resp.isGranted)
        assertEquals(GRANT, resp.grantRowId)
    }
}
