package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ContactsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.chat.pairRooms
import ai.ciris.mobile.shared.models.federation.InboxInvite
import ai.ciris.mobile.shared.models.federation.InviteAnswer
import ai.ciris.mobile.shared.models.federation.InviteInbox
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
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NODE = "http://node.invalid:4243"
private const val ALICE = "alice-fed-id"
private const val BOB = "bob-fed-id"
private const val ALICE_ROOM = "chat:pair:v1:aaaa"
private const val BOB_ROOM = "chat:pair:v1:bbbb"

/** The inbox and the contact list, as a 0.5.218 node answers them; records every decline. */
private class FakeInboxNode(
    var inbox: InviteInbox = InviteInbox(),
    var inboxError: NodeRefusal? = null,
    var declineError: NodeRefusal? = null,
) : ContactsApi {
    val declines = mutableListOf<Pair<String, String>>()
    override suspend fun listContacts(nodeUrl: String) = ContactListResponse(
        contacts = listOf(
            Contact(keyId = ALICE, chatCommunityId = ALICE_ROOM),
            Contact(keyId = BOB, chatCommunityId = BOB_ROOM),
        ),
        total = 2,
    )
    override suspend fun listPeers() = FederationPeerListResponse()
    override suspend fun addContact(nodeUrl: String, keyId: String): AddContactResponse = error("not used")
    override suspend fun contactCode(nodeUrl: String, nodes: String?): ContactCodeResponse = error("not used")
    override suspend fun announceThisDevice(nodeUrl: String): AnnounceOwnershipResponse = error("not used")
    override suspend fun pairRoomInvites(nodeUrl: String): InviteInbox {
        inboxError?.let { throw it }
        return inbox
    }
    override suspend fun declinePairRoomInvite(nodeUrl: String, proposalId: String): InviteAnswer {
        declines += nodeUrl to proposalId
        declineError?.let { throw it }
        inbox = InviteInbox(invites = inbox.invites.filterNot { it.proposalId == proposalId })
        return InviteAnswer(state = "declined", proposalId = proposalId, replyId = "reply-1")
    }
}

private fun pairInvite(id: String, room: String) = InboxInvite(
    proposalId = id, groupKind = "community", groupId = room, isPairRoom = true,
    role = "founder", proposerKeyId = "the-other-persons-NODE",
)

/**
 * CSD-005 / CSD-091: a pair-room invitation (ciris-server 0.5.218,
 * CIRISServer#706) is shown on its contact's People row, matched by the room
 * id — never by the proposer, which is the other person's node.
 */
class PairRoomInvitationTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm(node: FakeInboxNode) = ContactsViewModel(CIRISApiClient(baseUrl = "http://agent.invalid:8080"), { NODE }, node)

    @Test
    fun aPairRoomInvitationLandsOnTheContactWhoseRoomItIs() {
        val node = FakeInboxNode(
            inbox = InviteInbox(
                invites = listOf(
                    pairInvite("prop-alice", ALICE_ROOM),
                    // A household's invitation is CSD-106's inbox, not a conversation.
                    InboxInvite(proposalId = "prop-family", groupKind = "family", groupId = "family:x"),
                    // A pair room with someone who is not a contact: no row to put it on.
                    pairInvite("prop-stranger", "chat:pair:v1:ffff"),
                ),
            ),
        )
        val vm = vm(node)
        assertEquals(setOf(ALICE), vm.pairInvites.value.keys)
        assertEquals("prop-alice", vm.pairInvites.value[ALICE]?.proposalId)
    }

    @Test
    fun aRowMarkedAsAPairRoomButOutsideThePairPrefixIsNotAConversation() {
        val inbox = InviteInbox(
            invites = listOf(InboxInvite(proposalId = "p", groupId = "chat:room:v1:123", isPairRoom = true)),
        )
        assertTrue(inbox.pairRooms.isEmpty())
    }

    @Test
    fun aNodeOlderThan0_5_218HasNoInvitationsAndTheListStillShows() {
        val node = FakeInboxNode(inboxError = NodeRefusal(null, null, 404))
        val vm = vm(node)
        assertTrue(vm.pairInvites.value.isEmpty())
        assertEquals(2, vm.contacts.value.size, "an unread inbox never blanks the people")
        assertNull(vm.error.value)
    }

    @Test
    fun declineSendsTheProposalToTheNodeAndTheRowGoesBackToChat() {
        val node = FakeInboxNode(inbox = InviteInbox(invites = listOf(pairInvite("prop-bob", BOB_ROOM))))
        val vm = vm(node)
        assertEquals(setOf(BOB), vm.pairInvites.value.keys)
        vm.declinePairInvite(BOB)
        assertEquals(listOf(NODE to "prop-bob"), node.declines, "the decline names the invitation, at the node")
        assertTrue(vm.pairInvites.value.isEmpty())
        assertNull(vm.declining.value)
    }

    @Test
    fun aRefusedDeclineIsSaidByIdAndTheInvitationStays() {
        val node = FakeInboxNode(
            inbox = InviteInbox(invites = listOf(pairInvite("prop-bob", BOB_ROOM))),
            declineError = NodeRefusal("membership.invite_expired", "That invitation has expired.", 410),
        )
        val vm = vm(node)
        vm.declinePairInvite(BOB)
        assertEquals("membership.invite_expired", vm.declineRefusal.value?.reasonId)
        assertEquals(setOf(BOB), vm.pairInvites.value.keys)
    }

    @Test
    fun theInboxWireShapeIsTheServersReadThroughCsd106sOneModel() {
        // `src/membership_invites.rs::inbox` at 53d1ffb5, field for field.
        val json = Json { ignoreUnknownKeys = true }
        val inbox = json.decodeFromString(
            InviteInbox.serializer(),
            """{"invitee_key_id":"me","invites":[{"proposal_id":"p1","group_kind":"community","group_id":"chat:pair:v1:ab",""" +
                """"group_name":null,"is_pair_room":true,"role":"founder","proposer_key_id":"node-x",""" +
                """"proposed_at":"2026-10-01T00:00:00Z","expires_at":"2026-10-15T00:00:00Z"}]}""",
        )
        assertEquals(listOf("p1"), inbox.pairRooms.map { it.proposalId })
        assertEquals("node-x", inbox.pairRooms.single().proposerKeyId)
    }
}
