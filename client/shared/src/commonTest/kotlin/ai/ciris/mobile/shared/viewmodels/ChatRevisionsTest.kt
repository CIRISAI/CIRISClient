package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ChatApi
import ai.ciris.mobile.shared.models.chat.CegChatMessage
import ai.ciris.mobile.shared.models.chat.ChatCommunity
import ai.ciris.mobile.shared.models.chat.ChatHistoryStep
import ai.ciris.mobile.shared.models.chat.ChatMessageHistory
import ai.ciris.mobile.shared.models.chat.ChatTranscript
import ai.ciris.mobile.shared.models.chat.SendChatMessageResult
import ai.ciris.mobile.shared.ui.components.AttHistory
import ai.ciris.mobile.shared.ui.components.AttKind
import ai.ciris.mobile.shared.ui.components.AttOp
import ai.ciris.mobile.shared.ui.components.AttStatus
import ai.ciris.mobile.shared.ui.components.Attestation
import ai.ciris.mobile.shared.ui.components.HistoryStep
import ai.ciris.mobile.shared.ui.components.ViewerAuthority
import ai.ciris.mobile.shared.ui.components.disabledReasonKey
import ai.ciris.mobile.shared.ui.components.enabledFor
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **History is the lifecycle, and the revision ops are the node's to grant.**
 *
 * From the steward's desktop test of live chat (2026-10-29): History only
 * toggled the details, and Replace / Withdraw / Recant were disabled with a
 * hard-coded "a sign-in session cannot produce one". The node now signs as the
 * author with the person key it holds, and says per row what this viewer may do
 * (`viewer_ops`).
 */
private class RevisionChat(
    var history: ChatMessageHistory? = null,
) : ChatApi {
    val reviseCalls = mutableListOf<List<String?>>()
    val sendCalls = mutableListOf<String>()

    override suspend fun open(nodeUrl: String, contactKeyId: String) =
        ChatCommunity(communityId = "room-1", memberKeyIds = listOf("me", contactKeyId))

    override suspend fun transcript(nodeUrl: String, communityId: String) =
        ChatTranscript(communityId = communityId, messages = listOf(MINE), total = 1, ready = true)

    override suspend fun send(nodeUrl: String, communityId: String, body: String): SendChatMessageResult {
        sendCalls += body
        return SendChatMessageResult(attestationId = "att-new", communityId = communityId)
    }

    override suspend fun history(nodeUrl: String, communityId: String, attestationId: String) = history

    override suspend fun revise(
        nodeUrl: String,
        communityId: String,
        attestationId: String,
        op: String,
        body: String?,
        reason: String?,
    ): String {
        reviseCalls += listOf(attestationId, op, body, reason)
        return "att-rev"
    }

    companion object {
        val MINE = CegChatMessage(
            attestationId = "att-1",
            body = "hello",
            communityId = "room-1",
            mine = true,
            viewerOps = listOf("supersede", "withdraw", "recant"),
        )
    }
}

class ChatRevisionsTest {
    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain() }

    private fun vm(chat: RevisionChat) =
        UserChatViewModel(CIRISApiClient(baseUrl = "http://agent.invalid"), { "http://node.invalid" }, chat)
            .also { made += it; it.enter("room-1", "peer") }

    private fun message(allowed: Set<AttOp>?) =
        Attestation(id = "att-1", kind = AttKind.Message, status = AttStatus.Active, badge = "MSG", allowedOps = allowed)

    @Test
    fun theRevisionOpsAreExactlyWhatTheNodeGrantsThisViewer() {
        val viewer = ViewerAuthority(isHolder = true)
        val all = message(setOf(AttOp.Supersede, AttOp.Withdraw, AttOp.Recant))
        listOf(AttOp.Supersede, AttOp.Withdraw, AttOp.Recant).forEach {
            assertTrue(it.enabledFor(all, viewer), "$it granted by viewer_ops")
            assertNull(it.disabledReasonKey(all))
        }
        val someoneElses = message(emptySet())
        listOf(AttOp.Supersede, AttOp.Withdraw, AttOp.Recant).forEach {
            assertFalse(it.enabledFor(someoneElses, viewer), "$it not granted: not the author, or not live")
            assertEquals("mobile.chat_op_not_permitted", it.disabledReasonKey(someoneElses))
        }
        val oldNode = message(null)
        listOf(AttOp.Supersede, AttOp.Withdraw, AttOp.Recant).forEach {
            assertFalse(it.enabledFor(oldNode, viewer))
            assertEquals("mobile.chat_op_no_route", it.disabledReasonKey(oldNode), "said only when the node gives no judgement")
        }
        // A canonical's ops are unchanged.
        val canonical = Attestation(id = "c", kind = AttKind.Canonical, status = AttStatus.Active, badge = "C")
        assertTrue(AttOp.Withdraw.enabledFor(canonical, viewer))
        assertFalse(AttOp.Recant.enabledFor(canonical, viewer))
    }

    @Test
    fun historyShowsTheNodesChainNotTheDetails() {
        val chain = ChatMessageHistory(
            listOf(
                ChatHistoryStep("att-1", "post", "node-key", "me", "2026-10-29T10:00:00Z", "hello"),
                ChatHistoryStep("att-2", "supersede", "node-key", "me", "2026-10-29T10:01:00Z", "hello, world"),
                ChatHistoryStep("att-3", "withdraw", "node-key", "me", "2026-10-29T10:02:00Z", reason = "sent_in_error"),
            ),
        )
        val vm = vm(RevisionChat(history = chain))
        vm.openHistory(RevisionChat.MINE)
        val h = assertIs<AttHistory.Loaded>(vm.history.value)
        assertTrue(h.complete)
        assertEquals(listOf(HistoryStep.Original, HistoryStep.Supersede, HistoryStep.Withdraw), h.entries.map { it.step })
        assertEquals("2026-10-29T10:02:00Z", h.entries[2].at)
        vm.closeHistory()
        assertNull(vm.history.value)
    }

    @Test
    fun anOlderNodeGivesTheRowsOwnRecordMarkedIncomplete() {
        val vm = vm(RevisionChat(history = null))
        val withdrawn = RevisionChat.MINE.copy(status = CegChatMessage.STATUS_WITHDRAWN, statusAttestationId = "att-w")
        vm.openHistory(withdrawn)
        val h = assertIs<AttHistory.Loaded>(vm.history.value)
        assertFalse(h.complete, "a row's own record is never presented as the whole chain")
        assertEquals(listOf(HistoryStep.Original, HistoryStep.Withdraw), h.entries.map { it.step })
        assertEquals("att-w", h.entries[1].attestationId)
        assertNull(h.entries[1].at, "the row does not carry the withdrawal's time")
    }

    @Test
    fun replacingSendsASupersedeNotANewMessage() {
        val chat = RevisionChat()
        val vm = vm(chat)
        vm.startEdit(RevisionChat.MINE)
        assertEquals("hello", vm.draft.value, "the composer takes the message's text")
        vm.setDraft("hello, world")
        vm.send()
        assertEquals(listOf(listOf<String?>("att-1", "supersede", "hello, world", null)), chat.reviseCalls)
        assertTrue(chat.sendCalls.isEmpty(), "a replacement is never a second message")
        assertNull(vm.editing.value)
        assertEquals("", vm.draft.value)
    }

    @Test
    fun withdrawAndRecantSendTheChosenReasonOrNone() {
        val chat = RevisionChat()
        val vm = vm(chat)
        vm.revise(RevisionChat.MINE, "withdraw", "sent_in_error")
        vm.revise(RevisionChat.MINE, "recant")
        assertEquals(
            listOf(listOf<String?>("att-1", "withdraw", null, "sent_in_error"), listOf<String?>("att-1", "recant", null, null)),
            chat.reviseCalls,
        )
    }

    @Test
    fun cancellingAnEditClearsIt() {
        val vm = vm(RevisionChat())
        vm.startEdit(RevisionChat.MINE)
        vm.cancelEdit()
        assertNull(vm.editing.value)
        assertEquals("", vm.draft.value)
    }
}
