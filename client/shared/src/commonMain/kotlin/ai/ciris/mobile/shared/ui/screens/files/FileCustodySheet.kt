package ai.ciris.mobile.shared.ui.screens.files

import ai.ciris.mobile.shared.ceg.shortKey
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.drive.CopiesLine
import ai.ciris.mobile.shared.models.drive.CustodyDevice
import ai.ciris.mobile.shared.models.drive.CustodySummary
import ai.ciris.mobile.shared.models.drive.FileCustody
import ai.ciris.mobile.shared.models.drive.Holds
import ai.ciris.mobile.shared.models.drive.custodyCopies
import ai.ciris.mobile.shared.models.drive.custodyHolds
import ai.ciris.mobile.shared.models.drive.custodyReceiptsUnsupported
import ai.ciris.mobile.shared.models.drive.custodySummary
import ai.ciris.mobile.shared.models.drive.custodyWhyKey
import ai.ciris.mobile.shared.models.drive.custodyWhyHas
import ai.ciris.mobile.shared.models.drive.custodyWhyLines
import ai.ciris.mobile.shared.models.drive.WHY_INLINE_NO_RECEIPT
import ai.ciris.mobile.shared.models.drive.WHY_RECEIPT_IS_DELIVERY
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.ChipKind
import ai.ciris.mobile.shared.ui.primitives.ChipSpec
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.FieldRow
import ai.ciris.mobile.shared.ui.primitives.ItemRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.RowFlag
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.viewmodels.CustodyState
import ai.ciris.mobile.shared.viewmodels.CustodyTarget
import ai.ciris.mobile.shared.viewmodels.FileCustodyViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Tags for the "Where is this file" card (CSD-107). Stable: the CSD and its flow name them. */
object CustodyTags {
    const val SHEET = "sheet_file_custody"
    const val CARD = "card_file_custody"
    const val LOADING = "custody_loading"
    const val ERROR = "custody_error"
    const val TOO_OLD = "custody_node_too_old"
    const val EMPTY = "custody_empty"
    const val SUMMARY = "text_custody_summary"
    const val HELD_HERE = "custody_held_here"
    const val COPIES = "custody_copies"
    const val FOOT_RECEIPTS = "custody_footnote_receipts"
    const val FOOT_UNSUPPORTED = "custody_footnote_receipts_unsupported"
    const val FOOT_REMOVE = "custody_footnote_remove"
    const val AUTHOR = "custody_author"
    const val OTHER_KEYS = "custody_receipts_other_keys"
    const val CLOSE = "btn_custody_close"
    fun device(key: String) = "row_custody_device_$key"
    fun holds(key: String) = "custody_holds_$key"
    fun canOpen(key: String) = "custody_can_open_$key"
    fun reported(key: String) = "custody_reported_$key"
    fun copy(key: String) = "btn_custody_copy_$key"
    fun remove(key: String) = "btn_custody_remove_$key"
    /** A `why[]` entry, by its reason id (`custody.inline_no_receipt` → `custody_why_custody_inline_no_receipt`), else its position. */
    fun why(reasonId: String?, i: Int) = "custody_why_" + (reasonId?.replace('.', '_') ?: "$i")
    fun whyDetail(reasonId: String?, i: Int) = why(reasonId, i) + "_detail"

    /** The entry: a receipt act on a drive or notes row. */
    fun whereAct(attestationId: String) = "btn_receipt_act_where_$attestationId"
}

/**
 * Where a surface puts the card: the sheet over whatever the shared view model
 * holds, closed when the surface leaves, so a card opened on Files does not
 * reappear over Notes.
 */
@Composable
fun FileCustodyHost(custody: FileCustodyViewModel, nodeVersion: String?) {
    val state by custody.state.collectAsState()
    DisposableEffect(custody) { onDispose { custody.close() } }
    FileCustodySheet(state, nodeVersion, onDismiss = { custody.close() })
}

/** A device's tag key: its key id, or its position when the node sent none. */
internal fun custodyDeviceKey(device: CustodyDevice, index: Int): String = device.nodeKeyId?.takeIf { it.isNotBlank() } ?: "idx$index"

/**
 * "Where is this file" — which of the person's devices a file is on. ONE card,
 * opened from the hamburger on every file surface (Files, Notes, a file in
 * chat); no surface builds its own.
 *
 * What it will not say: that a device does NOT hold a file (only here,
 * received, or unknown); how many copies exist when the node can't count them
 * (self and family files, CC 5.2); that a receipt means the device still holds
 * it (receipts prove delivery, and eviction does not retract them yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileCustodySheet(state: CustodyState, nodeVersion: String?, onDismiss: () -> Unit) {
    if (state is CustodyState.Closed) return
    val t = CirisTheme.tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.ground,
        contentColor = t.ink,
        dragHandle = null,
        modifier = Modifier.testable(CustodyTags.SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .navigationBarsPadding(),
        ) {
            FileCustodyCard(state, nodeVersion, onDismiss)
        }
    }
}

@Composable
private fun FileCustodyCard(state: CustodyState, nodeVersion: String?, onDismiss: () -> Unit) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val target: CustodyTarget = when (state) {
        CustodyState.Closed -> return
        is CustodyState.Loading -> state.target
        is CustodyState.Ready -> state.target
        is CustodyState.NodeTooOld -> state.target
        is CustodyState.Failed -> state.target
    }
    CardShell(tag = if (state is CustodyState.Ready) CustodyTags.CARD else null, accent = t.brand) {
        Text(localizedString("mobile.files_custody_title"), style = type.title, color = t.ink)
        Text(
            target.name ?: localizedString("mobile.files_untitled"),
            style = type.label, color = t.mute,
        )
        Spacer(Modifier.height(8.dp))
        when (state) {
            CustodyState.Closed -> Unit
            is CustodyState.Loading -> StateBlock(ListState.Loading, tag = CustodyTags.LOADING, inline = true)
            is CustodyState.NodeTooOld -> StateBlock(
                ListState.Error(
                    title = localizedString("mobile.files_custody_error"),
                    body = if (nodeVersion != null) localizedString("mobile.files_custody_node_too_old_versioned", "version", nodeVersion)
                    else localizedString("mobile.files_custody_node_too_old"),
                ),
                tag = CustodyTags.TOO_OLD,
                inline = true,
            )
            is CustodyState.Failed -> StateBlock(
                ListState.Error(
                    title = localizedString("mobile.files_custody_error"),
                    // By id when the node gave one; an id the bundle lacks resolves to itself, so the detail follows.
                    body = state.reasonId?.let { localizedString(it) },
                    detail = state.detail?.takeIf { it.isNotBlank() },
                ),
                tag = CustodyTags.ERROR,
                inline = true,
            )
            is CustodyState.Ready -> Populated(state.custody)
        }
        Spacer(Modifier.height(8.dp))
        CirisTextButton(localizedString("common_close"), tag = CustodyTags.CLOSE, onClick = onDismiss)
    }
}

@Composable
private fun Populated(c: FileCustody) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val summary = custodySummary(c)
    if (summary is CustodySummary.NoDevices) {
        StateBlock(ListState.Empty(localizedString("mobile.files_custody_no_devices"), glyph = GlyphName.DEVICES), tag = CustodyTags.EMPTY, inline = true)
    } else {
        val sentence = when (summary) {
            is CustodySummary.Exactly -> localizedString(
                "mobile.files_custody_on_n_of_m", mapOf("n" to summary.on.toString(), "m" to summary.total.toString()),
            )
            is CustodySummary.AtLeast -> localizedString(
                "mobile.files_custody_on_at_least_n_of_m",
                mapOf("n" to summary.on.toString(), "m" to summary.total.toString(), "unknown" to summary.unknown.toString()),
            )
            CustodySummary.NoDevices -> ""
        }
        Text(sentence, style = type.body, color = t.ink, modifier = Modifier.testable(CustodyTags.SUMMARY, sentence))
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            c.devices.forEachIndexed { i, device -> DeviceRow(device, custodyDeviceKey(device, i), c.receiptsSupported) }
        }
    }

    Spacer(Modifier.height(4.dp))
    // Receipts are collected on the device that wrote the file, so ITS view is
    // the fullest; say which device that is.
    val authorName = c.authorDevice?.let { a ->
        c.devices.firstOrNull { it.nodeKeyId == a }?.label ?: shortKey(a)
    }
    FieldRow(
        label = localizedString("mobile.files_custody_author"),
        value = when {
            c.thisDeviceIsAuthor == true -> localizedString("mobile.files_custody_author_here")
            authorName != null -> localizedString("mobile.files_custody_author_elsewhere", "device", authorName)
            else -> localizedString("ceg.envelope.not_sent")
        },
        tone = if (c.thisDeviceIsAuthor == null && authorName == null) Tone.DANGER else Tone.INK,
        tag = CustodyTags.AUTHOR,
    )
    FieldRow(
        label = localizedString("mobile.files_custody_held_here"),
        value = when (c.heldHere) {
            true -> localizedString("mobile.files_custody_yes")
            false -> localizedString("mobile.files_custody_no")
            null -> localizedString("ceg.envelope.not_sent")
        },
        tone = if (c.heldHere == null) Tone.DANGER else Tone.INK,
        tag = CustodyTags.HELD_HERE,
    )
    val copies = custodyCopies(c)
    FieldRow(
        label = localizedString("mobile.files_custody_copies"),
        value = when (copies) {
            is CopiesLine.Known -> localizedString("mobile.files_custody_copies_known", "count", copies.count.toString())
            CopiesLine.NotObservable -> localizedString("mobile.files_custody_copies_not_observable")
            CopiesLine.NotSent -> localizedString("ceg.envelope.not_sent")
        },
        tone = when (copies) {
            is CopiesLine.Known -> Tone.INK
            CopiesLine.NotObservable -> Tone.DIM
            CopiesLine.NotSent -> Tone.DANGER
        },
        tag = CustodyTags.COPIES,
        divider = c.receiptsFromOtherKeys > 0,
    )
    if (c.receiptsFromOtherKeys > 0) {
        FieldRow(
            label = localizedString("mobile.files_custody_other_keys"),
            value = c.receiptsFromOtherKeys.toString(),
            mono = true,
            tag = CustodyTags.OTHER_KEYS,
            divider = false,
        )
    }

    // Footnotes: what a receipt proves, what this file cannot carry, and the node's own reasons.
    Spacer(Modifier.height(8.dp))
    // The node's own reasons first (CIRISServer#704: `{reason_id, detail}`),
    // localized by id with its detail as small print; then the card's own
    // footnotes, each only where the node did not already say it by id.
    c.why.forEachIndexed { i, entry ->
        val key = entry.reasonId?.let(::custodyWhyKey)
        val localized = key?.let { localizedString(it) }
        val lines = custodyWhyLines(entry) { k -> if (k == key && localized != null) localized else k }
        Footnote(lines.headline, CustodyTags.why(entry.reasonId, i))
        lines.smallPrint?.let { SmallPrint(it, CustodyTags.whyDetail(entry.reasonId, i)) }
    }
    if (!custodyWhyHas(c, WHY_RECEIPT_IS_DELIVERY)) {
        Footnote(localizedString("mobile.files_custody_footnote_receipts"), CustodyTags.FOOT_RECEIPTS)
    }
    if (custodyReceiptsUnsupported(c) && !custodyWhyHas(c, WHY_INLINE_NO_RECEIPT)) {
        Footnote(localizedString("mobile.files_custody_footnote_receipts_unsupported"), CustodyTags.FOOT_UNSUPPORTED)
    }
    Footnote(localizedString("mobile.files_custody_footnote_remove"), CustodyTags.FOOT_REMOVE)
}

/** A node detail under its localized reason: mono, small, verbatim. */
@Composable
private fun SmallPrint(text: String, tag: String) {
    Text(
        text,
        style = CirisTheme.type.signed,
        color = CirisTheme.tokens.mute,
        modifier = Modifier.padding(start = 8.dp).testable(tag, text),
    )
}

@Composable
private fun DeviceRow(device: CustodyDevice, key: String, receiptsSupported: Boolean?) {
    val holds = custodyHolds(device, receiptsSupported)
    val name = device.label ?: device.nodeKeyId?.let { shortKey(it) } ?: localizedString("mobile.files_custody_unnamed_device")
    val holdsText = when (holds) {
        Holds.Here -> localizedString("mobile.files_custody_holds_here")
        is Holds.Received -> holds.at?.let {
            localizedString("mobile.files_custody_holds_received_at", "at", it.take(16).replace('T', ' '))
        } ?: localizedString("mobile.files_custody_holds_received")
        Holds.None -> localizedString("mobile.files_custody_holds_none")
        Holds.Unknown -> localizedString("mobile.files_custody_holds_unknown")
    }
    // A remote device's own report (custody:ack:v1, CC 3.1.3.3), when one has arrived.
    val reported = device.reportedAt?.let {
        localizedString("mobile.files_custody_reported_at", "at", it.take(16).replace('T', ' '))
    }
    val canOpenText = when (device.canOpen) {
        true -> localizedString("mobile.files_custody_can_open_yes")
        false -> localizedString("mobile.files_custody_can_open_no")
        null -> localizedString("mobile.files_custody_can_open_unknown")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ItemRow(
            glyph = GlyphName.DEVICES,
            title = name,
            tag = CustodyTags.device(key),
            meta = if (device.label != null) device.nodeKeyId?.let { shortKey(it) } else null,
            chips = if (device.thisDevice) {
                listOf(ChipSpec(localizedString("mobile.files_custody_this_device"), kind = ChipKind.CHOICE, tone = Tone.BRAND))
            } else emptyList(),
            flags = listOf(
                RowFlag(holdsText, tag = CustodyTags.holds(key), tone = if (holds is Holds.Unknown) Tone.DIM else Tone.INK),
                RowFlag(canOpenText, tag = CustodyTags.canOpen(key), tone = Tone.DIM),
            ) + listOfNotNull(reported?.let { RowFlag(it, tag = CustodyTags.reported(key), tone = Tone.DIM) }),
        )
        // The next cut, shown so the shape is learnable, and refused to /click
        // until it ships: `testableClickable(enabled = false)` registers no
        // handler, so automation can press it no more than a person can.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (holds != Holds.Here) ComingNext(localizedString("mobile.files_custody_copy_to", "device", name), CustodyTags.copy(key))
            if (holds == Holds.Here || holds is Holds.Received) ComingNext(localizedString("mobile.files_custody_remove_from", "device", name), CustodyTags.remove(key))
        }
    }
}

/** A next-cut action: visible, labelled "coming next", and not pressable by anyone. */
@Composable
private fun ComingNext(action: String, tag: String) {
    val t = CirisTheme.tokens
    val text = localizedString("mobile.files_custody_coming_next", "action", action)
    Text(
        text,
        style = CirisTheme.type.label,
        color = t.mute,
        modifier = Modifier.testableClickable(tag, text, enabled = false) {},
    )
}

@Composable
private fun Footnote(text: String, tag: String) {
    val t = CirisTheme.tokens
    Text(
        text,
        style = CirisTheme.type.body,
        color = t.dim,
        modifier = Modifier.padding(top = 4.dp).testable(tag, text),
    )
}
