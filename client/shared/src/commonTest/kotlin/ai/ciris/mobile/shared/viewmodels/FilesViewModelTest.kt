package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DriveApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.drive.ByteState
import ai.ciris.mobile.shared.models.drive.DriveEntry
import ai.ciris.mobile.shared.models.drive.DriveListing
import ai.ciris.mobile.shared.models.drive.FileWrite
import ai.ciris.mobile.shared.models.drive.FileWritten
import ai.ciris.mobile.shared.models.drive.Note
import ai.ciris.mobile.shared.models.drive.NoteListing
import ai.ciris.mobile.shared.models.drive.OpenedFile
import ai.ciris.mobile.shared.platform.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A drive that answers what it is told to, and records what it was asked. */
private class FakeDrive(
    var listing: DriveListing = DriveListing(),
    var listingError: Exception? = null,
    var openError: Exception? = null,
    var written: FileWritten = FileWritten("att-new", addressed = true, cohort = "self", room = "self", tier = "Self", crossed = true),
    var notes: MutableList<Note> = mutableListOf(),
) : DriveApi {
    val writes = mutableListOf<FileWrite>()
    val noteWrites = mutableListOf<String>()
    var driveReads = 0

    override suspend fun readDrive(cohort: String?, roomId: String?, limit: Int): DriveListing {
        driveReads++
        listingError?.let { throw it }
        return listing
    }
    override suspend fun readFile(attestationId: String, roomId: String): OpenedFile {
        openError?.let { throw it }
        return OpenedFile(attestationId, "text/plain", "a.txt", "aGVsbG8=") // "hello"
    }
    override suspend fun writeFile(write: FileWrite): FileWritten { writes += write; return written }
    override suspend fun readNotes(): NoteListing = NoteListing("self", notes.toList())
    override suspend fun writeNote(body: String) {
        noteWrites += body
        notes += Note("n${notes.size}", "2026-09-24T00:00:00Z", "me", body, "open") // the notes wire says open, not here
    }
}

private fun entry(id: String, room: String, cohort: String = "community", bytes: String = "here", at: String = "2026-09-24T10:00:00Z") =
    DriveEntry(cohort, room, id, "author", at, "$id.txt", "text/plain", bytes, "")

class FilesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun anUnknownByteTokenIsNeverHere() {
        // The one guess that must not be made is "the bytes are here".
        assertEquals(ByteState.HERE, ByteState.of("here"))
        assertEquals(ByteState.NOT_FETCHED, ByteState.of("not_fetched"))
        assertEquals(ByteState.NOT_GRANTED, ByteState.of("not_granted"))
        assertEquals(ByteState.UNOPENED, ByteState.of("something_new"))
    }

    @Test
    fun communityFilesAreGroupedByRoomAndOnlyThisCohortIsListed() {
        val drive = FakeDrive(listing = DriveListing(entries = listOf(
            entry("a", "room-2"), entry("b", "room-1"), entry("c", "room-1", at = "2026-09-24T11:00:00Z"),
            entry("mine", "self", cohort = "self"),
        )))
        val vm = FilesViewModel(drive, FilesCohort.COMMUNITY).also { it.refresh() }
        val listed = assertIs<FilesState.Listed>(vm.state.value)
        assertEquals(listOf("room-1", "room-2"), listed.groups.map { it.roomId })
        assertEquals(listOf("c", "b"), listed.groups[0].entries.map { it.attestationId }, "newest first within a room")
        assertTrue(listed.groups.none { g -> g.entries.any { it.cohort == "self" } }, "a self row leaked into a community tab")
    }

    @Test
    fun aNoteIsNotAFileButANamedTextFileIs() {
        // The node lists notes in the self drive; Files must not show them as "Untitled".
        val note = DriveEntry("self", "me", "n", "author", "2026-09-24T10:00:00Z", null, "text/plain; charset=utf-8", "here", "")
        val txt = entry("named", "me", cohort = "self")
        val vm = FilesViewModel(FakeDrive(listing = DriveListing(entries = listOf(note, txt))), FilesCohort.SELF).also { it.refresh() }
        assertEquals(listOf("named"), assertIs<FilesState.Listed>(vm.state.value).groups.flatMap { g -> g.entries.map { it.attestationId } })

        val onlyNotes = FilesViewModel(FakeDrive(listing = DriveListing(entries = listOf(note))), FilesCohort.SELF).also { it.refresh() }
        assertEquals(FilesState.Empty, onlyNotes.state.value)
    }

    @Test
    fun noRowsIsEmptyNotAnError() {
        val vm = FilesViewModel(FakeDrive(), FilesCohort.SELF).also { it.refresh() }
        assertEquals(FilesState.Empty, vm.state.value)
    }

    @Test
    fun aNodeWithoutTheRouteIsTooOldAndARefusalIsARefusal() {
        val old = FilesViewModel(FakeDrive(listingError = NodeRefusal(null, null, 404)), FilesCohort.SELF).also { it.refresh() }
        assertEquals(FilesState.NodeTooOld, old.state.value)

        val refused = FilesViewModel(
            FakeDrive(listingError = NodeRefusal("drive.owner_session_required", "sign in", 403)),
            FilesCohort.SELF,
        ).also { it.refresh() }
        assertEquals("drive.owner_session_required", assertIs<FilesState.Refused>(refused.state.value).refusal.reasonId)
    }

    @Test
    fun bytesOnAnotherDeviceAreAnAnswerNotAFailure() {
        val drive = FakeDrive(openError = NodeRefusal("drive.not_fetched", "on another device", 409))
        val vm = FilesViewModel(drive, FilesCohort.SELF)
        vm.openFile(entry("x", "self", cohort = "self", bytes = "not_fetched"))
        val notOpened = assertIs<OpenState.NotOpened>(vm.open.value)
        assertEquals("drive.not_fetched", notOpened.refusal?.reasonId)
    }

    @Test
    fun anOpenedFileCarriesItsBytes() {
        val vm = FilesViewModel(FakeDrive(), FilesCohort.SELF)
        vm.openFile(entry("x", "self", cohort = "self"))
        val opened = assertIs<OpenState.Opened>(vm.open.value)
        assertEquals("hello", opened.bytes.decodeToString())
    }

    @Test
    fun aFileOverTheEdgeCapIsRefusedBeforeUpload() {
        val drive = FakeDrive()
        val vm = FilesViewModel(drive, FilesCohort.SELF)
        vm.addFile(PickedFile("big.bin", "application/octet-stream", "", FileWrite.MAX_INLINE_BYTES + 1))
        assertIs<AddState.TooLarge>(vm.add.value)
        assertTrue(drive.writes.isEmpty(), "a file the edge will refuse was uploaded anyway")
    }

    @Test
    fun aSelfWriteNamesNoRoomAndACommunityWriteNamesOne() {
        val drive = FakeDrive()
        FilesViewModel(drive, FilesCohort.SELF).addFile(PickedFile("a.txt", "text/plain", "aGk=", 2), roomId = "ignored")
        assertEquals("self", drive.writes.last().cohort)
        assertNull(drive.writes.last().roomId, "the self room IS the owner; naming one is a different request")

        FilesViewModel(drive, FilesCohort.COMMUNITY).addFile(PickedFile("b.txt", "text/plain", "aGk=", 2), roomId = "room-1")
        assertEquals("community", drive.writes.last().cohort)
        assertEquals("room-1", drive.writes.last().roomId)
    }

    @Test
    fun aWriteReportsWhetherItReachedAnyone() {
        val drive = FakeDrive(written = FileWritten("att", addressed = false, cohort = "self", room = "self", tier = "Local", crossed = false))
        val vm = FilesViewModel(drive, FilesCohort.SELF)
        vm.addFile(PickedFile("a.txt", "text/plain", "aGk=", 2))
        val written = assertIs<AddState.Written>(vm.add.value)
        assertEquals(false, written.result.crossed, "crossed=false means the file reached nobody; it must reach the UI as such")
        assertTrue(drive.driveReads >= 1, "the list is re-read after a write")
    }
}

class NotesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun aBlankNoteIsNotSent() {
        val drive = FakeDrive()
        val vm = NotesViewModel(drive)
        assertEquals(false, vm.write("   "))
        assertTrue(drive.noteWrites.isEmpty())
    }

    @Test
    fun aNoteIsWrittenTrimmedAndTheListRereads() {
        val drive = FakeDrive()
        val vm = NotesViewModel(drive).also { it.refresh() }
        assertEquals(NotesState.Empty, vm.state.value)
        assertTrue(vm.write("  buy milk  "))
        assertEquals(listOf("buy milk"), drive.noteWrites)
        val note = assertIs<NotesState.Listed>(vm.state.value).notes.single()
        assertEquals("buy milk", note.body)
        assertEquals(ByteState.HERE, note.byteState, "a note the node calls open must render its body")
    }
}
