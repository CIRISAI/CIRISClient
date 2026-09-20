package ai.ciris.mobile.shared.ui.glyphs

import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A glyph from the 71-glyph set, drawn from the generated path table.
 *
 * Drawn on a Canvas rather than through an ImageVector because four of the
 * glyphs are dashed (file-gone, share-out, pull-in, blurred) and a vector
 * cannot dash. The transform scales the 22-unit viewbox to [size], so the
 * 1.75 stroke and the dash intervals scale with it — the same drawing at
 * 16dp and 44dp, as the design's SVGs do.
 *
 * Colour is [tint], a token. There is no default other than ink; a glyph never
 * chooses its own colour.
 */
@Composable
fun Glyph(
    name: GlyphName,
    modifier: Modifier = Modifier,
    tint: Color = CirisTheme.tokens.ink,
    size: Dp = 22.dp,
    contentDescription: String? = null,
) {
    val paths = remember(name) { parsedPaths(name) }
    val semantics = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription; role = Role.Image }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.size(size).then(semantics)) {
        val s = this.size.minDimension / GLYPH_VIEWBOX
        scale(scale = s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            for ((seg, path) in paths) {
                if (seg.fill) drawPath(path, color = tint, style = Fill)
                if (seg.stroke) {
                    drawPath(
                        path,
                        color = tint,
                        style = Stroke(
                            width = GLYPH_STROKE,
                            cap = StrokeCap.Butt,
                            join = StrokeJoin.Miter,
                            pathEffect = seg.dash?.let { PathEffect.dashPathEffect(it) },
                        ),
                    )
                }
            }
        }
    }
}

private val parsedCache = HashMap<GlyphName, List<Pair<GlyphSeg, Path>>>()

private fun parsedPaths(name: GlyphName): List<Pair<GlyphSeg, Path>> =
    parsedCache.getOrPut(name) {
        (GLYPH_SEGMENTS[name] ?: emptyList()).map { seg ->
            seg to PathParser().parsePathString(seg.d).toPath()
        }
    }

/**
 * The same glyph as an [ImageVector], for call sites that take one
 * (`NavSurface.icon`, Material `Icon`). Dashes render solid here — prefer
 * [Glyph] wherever the call site is ours. [paint] is the colour baked into the
 * vector; `Icon(tint = …)` overrides it, so pass a token and never a literal.
 */
fun glyphVector(name: GlyphName, paint: Color): ImageVector {
    val builder = ImageVector.Builder(
        name = name.id,
        defaultWidth = 22.dp,
        defaultHeight = 22.dp,
        viewportWidth = GLYPH_VIEWBOX,
        viewportHeight = GLYPH_VIEWBOX,
    )
    for (seg in GLYPH_SEGMENTS[name] ?: emptyList()) {
        builder.addPath(
            pathData = PathParser().parsePathString(seg.d).toNodes(),
            fill = if (seg.fill) SolidColor(paint) else null,
            stroke = if (seg.stroke) SolidColor(paint) else null,
            strokeLineWidth = GLYPH_STROKE,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }
    return builder.build()
}
