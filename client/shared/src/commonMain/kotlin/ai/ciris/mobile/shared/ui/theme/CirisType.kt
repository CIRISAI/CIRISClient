package ai.ciris.mobile.shared.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * THE TYPE SCALE. Five steps, from the design handoff ("Type"):
 *
 *     display 30/600/-0.025em · title 19/600/-0.012em · body 14/1.55
 *     label 11 mono uppercase 0.06em · signed 13 mono
 *
 * Monospace is not decoration — it marks everything signed. A key id, a
 * dimension, a protocol field name under its plain label: mono. Prose: sans.
 *
 * Faces: the design specifies Geist and Geist Mono. Wave 0 ships the SCALE on
 * the platform sans / mono behind the two family slots below; the faces land
 * once the compose-resources font path is proven on the iOS leg (prebuilt
 * xcframework + manual embed script), so the slots are the only thing that
 * changes then.
 *
 * Compose has no `text-transform`; `label` callers uppercase at the call site.
 */
@Immutable
data class CirisType(
    val sansFamily: FontFamily,
    val monoFamily: FontFamily,
) {
    val display: TextStyle = TextStyle(
        fontFamily = sansFamily, fontSize = 30.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.025 * 30).sp,
    )
    val title: TextStyle = TextStyle(
        fontFamily = sansFamily, fontSize = 19.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.012 * 19).sp,
    )
    val body: TextStyle = TextStyle(
        fontFamily = sansFamily, fontSize = 14.sp, lineHeight = (14 * 1.55).sp,
        fontWeight = FontWeight.Normal,
    )
    /** 11 mono, uppercase at the call site, 0.06em tracking. */
    val label: TextStyle = TextStyle(
        fontFamily = monoFamily, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Medium, letterSpacing = (0.06 * 11).sp,
    )
    /** Anything that came off the wire signed: key ids, dimensions, hashes. */
    val signed: TextStyle = TextStyle(
        fontFamily = monoFamily, fontSize = 13.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    )

    companion object {
        fun default(): CirisType = CirisType(
            sansFamily = FontFamily.SansSerif,
            monoFamily = FontFamily.Monospace,
        )
    }
}
