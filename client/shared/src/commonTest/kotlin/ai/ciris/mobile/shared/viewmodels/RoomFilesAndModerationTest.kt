package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DriveApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.chat.CegChatMessage
import ai.ciris.mobile.shared.models.drive.DriveEntry
import ai.ciris.mobile.shared.models.drive.DriveListing
import ai.ciris.mobile.shared.models.drive.FileWrite
import ai.ciris.mobile.shared.models.drive.FileWritten
import ai.ciris.mobile.shared.models.drive.MediaPolicy
import ai.ciris.mobile.shared.models.drive.NoteListing
import ai.ciris.mobile.shared.models.drive.OpenedFile
import ai.ciris.mobile.shared.ui.screens.ModerationLabels
import ai.ciris.mobile.shared.ui.screens.chatModerationOps
import ai.ciris.mobile.shared.ui.screens.isModerator
import androidx.lifecycle.ViewModel
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

/**
 * **Files in rooms, and moderation under the item menu** (CSD-007, CSD-091,
 * CSD-065). A room's panel lists exactly that room; an author's rename, move
 * and withdraw reach the node as asked; a message's moderation items act on the
 * AUTHOR and are unavailable, with a reason, to a non-moderator; reporting has
 * no route yet and says so.
 */
private class RoomDrive(private val refuse: NodeRefusal? = null) : DriveApi {
    val reads = mutableListOf<Pair<String?, String?>>()
    val acts = mutableListOf<String>()
    val writes = mutableListOf<FileWrite>()
    override suspend fun readDrive(cohort: String?, roomId: String?, limit: Int, after: String?): DriveListing {
        reads += cohort to roomId
        return DriveListing(entries = listOf(ENTRY))
    }
    override suspend fun readFile(attestationId: String, cohort: String, roomId: String?): OpenedFile = error("unused")
    override suspend fun writeFile(write: FileWrite): FileWritten { writes += write; error("stop") }
    override suspend fun readNotes(): NoteListing = error("unused")
    override suspend fun writeNote(body: String) = error("unused")
    override suspend fun readMediaPolicy(): MediaPolicy = throw NodeRefusal(null, null, 404)
    override suspend fun readCustody(attestationId: String, cohort: String, roomId: String?) = error("unused")
    override suspend fun renameFile(attestationId: String, cohort: String, roomId: String?, filename: String) {
        refuse?.let { throw it }; acts += "rename $attestationId $cohort $roomId $filename"
    }
    override suspend fun moveFile(attestationId: String, cohort: String, roomId: String?, toCohort: String, toRoomId: String?, keepSource: Boolean) {
        acts += "move $attestationId $toCohort $toRoomId $keepSource"
    }
    override suspend fun withdrawFile(attestationId: String, cohort: String, roomId: String?) {
        acts += "withdraw $attestationId $cohort $roomId"
    }
    companion object {
        val ENTRY = DriveEntry("community", "room-7", "att-f", "alice-key", "2026-10-30T10:00:00Z", "plan.pdf", "application/pdf", "here", "")
    }
}

class RoomFilesAndModerationTest {
    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain() }

    @Test
    fun aRoomPanelListsExactlyThatRoom() {
        val drive = RoomDrive()
        val vm = FilesViewModel(drive, FilesCohort.COMMUNITY, room = "room-7").also { made += it }
        vm.refresh()
        assertEquals(listOf<Pair<String?, String?>>("community" to "room-7"), drive.reads, "one room, never the whole drive")
        assertIs<FilesState.Listed>(vm.state.value)
    }

    @Test
    fun theAuthorsActsReachTheNodeAsAsked() {
        val drive = RoomDrive()
        val vm = FilesViewModel(drive, FilesCohort.COMMUNITY, room = "room-7").also { made += it }
        vm.renameFile(RoomDrive.ENTRY, "plan-v2.pdf")
        assertIs<FileActState.Done>(vm.act.value)
        vm.dismissAct()
        vm.moveFile(RoomDrive.ENTRY, "self", null)
        vm.dismissAct()
        vm.withdrawFile(RoomDrive.ENTRY)
        assertEquals(
            listOf(
                "rename att-f community room-7 plan-v2.pdf",
                "move att-f self null false",
                "withdraw att-f community room-7",
            ),
            drive.acts,
        )
    }

    @Test
    fun aRefusedActIsShownByNameNeverAsDone() {
        val vm = FilesViewModel(RoomDrive(refuse = NodeRefusal("drive.not_author", "not yours", 403)), FilesCohort.COMMUNITY, room = "room-7")
            .also { made += it }
        vm.renameFile(RoomDrive.ENTRY, "x")
        val s = assertIs<FileActState.Refused>(vm.act.value)
        assertEquals("drive.not_author", s.refusal.reasonId)
    }

    private val labels = ModerationLabels(
        "Moderate", "Flag reported", "Flag harmful", "Clear", "Report", "Only a moderator", "No route yet",
        appeal = listOf("Appeal evidence", "Appeal process", "Appeal compromised"),
        rule = listOf("Rule stays", "Rule part", "Rule lift"),
    )
    private fun msg(
        mine: Boolean, author: String = "bob-key", duties: List<String> = emptyList(),
        flag: String? = null, flagged: Boolean = false, reconsideration: String? = null,
    ) = CegChatMessage(
        attestationId = "m1", communityId = "room-7", author = author, mine = mine, authorDuties = duties,
        moderation = if (!flagged) null else ai.ciris.mobile.shared.models.chat.RoomModeration(
            flag = flag, itemAttestationId = "m1", reconsideration = reconsideration,
        ),
    )

    /** Appeals (0.5.246): the author appeals their own flagged message; a reviewer rules once one is filed. */
    @Test
    fun theAuthorAppealsAndAReviewerRules() {
        val appealed = mutableListOf<String>()
        val ruled = mutableListOf<String>()
        fun ops(m: CegChatMessage, reviewer: Boolean = false, moderator: Boolean = false) = chatModerationOps(
            m, isModerator = moderator, onModerate = null, labels = labels, onFlag = { _, _ -> }, onClearFlag = {},
            isReviewer = reviewer, onAppeal = { _, g -> appealed += g }, onRule = { _, o -> ruled += o },
        )
        val mine = ops(msg(mine = true, flag = "reported", flagged = true))
        assertEquals(listOf("Appeal evidence", "Appeal process", "Appeal compromised"), mine.map { it.label },
            "an appeal is the one moderation act on your own message")
        assertEquals("mi_chat_appeal_new_evidence_m1", mine[0].tag)
        mine[1].onClick()
        assertEquals(listOf("procedural_error"), appealed)
        assertTrue(ops(msg(mine = true)).isEmpty(), "nothing to appeal on a message nobody flagged")
        assertTrue(ops(msg(mine = true, flag = null, flagged = true)).isEmpty(), "nor once the flag was cleared or reversed")
        assertTrue(ops(msg(mine = true, flag = "reported", flagged = true, reconsideration = "filed")).isEmpty(),
            "one appeal is waiting for its ruling")
        assertEquals(3, ops(msg(mine = true, flag = "reported", flagged = true, reconsideration = "upheld")).size,
            "a ruling that left the flag can be appealed again")

        val member = ops(msg(mine = false, flag = "reported", flagged = true))
        assertEquals(listOf("Report"), member.map { it.label }, "someone else's flag is not a member's to appeal")
        val reviewerBefore = ops(msg(mine = false, flag = "reported", flagged = true), reviewer = true)
        assertEquals(listOf("Appeal evidence", "Appeal process", "Appeal compromised", "Report"), reviewerBefore.map { it.label },
            "a reviewer may file too, and has nothing to rule on yet")
        val reviewerFiled = ops(msg(mine = false, flag = "reported", flagged = true, reconsideration = "filed"), reviewer = true)
        assertEquals(listOf("Rule stays", "Rule part", "Rule lift", "Report"), reviewerFiled.map { it.label })
        assertEquals("mi_chat_rule_reversed_m1", reviewerFiled[2].tag)
        reviewerFiled[2].onClick()
        assertEquals(listOf("reversed"), ruled)
        val moderatorFiled = ops(msg(mine = false, flag = "reported", flagged = true, reconsideration = "filed"), moderator = true)
        assertTrue(moderatorFiled.none { it.label.startsWith("Rule") }, "moderating a room is not reviewing it")
    }

    @Test
    fun aModeratorModeratesTheAuthorAndFlagsTheMessage() {
        val opened = mutableListOf<Pair<String, String>>()
        val flagged = mutableListOf<Pair<String, String>>()
        val asModerator = chatModerationOps(
            msg(mine = false), isModerator = true, onModerate = { r, a -> opened += r to a }, labels = labels,
            onFlag = { m, c -> flagged += m.attestationId to c }, onClearFlag = { error("nothing is flagged") },
        )
        assertEquals(listOf("Moderate", "Flag reported", "Flag harmful", "Report"), asModerator.map { it.label })
        assertEquals(
            listOf("mi_chat_moderate_m1", "mi_chat_flag_reported_m1", "mi_chat_flag_infohazard_m1", "mi_chat_report_m1"),
            asModerator.map { it.tag },
        )
        asModerator[0].onClick()
        assertEquals(listOf("room-7" to "bob-key"), opened, "moderating is on the room and the AUTHOR")
        asModerator[1].onClick()
        asModerator[2].onClick()
        assertEquals(listOf("m1" to "reported", "m1" to "infohazard"), flagged, "a flag is on the MESSAGE, with its class")
        assertNotNull(asModerator[3].unavailable, "reporting has no route yet (CIRISServer#665)")
        assertTrue(chatModerationOps(msg(mine = true), isModerator = true, onModerate = { _, _ -> }, labels = labels, onFlag = { _, _ -> }, onClearFlag = {}).isEmpty(),
            "no moderation items on your own message")
    }

    @Test
    fun aMemberIsOfferedNoFlagAndNoClear() {
        val asMember = chatModerationOps(
            msg(mine = false, flag = "reported", flagged = true), isModerator = false, onModerate = { _, _ -> }, labels = labels,
            onFlag = { _, _ -> error("not a moderator") }, onClearFlag = { error("not a moderator") },
        )
        assertEquals(listOf("Moderate", "Report"), asMember.map { it.label }, "flag and clear are drawn for a moderator only")
        assertEquals("Only a moderator", asMember[0].unavailable)
    }

    @Test
    fun aLiveFlagOffersClearAndOnlyTheOtherClass() {
        val cleared = mutableListOf<String>()
        fun ops(flag: String?, flagged: Boolean) = chatModerationOps(
            msg(mine = false, flag = flag, flagged = flagged), isModerator = true, onModerate = null, labels = labels,
            onFlag = { _, _ -> }, onClearFlag = { cleared += it.attestationId },
        )
        val live = ops("reported", true)
        assertEquals(listOf("Flag harmful", "Clear", "Report"), live.map { it.label }, "flagging the same class again signs nothing, so it is not offered")
        live[1].onClick()
        assertEquals(listOf("m1"), cleared)
        assertEquals(listOf("Flag reported", "Flag harmful", "Report"), ops(null, true).map { it.label }, "a cleared flag has nothing left to clear")
        assertEquals(listOf("Clear", "Report"), ops("infohazard", true).map { it.label },
            "under a live infohazard a reported flag changes nothing anyone sees: clear first, then flag")
    }

    @Test
    fun theNodesViewerDutiesWinElseTheViewersOwnMessagesSay() {
        assertTrue(isModerator(listOf("moderate", "review"), emptyList()))
        assertTrue(!isModerator(emptyList(), listOf(msg(mine = true, duties = listOf("moderate")))), "the node said no")
        assertTrue(isModerator(null, listOf(msg(mine = true, duties = listOf("moderate")))), "older node: the viewer's own author_duties")
        assertTrue(!isModerator(null, listOf(msg(mine = false, duties = listOf("moderate")))), "someone else's duties are not the viewer's")
    }
}
