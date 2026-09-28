package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.ceg.Envelope
import ai.ciris.mobile.shared.ceg.EnvelopeMember
import ai.ciris.mobile.shared.ceg.cohortScopeOf
import ai.ciris.mobile.shared.ceg.shortKey
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.Glyph
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * PRIMITIVE 4 · ReceiptSheet — the five facts, generic over any claim. Built
 * once. Long-press on any ItemRow, or its hamburger, opens this; the same
 * five facts and the same acts on a phone as on a desktop, never a reduced set.
 *
 * Each row is a [FieldRow] with the plain label from the envelope member's
 * localization key and the protocol field name under it in mono. A
 * [Fact.NotSent] renders "This node did not send this." in the error tone —
 * deliberately not the empty treatment: an absent fact is a fact about the
 * node, and a person should see it as such.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSheet(
    receipt: Receipt,
    onDismiss: () -> Unit,
    tag: String = "sheet_receipt",
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = t.raised,
        contentColor = t.ink,
        shape = CirisShape.sheet,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testable(tag, receipt.id)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph(GlyphName.RECEIPT, tint = t.brand, size = 18.dp)
                Text(localizedString("mobile.receipt_title"), style = type.title, color = t.ink)
            }
            Spacer(Modifier.height(6.dp))

            FactRow(Envelope.subjectKeyIds, receipt.subject, "receipt_subject", keyish = true)
            FactRow(Envelope.attestingKeyId, receipt.attester, "receipt_attester", keyish = true)
            ScopeRow(receipt.scope, receipt.scopeNote)
            FactRow(Envelope.dimension, receipt.dimensionValue, "receipt_dimension", keyish = false)
            FactRow(Envelope.consentScope, receipt.rule, "receipt_rule", keyish = false, divider = receipt.forAgent != null)
            receipt.forAgent?.let { FactRow(ForAgentMember, it, "receipt_for_agent", keyish = true, divider = false) }

            Spacer(Modifier.height(10.dp))
            FieldRow(
                label = localizedString("mobile.receipt_holders"),
                value = receipt.holders?.let { localizedString("mobile.receipt_holders_count", "count", it.toString()) }
                    ?: localizedString("ceg.envelope.not_sent"),
                tone = if (receipt.holders == null) Tone.DANGER else Tone.INK,
                mono = receipt.holders != null,
                tag = "receipt_holders",
            )
            FieldRow(
                label = localizedString("mobile.receipt_notes"),
                value = if (receipt.notes.isEmpty()) localizedString("mobile.receipt_notes_none")
                else receipt.notes.joinToString("\n") { "${it.by}: ${it.text}" },
                tone = if (receipt.notes.isEmpty()) Tone.DIM else Tone.INK,
                tag = "receipt_notes",
                divider = receipt.acts.isNotEmpty(),
            )
            if (receipt.acts.isNotEmpty()) {
                Text(
                    localizedString("mobile.receipt_acts").uppercase(),
                    style = type.label, color = t.mute,
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (act in receipt.acts) {
                        Chip(ChipSpec(label = act.label, tag = act.tag, kind = ChipKind.CHOICE, tone = Tone.BRAND, onClick = act.onAct))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CirisTextButton(localizedString("mobile.receipt_close"), tag = "btn_receipt_close", onClick = onDismiss)
            }
        }
    }
}

/**
 * `for_key_id` — the agent a consent grant is FOR. Not a CC 2.1 envelope
 * member (so not in [Envelope.all]); the grant payload names it (CC 3.3.7).
 */
private val ForAgentMember = EnvelopeMember(
    id = "forKeyId", wire = "for_key_id", ccSection = "3.3.7",
    labelKey = "mobile.receipt_for_agent", glossKey = "mobile.receipt_for_agent",
)

@Composable
private fun FactRow(member: EnvelopeMember, fact: Fact, tag: String, keyish: Boolean, divider: Boolean = true) {
    when (fact) {
        is Fact.Wire -> FieldRow(
            label = localizedString(member.labelKey), protocol = member.wire,
            value = if (keyish) shortKey(fact.value) else fact.value, mono = true, tag = tag, divider = divider,
            gloss = fact.gloss,
        )
        is Fact.ByRule -> FieldRow(
            label = localizedString(member.labelKey), protocol = member.wire,
            value = if (keyish) shortKey(fact.value) else fact.value, mono = true, tag = tag, divider = divider,
            gloss = localizedString("ceg.envelope.by_rule", "cc", fact.ccRef),
        )
        Fact.NotSent -> FieldRow(
            label = localizedString(member.labelKey), protocol = member.wire,
            value = localizedString("ceg.envelope.not_sent"), tone = Tone.DANGER, tag = tag, divider = divider,
        )
    }
}

@Composable
private fun ScopeRow(fact: Fact, note: String?) {
    val member = Envelope.cohortScope
    val wire = when (fact) { is Fact.Wire -> fact.value; is Fact.ByRule -> fact.value; Fact.NotSent -> null }
    val scope = wire?.let { cohortScopeOf(it) }
    if (scope != null) {
        FieldRow(
            label = localizedString(member.labelKey), protocol = member.wire, tag = "receipt_scope",
            gloss = listOfNotNull(
                (fact as? Fact.ByRule)?.let { localizedString("ceg.envelope.by_rule", "cc", it.ccRef) },
                note,
            ).joinToString(" ").ifBlank { null },
            input = { ScopePill(scope) },
        )
    } else {
        FactRow(member, fact, "receipt_scope", keyish = false)
    }
}
