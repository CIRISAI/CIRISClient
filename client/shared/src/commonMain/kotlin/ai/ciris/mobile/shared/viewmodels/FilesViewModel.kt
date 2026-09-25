package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DriveApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.drive.DriveEntry
import ai.ciris.mobile.shared.models.drive.FileWrite
import ai.ciris.mobile.shared.models.drive.FileWritten
import ai.ciris.mobile.shared.models.drive.OpenedFile
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

/** Which of the drive's cohorts a Files tab lists. Family has no production path yet (CIRISServer#627), so it is not here. */
enum class FilesCohort(val wire: String) { SELF("self"), COMMUNITY("community") }

/** The files of one room: a community circle lists every community room it is in, grouped. */
data class FileGroup(val roomId: String, val entries: List<DriveEntry>)

/** Exactly one of these is true of a Files tab at a time. */
sealed interface FilesState {
    data object Loading : FilesState
    data class Listed(val groups: List<FileGroup>) : FilesState
    data object Empty : FilesState

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
    data class Opened(val entry: DriveEntry, val file: OpenedFile, val bytes: ByteArray) : OpenState

    /** The bytes did not open; [refusal] carries `drive.not_fetched` / `drive.not_granted`, which are answers, not failures. */
    data class NotOpened(val entry: DriveEntry, val refusal: NodeRefusal?, val message: String) : OpenState
}

/** Adding a file. [Written] keeps the server's three facts apart: reached the audience, fetchable, fully granted. */
sealed interface AddState {
    data object Idle : AddState
    data class Uploading(val name: String) : AddState

    /** Refused BEFORE upload: the edge's inline cap, said in words rather than learnt from a 413. */
    data class TooLarge(val name: String, val sizeBytes: Long, val limitBytes: Long) : AddState
    data class Written(val name: String, val result: FileWritten) : AddState
    data class Refused(val name: String, val refusal: NodeRefusal) : AddState
    data class Failed(val name: String, val message: String) : AddState
}

/**
 * Files, for one circle (B3). Listing and opening follow the drive's own
 * account of where the bytes are; the view model never guesses that a file is
 * here.
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

    /** The rooms this circle's files are in, for choosing where a new community file goes. */
    val rooms: List<String>
        get() = (_state.value as? FilesState.Listed)?.groups?.map { it.roomId } ?: emptyList()

    fun refresh() {
        _state.value = FilesState.Loading
        viewModelScope.launch {
            _state.value = try {
                val listing = api.readDrive(cohort = cohort.wire)
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

    fun openFile(entry: DriveEntry) {
        _open.value = OpenState.Opening(entry)
        viewModelScope.launch {
            _open.value = try {
                val file = api.readFile(entry.attestationId, entry.roomId)
                OpenState.Opened(entry, file, Base64.decode(file.bytesBase64))
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
     * circle and ignored for Just me, where the room IS the owner.
     */
    fun addFile(picked: PickedFile, roomId: String? = null) {
        if (picked.sizeBytes > FileWrite.MAX_INLINE_BYTES) {
            _add.value = AddState.TooLarge(picked.name, picked.sizeBytes, FileWrite.MAX_INLINE_BYTES)
            return
        }
        _add.value = AddState.Uploading(picked.name)
        viewModelScope.launch {
            _add.value = try {
                val result = api.writeFile(
                    FileWrite(
                        cohort = cohort.wire,
                        roomId = if (cohort == FilesCohort.SELF) null else roomId,
                        bytesBase64 = picked.dataBase64,
                        mediaType = picked.mediaType.ifBlank { "application/octet-stream" },
                        filename = picked.name,
                    )
                )
                refresh()
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

    private companion object {
        const val TAG = "FilesVM"
    }
}
