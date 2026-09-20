package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * PRIMITIVE 9 · CeremonyBlock — a proposal plus who has signed. Rosters,
 * votes, the brake.
 *
 * A roster is never edited; it is replaced by a ceremony and the old one stays
 * in the record (CC 3.3.4 / 3.3.5). This block is the ceremony on screen:
 * the proposal, who proposed it and when, each signer's state, and the
 * "2 of 3 signed" line that says how close it is. Signing and saying no are
 * two different verbs on two different buttons.
 */
enum class SignerState { PROPOSED, SIGNED, NOT_YET }

@Immutable
data class Signer(val name: String, val state: SignerState)

/** Pure: how a signer's chip reads — proposed = brand, signed = ok, not yet = mute. */
fun SignerState.tone(): Tone = when (this) {
    SignerState.PROPOSED -> Tone.BRAND
    SignerState.SIGNED -> Tone.OK
    SignerState.NOT_YET -> Tone.MUTE
}

@Composable
fun CeremonyBlock(
    proposal: String,
    proposedBy: String,
    whenText: String,
    signers: List<Signer>,
    needed: Int,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    onSign: (() -> Unit)? = null,
    onRefuse: (() -> Unit)? = null,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val signed = signers.count { it.state == SignerState.SIGNED }
    CardShell(modifier = modifier, tag = "ceremony_$tagPrefix", accent = t.brand) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(proposal, style = type.title, color = t.ink, modifier = Modifier.weight(1f))
            Text(
                localizedString("mobile.ceremony_signed_of", mapOf("signed" to signed.toString(), "needed" to needed.toString())),
                style = type.label, color = t.mute,
                modifier = Modifier.testable("${tagPrefix}_signed_of", "$signed/$needed"),
            )
        }
        Text(
            localizedString("mobile.ceremony_proposed_by", mapOf("who" to proposedBy, "when" to whenText)),
            style = type.body, color = t.dim,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in signers) {
                val stateText = when (s.state) {
                    SignerState.PROPOSED -> localizedString("mobile.ceremony_state_proposed")
                    SignerState.SIGNED -> localizedString("mobile.ceremony_state_signed")
                    SignerState.NOT_YET -> localizedString("mobile.ceremony_state_not_yet")
                }
                Chip(ChipSpec(label = "${s.name} · $stateText", tone = s.state.tone(),
                    glyph = if (s.state == SignerState.SIGNED) GlyphName.CHECK else null,
                    tag = "${tagPrefix}_signer_${s.name.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"))
            }
        }
        if (note != null) {
            Spacer(Modifier.height(10.dp))
            Text(note, style = type.body, color = t.dim)
        }
        if (onSign != null || onRefuse != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSign != null) CirisButton(localizedString("mobile.ceremony_sign"), tag = "btn_${tagPrefix}_sign", onClick = onSign)
                if (onRefuse != null) CirisTextButton(localizedString("mobile.ceremony_refuse"), tag = "btn_${tagPrefix}_refuse", onClick = onRefuse)
            }
        }
    }
}
