package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.ceg.shortKey
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.EvictPart
import ai.ciris.mobile.shared.models.federation.EvictionReport
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.Glyph
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The bundle key that names one part of an eviction. Pure, so a test can pin
 * that every part the server documents has its own sentence and an unknown
 * part lands on the generic one (never the raw token on screen).
 */
fun evictPartKey(part: String): String =
    if (part in EvictPart.KNOWN) "mobile.evict_part_$part" else "mobile.evict_part_other"

/** Tags of an [EvictionIncompleteBlock] under [prefix] — the block, each part, and the two buttons. */
object EvictionTags {
    fun block(prefix: String) = "${prefix}_incomplete"
    fun part(prefix: String, index: Int) = "${prefix}_part_$index"
    fun retry(prefix: String) = "btn_${prefix}_retry"
    fun dismiss(prefix: String) = "btn_${prefix}_dismiss"
    fun history(prefix: String) = "${prefix}_history_note"
}

/**
 * **An eviction that did not finish, part by part** (ciris-server 0.5.218,
 * `self.evict_incomplete` / `self.release_incomplete`, CIRISServer#700).
 *
 * The node's answer names every part done and every part not done, and says
 * that what was done STAYS done. Before this the client showed the 500 as raw
 * text. Now: the id's sentence as the headline, one line per part (done / not
 * done, what it was about), the history note — already-shared history stays
 * readable by the removed device, because eviction rotates nothing — and Try
 * again, which re-runs the same act (the node skips what is already done).
 */
@Composable
fun EvictionIncompleteBlock(
    refusal: NodeRefusal,
    report: EvictionReport,
    tagPrefix: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    retrying: Boolean = false,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val headline = refusal.reasonId?.let { id -> localizedString(id).takeIf { it != id && it.isNotBlank() } }
        ?: refusal.detail
        ?: localizedString("mobile.evict_incomplete_title")
    CardShell(
        modifier = Modifier.padding(vertical = 8.dp),
        tag = EvictionTags.block(tagPrefix),
        accent = t.danger,
    ) {
        Text(headline, style = type.title, color = t.ink)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            report.parts.forEachIndexed { i, part ->
                val status = localizedString(if (part.done) "mobile.evict_part_done" else "mobile.evict_part_not_done")
                val what = localizedString(evictPartKey(part.part), "part", part.part)
                val line = "$status · $what"
                Row(
                    modifier = Modifier.fillMaxWidth().testable(EvictionTags.part(tagPrefix, i), "$line · ${part.target}"),
                    verticalAlignment = Alignment.Top,
                ) {
                    Glyph(
                        if (part.done) GlyphName.CHECK else GlyphName.ERROR,
                        tint = if (part.done) t.ok else t.danger,
                        size = 16.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(line, style = type.body, color = t.ink)
                        if (part.target.isNotBlank()) {
                            Text(shortKey(part.target, head = 16, tail = 0), style = type.signed, color = t.mute)
                        }
                        part.error?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = type.signed, color = t.mute)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val note = localizedString("mobile.evict_history_note")
        Text(note, style = type.body, color = t.dim, modifier = Modifier.testable(EvictionTags.history(tagPrefix), note))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CirisButton(
                label = localizedString("mobile.evict_retry"),
                tag = EvictionTags.retry(tagPrefix),
                enabled = !retrying,
                onClick = { if (!retrying) onRetry() },
            )
            CirisTextButton(localizedString("mobile.common_dismiss"), tag = EvictionTags.dismiss(tagPrefix), onClick = onDismiss)
        }
    }
}
