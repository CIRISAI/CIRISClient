package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ChatApi
import ai.ciris.mobile.shared.api.DriveApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.chat.CegChatMessage
import ai.ciris.mobile.shared.models.chat.ChatCommunity
import ai.ciris.mobile.shared.models.chat.ChatTranscript
import ai.ciris.mobile.shared.models.chat.SendChatMessageResult
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOM_A = "room-a"
private const val NODE_A = "http://node-a.invalid:4243"
private const val NODE_B = "http://node-b.invalid:4243"

private class OneRoomChat : ChatApi {
    override suspend fun open(nodeUrl: String, contactKeyId: String) = ChatCommunity(communityId = ROOM_A)
    override suspend fun transcript(nodeUrl: String, communityId: String) = ChatTranscript(
        communityId = communityId,
        messages = listOf(CegChatMessage(attestationId = "m1", body = "hi", communityId = communityId, assertedAt = "2026-11-18T16:00:00Z")),
        total = 1,
        ready = true,
    )
    override suspend fun send(nodeUrl: String, communityId: String, body: String) =
        SendChatMessageResult(attestationId = "m2", communityId = communityId)
}

/** Two pages of one room's files; records which node it was made for and every `after` asked. */
private class PagedDrive(val node: String) : DriveApi {
    val afters = mutableListOf<String?>()
    private fun file(id: String, at: String) = DriveEntry(
        cohort = "community", roomId = ROOM_A, attestationId = id, authorKeyId = "bob-key",
        assertedAt = at, filename = "$id.txt", mediaType = "text/plain", bytes = "here",
    )
    override suspend fun readDrive(cohort: String?, roomId: String?, limit: Int, after: String?): DriveListing {
        afters += after
        return if (after == null) DriveListing(entries = listOf(file("f1", "2026-11-18T15:00:00Z")), resume = "page-2")
        else DriveListing(entries = listOf(file("f2", "2026-11-18T17:00:00Z")), resume = null)
    }
    override suspend fun readFile(attestationId: String, cohort: String, roomId: String?): OpenedFile = error("unused")
    override suspend fun writeFile(write: FileWrite): FileWritten = error("unused")
    override suspend fun readNotes(): NoteListing = error("unused")
    override suspend fun writeNote(body: String) = error("unused")
    override suspend fun readMediaPolicy(): MediaPolicy = throw NodeRefusal(null, null, 404)
    override suspend fun readCustody(attestationId: String, cohort: String, roomId: String?) = error("unused")
    override suspend fun renameFile(attestationId: String, cohort: String, roomId: String?, filename: String) = error("unused")
    override suspend fun moveFile(attestationId: String, cohort: String, roomId: String?, toCohort: String, toRoomId: String?, keepSource: Boolean) = error("unused")
    override suspend fun withdrawFile(attestationId: String, cohort: String, roomId: String?) = error("unused")
}

/** Codex on CIRISClient#317: the room's files come from every page, and from the room's own node. */
class RoomFilesInChatTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain() }

    @Test
    fun everyPageOfTheRoomsFilesIsReadFromTheRoomsOwnNode() {
        var active = NODE_A
        val drives = mutableListOf<PagedDrive>()
        val vm = UserChatViewModel(
            CIRISApiClient(baseUrl = "http://agent.invalid:8080"),
            { active },
            OneRoomChat(),
            driveAt = { node -> PagedDrive(node).also { drives += it } },
        ).also { made += it }
        vm.enterRoom(ROOM_A, name = "Garden", memberCount = 3)
        active = NODE_B // the person switches node after entering: the room's reads stay on A

        assertEquals(listOf(NODE_A), drives.map { it.node }, "the files are read from the node the room was opened on")
        assertEquals(listOf(null, "page-2"), drives.single().afters, "the resume cursor is followed")
        assertEquals(listOf("file:f1", "m1", "file:f2"), vm.timeline.value.map { it.key })
        assertTrue(vm.timeline.value.filterIsInstance<RoomRow.FileAdded>().none { it.mine })
    }
}
