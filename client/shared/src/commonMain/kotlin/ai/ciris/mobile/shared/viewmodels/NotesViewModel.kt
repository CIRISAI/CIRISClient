package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DriveApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.drive.Note
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface NotesState {
    data object Loading : NotesState
    data class Listed(val notes: List<Note>) : NotesState
    data object Empty : NotesState
    data object NodeTooOld : NotesState
    data class Refused(val refusal: NodeRefusal) : NotesState
    data class Failed(val message: String) : NotesState
}

/**
 * Notes to self: Just me › Chats. The server's own model is that self-chat
 * IS note-taking (unnamed `text/plain` rows in the owner's self room), and the
 * design's is that a chat is a group of two. A note to self is the group of one.
 */
class NotesViewModel(private val api: DriveApi) : ViewModel() {

    private val _state = MutableStateFlow<NotesState>(NotesState.Loading)
    val state: StateFlow<NotesState> = _state.asStateFlow()

    /** Why the last write did not land, in the node's words; null when it did (or none was tried). */
    private val _writeError = MutableStateFlow<String?>(null)
    val writeError: StateFlow<String?> = _writeError.asStateFlow()

    private val _writing = MutableStateFlow(false)
    val writing: StateFlow<Boolean> = _writing.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = try {
                val notes = api.readNotes().notes
                if (notes.isEmpty()) NotesState.Empty else NotesState.Listed(notes)
            } catch (e: NodeRefusal) {
                if (e.statusCode == 404 && e.reasonId == null) NotesState.NodeTooOld else NotesState.Refused(e)
            } catch (e: Exception) {
                PlatformLogger.w("NotesVM", "readNotes failed: ${e.message}")
                NotesState.Failed(e.message ?: "error")
            }
        }
    }

    /** Write [body] and re-read. Blank text is not sent: the node refuses it (`notes.empty`), and so does this. */
    fun write(body: String): Boolean {
        val text = body.trim()
        if (text.isEmpty() || _writing.value) return false
        _writing.value = true
        _writeError.value = null
        viewModelScope.launch {
            try {
                api.writeNote(text)
                refresh()
            } catch (e: NodeRefusal) {
                _writeError.value = e.detail ?: e.reasonId ?: "refused (${e.statusCode})"
            } catch (e: Exception) {
                _writeError.value = e.message ?: "error"
            } finally {
                _writing.value = false
            }
        }
        return true
    }
}
