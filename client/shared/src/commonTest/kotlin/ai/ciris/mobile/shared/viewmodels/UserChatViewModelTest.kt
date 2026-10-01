package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ChatApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.chat.CegChatMessage
import ai.ciris.mobile.shared.models.chat.ChatCommunity
import ai.ciris.mobile.shared.models.chat.ChatTranscript
import ai.ciris.mobile.shared.models.chat.PairPhase
import ai.ciris.mobile.shared.models.chat.pairPhase
import ai.ciris.mobile.shared.models.chat.SendChatMessageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AGENT_URL = "http://agent.invalid:8080"
private const val NODE_URL = "http://node.invalid:4243"
private const val PAIR = "pair-community-1"
private const val ROOM = "room-community-9"

/** A node that answers what it is told to and records WHERE each chat call went. */
private class FakeChat(
    var transcriptRefusal: NodeRefusal? = null,
    /** Runs while `open` is in flight — a test switches the active node here. */
    var onOpen: () -> Unit = {},
    /** What `POST /v1/chat` answers, in order; the last answer repeats. Null: the open two-member room. */
    var openAnswers: List<ChatCommunity>? = null,
) : ChatApi {
    val openCalls = mutableListOf<Pair<String, String>>()
    val transcriptCalls = mutableListOf<Pair<String, String>>()
    val sendCalls = mutableListOf<Triple<String, String, String>>()

    override suspend fun open(nodeUrl: String, contactKeyId: String): ChatCommunity {
        openCalls += nodeUrl to contactKeyId
        onOpen()
        openAnswers?.let { answers -> return answers[minOf(openCalls.size - 1, answers.lastIndex)] }
        return ChatCommunity(communityId = PAIR, memberKeyIds = listOf("me", contactKeyId))
    }

    override suspend fun transcript(nodeUrl: String, communityId: String): ChatTranscript {
        transcriptCalls += nodeUrl to communityId
        transcriptRefusal?.let { throw it }
        return ChatTranscript(
            communityId = communityId,
            messages = listOf(CegChatMessage(attestationId = "att-1", body = "hi", communityId = communityId)),
            total = 1,
            ready = true,
        )
    }

    override suspend fun send(nodeUrl: String, communityId: String, body: String): SendChatMessageResult {
        sendCalls += Triple(nodeUrl, communityId, body)
        return SendChatMessageResult(attestationId = "att-2", communityId = communityId)
    }
}

/**
 * CSD-091: the chat routes are the NODE's (`src/contacts_chat.rs`), and a room
 * of more than two is entered by its id — `GET/POST /v1/chat/{id}/messages`
 * serve N-member rooms since CIRISServer#594 — never through `POST /v1/chat`,
 * which is pair-only by construction.
 */
class UserChatViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    /** The real client with the AGENT at its api base, as on a with-AI install; the fake answers every call. */
    private fun vm(chat: FakeChat) = UserChatViewModel(CIRISApiClient(baseUrl = AGENT_URL), { NODE_URL }, chat)

    @Test
    fun aPairRoomIsOpenedThroughPostChatAtTheNodeAndReadFromTheSameNode() {
        val chat = FakeChat()
        val vm = vm(chat)
        vm.enter(PAIR, "peer-1")
        assertEquals(listOf(NODE_URL to "peer-1"), chat.openCalls, "the open goes to the node, not the agent front door")
        assertEquals(listOf(NODE_URL to PAIR), chat.transcriptCalls)
        assertEquals(PAIR, vm.community.value?.communityId)
        assertEquals(1, vm.messages.value.size)
        assertTrue(vm.transcriptLoaded.value)
    }

    @Test
    fun aRoomOfMoreThanTwoIsEnteredByItsIdAndNeverThroughPostChat() {
        val chat = FakeChat()
        val vm = vm(chat)
        vm.enterRoom(ROOM, name = "Garden club", memberCount = 5)
        assertTrue(chat.openCalls.isEmpty(), "POST /v1/chat is pair-only; a room is not opened through it")
        assertEquals(listOf(NODE_URL to ROOM), chat.transcriptCalls)
        val c = vm.community.value
        assertEquals(ROOM, c?.communityId)
        assertEquals("Garden club", c?.communityName)
        assertEquals(5, vm.memberCount.value, "the header's count comes from the room row, since the transcript carries no roster")
        assertEquals(1, vm.messages.value.size)
    }

    @Test
    fun aSendFromARoomAddressesThatRoomAtTheNode() {
        val chat = FakeChat()
        val vm = vm(chat)
        vm.enterRoom(ROOM, name = "", memberCount = 3)
        vm.setDraft("hello room")
        vm.send()
        assertEquals(listOf(Triple(NODE_URL, ROOM, "hello room")), chat.sendCalls)
        assertEquals("", vm.draft.value, "a landed send clears the draft")
    }

    @Test
    fun aRefusedRoomReadIsARefusalNotAnEmptyRoom() {
        val chat = FakeChat(transcriptRefusal = NodeRefusal(reasonId = "chat.not_a_member", detail = "not on the roster", statusCode = 403))
        val vm = vm(chat)
        vm.enterRoom(ROOM, name = "", memberCount = 3)
        assertEquals("chat.not_a_member", vm.refusalReasonId.value)
        assertTrue(vm.messages.value.isEmpty())
    }

    @Test
    fun enteringAPairAfterARoomForgetsTheRoomsCount() {
        val chat = FakeChat()
        val vm = vm(chat)
        vm.enterRoom(ROOM, name = "", memberCount = 7)
        vm.enter(PAIR, "peer-1")
        assertNull(vm.memberCount.value, "a pair's count is the roster POST /v1/chat returned, never a stale room's")
        assertEquals(PAIR, vm.community.value?.communityId)
    }

    @Test
    fun aRoomIsReadAndWrittenOnTheNodeItWasOpenedOn() {
        // Codex, PR #126: the active node switches while `open()` is in flight.
        // The room exists on A; reading or sending it on B addresses a room
        // that is not there, or worse, one with the same id on another node.
        val nodeA = "http://node-a.invalid:4243"
        val nodeB = "http://node-b.invalid:4243"
        var active = nodeA
        val chat = FakeChat(onOpen = { active = nodeB })
        val vm = UserChatViewModel(CIRISApiClient(baseUrl = AGENT_URL), { active }, chat)
        vm.enter(PAIR, "peer-1")
        assertEquals(listOf(nodeA to "peer-1"), chat.openCalls)
        assertEquals(listOf(nodeA to PAIR), chat.transcriptCalls, "the transcript is read where the room was opened")

        vm.refresh()
        vm.setDraft("hello")
        vm.send()
        assertEquals(listOf(nodeA, nodeA, nodeA), chat.transcriptCalls.map { it.first }, "refresh and the post-send read stay on A")
        assertEquals(listOf(Triple(nodeA, PAIR, "hello")), chat.sendCalls, "the send goes to the room's node, not the newly active one")
    }

    // ── The pair room by invitation (ciris-server 0.5.218, CIRISServer#706) ──

    private fun answer(state: String?, members: List<String>, proposal: String? = null) =
        ChatCommunity(communityId = PAIR, memberKeyIds = members, state = state, proposalId = proposal)

    @Test
    fun theOpenerOfAnInvitedRoomWaitsForThemAndReadsNoTranscript() {
        val chat = FakeChat(openAnswers = listOf(answer("invited", listOf("me"), "prop-1")))
        val vm = vm(chat)
        vm.enter(PAIR, "peer-1")
        assertEquals(PairPhase.WAITING_FOR_THEM, vm.pairPhase.value)
        assertEquals("prop-1", vm.community.value?.proposalId)
        assertTrue(chat.transcriptCalls.isEmpty(), "a room nobody else is seated in has no conversation to read")
        assertFalse(vm.transcriptLoaded.value, "waiting is not 'loaded and empty'")
        assertNull(vm.refusalReasonId.value, "waiting is not a refusal")
    }

    @Test
    fun aWaitingRoomCannotSend() {
        val chat = FakeChat(openAnswers = listOf(answer("invited", listOf("me"), "prop-1")))
        val vm = vm(chat)
        vm.enter(PAIR, "peer-1")
        vm.setDraft("anyone there?")
        vm.send()
        assertTrue(chat.sendCalls.isEmpty(), "nothing is sent into a room the other person has not joined")
    }

    @Test
    fun theRoomBecomesANormalRoomWhenTheyAccept() {
        val chat = FakeChat(
            openAnswers = listOf(
                answer("invited", listOf("me"), "prop-1"),
                answer("open", listOf("me", "peer-1"), "prop-1"),
            ),
        )
        val vm = vm(chat)
        vm.enter(PAIR, "peer-1")
        assertEquals(PairPhase.WAITING_FOR_THEM, vm.pairPhase.value)
        vm.recheck()
        assertEquals(listOf(NODE_URL to "peer-1", NODE_URL to "peer-1"), chat.openCalls, "the re-check asks the room's node")
        assertEquals(PairPhase.OPEN, vm.pairPhase.value)
        assertEquals(listOf(NODE_URL to PAIR), chat.transcriptCalls, "an open room reads its transcript")
        assertEquals(1, vm.messages.value.size)
    }

    @Test
    fun refreshOnAWaitingRoomAsksTheNodeAgainInsteadOfReadingATranscript() {
        val chat = FakeChat(openAnswers = listOf(answer("invited", listOf("me"))))
        val vm = vm(chat)
        vm.enter(PAIR, "peer-1")
        vm.refresh()
        assertEquals(2, chat.openCalls.size)
        assertTrue(chat.transcriptCalls.isEmpty())
    }

    @Test
    fun theInviteeWhoAcceptedIsJoiningEvenWhenTheNextAnswerSaysAwaitingInvitation() {
        // At 0.5.218 the joiner's POST /v1/chat answers `accepted` once, then
        // `awaiting_invitation` (the invitation is no longer pending) until the
        // opener's widening replicates back.
        val chat = FakeChat(
            openAnswers = listOf(
                answer("accepted", emptyList(), "prop-9"),
                answer("awaiting_invitation", listOf("peer-1")),
                answer("awaiting_invitation", listOf("me", "peer-1")),
            ),
        )
        val vm = vm(chat)
        vm.enter(PAIR, "peer-1")
        assertEquals(PairPhase.JOINING, vm.pairPhase.value)
        vm.recheck()
        assertEquals(PairPhase.JOINING, vm.pairPhase.value, "a person who said yes is never told the other side has not opened it")
        vm.recheck()
        assertEquals(PairPhase.OPEN, vm.pairPhase.value, "seated on the roster is open, whatever `state` says")
        assertEquals(listOf(NODE_URL to PAIR), chat.transcriptCalls)
    }

    @Test
    fun aJoinerWithNoInvitationYetWaitsForTheirSide() {
        val chat = FakeChat(openAnswers = listOf(answer("awaiting_invitation", emptyList())))
        val vm = vm(chat)
        vm.enter(PAIR, "peer-1")
        assertEquals(PairPhase.WAITING_FOR_THEIR_SIDE, vm.pairPhase.value)
        assertTrue(chat.transcriptCalls.isEmpty())
    }

    @Test
    fun a0_5_217AnswerWithNoStateBehavesExactlyAsBefore() {
        // A 0.5.217 node: no `state`, no `proposal_id`. Even a one-member
        // roster opens the room and reads the transcript, as it always did.
        val chat = FakeChat(openAnswers = listOf(ChatCommunity(communityId = PAIR, memberKeyIds = listOf("me"))))
        val vm = vm(chat)
        vm.enter(PAIR, "peer-1")
        assertEquals(PairPhase.OPEN, vm.pairPhase.value)
        assertEquals(listOf(NODE_URL to PAIR), chat.transcriptCalls)
        vm.setDraft("hi")
        vm.send()
        assertEquals(1, chat.sendCalls.size)
    }

    @Test
    fun enteringAnotherRoomDoesNotInheritTheLastRoomsWait() {
        val chat = FakeChat(openAnswers = listOf(answer("invited", listOf("me"))))
        val vm = vm(chat)
        vm.enter(PAIR, "peer-1")
        vm.enterRoom(ROOM, name = "Garden club", memberCount = 4)
        assertEquals(PairPhase.OPEN, vm.pairPhase.value)
    }

    @Test
    fun pairPhaseReadsEveryStateAndFallsBackToOpenOnAnUnknownWord() {
        fun c(state: String?, n: Int) = ChatCommunity(communityId = PAIR, memberKeyIds = List(n) { "k$it" }, state = state)
        assertEquals(PairPhase.OPEN, c(null, 1).pairPhase())
        assertEquals(PairPhase.OPEN, c("open", 2).pairPhase())
        assertEquals(PairPhase.WAITING_FOR_THEM, c("invited", 1).pairPhase())
        assertEquals(PairPhase.JOINING, c("accepted", 0).pairPhase())
        assertEquals(PairPhase.WAITING_FOR_THEIR_SIDE, c("awaiting_invitation", 0).pairPhase())
        assertEquals(PairPhase.JOINING, c("awaiting_invitation", 1).pairPhase(acceptedHere = true))
        assertEquals(PairPhase.OPEN, c("awaiting_invitation", 2).pairPhase(), "seated is open")
        assertEquals(PairPhase.OPEN, c("some_future_word", 1).pairPhase(), "an undocumented word must not lock anyone out")
    }
}
