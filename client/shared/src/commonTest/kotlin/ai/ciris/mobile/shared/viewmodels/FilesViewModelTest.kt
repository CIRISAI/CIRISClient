package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DriveApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.drive.ByteState
import ai.ciris.mobile.shared.models.drive.DriveEntry
import ai.ciris.mobile.shared.models.drive.DriveListing
import ai.ciris.mobile.shared.models.drive.FileWrite
import ai.ciris.mobile.shared.models.drive.DigestCheck
import ai.ciris.mobile.shared.models.drive.FileWritten
import ai.ciris.mobile.shared.models.drive.MediaPolicy
import ai.ciris.mobile.shared.models.drive.Note
import ai.ciris.mobile.shared.models.drive.NoteListing
import ai.ciris.mobile.shared.models.drive.OpenedFile
import ai.ciris.mobile.shared.models.drive.PolicySource
import ai.ciris.mobile.shared.platform.PickedFile
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
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

/** A drive that answers what it is told to, and records what it was asked. */
private class FakeDrive(
    var listing: DriveListing = DriveListing(),
    var listingError: Exception? = null,
    var openError: Exception? = null,
    var written: FileWritten = FileWritten("att-new", addressed = true, cohort = "self", room = "self", tier = "Self", crossed = true),
    var notes: MutableList<Note> = mutableListOf(),
    /** The node's answer to `GET /v1/files/{id}`, as 0.5.217 sends it (with `content_digest`); null answers "hello" undigested. */
    var fileJson: String? = null,
    /** `GET /v1/media/policy`: the node's table, or the refusal an older node gives (404, no id). */
    var policy: MediaPolicy? = null,
    var policyError: Exception? = NodeRefusal(null, null, 404),
) : DriveApi {
    val writes = mutableListOf<FileWrite>()
    val noteWrites = mutableListOf<String>()
    var driveReads = 0
    val driveAsks = mutableListOf<Pair<String?, String?>>()
    /** Every `after` the drive was asked with, in order (null = the first page). */
    val driveAfters = mutableListOf<String?>()
    /** Pages to answer in turn for an unfiltered drive; [listing] when empty. */
    var pages: MutableList<DriveListing> = mutableListOf()
    /** Every per-file open, as (id, cohort, room) — the query `GET /v1/files/{id}` is sent with. */
    val fileAsks = mutableListOf<Triple<String, String, String?>>()
    var policyReads = 0

    override suspend fun readDrive(cohort: String?, roomId: String?, limit: Int, after: String?): DriveListing {
        driveReads++
        driveAsks += cohort to roomId
        driveAfters += after
        listingError?.let { throw it }
        return if (pages.isNotEmpty()) pages.removeAt(0) else listing
    }
    override suspend fun readMediaPolicy(): MediaPolicy {
        policyReads++
        policy?.let { return it }
        throw policyError ?: IllegalStateException("no policy")
    }
    override suspend fun readFile(attestationId: String, cohort: String, roomId: String?): OpenedFile {
        fileAsks += Triple(attestationId, cohort, roomId)
        openError?.let { throw it }
        fileJson?.let { return json.decodeFromString(OpenedFile.serializer(), it) }
        return OpenedFile(attestationId, "text/plain", "a.txt", bytesBase64 = "aGVsbG8=") // "hello"
    }
    override suspend fun writeFile(write: FileWrite): FileWritten { writes += write; return written }
    override suspend fun readNotes(): NoteListing = NoteListing("self", notes.toList())
    override suspend fun readCustody(attestationId: String, cohort: String, roomId: String?): ai.ciris.mobile.shared.models.drive.FileCustody =
        throw NodeRefusal(null, null, 404) // a released node: the route is not mounted
    override suspend fun writeNote(body: String) {
        noteWrites += body
        notes += Note("n${notes.size}", "2026-09-24T00:00:00Z", "me", body, "open") // the notes wire says open, not here
    }
}

private val json = Json { ignoreUnknownKeys = true }

/** "hello", as `GET /v1/files/{id}` answers on 0.5.217 (`src/drive.rs:2084-2090`), with [digest] where the node states the plaintext SHA-256. */
private fun helloJson(digest: String?) = buildString {
    append("""{"attestation_id":"x","media_type":"text/plain","filename":"a.txt","size":5,""")
    if (digest != null) append(""""content_digest":"$digest","content_digest_alg":"sha-256",""")
    append(""""bytes_base64":"aGVsbG8="}""")
}
private const val HELLO_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

private fun entry(id: String, room: String, cohort: String = "community", bytes: String = "here", at: String = "2026-09-24T10:00:00Z") =
    DriveEntry(cohort, room, id, "author", at, "$id.txt", "text/plain", bytes, "")

@OptIn(ExperimentalEncodingApi::class)
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
    fun aFileAboveTheWholeReadCapIsNamedTooLargeNotAFailure() {
        // ciris-server 0.5.218: a whole read above 64 MiB is 413
        // `drive.too_large_for_whole_read`. The bytes are there; this client
        // has no streaming read, so it is a named state — not "could not open".
        val drive = FakeDrive(openError = NodeRefusal("drive.too_large_for_whole_read", "this file is 70000000 bytes", 413))
        val vm = FilesViewModel(drive, FilesCohort.SELF)
        vm.openFile(entry("big", "self", cohort = "self"))
        val tooLarge = assertIs<OpenState.TooLarge>(vm.open.value)
        assertEquals("big", tooLarge.entry.attestationId)
        assertEquals(413, tooLarge.refusal.statusCode)
    }

    @Test
    fun anOpenedFileCarriesItsBytes() {
        val vm = FilesViewModel(FakeDrive(), FilesCohort.SELF)
        vm.openFile(entry("x", "self", cohort = "self"))
        val opened = assertIs<OpenState.Opened>(vm.open.value)
        assertEquals("hello", opened.bytes.decodeToString())
    }

    /**
     * CC 5.3.2.5: the full SHA-256 is verified BEFORE the bytes reach a
     * renderer. 0.5.217 states the plaintext digest on the read (CIRISServer#641);
     * bytes that do not match it are not the file, and are not opened.
     */
    @Test
    fun bytesThatDoNotMatchTheNodesDigestAreNotOpened() {
        val wrong = "0".repeat(64)
        val vm = FilesViewModel(FakeDrive(fileJson = helloJson(wrong)), FilesCohort.SELF)
        vm.openFile(entry("x", "self", cohort = "self"))
        assertFalse(vm.open.value is OpenState.Opened, "bytes whose digest is not the one the node sent were handed to the renderer")
        assertFalse(vm.open.value is OpenState.Closed, "a digest mismatch vanished instead of being said")
        val unreadable = assertIs<OpenState.Unreadable>(vm.open.value, "a mismatch is UNREADABLE, not a failed open and not an empty file")
        assertEquals(wrong, unreadable.expected)
        assertEquals(HELLO_SHA256, unreadable.actual)
    }

    @Test
    fun bytesThatMatchTheNodesDigestOpenVerified() {
        val vm = FilesViewModel(FakeDrive(fileJson = helloJson(HELLO_SHA256)), FilesCohort.SELF)
        vm.openFile(entry("x", "self", cohort = "self"))
        val opened = assertIs<OpenState.Opened>(vm.open.value)
        assertEquals(DigestCheck.Verified(HELLO_SHA256), opened.digest)
        assertEquals("hello", opened.bytes.decodeToString())
    }

    @Test
    fun aNodeThatSendsNoDigestOpensTheFileAndSaysNothingWasVerified() {
        // Pre-0.5.217: the limit is stated, never dressed as a verification.
        val vm = FilesViewModel(FakeDrive(fileJson = helloJson(null)), FilesCohort.SELF)
        vm.openFile(entry("x", "self", cohort = "self"))
        assertEquals(DigestCheck.NotSent, assertIs<OpenState.Opened>(vm.open.value).digest)
    }

    // ── The render policy: the node's table, never widened ──────────────────

    @Test
    fun theNodesPolicyIsReadOnceAndNarrowsTheBuiltInTable() {
        val drive = FakeDrive(policy = MediaPolicy(tierA = mapOf("text/plain" to 1_000_000L, "image/png" to 999L), inlineMaxBytes = 512L * 1024, renditions = false))
        val vm = FilesViewModel(drive, FilesCohort.SELF).also { it.refresh(); it.refresh() }
        assertEquals(1, drive.policyReads, "the policy is one read per model, not one per refresh")
        val fromNode = assertIs<PolicySource.FromNode>(vm.policy.value)
        assertEquals(1_000_000L, fromNode.policy.tierA["text/plain"], "the node's lower cap wins")
        assertEquals(999L, fromNode.policy.tierA["image/png"])
        assertNull(fromNode.policy.tierA["image/jpeg"], "a format the node dropped is not rendered here")
        assertEquals(false, fromNode.policy.renditions)
    }

    @Test
    fun theNodesOwnCapGatesAnUploadInsteadOfTheConstant() {
        // The write door refuses over `whole_read_max_bytes` (src/drive.rs:728); `inline_max_bytes` is a storage fact.
        val drive = FakeDrive(policy = MediaPolicy(tierA = emptyMap(), inlineMaxBytes = 1L * 1024 * 1024, wholeReadMaxBytes = 64L * 1024 * 1024))
        val vm = FilesViewModel(drive, FilesCohort.SELF).also { it.refresh() }
        vm.addFile(PickedFile("mid.bin", "application/octet-stream", "", 8L * 1024 * 1024))
        assertIs<AddState.Written>(vm.add.value, "8 MiB is over the compiled-in 1 MiB but under what this node takes whole")
        assertEquals(1, drive.writes.size)

        vm.addFile(PickedFile("huge.bin", "application/octet-stream", "", 65L * 1024 * 1024))
        val tooLarge = assertIs<AddState.TooLarge>(vm.add.value)
        assertEquals(64L * 1024 * 1024, tooLarge.limitBytes, "the limit said is the node's, not the constant")
        assertEquals(1, drive.writes.size, "refused before upload")

        val narrower = FakeDrive(policy = MediaPolicy(tierA = emptyMap(), wholeReadMaxBytes = 512L * 1024))
        val small = FilesViewModel(narrower, FilesCohort.SELF).also { it.refresh() }
        small.addFile(PickedFile("mid.bin", "application/octet-stream", "", 600L * 1024))
        assertIs<AddState.TooLarge>(small.add.value, "a node that takes less than the constant is obeyed too")
    }

    @Test
    fun aNodeWithoutThePolicyRouteLeavesTheBuiltInTableAndSaysWhy() {
        val vm = FilesViewModel(FakeDrive(), FilesCohort.SELF).also { it.refresh() }
        assertEquals("not_on_this_node", assertIs<PolicySource.BuiltIn>(vm.policy.value).reason)
        assertEquals(MediaPolicy.RECOMMENDED, vm.policy.value.policy)
    }

    // ── Family: the picked household's room ─────────────────────────────────

    @Test
    fun aFamilyTabWithNoHouseholdAsksNothingAndIsNotEmpty() {
        val drive = FakeDrive()
        val vm = FilesViewModel(drive, FilesCohort.FAMILY).also { it.refresh(null) }
        assertEquals(FilesState.NoRoom, vm.state.value, "no household picked is its own state, not 'no files'")
        assertEquals(0, drive.driveReads, "with no room there is nothing to ask the node")
    }

    @Test
    fun aFamilyTabListsThePickedHouseholdsRoomAndAddsThere() {
        val drive = FakeDrive(listing = DriveListing(entries = listOf(entry("f", "fam-1", cohort = "family"))))
        val vm = FilesViewModel(drive, FilesCohort.FAMILY).also { it.refresh("fam-1") }
        assertEquals("family" to "fam-1", drive.driveAsks.last(), "GET /v1/drive?cohort=family&room_id=<household>")
        assertEquals(listOf("f"), assertIs<FilesState.Listed>(vm.state.value).groups.single().entries.map { it.attestationId })

        vm.addFile(PickedFile("a.txt", "text/plain", "aGk=", 2))
        assertEquals("family", drive.writes.last().cohort)
        assertEquals("fam-1", drive.writes.last().roomId, "a family add goes to the picked household without naming it again")
    }

    @Test
    fun aFamilyFileOpensInItsHouseholdNotInTheSelfDefault() {
        // The bug: `GET /v1/files/{id}?room_id=…` with no cohort, which the node
        // reads as `self` (`Cohort::parse`) and answers `404 drive.not_in_room`.
        val household = entry("f", "fam-1", cohort = "family")
        val drive = FakeDrive(listing = DriveListing(entries = listOf(household)))
        val vm = FilesViewModel(drive, FilesCohort.FAMILY).also { it.refresh("fam-1") }
        vm.openFile(assertIs<FilesState.Listed>(vm.state.value).groups.single().entries.single())
        assertEquals(Triple("f", "family", "fam-1"), drive.fileAsks.single(), "the household's scope, as the listing named it")
        assertIs<OpenState.Opened>(vm.open.value)
    }

    @Test
    fun everyCohortOpensWithItsOwnScope() {
        val drive = FakeDrive()
        FilesViewModel(drive, FilesCohort.SELF).openFile(entry("s", "owner-room", cohort = "self"))
        FilesViewModel(drive, FilesCohort.COMMUNITY).openFile(entry("c", "room-9", cohort = "community"))
        assertEquals(
            listOf<Triple<String, String, String?>>(Triple("s", "self", "owner-room"), Triple("c", "community", "room-9")),
            drive.fileAsks,
            "the row's cohort always travels; the client drops the room for self (ClientDrive)",
        )
    }

    @Test
    fun aCommunityTabReadsTheWholeDriveAndWalksEveryPage() {
        // `cohort=community` with no room is `400 drive.community_id_required`
        // on 0.5.217 and 0.5.218, so the tab asks the unfiltered drive (every
        // admitted room) and keeps the community rows, page after page.
        val drive = FakeDrive().apply {
            pages = mutableListOf(
                DriveListing(entries = listOf(entry("mine", "me", cohort = "self")), resume = "cur-1"),
                DriveListing(entries = listOf(entry("c1", "room-1"), entry("fam", "fam-1", cohort = "family")), resume = "cur-2"),
                DriveListing(entries = listOf(entry("c2", "room-2")), resume = null),
            )
        }
        val vm = FilesViewModel(drive, FilesCohort.COMMUNITY).also { it.refresh() }
        assertEquals(List<Pair<String?, String?>>(3) { null to null }, drive.driveAsks, "no cohort, no room: the whole drive")
        assertEquals(listOf(null, "cur-1", "cur-2"), drive.driveAfters)
        val listed = assertIs<FilesState.Listed>(vm.state.value)
        assertEquals(listOf("c1", "c2"), listed.groups.flatMap { g -> g.entries.map { it.attestationId } })
    }

    @Test
    fun aPickTheDeviceRefusedIsSaidNotDropped() {
        val drive = FakeDrive()
        val vm = FilesViewModel(drive, FilesCohort.SELF)
        vm.refuseTooLarge("film.mov", 50L * 1024 * 1024, PickedFile.MAX_FILE_SIZE_BYTES)
        val tooLarge = assertIs<AddState.TooLarge>(vm.add.value)
        assertEquals(PickedFile.MAX_FILE_SIZE_BYTES, tooLarge.limitBytes)
        assertTrue(drive.writes.isEmpty())
    }

    @Test
    fun aHouseholdWithNoFilesIsEmptyNotNoRoom() {
        val vm = FilesViewModel(FakeDrive(), FilesCohort.FAMILY).also { it.refresh("fam-1") }
        assertEquals(FilesState.Empty, vm.state.value)
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

    /**
     * 0.5.217's write gate refuses bytes that contradict their declared type,
     * and `application/octet-stream` over a real PNG is a contradiction
     * (`media_gate::check_format`): a picker that reports no type must not
     * make the node refuse the file. The type declared is the sniffed one.
     */
    @Test
    fun anUploadDeclaresTheSniffedTypeNotThePickersLabel() {
        val drive = FakeDrive()
        val vm = FilesViewModel(drive, FilesCohort.SELF)
        // A PNG header, base64: the picker said nothing.
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
        vm.addFile(PickedFile("shot", "", kotlin.io.encoding.Base64.encode(png), png.size.toLong()))
        assertEquals("image/png", drive.writes.last().mediaType, "the bytes are a PNG whatever the picker said")
        // The picker's label survives only where the bytes say nothing.
        vm.addFile(PickedFile("blob", "application/x-widget", kotlin.io.encoding.Base64.encode(byteArrayOf(0, 1, 2, 3)), 4))
        assertEquals("application/x-widget", drive.writes.last().mediaType)
        vm.addFile(PickedFile("blob2", "", kotlin.io.encoding.Base64.encode(byteArrayOf(0, 1, 2, 3)), 4))
        assertEquals("application/octet-stream", drive.writes.last().mediaType, "an honest 'I don't know' for unrecognised bytes")
    }

    @Test
    fun aNoteThatOpenedButIsNotTextIsUnreadableNotUnopened() {
        // 0.5.217 `read_notes`: `unreadable` is the one note-only fact — the bytes opened and are not UTF-8.
        assertEquals(ByteState.UNREADABLE, ByteState.of("unreadable"))
        assertEquals(ByteState.UNREADABLE, Note("n", "2026-09-28T00:00:00Z", "me", null, "unreadable").byteState)
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
