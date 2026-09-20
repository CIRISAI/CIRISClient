package ai.ciris.mobile.shared.ui.theme

import ai.ciris.mobile.shared.ui.nav.CohortScope
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * THE SIXTEEN TOKENS, RESOLVED PER GROUND.
 *
 * This is the only file in the client that may name a colour. Everything
 * else reads a token by name through [CirisTheme]; `client/tools/
 * check_colour_literals.py` refuses a literal anywhere else.
 *
 * Two grounds: PAPER (light, the default a person opens into) and INSTRUMENT
 * (dark). Three rules the design learned the hard way (design handoff,
 * "Design tokens"):
 *
 *  1. Surfaces do not invert. `raised` is lighter than `ground` in BOTH
 *     grounds and `sunken` is darker in both. Only `ink` reverses.
 *  2. Colour re-resolves, never inverts. Same hue per token; lightness moves
 *     only as far as contrast demands.
 *  3. Every text token clears 4.5:1 against the worst surface it can sit on,
 *     and the surface list is CLOSED AT THREE. A fourth surface (`#1A222C`,
 *     the old `--bg-card`) is how three tokens quietly fell under 4.5.
 *     `CirisTokensContrastTest` proves the ratios; do not "restore" older
 *     values without re-measuring.
 *
 * Deliberate deviations from the older palette: `mute` dark `#6B7280 →
 * #7E8693` (was 3.91:1); `circle.neighbours` dark `#7A6FD6 → #8278D8` (was
 * 4.18:1 on raised); `circle.self` paper `#0E7C99 → #0D7691` (was 4.51:1 on
 * sunken); brand needs two values — the old `#E5399F` is 3.88:1 on white;
 * `ok` paper `#1D7F45 → #1B7A42` (was 4.43:1 on sunken, found by the test); and
 * `sunken` instrument `#0F141B → #0A0E13` (was lighter than ground, found by the test).
 */
@Immutable
data class CirisTokens(
    /** The page. */
    val ground: Color,
    /** A card. Lighter than ground in both grounds. */
    val raised: Color,
    /** An input well, a selected chip. Darker than ground in both grounds. */
    val sunken: Color,
    /** The only border the product draws. */
    val hairline: Color,
    /** A hairline that must read on its own (outlined chip, focus). */
    val hairlineStrong: Color,
    /** Primary text. The one token that reverses between grounds. */
    val ink: Color,
    /** Secondary text. */
    val dim: Color,
    /** Labels and metadata — the quietest legal text colour. */
    val mute: Color,
    /** The CIRIS magenta. Two values, because one cannot clear 4.5:1 on both grounds. */
    val brand: Color,
    val circleSelf: Color,
    val circleFamily: Color,
    val circleNeighbours: Color,
    val circleOrgs: Color,
    val circleEveryone: Color,
    /** Good. NEVER used for an error, an unreviewed state, or anything that merely finished. */
    val ok: Color,
    /** Wrong, gone, or the one interruption the product allows. */
    val danger: Color,
) {
    /**
     * The circle colour for a cohort scope. The only mapping from scope to
     * colour in the product; `ScopePill` is the only composable that renders it.
     */
    fun circle(scope: CohortScope): Color = when (scope) {
        CohortScope.AGENT -> circleSelf
        CohortScope.FAMILY -> circleFamily
        CohortScope.LOCAL_COMMUNITY -> circleNeighbours
        CohortScope.GLOBAL_COMMUNITIES -> circleOrgs
        CohortScope.GLOBAL_COMMONS -> circleEveryone
    }

    /** Text drawn ON a brand / ok / danger fill. Ink on brand is 3.26:1 — never that. */
    val onAccent: Color get() = ground

    /** The three surfaces, in the order a contrast audit walks them. There is no fourth. */
    val surfaces: List<Color> get() = listOf(ground, raised, sunken)

    /** Every token that is drawn as text or glyph on a surface. */
    val foregrounds: List<Pair<String, Color>>
        get() = listOf(
            "ink" to ink, "dim" to dim, "mute" to mute, "brand" to brand,
            "circle.self" to circleSelf, "circle.family" to circleFamily,
            "circle.neighbours" to circleNeighbours, "circle.orgs" to circleOrgs,
            "circle.everyone" to circleEveryone, "ok" to ok, "danger" to danger,
        )
}

/** Light. The default a person opens into. */
val PaperTokens = CirisTokens(
    ground = Color(0xFFFCFAFC),
    raised = Color(0xFFFFFFFF),
    sunken = Color(0xFFF4EFF4),
    hairline = Color(0xFFE8E2E8),
    hairlineStrong = Color(0xFFD8D0D8),
    ink = Color(0xFF17141B),
    dim = Color(0xFF4B4653),
    mute = Color(0xFF6E6875),
    brand = Color(0xFFC4157F),
    circleSelf = Color(0xFF0D7691),
    circleFamily = Color(0xFF2C6F72),
    circleNeighbours = Color(0xFF5B4FC0),
    circleOrgs = Color(0xFFA8511F),
    circleEveryone = Color(0xFF7A5B12),
    // The design shipped #1D7F45, which is 4.43:1 on sunken. Same hue, one step
    // darker, clears it (4.73) — rule 2 applied, and the test pins it.
    ok = Color(0xFF1B7A42),
    danger = Color(0xFFB42318),
)

/** Dark. */
val InstrumentTokens = CirisTokens(
    ground = Color(0xFF0D1117),
    raised = Color(0xFF151B24),
    // The design shipped #0F141B, a hair LIGHTER than ground (#0D1117), which
    // breaks its own rule 1. One step darker keeps the rule; the test pins it.
    sunken = Color(0xFF0A0E13),
    hairline = Color(0xFF1E242D),
    hairlineStrong = Color(0xFF2A313B),
    ink = Color(0xFFF4F5F7),
    dim = Color(0xFF9AA3AF),
    mute = Color(0xFF7E8693),
    brand = Color(0xFFF25FB8),
    circleSelf = Color(0xFF22C0E8),
    circleFamily = Color(0xFF419CA0),
    circleNeighbours = Color(0xFF8278D8),
    circleOrgs = Color(0xFFC96A38),
    circleEveryone = Color(0xFFB08A3E),
    ok = Color(0xFF4ADE80),
    danger = Color(0xFFF87171),
)

/**
 * A text or glyph colour named by ROLE, for data that has to say which tone it
 * wants without holding a Color (formatters, state styles — pure Kotlin, testable).
 */
enum class Tone { INK, DIM, MUTE, BRAND, OK, DANGER }

fun CirisTokens.tone(t: Tone): Color = when (t) {
    Tone.INK -> ink
    Tone.DIM -> dim
    Tone.MUTE -> mute
    Tone.BRAND -> brand
    Tone.OK -> ok
    Tone.DANGER -> danger
}

/** Which of the two grounds is on screen. */
enum class Ground {
    PAPER, INSTRUMENT;

    val tokens: CirisTokens get() = if (this == PAPER) PaperTokens else InstrumentTokens
}
