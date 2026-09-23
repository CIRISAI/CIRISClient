package ai.ciris.mobile.shared.ui.shell

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.rememberTestableScrollState
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.platform.testableWithHandler
import ai.ciris.mobile.shared.ui.components.CIRISSignet
import ai.ciris.mobile.shared.ui.glyphs.Glyph
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.nav.CirclesNav
import ai.ciris.mobile.shared.ui.nav.CohortScope
import ai.ciris.mobile.shared.ui.nav.Tab
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.LocalLayoutBand
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE SHELL — phone first; the desktop is the phone with room.
 *
 * Five circles live in a fixed bottom bar, the seven tabs scroll above the
 * content, and the two things that cannot be in either — who you are (My
 * things, the avatar at top left) and the way to stop (top right, every
 * circle, every tab, above the scroll) — sit in the top bar where a thumb can
 * always reach them. Never a sixth item in the bar, never reordered, never a
 * drawer.
 *
 * On a large screen three things move and nothing is added (locked spec §1):
 * ≥ 560 field rows go two-column (FieldRow's business), ≥ 700 the tabs stop
 * scrolling, ≥ 900 the bottom bar becomes a left rail with subtitles and the
 * instruments under it. `LocalLayoutBand` says which.
 *
 * Counts in the rail ("31 people") are deliberately absent until the wire
 * supplies a per-circle count; a made-up number would be a lie in a rail.
 */
@Composable
fun CirclesShell(
    circle: CohortScope,
    tab: Tab?,
    hasAgent: Boolean,
    /** A back affordance when a card was opened from a list; null on a tab or a single-card tab. */
    onBack: (() -> Unit)?,
    onCircle: (CohortScope) -> Unit,
    onTab: (Tab) -> Unit,
    onMyThings: () -> Unit,
    onStop: () -> Unit,
    /** The open card's plain name — the title the screen no longer draws for itself. */
    cardTitle: String? = null,
    rail: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val band = LocalLayoutBand.current
    val t = CirisTheme.tokens
    // The left side can be put away. Open by default where there is room for
    // it; the mark in the top bar is the one control, at every width.
    var railOpen by rememberSaveable { mutableStateOf(true) }
    val railShown = band.rail && railOpen
    // What the hosted screen says it is showing right now. Null until one
    // publishes; then it wins over the card's name, because a step ("Preview")
    // and a leaf (a chat, a federation page) know what they are and the card
    // does not.
    val publishedTitle = rememberShellCardTitleSlot()
    if (band.rail) {
        Row(modifier = Modifier.fillMaxSize().background(t.ground)) {
            if (railShown) {
                Rail(circle, hasAgent, onCircle, rail)
                VerticalDivider(thickness = CirisShape.hairlineWidth, color = t.hairline)
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                TopBar(circle, onMyThings, onStop, compact = false, railOpen = railShown,
                    onToggleRail = { railOpen = !railOpen })
                TabStrip(circle, tab, hasAgent, onTab, fits = band.tabsFit)
                CardHeader(cardTitle, onBack, publishedTitle.value)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(LocalShellCardTitle provides publishedTitle) { content() }
                }
                // Put the side away and the circles come back as the bar they
                // are on a phone. They are the one piece of chrome that never
                // moves (locked spec §1) — a toggle that could hide them would
                // be a toggle that hides the product.
                if (!railShown) BottomBar(circle, onCircle)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().background(t.ground)) {
            TopBar(circle, onMyThings, onStop, compact = true, railOpen = false, onToggleRail = null)
            TabStrip(circle, tab, hasAgent, onTab, fits = band.tabsFit)
            CardHeader(cardTitle, onBack, publishedTitle.value)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalShellCardTitle provides publishedTitle) { content() }
            }
            BottomBar(circle, onCircle)
        }
    }
}

// ── Top bar ──────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    circle: CohortScope,
    onMyThings: () -> Unit,
    onStop: () -> Unit,
    compact: Boolean,
    railOpen: Boolean,
    /** Non-null only where there is a rail to put away (≥900dp). */
    onToggleRail: (() -> Unit)?,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val colour = t.circle(circle)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.ground)
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testable("shell_top_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The mark, top left: it opens and closes the left side wherever there
        // is one, and is My things where there is not — one icon, one place,
        // and on a narrow window My things IS the left side, as a sheet.
        if (onToggleRail != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (railOpen) t.sunken else t.raised)
                    .border(CirisShape.hairlineWidth, t.hairlineStrong, CircleShape)
                    .testableClickable(
                        CirclesNav.RAIL_TOGGLE_TAG,
                        localizedString(if (railOpen) "nav.rail_close" else "nav.rail_open"),
                    ) { onToggleRail() },
                contentAlignment = Alignment.Center,
            ) {
                RailMark(open = railOpen, label = localizedString(if (railOpen) "nav.rail_close" else "nav.rail_open"))
            }
        }
        // My things: the avatar. Not a sixth circle, so it cannot join the bar.
        val myThingsLabel = localizedString("nav.my_things")
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(t.raised)
                .border(CirisShape.hairlineWidth, t.hairlineStrong, CircleShape)
                .testableClickable(CirclesNav.MY_THINGS_TAG, localizedString("nav.my_things")) { onMyThings() },
            contentAlignment = Alignment.Center,
        ) {
            CIRISSignet(
                modifier = Modifier.size(24.dp).semantics { contentDescription = myThingsLabel },
                tintColor = t.brand,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(colour))
                Text(
                    localizedString(CirclesNav.circleNameKey(circle)),
                    style = type.title, color = t.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testable("shell_circle_name"),
                )
            }
            Text(
                localizedString(CirclesNav.circleRuleKey(circle)),
                style = type.label, color = t.mute,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // Stop everything — always on screen, above the scroll.
        Row(
            modifier = Modifier
                .clip(CirisShape.chip)
                .background(t.danger.copy(alpha = 0.10f).compositeOver(t.ground))
                .border(CirisShape.hairlineWidth, t.danger.copy(alpha = 0.45f).compositeOver(t.ground), CirisShape.chip)
                .testableClickable(CirclesNav.STOP_TAG, localizedString("nav.stop_everything")) { onStop() }
                .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Glyph(GlyphName.STOP, tint = t.danger, size = 16.dp, contentDescription = localizedString("nav.stop_everything"))
            if (!compact) Text(localizedString("nav.stop_everything"), style = type.label, color = t.danger)
        }
    }
}

private fun Modifier.mirrored(): Modifier = this.scale(scaleX = -1f, scaleY = 1f)

/**
 * The mark on the rail toggle: a page with its side band filled when the side
 * is showing, hollow when it is away.
 *
 * NOT AN ARROW. The first pass drew a mirrored `ARROW_FORWARD` here, which put
 * two left-pointing arrows within 90dp of each other — one meaning "hide the
 * side", the other "go back" — in the corner of a shell whose whole point this
 * week was that back means one thing and lives in one place. The 71 glyphs
 * carry no panel icon and inventing a path would put it outside the generated
 * set, so this is drawn from the primitives instead: two boxes and a border,
 * in tokens, showing the thing it toggles.
 */
@Composable
private fun RailMark(open: Boolean, label: String) {
    val t = CirisTheme.tokens
    Row(
        modifier = Modifier
            .width(20.dp)
            .height(15.dp)
            .clip(CirisShape.input)
            .border(CirisShape.hairlineWidth, t.dim, CirisShape.input)
            .semantics { contentDescription = label },
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(if (open) t.dim else t.ground))
    }
}

// ── The open card: its name, and the one back ────────────────────────────────

/**
 * ONE BACK, ONE TITLE. A card screen inside the shell draws neither: this row
 * does, directly under the tabs, so back always sits in the same place and
 * always means the same thing — the tab or the instrument this card was opened
 * from. A tab is not a sub-screen, so on a tab there is nothing to go back to
 * and the arrow is absent rather than disabled.
 */
@Composable
private fun CardHeader(title: String?, onBack: (() -> Unit)?, published: (@Composable () -> Unit)?) {
    if (title == null && onBack == null && published == null) return
    val t = CirisTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.ground)
            .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .testable("shell_card_header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            val label = localizedString("nav.back")
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).testableClickable("btn_nav_back", label) { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                // The label is the screen reader's only handle on this control:
                // the per-screen arrows that carried one are gone, and a test
                // tag is not a description.
                Glyph(
                    GlyphName.ARROW_FORWARD, tint = t.dim, size = 18.dp,
                    contentDescription = label, modifier = Modifier.mirrored(),
                )
            }
        } else {
            Spacer(Modifier.width(6.dp))
        }
        if (published != null) {
            Box(modifier = Modifier.testable("shell_card_title")) {
                ProvideTextStyle(CirisTheme.type.title.copy(color = t.ink)) { published() }
            }
        } else if (title != null) {
            Text(
                title,
                style = CirisTheme.type.title, color = t.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testable("shell_card_title"),
            )
        }
    }
}

// ── Tabs ─────────────────────────────────────────────────────────────────────

@Composable
private fun TabStrip(circle: CohortScope, tab: Tab?, hasAgent: Boolean, onTab: (Tab) -> Unit, fits: Boolean) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val scroll = rememberTestableScrollState(name = CirclesNav.TABS_SCROLLABLE)
    val rowModifier = if (fits) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().horizontalScroll(scroll)
    Column(modifier = Modifier.fillMaxWidth().background(t.ground)) {
        Row(
            modifier = rowModifier.padding(horizontal = 8.dp),
            horizontalArrangement = if (fits) Arrangement.SpaceBetween else Arrangement.spacedBy(2.dp),
        ) {
            for (x in Tab.entries) {
                val on = x == tab
                val hasCards = CirclesNav.cards(circle, x, hasAgent).isNotEmpty()
                Column(
                    modifier = Modifier
                        .clip(CirisShape.chip)
                        .testableClickable(x.tag, localizedString(x.labelKey)) { onTab(x) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        localizedString(x.labelKey),
                        style = type.body,
                        color = when { on -> t.ink; hasCards -> t.dim; else -> t.mute },
                        maxLines = 1,
                    )
                    Box(Modifier.width(22.dp).height(2.dp).background(if (on) t.circle(circle) else t.ground))
                }
            }
        }
        HorizontalDivider(thickness = CirisShape.hairlineWidth, color = t.hairline)
    }
}

// ── The five circles: bottom bar (phone) ─────────────────────────────────────

@Composable
private fun BottomBar(circle: CohortScope, onCircle: (CohortScope) -> Unit) {
    val t = CirisTheme.tokens
    Column(modifier = Modifier.fillMaxWidth().background(t.raised).testable("shell_bottom_bar")) {
        HorizontalDivider(thickness = CirisShape.hairlineWidth, color = t.hairline)
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (c in CirclesNav.circles) {
                val on = c == circle
                val colour = t.circle(c)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CirisShape.card)
                        .testableClickable(CirclesNav.circleTag(c), localizedString(CirclesNav.circleNameKey(c))) { onCircle(c) }
                        .padding(vertical = 4.dp)
                        .alpha(if (on) 1f else 0.4f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Glyph(circleGlyph(c), tint = if (on) colour else t.ink, size = 21.dp)
                    Text(
                        localizedString(CirclesNav.circleNameKey(c)),
                        fontSize = 8.5.sp, lineHeight = 10.sp,
                        color = if (on) colour else t.ink,
                        maxLines = 2, textAlign = TextAlign.Center,
                        style = CirisTheme.type.label.copy(letterSpacing = 0.sp),
                        softWrap = true,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

// ── The five circles: left rail (≥900dp) ─────────────────────────────────────

@Composable
private fun Rail(
    circle: CohortScope,
    hasAgent: Boolean,
    onCircle: (CohortScope) -> Unit,
    below: @Composable () -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val scroll = rememberTestableScrollState(name = CirclesNav.RAIL_SCROLLABLE)
    Column(
        modifier = Modifier
            .width(208.dp)
            .fillMaxHeight()
            .background(t.raised)
            .statusBarsPadding()
            .verticalScroll(scroll)
            .testable("shell_rail")
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CIRISSignet(modifier = Modifier.size(20.dp), tintColor = t.brand)
            Text("CIRIS", style = type.label, color = t.ink)
        }
        Spacer(Modifier.height(6.dp))
        for (c in CirclesNav.circles) {
            val on = c == circle
            val colour = t.circle(c)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .clip(CirisShape.card)
                    .background(if (on) colour.copy(alpha = 0.16f).compositeOver(t.raised) else t.raised)
                    .testableClickable(CirclesNav.circleTag(c), localizedString(CirclesNav.circleNameKey(c))) { onCircle(c) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.width(2.dp).height(28.dp).background(if (on) colour else t.raised))
                Box(Modifier.size(9.dp).clip(CircleShape).background(colour))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        localizedString(CirclesNav.circleNameKey(c)),
                        style = type.body, color = if (on) t.ink else t.dim,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        localizedString(CirclesNav.circleSubtitleKey(c)),
                        style = type.label, color = t.mute, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(thickness = CirisShape.hairlineWidth, color = t.hairline, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(10.dp))
        below()
    }
}

/** The glyph each circle wears in the bar and the rail. */
fun circleGlyph(c: CohortScope): GlyphName = when (c) {
    CohortScope.AGENT -> GlyphName.CIRCLE_SELF
    CohortScope.FAMILY -> GlyphName.CIRCLE_FAMILY
    CohortScope.LOCAL_COMMUNITY -> GlyphName.CIRCLE_NEIGHBOURS
    CohortScope.GLOBAL_COMMUNITIES -> GlyphName.CIRCLE_ORGS
    CohortScope.GLOBAL_COMMONS -> GlyphName.GLOBE
}

/** A rail row for an instrument, used by the rail's `below` slot and by the My things sheet. */
@Composable
fun InstrumentRow(
    label: String,
    glyph: GlyphName,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val t = CirisTheme.tokens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(CirisShape.card)
            .background(if (selected) t.sunken else t.raised)
            .testableClickable(tag, label) { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(glyph, tint = if (selected) t.ink else t.dim, size = 18.dp)
        Text(label, style = CirisTheme.type.body, color = if (selected) t.ink else t.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
