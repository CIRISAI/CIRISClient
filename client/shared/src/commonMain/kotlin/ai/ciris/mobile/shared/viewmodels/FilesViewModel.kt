package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DriveApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.drive.DigestCheck
import ai.ciris.mobile.shared.models.drive.DriveEntry
import ai.ciris.mobile.shared.models.drive.FileWrite
import ai.ciris.mobile.shared.models.drive.FileWritten
import ai.ciris.mobile.shared.models.drive.MediaPolicy
import ai.ciris.mobile.shared.models.drive.OpenedFile
import ai.ciris.mobile.shared.models.drive.PolicySource
import ai.ciris.mobile.shared.models.drive.RenderTier
import ai.ciris.mobile.shared.platform.PickedFile
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Which of the drive's cohorts a Files tab lists. Family lists ONE household's
 * room — the one picked in the Family hub (CSD-100) — so a family tab is
 * refreshed with that room and says so when none is picked.
 */
enum class FilesCohort(val wire: String) { SELF("self"), FAMILY("family"), COMMUNITY("community") }

/** The files of one room: a community circle lists every community room it is in, grouped. */
data class FileGroup(val roomId: String, val entries: List<DriveEntry>)

/** Exactly one of these is true of a Files tab at a time. */
sealed interface FilesState {
    data object Loading : FilesState
    data class Listed(val groups: List<FileGroup>) : FilesState
    data object Empty : FilesState

    /** A family tab with no household picked: there is no room to list. Not "empty" — nothing was asked. */
    data object NoRoom : FilesState

    /** The node answered 404 with no reason id: it predates the drive plane (CIRISServer 0.5.215). */
    data object NodeTooOld : FilesState

    /** The node refused, by name. Not "empty": an error must never look like one. */
    data class Refused(val refusal: NodeRefusal) : FilesState
    data class Failed(val message: String) : FilesState
}

/** Opening one file. Its own state, so the list behind it doesn't flicker. */
sealed interface OpenState {
    data object Closed : OpenState
    data class Opening(val entry: DriveEntry) : OpenState

    /** The bytes opened and passed the digest check, or the node sent none to check against ([digest] says which). */
    data class Opened(val entry: DriveEntry, val file: OpenedFile, val bytes: ByteArray, val digest: DigestCheck) : OpenState

    /**
     * The bytes the node sent do not hash to the digest it sent with them
     * (CC 5.3.2.5). Not opened, not shown, not saved: the file is UNREADABLE
     * here, and the sheet says so — never an empty preview, never a failure
     * dressed as one.
     */
    data class Unreadable(val entry: DriveEntry, val expected: String, val actual: String) : OpenState

    /** The bytes did not open; [refusal] carries `drive.not_fetched` / `drive.not_granted`, which are answers, not failures. */
    data class NotOpened(val entry: DriveEntry, val refusal: NodeRefusal?, val message: String) : OpenState
}

/** Adding a file. [Written] keeps the server's three facts apart: reached the audience, fetchable, fully granted. */
sealed interface AddState {
    data object Idle : AddState
    data class Uploading(val name: String) : AddState

    /** Refused BEFORE upload: the node's inline cap, said in words rather than learnt from a 413. */
    data class TooLarge(val name: String, val sizeBytes: Long, val limitBytes: Long) : AddState
    data class Written(val name: String, val result: FileWritten) : AddState
    data class Refused(val name: String, val refusal: NodeRefusal) : AddState
    data class Failed(val name: String, val message: String) : AddState
}

/**
 * Files, for one circle (B3). Listing and opening follow the drive's own
 * account of where the bytes are; the view model never guesses that a file is
 * here. Opened bytes are checked against the digest the node states before
 * any renderer sees them, and the render table is the node's own policy,
 * narrowed by the recommended set — never widened.
 */
@OptIn(ExperimentalEncodingApi::class)
class FilesViewModel(
    private val api: DriveApi,
    val cohort: FilesCohort,
) : ViewModel() {

    private val _state = MutableStateFlow<FilesState>(FilesState.Loading)
    val state: StateFlow<FilesState> = _state.asStateFlow()

    private val _open = MutableStateFlow<OpenState>(OpenState.Closed)
    val open: StateFlow<OpenState> = _open.asStateFlow()

    private val _add = MutableStateFlow<AddState>(AddState.Idle)
    val add: StateFlow<AddState> = _add.asStateFlow()

    /** The render policy in force: the node's (`GET /v1/media/policy`) narrowed by [MediaPolicy.RECOMMENDED], or the built-in table with the reason. */
    private val _policy = MutableStateFlow<PolicySource>(PolicySource.BuiltIn(reason = null))
    val policy: StateFlow<PolicySource> = _policy.asStateFlow()

    /** The room a family tab was last refreshed with; a family add goes there. */
    private var familyRoom: String? = null

    /** The rooms this circle's files are in, for choosing where a new community file goes. */
    val rooms: List<String>
        get() = (_state.value as? FilesState.Listed)?.groups?.map { it.roomId } ?: emptyList()

    /**
     * Re-read the listing. [roomId] is the household for a family tab — with
     * none the tab is [FilesState.NoRoom] and nothing is asked of the node —
     * and ignored elsewhere, where the drive spans every room the cohort has.
     */
    fun refresh(roomId: String? = null) {
        if (cohort == FilesCohort.FAMILY) {
            familyRoom = roomId
            if (roomId == null) {
                _state.value = FilesState.NoRoom
                return
            }
        }
        _state.value = FilesState.Loading
        viewModelScope.launch {
            readPolicyOnce()
            _state.value = try {
                val listing = api.readDrive(cohort = cohort.wire, roomId = roomId.takeIf { cohort == FilesCohort.FAMILY })
                val groups = listing.entries
                    .filter { it.cohort == cohort.wire && !it.isNote } // notes live in Chats, not here
                    .groupBy { it.roomId }
                    .map { (room, entries) -> FileGroup(room, entries.sortedByDescending { it.assertedAt }) }
                    .sortedBy { it.roomId }
                if (groups.isEmpty()) FilesState.Empty else FilesState.Listed(groups)
            } catch (e: NodeRefusal) {
                if (e.statusCode == 404 && e.reasonId == null) FilesState.NodeTooOld else FilesState.Refused(e)
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "readDrive failed: ${e.message}")
                FilesState.Failed(e.message ?: e::class.simpleName ?: "error")
            }
        }
    }

    /**
     * The node's policy, read once per model. A node without the route (404,
     * no id: it predates 0.5.217) or a failed read leaves the built-in table
     * in force, with the reason kept so the sheet can say it.
     */
    private suspend fun readPolicyOnce() {
        if (_policy.value is PolicySource.FromNode) return
        _policy.value = try {
            PolicySource.FromNode(MediaPolicy.RECOMMENDED.narrowedBy(api.readMediaPolicy()))
        } catch (e: NodeRefusal) {
            PlatformLogger.w(TAG, "media policy: ${e.reasonId ?: e.statusCode}")
            PolicySource.BuiltIn(reason = if (e.statusCode == 404 && e.reasonId == null) "not_on_this_node" else (e.reasonId ?: "${e.statusCode}"))
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "media policy unreadable: ${e.message}")
            PolicySource.BuiltIn(reason = e.message ?: "unreadable")
        }
    }

    fun openFile(entry: DriveEntry) {
        _open.value = OpenState.Opening(entry)
        viewModelScope.launch {
            _open.value = try {
                val file = api.readFile(entry.attestationId, entry.roomId)
                val bytes = Base64.decode(file.bytesBase64)
                // CC 5.3.2.5: the digest is checked BEFORE the bytes reach any renderer.
                when (val check = DigestCheck.of(file, bytes)) {
                    is DigestCheck.Mismatch -> OpenState.Unreadable(entry, check.expected, check.actual)
                    else -> OpenState.Opened(entry, file, bytes, check)
                }
            } catch (e: NodeRefusal) {
                OpenState.NotOpened(entry, e, e.detail ?: entry.detail)
            } catch (e: Exception) {
                OpenState.NotOpened(entry, null, e.message ?: "error")
            }
        }
    }

    fun closeFile() {
        _open.value = OpenState.Closed
    }

    /**
     * Add [picked] to this circle. [roomId] is required for a community
     * circle, defaults to the picked household for a family one, and is
     * ignored for Just me, where the room IS the owner. The size cap is the
     * node's own, from its policy, when it published one ([MediaPolicy.uploadCap]).
     */
    fun addFile(picked: PickedFile, roomId: String? = null) {
        val limit = _policy.value.policy.uploadCap
        if (picked.sizeBytes > limit) {
            _add.value = AddState.TooLarge(picked.name, picked.sizeBytes, limit)
            return
        }
        _add.value = AddState.Uploading(picked.name)
        viewModelScope.launch {
            _add.value = try {
                val result = api.writeFile(
                    FileWrite(
                        cohort = cohort.wire,
                        roomId = when (cohort) {
                            FilesCohort.SELF -> null
                            FilesCohort.FAMILY -> roomId ?: familyRoom
                            FilesCohort.COMMUNITY -> roomId
                        },
                        bytesBase64 = picked.dataBase64,
                        mediaType = typeToDeclare(picked),
                        filename = picked.name,
                    )
                )
                refresh(familyRoom)
                AddState.Written(picked.name, result)
            } catch (e: NodeRefusal) {
                AddState.Refused(picked.name, e)
            } catch (e: Exception) {
                AddState.Failed(picked.name, e.message ?: "error")
            }
        }
    }

    fun dismissAdd() {
        _add.value = AddState.Idle
    }

    /**
     * The type an upload declares: what the bytes ARE, when the sniff knows
     * (0.5.217's write gate refuses `application/octet-stream` over a real
     * PNG, `media_gate::check_format`, and a picker that reports no type must
     * not make the node refuse the file); the picker's label only where the
     * bytes say nothing; an honest `application/octet-stream` otherwise.
     */
    private fun typeToDeclare(picked: PickedFile): String {
        val sniffed = try { RenderTier.typeToDeclare(Base64.decode(picked.dataBase64)) } catch (_: IllegalArgumentException) { OCTET }
        return if (sniffed != OCTET) sniffed else picked.mediaType.ifBlank { OCTET }
    }

    private companion object {
        const val TAG = "FilesVM"
        const val OCTET = "application/octet-stream"
    }
}
