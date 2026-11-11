package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ChatApi
import ai.ciris.mobile.shared.api.DriveApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.chat.CegChatMessage
import ai.ciris.mobile.shared.models.chat.ChatCommunity
import ai.ciris.mobile.shared.models.chat.ChatTranscript
import ai.ciris.mobile.shared.models.chat.RoomModeration
import ai.ciris.mobile.shared.models.chat.SendChatMessageResult
import ai.ciris.mobile.shared.models.chat.unopenedText
import ai.ciris.mobile.shared.models.drive.DriveEntry
import ai.ciris.mobile.shared.models.drive.DriveListing
import ai.ciris.mobile.shared.models.drive.FileWrite
import ai.ciris.mobile.shared.models.drive.FileWritten
import ai.ciris.mobile.shared.models.drive.MediaPolicy
import ai.ciris.mobile.shared.models.drive.NoteListing
import ai.ciris.mobile.shared.models.drive.OpenedFile
import androidx.lifecycle.ViewModel
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Attachments and reveal** (server 0.5.243, CSD-091, CSD-065, CSD-007). Attach
 * is offered only by a node that says it takes one, a caption may be blank, and
 * a reveal names the flagged item, not the row that shows it.
 */
private class AttachChat(private val accepts: Boolean, private val refuse: NodeRefusal? = null) : ChatApi {
    val sends = mutableListOf<String>()
    val reveals = mutableListOf<String>()
    var reads = 0
    override suspend fun open(nodeUrl: String, contactKeyId: String) = ChatCommunity(communityId = "room-1")
    override suspend fun transcript(nodeUrl: String, communityId: String): ChatTranscript {
        reads++
        return ChatTranscript(communityId = communityId, acceptsAttachments = accepts, ready = true)
    }
    override suspend fun send(nodeUrl: String, communityId: String, body: String): SendChatMessageResult {
        sends += "text:$body"
        return SendChatMessageResult(attestationId = "a", communityId = communityId)
    }
    override suspend fun sendAttachment(nodeUrl: String, communityId: String, body: String, attachmentId: String): SendChatMessageResult {
        refuse?.let { throw it }
        sends += "file:$attachmentId:$body"
        return SendChatMessageResult(attestationId = "a", communityId = communityId)
    }
    override suspend fun reveal(nodeUrl: String, communityId: String, itemId: String) {
        refuse?.let { throw it }
        reveals += itemId
    }
    val decisions = mutableListOf<String>()
    override suspend fun moderateItem(nodeUrl: String, communityId: String, itemId: String, contentClass: String?) {
        refuse?.let { throw it }
        decisions += "$communityId $itemId ${contentClass ?: "clear"}"
    }
    override suspend fun appealItem(nodeUrl: String, communityId: String, itemId: String, grounds: String?, outcome: String?) {
        refuse?.let { throw it }
        decisions += "$communityId $itemId grounds=$grounds outcome=$outcome"
    }
}

private class RevealDrive : DriveApi {
    val reveals = mutableListOf<String>()
    override suspend fun readDrive(cohort: String?, roomId: String?, limit: Int, after: String?) =
        DriveListing(viewerDuties = listOf("moderate"))
    override suspend fun readFile(attestationId: String, cohort: String, roomId: String?): OpenedFile = error("unused")
    override suspend fun writeFile(write: FileWrite): FileWritten = error("unused")
    override suspend fun readNotes(): NoteListing = error("unused")
    override suspend fun writeNote(body: String) = error("unused")
    override suspend fun readMediaPolicy(): MediaPolicy = throw NodeRefusal(null, null, 404)
    override suspend fun readCustody(attestationId: String, cohort: String, roomId: String?) = error("unused")
    override suspend fun revealItem(roomId: String, itemId: String) { reveals += "$roomId $itemId" }
    val decisions = mutableListOf<String>()
    var refuse: NodeRefusal? = null
    override suspend fun moderateItem(roomId: String, itemId: String, contentClass: String?) {
        refuse?.let { throw it }
        decisions += "$roomId $itemId ${contentClass ?: "clear"}"
    }
    override suspend fun appealItem(roomId: String, itemId: String, grounds: String?, outcome: String?) {
        refuse?.let { throw it }
        decisions += "$roomId $itemId grounds=$grounds outcome=$outcome"
    }
}

class ChatAttachmentsAndRevealTest {
    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain() }

    private fun room(chat: ChatApi) =
        UserChatViewModel(CIRISApiClient(baseUrl = "http://agent.invalid"), { "http://node.invalid" }, chat).also { made += it; it.enterRoom("room-1", "Garden", 3) }

    @Test
    fun onlyANodeThatSaysSoIsOfferedAttach() {
        assertTrue(room(AttachChat(accepts = true)).acceptsAttachments.value)
        assertFalse(room(AttachChat(accepts = false)).acceptsAttachments.value, "an older node would drop the file")
    }

    @Test
    fun aFileGoesOutWithABlankCaptionAndClearsAfterTheSend() {
        val chat = AttachChat(accepts = true)
        val vm = room(chat)
        vm.attach(AttachedFile("file-1", "plan.pdf"))
        vm.send()
        assertEquals(listOf("file:file-1:"), chat.sends)
        assertNull(vm.attached.value, "the next message must not carry it again")
    }

    @Test
    fun aRefusedAttachmentKeepsTheFileAndNamesTheRefusal() {
        val chat = AttachChat(accepts = true, refuse = NodeRefusal("chat.attachment_not_in_room", "share it first", 409))
        val vm = room(chat)
        vm.attach(AttachedFile("file-1", null))
        vm.send()
        assertEquals("chat.attachment_not_in_room", vm.refusalReasonId.value)
        assertEquals("file-1", vm.attached.value?.attestationId)
    }

    @Test
    fun aRevealNamesTheItemAndReadsTheTranscriptAgain() {
        val chat = AttachChat(accepts = true)
        val vm = room(chat)
        val before = chat.reads
        vm.reveal("item-9")
        assertEquals(listOf("item-9"), chat.reveals)
        assertEquals(before + 1, chat.reads)
    }

    @Test
    fun aRefusedRevealIsShownAndLeavesTheTranscriptAlone() {
        val chat = AttachChat(accepts = true, refuse = NodeRefusal("moderation.not_flagged", "nothing to reveal", 409))
        val vm = room(chat)
        val before = chat.reads
        vm.reveal("item-9")
        assertEquals("moderation.not_flagged", vm.refusalReasonId.value)
        assertEquals(before, chat.reads)
    }

    @Test
    fun aModeratorFlagsAndClearsARoomFileByItsOwnId() {
        val drive = RevealDrive()
        val vm = FilesViewModel(drive, FilesCohort.COMMUNITY, room = "room-7").also { made += it }
        val file = DriveEntry("community", "room-7", "row-2", "a", "2026-11-01T00:00:00Z", bytes = "here")
        vm.flagFile(file, "reported")
        assertEquals(FileActState.Kind.FLAG, assertIs<FileActState.Done>(vm.act.value).kind)
        vm.dismissAct()
        vm.clearFileFlag(file)
        assertIs<FileActState.Idle>(vm.act.value, "a file the node shows no flag on has nothing to clear, so nothing is sent")
        // The flag sits on an earlier version: clear names THAT version, or the node answers nothing_to_clear.
        val flagged = file.copy(moderation = RoomModeration(flag = "reported", itemAttestationId = "row-1", withheld = true))
        vm.clearFileFlag(flagged)
        assertEquals(FileActState.Kind.CLEAR_FLAG, assertIs<FileActState.Done>(vm.act.value).kind)
        assertEquals(listOf("room-7 row-2 reported", "room-7 row-1 clear"), drive.decisions)
        vm.dismissAct()
        drive.refuse = NodeRefusal("moderation.nothing_to_clear", "nothing to clear", 409)
        vm.clearFileFlag(flagged)
        assertEquals("moderation.nothing_to_clear", assertIs<FileActState.Refused>(vm.act.value).refusal.reasonId)
    }

    @Test
    fun aRoomFileRevealGoesToTheRoomByItsFlaggedItem() {
        val drive = RevealDrive()
        val vm = FilesViewModel(drive, FilesCohort.COMMUNITY, room = "room-7").also { made += it }
        vm.refresh()
        assertEquals(listOf("moderate"), vm.viewerDuties.value)
        vm.revealFile(
            DriveEntry(
                "community", "room-7", "row-2", "a", "2026-11-01T00:00:00Z", bytes = "here",
                moderation = RoomModeration(flag = "reported", itemAttestationId = "item-1", withheld = true),
            ),
        )
        assertEquals(listOf("room-7 item-1"), drive.reveals)
        assertIs<FileActState.Done>(vm.act.value)
    }

    @Test
    fun aWithheldMessageReadsAsWithheldAndItsWireShapeDecodes() {
        val json = Json { ignoreUnknownKeys = true }
        val m = json.decodeFromString(
            CegChatMessage.serializer(),
            """{"attestation_id":"m1","body":"","unopened_reason":"withheld_by_moderation",
                "moderation":{"flag":"infohazard","item_attestation_id":"i1","flag_attestation_id":"f1",
                "flagged_by":"mod","withheld":true,"revealed":false,"reconsideration":"filed"}}""",
        )
        assertEquals("mobile.chat_unopened_withheld_by_moderation", unopenedText(m)?.key)
        assertEquals("filed", m.moderation?.reconsideration)
        assertTrue(m.moderation?.withheld == true)
    }

    /** 0.5.246: a moderator's flag and clear go to the item's own routes, and the transcript is read again for the node's view. */
    @Test
    fun aModeratorsFlagAndClearGoToTheItemAndTheTranscriptIsReadAgain() {
        val chat = AttachChat(accepts = true)
        val vm = room(chat)
        val before = chat.reads
        vm.flagItem("msg-9", "infohazard")
        assertEquals(listOf("room-1 msg-9 infohazard"), chat.decisions)
        assertEquals(before + 1, chat.reads, "the note under the message is the node's, so it is read again")
        vm.clearFlag("msg-9")
        assertEquals(listOf("room-1 msg-9 infohazard", "room-1 msg-9 clear"), chat.decisions)
        assertEquals(before + 2, chat.reads)
        assertNull(vm.refusalReasonId.value)
    }

    /** An appeal files with grounds and a ruling carries an outcome, never both, and each re-reads the transcript. */
    @Test
    fun anAppealFilesWithGroundsAndARulingCarriesAnOutcome() {
        val chat = AttachChat(accepts = true)
        val vm = room(chat)
        val before = chat.reads
        vm.appealFlag("msg-1", "new_evidence")
        vm.ruleOnAppeal("msg-1", "reversed")
        assertEquals(listOf("room-1 msg-1 grounds=new_evidence outcome=null", "room-1 msg-1 grounds=null outcome=reversed"), chat.decisions)
        assertEquals(before + 2, chat.reads)
    }

    @Test
    fun aFileAppealAndRulingNameTheFlaggedVersionAndARefusalIsNamed() {
        val drive = RevealDrive()
        val vm = FilesViewModel(drive, FilesCohort.COMMUNITY, room = "room-7").also { made += it }
        val file = DriveEntry(
            "community", "room-7", "row-2", "a", "2026-11-01T00:00:00Z", bytes = "here",
            moderation = RoomModeration(flag = "reported", itemAttestationId = "row-1", withheld = true),
        )
        vm.appealFileFlag(file, "procedural_error")
        assertEquals(FileActState.Kind.APPEAL, assertIs<FileActState.Done>(vm.act.value).kind)
        vm.dismissAct()
        vm.ruleOnFileAppeal(file, "upheld")
        assertEquals(FileActState.Kind.RULE, assertIs<FileActState.Done>(vm.act.value).kind)
        assertEquals(listOf("room-7 row-1 grounds=procedural_error outcome=null", "room-7 row-1 grounds=null outcome=upheld"), drive.decisions)
        vm.dismissAct()
        drive.refuse = NodeRefusal("moderation.no_standing", "not yours to appeal", 403)
        vm.appealFileFlag(file, "new_evidence")
        assertEquals("moderation.no_standing", assertIs<FileActState.Refused>(vm.act.value).refusal.reasonId)
    }

    @Test
    fun aRefusedFlagIsNamedAndNothingIsReadAgain() {
        val chat = AttachChat(accepts = true, refuse = NodeRefusal("moderation.not_a_moderator", "only a moderator", 403))
        val vm = room(chat)
        val before = chat.reads
        vm.flagItem("msg-9", "reported")
        assertEquals("moderation.not_a_moderator", vm.refusalReasonId.value)
        assertTrue(chat.decisions.isEmpty())
        assertEquals(before, chat.reads, "a refusal changed nothing, so there is nothing new to read")
    }
}
