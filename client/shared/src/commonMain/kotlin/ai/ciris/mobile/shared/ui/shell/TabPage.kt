package ai.ciris.mobile.shared.ui.shell

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.nav.CirclesNav
import ai.ciris.mobile.shared.ui.nav.CohortScope
import ai.ciris.mobile.shared.ui.nav.Instrument
import ai.ciris.mobile.shared.ui.nav.NavSurface
import ai.ciris.mobile.shared.ui.nav.Tab
import ai.ciris.mobile.shared.ui.primitives.ItemRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A tab in a circle: the cards that live here, or the honest sentence when
 * nothing does. (A tab with exactly one card never renders this — the shell
 * shows that card directly — so this is the list, or the empty.)
 *
 * The four honest zeroes are facts about data, not about the build:
 * Decisions in Just me saying "there is nobody here to decide with" is a
 * finished screen, not a gap.
 */
@Composable
fun TabPage(
    circle: CohortScope,
    tab: Tab,
    hasAgent: Boolean,
    onOpen: (NavSurface) -> Unit,
) {
    val cards = CirclesNav.cards(circle, tab, hasAgent)
    if (cards.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            StateBlock(
                ListState.Empty(localizedString(CirclesNav.emptyKey(circle, tab)), glyph = tab.glyph),
                tag = "tab_empty_${tab.id}",
            )
        }
        return
    }
    SurfaceList(cards, tag = "tab_cards_${tab.id}", onOpen = onOpen)
}

/** An instrument under My things: its surfaces as rows. */
@Composable
fun InstrumentPage(instrument: Instrument, hasAgent: Boolean, onOpen: (NavSurface) -> Unit) {
    SurfaceList(instrument.surfaces(hasAgent), tag = "instrument_${instrument.id.replace('-', '_')}", onOpen = onOpen)
}

/**
 * Rows of surfaces. These rows are app chrome — a way to a screen, not a
 * signed claim — so they carry no receipt and therefore no hamburger.
 */
@Composable
private fun SurfaceList(surfaces: List<NavSurface>, tag: String, onOpen: (NavSurface) -> Unit) {
    val t = CirisTheme.tokens
    LazyColumn(
        modifier = Modifier.fillMaxSize().testable(tag),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(surfaces, key = { it.id }) { s ->
            ItemRow(
                glyph = GlyphName.CIRCLE,
                glyphTint = t.dim,
                title = surfaceLabel(s),
                tag = CirclesNav.navTag(s),
                onClick = { onOpen(s) },
                leading = { Icon(s.icon, contentDescription = null, tint = t.dim, modifier = Modifier.size(20.dp)) },
            )
        }
    }
}

/** The surface's plain name: its localization key when the locale has it, else its English label. */
@Composable
fun surfaceLabel(s: NavSurface): String {
    val key = s.labelKey ?: return s.label
    val v = localizedString(key)
    return if (v == key) s.label else v
}
