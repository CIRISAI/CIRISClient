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

    private val labels = ModerationLabels("Moderate", "Flag", "Report", "Only a moderator", "No route yet")
    private fun msg(mine: Boolean, author: String = "bob-key", duties: List<String> = emptyList()) =
        CegChatMessage(attestationId = "m1", communityId = "room-7", author = author, mine = mine, authorDuties = duties)

    @Test
    fun moderationActsOnTheAuthorAndOnlyForAModerator() {
        val opened = mutableListOf<Pair<String, String>>()
        val asModerator = chatModerationOps(msg(mine = false), isModerator = true, onModerate = { r, a -> opened += r to a }, labels = labels)
        assertEquals(listOf("Moderate", "Flag", "Report"), asModerator.map { it.label })
        assertNull(asModerator[0].unavailable)
        asModerator[0].onClick()
        assertEquals(listOf("room-7" to "bob-key"), opened, "the room and the AUTHOR, never the message id")
        assertNotNull(asModerator[2].unavailable, "reporting has no route yet (CIRISServer#665)")

        val asMember = chatModerationOps(msg(mine = false), isModerator = false, onModerate = { _, _ -> }, labels = labels)
        assertEquals("Only a moderator", asMember[0].unavailable)
        assertTrue(chatModerationOps(msg(mine = true), isModerator = true, onModerate = { _, _ -> }, labels = labels).isEmpty(),
            "no moderation items on your own message")
    }

    @Test
    fun theNodesViewerDutiesWinElseTheViewersOwnMessagesSay() {
        assertTrue(isModerator(listOf("moderate", "review"), emptyList()))
        assertTrue(!isModerator(emptyList(), listOf(msg(mine = true, duties = listOf("moderate")))), "the node said no")
        assertTrue(isModerator(null, listOf(msg(mine = true, duties = listOf("moderate")))), "older node: the viewer's own author_duties")
        assertTrue(!isModerator(null, listOf(msg(mine = false, duties = listOf("moderate")))), "someone else's duties are not the viewer's")
    }
}
