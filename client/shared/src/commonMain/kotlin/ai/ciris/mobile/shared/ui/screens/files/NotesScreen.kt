package ai.ciris.mobile.shared.ui.screens.files

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.drive.ByteState
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextField
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.viewmodels.NotesState
import ai.ciris.mobile.shared.viewmodels.NotesViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object NotesTags {
    const val LIST = "notes_list"
    const val EMPTY = "notes_empty"
    const val LOADING = "notes_loading"
    const val ERROR = "notes_error"
    const val TOO_OLD = "notes_node_too_old"
    const val INPUT = "input_note"
    const val SAVE = "btn_note_save"
    const val WRITE_ERROR = "notes_write_error"
    fun row(attestationId: String) = "notes_row_$attestationId"
}

/**
 * Notes to self — Just me › Chats. The group of one: the server's self room,
 * where unnamed text rows are notes and the person is both ends.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(viewModel: NotesViewModel, nodeVersion: String? = null) {
    val t = CirisTheme.tokens
    val state by viewModel.state.collectAsState()
    val writing by viewModel.writing.collectAsState()
    val writeError by viewModel.writeError.collectAsState()
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("mobile.notes_title")) },
                actions = {
                    CirisTextButton(label = localizedString("mobile.files_refresh"), tag = "btn_notes_refresh", onClick = { viewModel.refresh() })
                },
            )
        },
        containerColor = t.ground,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val s = state) {
                    NotesState.Loading -> StateBlock(ListState.Loading, tag = NotesTags.LOADING)
                    NotesState.Empty -> StateBlock(
                        ListState.Empty(localizedString("mobile.notes_empty"), glyph = GlyphName.NOTE),
                        tag = NotesTags.EMPTY,
                    )
                    NotesState.NodeTooOld -> StateBlock(
                        ListState.Error(
                            title = localizedString("mobile.files_node_too_old_title"),
                            body = if (nodeVersion != null)
                                localizedString("mobile.files_node_too_old_body_versioned", "version", nodeVersion)
                            else localizedString("mobile.files_node_too_old_body"),
                        ),
                        tag = NotesTags.TOO_OLD,
                    )
                    is NotesState.Refused -> StateBlock(
                        ListState.Error(localizedString("mobile.notes_error_title"), body = refusalText(s.refusal)),
                        tag = NotesTags.ERROR,
                    )
                    is NotesState.Failed -> StateBlock(
                        ListState.Error(localizedString("mobile.notes_error_title"), detail = s.message),
                        tag = NotesTags.ERROR,
                    )
                    is NotesState.Listed -> LazyColumn(
                        modifier = Modifier.fillMaxSize().testable(NotesTags.LIST),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(s.notes, key = { it.attestationId }) { note ->
                            CardShell(tag = NotesTags.row(note.attestationId)) {
                                Text(note.assertedAt.take(16).replace('T', ' '), style = CirisTheme.type.label, color = t.mute)
                                // A note this device cannot open is still a note that exists; say where it is.
                                if (note.body != null && note.byteState == ByteState.HERE) {
                                    Text(note.body, style = CirisTheme.type.body, color = t.ink)
                                } else {
                                    Text(byteStateText(note.byteState), style = CirisTheme.type.body, color = t.dim)
                                }
                            }
                        }
                    }
                }
            }
            writeError?.let {
                Text(it, style = CirisTheme.type.body, color = t.danger,
                    modifier = Modifier.padding(horizontal = 16.dp).testable(NotesTags.WRITE_ERROR))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CirisTextField(
                    tag = NotesTags.INPUT,
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = localizedString("mobile.notes_placeholder"),
                    singleLine = false,
                    modifier = Modifier.weight(1f),
                )
                CirisButton(
                    label = localizedString("mobile.notes_save"),
                    tag = NotesTags.SAVE,
                    enabled = draft.isNotBlank() && !writing,
                    onClick = { if (viewModel.write(draft)) draft = "" },
                )
            }
        }
    }
}
