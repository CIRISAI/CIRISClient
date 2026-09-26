package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.ceg.shortKey
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.ContactCodeResponse
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.Chip
import ai.ciris.mobile.shared.ui.primitives.ChipKind
import ai.ciris.mobile.shared.ui.primitives.ChipSpec
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.QrCode
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.viewmodels.ContactCodeNodes
import ai.ciris.mobile.shared.viewmodels.ContactCodeState
import ai.ciris.mobile.shared.viewmodels.MakeReachableState
import ai.ciris.mobile.shared.viewmodels.contactCodeRowTicked
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * **Share my contact code** (CSD-092) — the person's own code, as text and as
 * a QR, naming the devices they choose.
 *
 * One card, one state at a time, each under the CSD's tag: the code
 * (`card_contact_code`), nothing announced (`contact_code_unreachable`, and NO
 * QR: a code that reaches nobody is not drawn as if it worked), loading, and
 * the error — including a node older than 0.5.218, which is said as that and
 * not as an empty card.
 *
 * The code is an address, not a credential (CC 2.6.8(e)): nothing here calls
 * it proof of anything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactCodeCard(
    state: ContactCodeState,
    nodesChoice: ContactCodeNodes,
    ticked: Set<String>,
    refusal: NodeRefusal?,
    makeReachable: MakeReachableState,
    onChoose: (ContactCodeNodes) -> Unit,
    onToggleNode: (String) -> Unit,
    onMakeReachable: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    CardShell(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        tag = if (state is ContactCodeState.Ready) PeopleTags.CODE_CARD else null,
        accent = t.brand,
    ) {
        Text(localizedString("mobile.contact_code_title"), style = type.title, color = t.ink)
        Spacer(Modifier.height(8.dp))
        when (state) {
            ContactCodeState.Closed -> Unit
            ContactCodeState.Loading -> StateBlock(ListState.Loading, tag = PeopleTags.CODE_LOADING, inline = true)
            ContactCodeState.NodeTooOld -> StateBlock(
                ListState.Error(
                    title = localizedString("mobile.contact_code_error"),
                    body = localizedString("mobile.contact_code_node_too_old"),
                ),
                tag = PeopleTags.CODE_ERROR,
                inline = true,
            )
            is ContactCodeState.Failed -> StateBlock(
                ListState.Error(
                    title = localizedString("mobile.contact_code_error"),
                    // By id when the node gave one; an id the bundle lacks resolves
                    // to itself, so the node's English follows as the detail.
                    body = state.reasonId?.let { localizedString(it) },
                    detail = state.detail?.takeIf { it.isNotBlank() },
                ),
                tag = PeopleTags.CODE_ERROR,
                inline = true,
            )
            ContactCodeState.Unreachable -> Unreachable(makeReachable, onMakeReachable)
            is ContactCodeState.Ready -> Populated(state.code, nodesChoice, ticked, refusal, onChoose, onToggleNode)
        }
        Spacer(Modifier.height(8.dp))
        CirisTextButton(localizedString("common_close"), tag = PeopleTags.CODE_CLOSE, onClick = onClose)
    }
}

@Composable
private fun Unreachable(makeReachable: MakeReachableState, onMakeReachable: () -> Unit) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val sentence = localizedString("mobile.contact_code_unreachable")
    Text(sentence, style = type.body, color = t.ink, modifier = Modifier.testable(PeopleTags.CODE_UNREACHABLE, sentence))
    Spacer(Modifier.height(8.dp))
    CirisButton(
        label = if (makeReachable == MakeReachableState.Busy) localizedString("mobile.contact_code_make_reachable_busy")
        else localizedString("mobile.contact_code_make_reachable"),
        tag = PeopleTags.CODE_MAKE_REACHABLE,
        enabled = makeReachable != MakeReachableState.Busy,
        onClick = onMakeReachable,
    )
    val status = when (makeReachable) {
        // Both halves, because both are true: the binding is wide now, the
        // network announce waits for the node's next start.
        is MakeReachableState.Done -> localizedString("mobile.contact_code_made_reachable")
        is MakeReachableState.Failed -> localizedString("mobile.contact_code_make_reachable_failed") +
            (makeReachable.detail?.let { " $it" } ?: "")
        else -> null
    }
    status?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            it, style = type.body,
            color = if (makeReachable is MakeReachableState.Failed) t.danger else t.dim,
            modifier = Modifier.testable(PeopleTags.CODE_REACHABLE_STATUS, it),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Populated(
    code: ContactCodeResponse,
    nodesChoice: ContactCodeNodes,
    ticked: Set<String>,
    refusal: NodeRefusal?,
    onChoose: (ContactCodeNodes) -> Unit,
    onToggleNode: (String) -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val clipboard = LocalClipboardManager.current
    var copied by remember(code.code) { mutableStateOf(false) }
    val names = code.availableNodes.associate { n -> n.nodeKeyId to (n.label ?: shortKey(n.nodeKeyId)) }
    fun nameOf(id: String) = names[id] ?: shortKey(id)

    // ── The stale-picker refusal, said beside the code that is true now ─────
    refusal?.let { r ->
        StateBlock(
            ListState.Error(
                title = localizedString(r.reasonId ?: "mobile.contact_code_error"),
                body = localizedString("mobile.contact_code_list_reloaded"),
            ),
            tag = PeopleTags.CODE_REFUSAL,
            inline = true,
        )
        Spacer(Modifier.height(8.dp))
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        QrCode(
            value = code.qrValue,
            contentDescription = localizedString("mobile.contact_code_qr_desc"),
            tag = PeopleTags.CODE_QR,
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            code.code, style = type.signed, color = t.ink,
            modifier = Modifier.weight(1f).testable(PeopleTags.CODE_TEXT, code.code),
        )
        Spacer(Modifier.width(8.dp))
        CirisTextButton(
            if (copied) localizedString("mobile.contact_code_copied") else localizedString("mobile.contact_code_copy"),
            tag = PeopleTags.CODE_COPY,
            onClick = { clipboard.setText(AnnotatedString(code.code)); copied = true },
        )
    }
    Spacer(Modifier.height(6.dp))
    val included = includedSentence(code.includedNodes.map { nameOf(it.keyId) })
    Text(included, style = type.body, color = t.ink, modifier = Modifier.testable(PeopleTags.CODE_INCLUDED, included))
    if (code.nodesWithoutTransport.isNotEmpty()) {
        Text(
            localizedString(
                "mobile.contact_code_without_transport", "devices",
                code.nodesWithoutTransport.joinToString(", ") { nameOf(it) },
            ),
            style = type.body, color = t.dim,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(localizedString("mobile.contact_code_disclosure"), style = type.body, color = t.dim)

    // ── Which devices go in: the person's choice ────────────────────────────
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Triple(ContactCodeNodes.ALL, localizedString("mobile.contact_code_nodes_all"), PeopleTags.CODE_NODES_ALL),
            Triple(ContactCodeNodes.LIST, localizedString("mobile.contact_code_nodes_list"), PeopleTags.CODE_NODES_LIST),
            Triple(ContactCodeNodes.NONE, localizedString("mobile.contact_code_nodes_none"), PeopleTags.CODE_NODES_NONE),
        ).forEach { (mode, label, tag) ->
            Chip(ChipSpec(
                label = label,
                tag = tag,
                kind = ChipKind.CHOICE,
                selected = nodesChoice == mode,
                onClick = { onChoose(mode) },
            ))
        }
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        code.availableNodes.forEach { node ->
            Chip(ChipSpec(
                label = nameOf(node.nodeKeyId),
                tag = PeopleTags.codeNode(node.nodeKeyId),
                kind = ChipKind.FILTER,
                selected = contactCodeRowTicked(nodesChoice, ticked, node.nodeKeyId),
                onClick = { onToggleNode(node.nodeKeyId) },
            ))
        }
    }
    Spacer(Modifier.height(6.dp))
    val note = localizedString("mobile.contact_code_private_note")
    Text(note, style = type.body, color = t.dim, modifier = Modifier.testable(PeopleTags.CODE_PRIVATE_NOTE, note))
}

/** "This code includes 2 devices: Phone, Laptop." — read back from the node, not the picker. */
@Composable
private fun includedSentence(names: List<String>): String = when (names.size) {
    0 -> localizedString("mobile.contact_code_included_none")
    1 -> localizedString("mobile.contact_code_included_one", "device", names.single())
    else -> localizedString(
        "mobile.contact_code_included_many",
        mapOf("count" to names.size.toString(), "devices" to names.joinToString(", ")),
    )
}
