package ai.ciris.mobile.shared.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.compositeOver

/**
 * ONE THEME, TWO GROUNDS.
 *
 * Provided once at the root of `CIRISApp` and read by name everywhere:
 *
 *     CirisTheme.tokens.mute
 *     CirisTheme.tokens.circle(CohortScope.LOCAL_COMMUNITY)
 *     CirisTheme.type.signed
 *
 * The ground follows the person's brightness preference (System / Light /
 * Dark) exactly as `MaterialTheme` did before; nothing about how the
 * preference is stored changed.
 *
 * THE MATERIAL BRIDGE. Every existing screen reads `MaterialTheme.colorScheme`
 * (1,620 call sites), so the theme also resolves Material's NEUTRAL slots from
 * the tokens — background, surfaces, outlines, on-colours, error — and leaves
 * the three accents (primary / secondary / tertiary) on the person's chosen
 * `ColorTheme`. That is what lets the old screens land on the two grounds
 * without being touched; the accents are a wave-2 decision. Surfaces here map
 * onto the three tokens and nothing else, so Material cannot reintroduce a
 * fourth surface through a container role. Typography is NOT fed to Material
 * in wave 0: `MaterialTheme` sets `LocalTextStyle`, so a scale change would
 * reflow every screen — the primitives read [CirisType] directly.
 */
val LocalCirisTokens = staticCompositionLocalOf<CirisTokens> { PaperTokens }
val LocalCirisType = staticCompositionLocalOf<CirisType> { CirisType.default() }
val LocalGround = staticCompositionLocalOf<Ground> { Ground.PAPER }

object CirisTheme {
    val tokens: CirisTokens
        @Composable @ReadOnlyComposable get() = LocalCirisTokens.current
    val type: CirisType
        @Composable @ReadOnlyComposable get() = LocalCirisType.current
    val ground: Ground
        @Composable @ReadOnlyComposable get() = LocalGround.current
}

@Composable
fun CirisTheme(
    ground: Ground,
    accent: ColorTheme,
    type: CirisType = CirisType.default(),
    content: @Composable () -> Unit,
) {
    val tokens = ground.tokens
    val colorScheme = remember(ground, accent) { materialSchemeFor(ground, tokens, accent) }
    CompositionLocalProvider(
        LocalCirisTokens provides tokens,
        LocalCirisType provides type,
        LocalGround provides ground,
    ) {
        MaterialTheme(colorScheme = colorScheme) {
            LayoutBandProvider(content)
        }
    }
}

/** Material's neutral slots from the tokens; the accents from the person's theme. */
private fun materialSchemeFor(ground: Ground, t: CirisTokens, accent: ColorTheme): ColorScheme {
    val onAccent = if (accent.primaryTextDark) t.ink else t.onAccent
    val errorContainer = t.danger.copy(alpha = 0.12f).compositeOver(t.sunken)
    val base = if (ground == Ground.INSTRUMENT) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent.primary,
        onPrimary = onAccent,
        primaryContainer = accent.primary.copy(alpha = 0.16f).compositeOver(t.sunken),
        onPrimaryContainer = t.ink,
        secondary = accent.secondary,
        onSecondary = onAccent,
        secondaryContainer = accent.secondary.copy(alpha = 0.16f).compositeOver(t.sunken),
        onSecondaryContainer = t.ink,
        tertiary = accent.tertiary,
        onTertiary = onAccent,
        tertiaryContainer = accent.tertiary.copy(alpha = 0.16f).compositeOver(t.sunken),
        onTertiaryContainer = t.ink,
        background = t.ground,
        onBackground = t.ink,
        surface = t.raised,
        onSurface = t.ink,
        surfaceVariant = t.sunken,
        onSurfaceVariant = t.dim,
        // Tonal elevation blends surfaceTint over the surface; tint == raised
        // makes it a no-op, which is how "no shadows anywhere" survives
        // Material's elevated containers.
        surfaceTint = t.raised,
        inverseSurface = t.ink,
        inverseOnSurface = t.ground,
        inversePrimary = accent.primary,
        error = t.danger,
        onError = t.onAccent,
        errorContainer = errorContainer,
        onErrorContainer = t.danger,
        outline = t.hairlineStrong,
        outlineVariant = t.hairline,
        scrim = t.ink,
        surfaceBright = t.raised,
        surfaceDim = t.sunken,
        surfaceContainer = t.raised,
        surfaceContainerHigh = t.raised,
        surfaceContainerHighest = t.raised,
        surfaceContainerLow = t.sunken,
        surfaceContainerLowest = t.sunken,
    )
}
