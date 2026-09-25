package ai.ciris.mobile.shared.ui.screens.files

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.ceg.Dim
import ai.ciris.mobile.shared.ceg.shortKey
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.drive.ByteState
import ai.ciris.mobile.shared.models.drive.RenderTier
import ai.ciris.mobile.shared.models.drive.DriveEntry
import ai.ciris.mobile.shared.models.drive.FileWrite
import ai.ciris.mobile.shared.platform.FilePickerDialog
import ai.ciris.mobile.shared.platform.saveFileCopy
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.Fact
import ai.ciris.mobile.shared.ui.primitives.ItemRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.Receipt
import ai.ciris.mobile.shared.ui.primitives.ReceiptAct
import ai.ciris.mobile.shared.ui.primitives.ReceiptSheet
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.viewmodels.AddState
import ai.ciris.mobile.shared.viewmodels.FilesCohort
import ai.ciris.mobile.shared.viewmodels.FilesState
import ai.ciris.mobile.shared.viewmodels.FilesViewModel
import ai.ciris.mobile.shared.viewmodels.OpenState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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

/** Tags the gates and the atlas drive. Stable: CSD-007 names them. */
object FilesTags {
    const val LIST = "files_list"
    const val EMPTY = "files_empty"
    const val LOADING = "files_loading"
    const val ERROR = "files_error"
    const val TOO_OLD = "files_node_too_old"
    const val ADD = "btn_files_add"
    const val REFRESH = "btn_files_refresh"
    const val ADD_STATUS = "files_add_status"
    const val SHEET = "sheet_file"
    const val SAVE_COPY = "btn_file_save_copy"
    const val CLOSE = "btn_file_close"
    fun row(attestationId: String) = "files_row_$attestationId"
    fun room(roomId: String) = "files_room_$roomId"
    fun pickRoom(roomId: String) = "btn_files_room_$roomId"
}

/** The glyph for a file's kind, from the 71. Never a colour alone: the kind is also in the row's words. */
internal fun fileGlyph(mediaType: String?): GlyphName = when {
    mediaType == null -> GlyphName.FILE
    mediaType.startsWith("image/") -> GlyphName.PHOTO
    mediaType.startsWith("video/") -> GlyphName.VIDEO_FILE
    mediaType.startsWith("audio/") -> GlyphName.AUDIO_FILE
    mediaType.startsWith("text/") -> GlyphName.NOTE
    else -> GlyphName.FILE
}

/** Where the bytes are, in words. The second line of every row, so no state rests on colour. */
@Composable
internal fun byteStateText(state: ByteState): String = when (state) {
    ByteState.HERE -> localizedString("mobile.files_bytes_here")
    ByteState.NOT_FETCHED -> localizedString("mobile.files_bytes_not_fetched")
    ByteState.NOT_GRANTED -> localizedString("mobile.files_bytes_not_granted")
    ByteState.UNOPENED -> localizedString("mobile.files_bytes_unopened")
}

/** A refusal in the reader's language: the node's id when the bundle has it, else the node's own English. */
@Composable
internal fun refusalText(refusal: NodeRefusal): String {
    refusal.reasonId?.let { id ->
        val text = localizedString(id)
        if (text != id) return text
    }
    return refusal.detail ?: refusal.reasonId ?: "(${refusal.statusCode})"
}

/**
 * A drive row's receipt: what the listing actually carries (who sent it, and
 * the cohort that can see it) off the wire, and what it does not — the
 * subjects, the content hash, the consent scope, the holder count — as
 * "this node did not send this", never guessed.
 */
internal fun fileReceipt(entry: DriveEntry, acts: List<ReceiptAct>): Receipt = Receipt(
    id = entry.attestationId,
    subject = Fact.NotSent,
    attester = Fact.Wire(entry.authorKeyId),
    scope = Fact.Wire(entry.cohort),
    dimension = Dim.holdsBytesSha256Prefix,
    dimensionValue = Fact.NotSent,
    rule = Fact.NotSent,
    holders = null,
    acts = acts,
)

/**
 * FILES HOLDS FILES (B3). One circle's files from the drive plane, each row
 * honest about where its bytes are: here, on another device, or on no device
 * that this one holds a grant for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: FilesViewModel, nodeVersion: String? = null) {
    val t = CirisTheme.tokens
    val state by viewModel.state.collectAsState()
    val open by viewModel.open.collectAsState()
    val add by viewModel.add.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var choosingRoomFor by remember { mutableStateOf<ai.ciris.mobile.shared.platform.PickedFile?>(null) }
    var receiptFor by remember { mutableStateOf<Receipt?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    FilePickerDialog(
        show = picking,
        mimeTypes = listOf("*/*"),
        onFilePicked = { picked ->
            picking = false
            val rooms = viewModel.rooms
            when {
                viewModel.cohort == FilesCohort.SELF -> viewModel.addFile(picked)
                rooms.size == 1 -> viewModel.addFile(picked, rooms.single())
                else -> choosingRoomFor = picked
            }
        },
        onDismiss = { picking = false },
    )

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("mobile.files_title")) },
                actions = {
                    CirisTextButton(
                        label = localizedString("mobile.files_refresh"),
                        tag = FilesTags.REFRESH,
                        onClick = { viewModel.refresh() },
                    )
                    // A community file needs a room; with none there is
                    // nowhere to put it, and the button says so by being absent
                    // while the empty state explains why.
                    val canAdd = viewModel.cohort == FilesCohort.SELF || viewModel.rooms.isNotEmpty()
                    if (canAdd) {
                        CirisTextButton(
                            label = localizedString("mobile.files_add"),
                            tag = FilesTags.ADD,
                            onClick = { picking = true },
                        )
                    }
                },
            )
        },
        containerColor = t.ground,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AddStatus(add, onDismiss = { viewModel.dismissAdd() })
            Box(modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    FilesState.Loading -> StateBlock(ListState.Loading, tag = FilesTags.LOADING)
                    FilesState.Empty -> StateBlock(
                        ListState.Empty(
                            localizedString(
                                if (viewModel.cohort == FilesCohort.SELF) "mobile.files_empty_self"
                                else "mobile.files_empty_community",
                            ),
                            glyph = GlyphName.FILE,
                        ),
                        tag = FilesTags.EMPTY,
                    )
                    FilesState.NodeTooOld -> StateBlock(
                        ListState.Error(
                            title = localizedString("mobile.files_node_too_old_title"),
                            body = if (nodeVersion != null)
                                localizedString("mobile.files_node_too_old_body_versioned", "version", nodeVersion)
                            else localizedString("mobile.files_node_too_old_body"),
                        ),
                        tag = FilesTags.TOO_OLD,
                    )
                    is FilesState.Refused -> StateBlock(
                        ListState.Error(localizedString("mobile.files_error_title"), body = refusalText(s.refusal)),
                        tag = FilesTags.ERROR,
                    )
                    is FilesState.Failed -> StateBlock(
                        ListState.Error(localizedString("mobile.files_error_title"), detail = s.message),
                        tag = FilesTags.ERROR,
                    )
                    is FilesState.Listed -> LazyColumn(
                        modifier = Modifier.fillMaxSize().testable(FilesTags.LIST),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val grouped = viewModel.cohort == FilesCohort.COMMUNITY
                        for (group in s.groups) {
                            if (grouped) {
                                item(key = "room-${group.roomId}") {
                                    Text(
                                        localizedString("mobile.files_room", "room", shortKey(group.roomId)),
                                        style = CirisTheme.type.label, color = t.mute,
                                        modifier = Modifier.padding(top = 8.dp).testable(FilesTags.room(group.roomId)),
                                    )
                                }
                            }
                            items(group.entries, key = { it.attestationId }) { entry ->
                                val openLabel = localizedString("mobile.files_open")
                                ItemRow(
                                    glyph = fileGlyph(entry.mediaType),
                                    title = entry.filename ?: localizedString("mobile.files_untitled"),
                                    tag = FilesTags.row(entry.attestationId),
                                    meta = "${entry.assertedAt.take(10)} · ${shortKey(entry.authorKeyId)}",
                                    secondary = byteStateText(entry.byteState),
                                    receipt = fileReceipt(
                                        entry,
                                        acts = listOf(ReceiptAct(openLabel, "btn_receipt_act_open_${entry.attestationId}") {
                                            receiptFor = null
                                            viewModel.openFile(entry)
                                        }),
                                    ),
                                    onClick = { viewModel.openFile(entry) },
                                    onOpenReceipt = { receiptFor = it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    choosingRoomFor?.let { picked ->
        ModalBottomSheet(onDismissRequest = { choosingRoomFor = null }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(localizedString("mobile.files_choose_room"), style = CirisTheme.type.title, color = t.ink)
                for (room in viewModel.rooms) {
                    CirisButton(
                        label = localizedString("mobile.files_room", "room", shortKey(room)),
                        tag = FilesTags.pickRoom(room),
                        onClick = { choosingRoomFor = null; viewModel.addFile(picked, room) },
                    )
                }
            }
        }
    }

    if (open !is OpenState.Closed) FileSheet(open, onClose = { viewModel.closeFile() })
    receiptFor?.let { r -> ReceiptSheet(receipt = r, onDismiss = { receiptFor = null }) }
}

/** What happened to the last add, in words. The three facts the server keeps apart stay apart here. */
@Composable
private fun AddStatus(add: AddState, onDismiss: () -> Unit) {
    val t = CirisTheme.tokens
    val text: String? = when (add) {
        AddState.Idle -> null
        is AddState.Uploading -> localizedString("mobile.files_uploading", "name", add.name)
        is AddState.TooLarge -> localizedString(
            "mobile.files_too_large",
            mapOf("name" to add.name, "size" to humanBytes(add.sizeBytes), "limit" to humanBytes(add.limitBytes)),
        )
        is AddState.Written -> when {
            !add.result.crossed -> localizedString("mobile.files_written_nobody", "name", add.name)
            add.result.excluded.isNotEmpty() -> localizedString(
                "mobile.files_written_partial",
                mapOf("name" to add.name, "count" to add.result.excluded.size.toString()),
            )
            else -> localizedString("mobile.files_written", "name", add.name)
        }
        is AddState.Refused -> localizedString(
            "mobile.files_add_failed", mapOf("name" to add.name, "reason" to refusalText(add.refusal)),
        )
        is AddState.Failed -> localizedString(
            "mobile.files_add_failed", mapOf("name" to add.name, "reason" to add.message),
        )
    }
    if (text == null) return
    val warn = add is AddState.TooLarge || add is AddState.Refused || add is AddState.Failed ||
        (add is AddState.Written && (!add.result.crossed || add.result.excluded.isNotEmpty()))
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        CardShell(tag = FilesTags.ADD_STATUS) {
            Text(text, style = CirisTheme.type.body, color = if (warn) t.danger else t.ink)
            if (add !is AddState.Uploading) {
                CirisTextButton(label = localizedString("mobile.files_close"), tag = "btn_files_add_status_close", onClick = onDismiss)
            }
        }
    }
}

/** Why an opened file is not shown, in words. */
@Composable
private fun renderDecisionText(d: RenderTier.Decision): String = when (d) {
    is RenderTier.Decision.Text -> ""
    is RenderTier.Decision.AwaitingNodeRendition -> localizedString("mobile.files_render_awaiting_node", "format", d.format)
    is RenderTier.Decision.DownloadOnly -> localizedString("mobile.files_preview_unavailable")
    is RenderTier.Decision.Refused -> localizedString("mobile.files_render_refused", "format", d.format)
    RenderTier.Decision.NotUtf8 -> localizedString("mobile.files_render_not_utf8")
    is RenderTier.Decision.Mismatch -> localizedString("mobile.files_render_mismatch", mapOf("declared" to d.declared, "sniffed" to d.sniffed))
    is RenderTier.Decision.Polyglot -> localizedString("mobile.files_render_polyglot", "format", d.format)
}

/**
 * One file, opened. What shows is [RenderTier]'s decision over the bytes
 * (CC 5.3.2.6); a copy may be saved unless it is disguised or runs code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSheet(open: OpenState, onClose: () -> Unit) {
    val t = CirisTheme.tokens
    var savedTo by remember(open) { mutableStateOf<String?>(null) }
    var saveFailed by remember(open) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, modifier = Modifier.testable(FilesTags.SHEET)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (open) {
                OpenState.Closed -> Unit
                is OpenState.Opening -> {
                    Text(open.entry.filename ?: localizedString("mobile.files_untitled"), style = CirisTheme.type.title, color = t.ink)
                    StateBlock(ListState.Loading, tag = "file_opening", inline = true)
                }
                is OpenState.NotOpened -> {
                    Text(open.entry.filename ?: localizedString("mobile.files_untitled"), style = CirisTheme.type.title, color = t.ink)
                    Text(byteStateText(open.entry.byteState), style = CirisTheme.type.body, color = t.dim)
                    Text(
                        open.refusal?.let { refusalText(it) } ?: open.message,
                        style = CirisTheme.type.body, color = t.dim,
                        modifier = Modifier.testable("file_not_opened"),
                    )
                }
                is OpenState.Opened -> {
                    val name = open.file.filename ?: open.entry.filename ?: localizedString("mobile.files_untitled")
                    val media = open.file.mediaType ?: open.entry.mediaType ?: "application/octet-stream"
                    Text(name, style = CirisTheme.type.title, color = t.ink)
                    Text("$media · ${humanBytes(open.bytes.size.toLong())}", style = CirisTheme.type.label, color = t.mute)
                    // CC 5.3.2.6: the render tier comes from the sniffed bytes, never from `media`.
                    val decision = remember(open) { RenderTier.decide(media, open.bytes) }
                    val saveAllowed = remember(open) { RenderTier.saveAllowed(decision, name, open.bytes) }
                    when (decision) {
                        is RenderTier.Decision.Text -> Box(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()).testable("file_preview_text"),
                        ) {
                            Text(decision.visible.take(20_000), style = CirisTheme.type.body, color = t.ink)
                        }
                        else -> Text(
                            renderDecisionText(decision),
                            style = CirisTheme.type.body,
                            color = if (decision is RenderTier.Decision.Mismatch || decision is RenderTier.Decision.Polyglot) t.danger else t.dim,
                            modifier = Modifier.testable("file_not_rendered"),
                        )
                    }
                    if (saveAllowed) {
                        CirisButton(
                            label = localizedString("mobile.files_save_copy"),
                            tag = FilesTags.SAVE_COPY,
                            onClick = {
                                val path = saveFileCopy(name, media, open.bytes)
                                savedTo = path
                                saveFailed = path == null
                            },
                        )
                    } else {
                        Text(
                            localizedString("mobile.files_save_blocked"),
                            style = CirisTheme.type.body, color = t.dim,
                            modifier = Modifier.testable("file_save_blocked"),
                        )
                    }
                    savedTo?.let {
                        Text(localizedString("mobile.files_saved_to", "path", it), style = CirisTheme.type.body, color = t.dim,
                            modifier = Modifier.testable("file_saved_to"))
                    }
                    if (saveFailed) {
                        Text(localizedString("mobile.files_save_failed"), style = CirisTheme.type.body, color = t.danger)
                    }
                }
            }
            CirisTextButton(label = localizedString("mobile.files_close"), tag = FilesTags.CLOSE, onClick = onClose)
        }
    }
}

/** 1.2 MB / 340 KB / 12 B — for a size a person reads, not a byte count. */
internal fun humanBytes(n: Long): String = when {
    n >= 1024L * 1024 -> "${(n * 10 / (1024L * 1024)) / 10.0} MB"
    n >= 1024L -> "${n / 1024} KB"
    else -> "$n B"
}
