package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ConsentWithdrawApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.PeeringApi
import ai.ciris.mobile.shared.models.NodeProfile
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.ContactListResponse
import ai.ciris.mobile.shared.models.federation.OwnedNodeDto
import ai.ciris.mobile.shared.models.federation.OwnedNodesDto
import ai.ciris.mobile.shared.models.federation.PeeringRequest
import ai.ciris.mobile.shared.models.federation.PeeringResponse
import ai.ciris.mobile.shared.models.federation.RemoveContactResponse
import ai.ciris.mobile.shared.models.federation.RevokeGrantResponse
import ai.ciris.mobile.shared.models.federation.SignedKeyRecord
import ai.ciris.mobile.shared.models.federation.WithdrawnGrant
import ai.ciris.mobile.shared.ui.screens.revokeNote
import kotlinx.coroutines.CompletableDeferred
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
private const val OTHER_NODE_URL = "http://127.0.0.1:29"
private const val PERSON = "person-fed-id"
private const val GRANT = "grant-a-to-b"

/** A node that answers what it is told to, and records every call with the URL it went to. */
private class FakeWithdraw(
    var remove: (keyId: String) -> RemoveContactResponse = { RemoveContactResponse(it, listOf(WithdrawnGrant("g1", "w1"))) },
    var revoke: suspend (id: String) -> RevokeGrantResponse = { RevokeGrantResponse(it, withdraws = "w-$it") },
    var mounted: Boolean? = true,
) : ConsentWithdrawApi {
    val removeCalls = mutableListOf<Pair<String, String>>()
    val revokeCalls = mutableListOf<Pair<String, String>>()
    /** The bearer token each revoke went out as, in order. */
    val revokeTokens = mutableListOf<String?>()
    /** The bearer token each route probe went out as, in order. */
    val probeTokens = mutableListOf<String?>()
    override suspend fun removeContact(nodeUrl: String, keyId: String): RemoveContactResponse {
        removeCalls += nodeUrl to keyId
        return remove(keyId)
    }
    override suspend fun revokeGrant(nodeUrl: String, attestationId: String, token: String?): RevokeGrantResponse {
        revokeCalls += nodeUrl to attestationId
        revokeTokens += token
        return revoke(attestationId)
    }
    override suspend fun revokeRouteMounted(nodeUrl: String, token: String?): Boolean? {
        probeTokens += token
        return mounted
    }
}

/** Two owned nodes, key records for any node asked, and a grant for any peering asked — unless told otherwise. */
private class FakePeering(
    var grant: suspend (nodeUrl: String, request: PeeringRequest) -> PeeringResponse =
        { _, _ -> PeeringResponse(grantAttestationId = "grant-new") },
) : PeeringApi {
    var ownedReads = 0
    override suspend fun ownedNodes(): OwnedNodesDto {
        ownedReads += 1
        return OwnedNodesDto(owner = PERSON, nodes = listOf(OwnedNodeDto("node-a", isSelf = true), OwnedNodeDto("node-b")))
    }
    override suspend fun selfKeyRecord(nodeUrl: String, token: String?): SignedKeyRecord = SignedKeyRecord(keyId = "key-of-$nodeUrl")
    override suspend fun peer(nodeUrl: String, token: String?, request: PeeringRequest): PeeringResponse = grant(nodeUrl, request)
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

    // ── The list's node, not the current one (Codex, PR #115) ────────────

    /** A model whose node can be switched under it, with a list that names the node it came from. */
    private class SwitchableContacts(fake: FakeWithdraw) {
        var node = NODE_URL
        val reads = mutableListOf<String>()
        val vm = ContactsViewModel(
            agentClient(),
            nodeUrl = { node },
            withdraw = fake,
            readContacts = { reads += node; ContactListResponse(listOf(Contact("$PERSON@$node")), 1) },
        )
    }

    @Test
    fun aRemovalIsBoundToTheNodeThatSuppliedTheList() {
        val fake = FakeWithdraw()
        val c = SwitchableContacts(fake)
        assertEquals("$PERSON@$NODE_URL", c.vm.contacts.value.single().keyId)

        // The operator switches nodes; the list on screen is still NODE_URL's,
        // and nothing has told the model yet. Remove on that row must not
        // DELETE at the new node.
        c.node = OTHER_NODE_URL
        c.vm.removeContact("$PERSON@$NODE_URL")
        assertTrue(fake.removeCalls.isEmpty(), "a row listed by $NODE_URL was sent as a DELETE to ${fake.removeCalls}")
        assertEquals(OTHER_NODE_URL, c.reads.last(), "the mismatch is answered by re-reading from the current node")
        assertEquals("$PERSON@$OTHER_NODE_URL", c.vm.contacts.value.single().keyId)

        // Now the list is OTHER_NODE_URL's, and a removal goes there — the node that listed the row.
        c.vm.removeContact("$PERSON@$OTHER_NODE_URL")
        assertEquals(listOf(OTHER_NODE_URL to "$PERSON@$OTHER_NODE_URL"), fake.removeCalls)
    }

    @Test
    fun aNodeSwitchDropsTheOldNodesListAndReReadsFromTheNew() {
        val c = SwitchableContacts(FakeWithdraw())
        assertEquals(listOf(NODE_URL), c.reads)
        c.vm.nodeChanged()
        assertEquals(listOf(NODE_URL), c.reads, "the same node is not re-read on a composition that changed nothing")

        c.node = OTHER_NODE_URL
        c.vm.nodeChanged()
        assertEquals(listOf(NODE_URL, OTHER_NODE_URL), c.reads)
        assertEquals("$PERSON@$OTHER_NODE_URL", c.vm.contacts.value.single().keyId, "the old node's list is gone")
        assertTrue(c.vm.contactsLoaded.value)
    }

    @Test
    fun aListReadTheNodeMovedOutFromUnderIsNotPublished() {
        // The read was issued to NODE_URL; the node is OTHER_NODE_URL by the
        // time it answers. Its rows are not what the screen is showing.
        var node = NODE_URL
        val vm = ContactsViewModel(
            agentClient(),
            nodeUrl = { node },
            withdraw = FakeWithdraw(),
            readContacts = {
                val from = node
                node = OTHER_NODE_URL
                ContactListResponse(listOf(Contact("$PERSON@$from")), 1)
            },
        )
        assertTrue(vm.contacts.value.isEmpty(), "published ${vm.contacts.value.map { it.keyId }} from a node no longer active")
    }

    @Test
    fun aLateFailureFromTheNodeSwitchedAwayFromIsNotPublished() {
        // The read went to NODE_URL; the node is OTHER_NODE_URL by the time it
        // fails with a bare 404. That 404 is A's age, not B's — published, it
        // would flip routeUnsupported and CIRISApp's landing fallback away
        // from Contacts on a node that serves it (Codex, PR #116).
        var node = NODE_URL
        val vm = ContactsViewModel(
            agentClient(),
            nodeUrl = { node },
            withdraw = FakeWithdraw(),
            readContacts = {
                node = OTHER_NODE_URL
                throw NodeRefusal(null, null, 404)
            },
        )
        assertFalse(vm.routeUnsupported.value, "node A's bare 404 was published as node B's")
        assertNull(vm.error.value)
        assertFalse(vm.contactsLoaded.value, "a read of another node does not count as this node having answered")
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

    private val peering = FakePeering()
    private val ownedReads: Int get() = peering.ownedReads
    private val nodeA = NodeProfile(
        id = NodeProfile.idFor(CIRISApiClient.LOCAL_NODE_URL),
        name = "This device",
        baseUrl = CIRISApiClient.LOCAL_NODE_URL,
        isLocal = true,
        isOwned = true,
    )

    private fun consentVm(
        fake: FakeWithdraw,
        granted: Boolean = true,
        client: CIRISApiClient = agentClient(),
        initial: ConsentObjectsState = ConsentObjectsState(
            aToB = if (granted) GrantDirectionState.GRANTED else GrantDirectionState.IDLE,
            aToBGrantId = if (granted) GRANT else null,
        ),
        peering: PeeringApi = this.peering,
    ) = ConsentObjectsViewModel(
        client,
        withdraw = fake,
        peering = peering,
        initialState = initial,
    )

    /** A card that has just reported a complete withdrawal, a refusal AND a still-active grant. */
    private val afterARevoke = ConsentObjectsState(
        aToB = GrantDirectionState.IDLE,
        bToA = GrantDirectionState.GRANTED,
        aToBGrantId = null,
        revokeRoute = RevokeRoute.MOUNTED,
        remainingGrants = listOf("old-node-authored"),
        withdrawnBy = "w-old",
        revokeRefusalId = "consent.grant_not_live",
        revokeRefusalDetail = "gone",
        message = "Partial: A→B=true, B→A=false",
    )

    // ── Logout (Codex, PR #115) ──────────────────────────────────────────

    @Test
    fun logoutForgetsTheSessionsGrantOutcomeAndToken() {
        val client = agentClient().apply { setAccessToken("tok-owner-a") }
        val vm = consentVm(FakeWithdraw(), client = client, initial = afterARevoke.copy(aToB = GrantDirectionState.GRANTED, aToBGrantId = GRANT))
        assertEquals("tok-owner-a", vm.state.value.nodeA?.sessionToken, "loadNodes recorded this owner's token on node A")

        vm.resetSession()
        val s = vm.state.value
        assertNull(s.aToBGrantId)
        assertEquals(GrantDirectionState.IDLE, s.aToB)
        assertEquals(GrantDirectionState.IDLE, s.bToA)
        assertTrue(s.remainingGrants.isEmpty())
        assertNull(s.withdrawnBy)
        assertNull(s.revokeRefusalId)
        assertNull(s.revokeRefusalDetail)
        assertNull(s.message)
        assertEquals(RevokeRoute.UNKNOWN, s.revokeRoute)
        assertFalse(s.canRevoke)
        // The node pair goes too: B is the previous owner's remote node, and
        // with it `canRun` (Codex, PR #116). The bearer token goes with A.
        assertNull(s.nodeA, "node A carried the previous owner's bearer token")
        assertNull(s.nodeB, "node B is the previous owner's remote node")
        assertFalse(s.canRun)
    }

    @Test
    fun theNextSessionLoadsItsOwnNodePairOnce() {
        val client = agentClient().apply { setAccessToken("tok-owner-a") }
        val vm = consentVm(FakeWithdraw(), client = client)
        vm.resetSession()
        val reads = ownedReads

        // The next owner signs in: CIRISApp's token effect fires sessionStarted.
        client.setAccessToken("tok-owner-b")
        vm.sessionStarted()
        val s = vm.state.value
        assertEquals(reads + 1, ownedReads, "the pair is re-read for the new session")
        assertEquals("tok-owner-b", s.nodeA?.sessionToken, "node A's session is the NEW owner's")
        assertTrue(s.canRun, "the new owner's pair is usable")

        // The effect firing again for the same session (a recomposition) loads nothing.
        vm.sessionStarted()
        assertEquals(reads + 1, ownedReads)
    }

    // ── A request still out at logout writes nothing back (Codex, PR #116) ──

    @Test
    fun aRevokeStillInFlightAtLogoutDoesNotRestoreTheOldOwnersState() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeWithdraw(revoke = { gate.await(); RevokeGrantResponse(it, withdraws = "w-$it") })
        val vm = consentVm(fake)
        vm.revokeAToB()
        assertTrue(vm.state.value.isRevoking)

        vm.resetSession()
        val reads = ownedReads
        gate.complete(Unit) // the node answers the previous owner's revoke after they left
        val s = vm.state.value
        assertEquals(ConsentObjectsState(), s, "a suspended revoke resumed after logout and wrote the old session back")
        assertEquals(reads, ownedReads, "no re-read on behalf of a session that ended")
    }

    @Test
    fun aSetupStillInFlightAtLogoutDoesNotRestoreTheOldOwnersState() {
        val gate = CompletableDeferred<Unit>()
        val slow = FakePeering(grant = { _, _ -> gate.await(); PeeringResponse(grantAttestationId = "grant-new") })
        val vm = consentVm(FakeWithdraw(), granted = false, peering = slow)
        vm.runBilateralPeering()
        assertTrue(vm.state.value.isRunning)

        vm.resetSession()
        gate.complete(Unit)
        val s = vm.state.value
        assertEquals(ConsentObjectsState(), s, "a suspended set-up resumed after logout and wrote its grant into the next session")
    }

    // ── Node A's session survives the re-read (Codex, PR #116) ──────────

    @Test
    fun theReReadAfterARevokeKeepsNodeAsOwnSession() {
        val client = agentClient().apply { setAccessToken("tok-A" ) }
        val fake = FakeWithdraw()
        val vm = consentVm(fake, client = client)

        // Switched: the client now carries B's session. The revoke goes out as
        // A (PR #115) — and the re-read that follows must not rebuild A with B's.
        client.setAccessToken("tok-B")
        vm.revokeAToB()
        assertEquals("tok-A", vm.state.value.nodeA?.sessionToken, "loadNodes rebuilt node A with the ACTIVE node's token")
        assertEquals(listOf<String?>("tok-A", "tok-A"), fake.probeTokens, "the probe after the re-read authenticated to A as B")
    }

    // ── A new set-up is a new grant (Codex, PR #115) ─────────────────────

    @Test
    fun aNewSetupForgetsTheLastRevokeOutcomeAtItsStart() {
        // No fake for the peering here: the assertion is about the state the
        // moment the run STARTS, before any node has answered.
        val vm = consentVm(FakeWithdraw(), initial = afterARevoke, peering = FakePeering(grant = { _, _ -> throw IllegalStateException("node down") }))
        vm.runBilateralPeering()
        val s = vm.state.value
        assertNull(s.withdrawnBy, "\"Withdrawn\" from the last grant shown over the new set-up")
        assertTrue(s.remainingGrants.isEmpty())
        assertNull(s.revokeRefusalId)
        assertNull(s.revokeRefusalDetail)
    }

    @Test
    fun anAcceptedGrantCarriesNoPredecessorsOutcome() {
        val vm = consentVm(FakeWithdraw(), initial = afterARevoke)
        vm.runBilateralPeering()
        val s = vm.state.value
        assertEquals(GrantDirectionState.GRANTED, s.aToB)
        assertEquals("grant-new", s.aToBGrantId, "the new grant's row id is what a later revoke names")
        assertTrue(s.isRatified)
        assertNull(s.withdrawnBy)
        assertTrue(s.remainingGrants.isEmpty())
        assertNull(s.revokeRefusalId)
        assertNull(s.revokeRefusalDetail)
        assertTrue(s.canRevoke, "the fresh grant is revocable")
        assertNull(revokeNote(s))
    }

    // ── Node A's own session (Codex, PR #115) ────────────────────────────

    @Test
    fun aRevokeGoesOutAsNodeAsOwnSessionNotTheActiveNodes() {
        val client = agentClient().apply { setAccessToken("tok-A") }
        val fake = FakeWithdraw()
        val vm = consentVm(fake, client = client)
        assertEquals(listOf<String?>("tok-A"), fake.probeTokens, "the route probe is asked as node A")

        // A node switch: the client now carries another node's session. Node
        // A's profile still records the token minted for A.
        client.setAccessToken("tok-B")
        vm.revokeAToB()
        assertEquals(listOf<String?>("tok-A"), fake.revokeTokens, "the revoke must go out as the session recorded for node A")
        assertEquals(listOf(CIRISApiClient.LOCAL_NODE_URL to GRANT), fake.revokeCalls)
    }

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
