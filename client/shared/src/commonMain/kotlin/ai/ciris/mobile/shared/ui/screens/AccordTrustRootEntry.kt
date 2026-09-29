package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.ItemRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.viewmodels.FamilyHistoryState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The Accord card's door into **this node's trust root** (`TrustRootScreen`).
 * The accord family is the default root; this row is where a person asks
 * whether THIS node trusts it, adopts another seed, or un-trusts one.
 */
@Composable
internal fun AccordTrustRootEntry(onOpen: () -> Unit) {
    ItemRow(
        glyph = GlyphName.ROOT,
        title = localizedString("mobile.accord_open_trust_root"),
        secondary = localizedString("mobile.accord_open_trust_root_meta"),
        tag = "btn_accord_open_trust_root",
        onClick = onOpen,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * The accord family's version chain (`GET /v1/accord/family/history`). Newest
 * first. Genesis and quorum-signed changes read differently, and a failed read
 * is never drawn as a family with no history.
 */
@Composable
internal fun AccordFamilyHistorySection(state: FamilyHistoryState) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(localizedString("mobile.accord_family_history_title"), style = type.title, color = t.ink)
        when (state) {
            FamilyHistoryState.Loading -> StateBlock(ListState.Loading, tag = "accord_family_history_loading", inline = true)
            is FamilyHistoryState.Failed -> TrustRootFailureBlock(state.failure, tagPrefix = "accord_family_history", readFailure = false)
            is FamilyHistoryState.Loaded -> {
                if (state.versions.isEmpty()) {
                    StateBlock(
                        ListState.Empty(localizedString("mobile.accord_family_history_empty")),
                        tag = "accord_family_history_empty",
                        inline = true,
                    )
                }
                state.versions.forEach { v ->
                    val title = localizedString("mobile.accord_family_version_title", "n", v.version.toString()) +
                        if (v.isCurrent) " · " + localizedString("mobile.accord_family_version_current") else ""
                    val how = if (v.byQuorum) localizedString("mobile.accord_family_version_by_quorum")
                    else localizedString("mobile.accord_family_version_genesis")
                    val meta = localizedString(
                        "mobile.accord_family_version_meta",
                        mapOf("protocol" to (v.consensusProtocol ?: "—"), "count" to v.members.size.toString()),
                    )
                    ItemRow(
                        glyph = if (v.isCurrent) GlyphName.HOLDERS else GlyphName.HOLD,
                        title = title,
                        meta = meta,
                        secondary = listOfNotNull(
                            how,
                            v.supersededAt?.let { localizedString("mobile.accord_family_version_superseded", "at", it) },
                        ).joinToString(" · "),
                        tag = "row_family_version_${v.version}",
                    )
                }
            }
        }
    }
}
