package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.util.QrEncoder
import ai.ciris.mobile.shared.platform.util.QrMatrix
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.PaperTokens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.min

/**
 * PRIMITIVE · QrCode — a REAL, scannable QR code of [value]: ISO/IEC 18004,
 * error-correction level M, encoded by `platform/util/QrEncoder` in common
 * code, so it is the same symbol on every target.
 *
 * It replaced a Canvas that drew finder patterns around a hash of the text: a
 * picture that LOOKED scannable and was not, which is a false claim on screen.
 * The text beside a QR stays the load-bearing fallback; this makes the QR true.
 *
 * COLOUR. Dark modules on a light quiet zone in BOTH grounds. A scanner needs
 * dark-on-light contrast and many readers do not try the inverse, so this is
 * the one place a surface does not re-resolve with the ground: [qrColours]
 * always takes Paper's `ink` on Paper's `raised`. Still tokens — just a fixed
 * ground's tokens — so the colour-literal lint stays at zero here. On the dark
 * ground a hairline from the CURRENT ground frames the light square.
 *
 * QUIET ZONE. Four modules of light margin on every side (§6.3.8) are part of
 * the symbol, not padding a caller may trim: [QrGeometry] draws them inside
 * the component's own bounds.
 *
 * Text too long for a version-40 symbol is SAID, not truncated into a code
 * that scans to something else.
 *
 * `/tree` reports [tag] with the encoded [value] as its text, so a driver can
 * read what the code says without a camera.
 */
@Composable
fun QrCode(
    value: String,
    contentDescription: String,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(value) { QrEncoder.encodeOrNull(value, QrEncoder.Ecc.M) }
    if (matrix == null) {
        Text(
            localizedString("mobile.qr_too_long"),
            style = CirisTheme.type.body,
            color = CirisTheme.tokens.dim,
            modifier = modifier.padding(8.dp).testable("${tag}_too_long"),
        )
        return
    }
    val (light, dark) = qrColours()
    Canvas(
        modifier = modifier
            .defaultMinSize(minWidth = QR_DEFAULT_SIDE, minHeight = QR_DEFAULT_SIDE)
            .clip(CirisShape.input)
            .border(CirisShape.hairlineWidth, CirisTheme.tokens.hairlineStrong, CirisShape.input)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            }
            .testable(tag, value),
    ) {
        val g = QrGeometry.fit(min(size.width, size.height), matrix.size)
        val originX = floor((size.width - g.side) / 2f)
        val originY = floor((size.height - g.side) / 2f)
        drawRect(light, Offset(originX, originY), Size(g.side, g.side))
        drawModules(matrix, dark, originX + g.inset, originY + g.inset, g.cell)
    }
}

private val QR_DEFAULT_SIDE = 176.dp

/** The fixed pair a scanner can read in either ground: (light, dark). Paper's tokens, by name. */
fun qrColours(): Pair<Color, Color> = PaperTokens.raised to PaperTokens.ink

/**
 * Where the symbol lands in a square of [available] pixels. Whole-pixel
 * modules when there is room for at least one pixel each — a module that
 * straddles pixels antialiases into grey and costs contrast — and the
 * leftover goes to the margin, never below the four-module quiet zone.
 */
data class QrGeometry(val side: Float, val cell: Float, val inset: Float) {
    companion object {
        const val QUIET_ZONE_MODULES = 4

        fun fit(available: Float, modules: Int): QrGeometry {
            val span = modules + QUIET_ZONE_MODULES * 2
            val raw = available / span
            val cell = if (raw >= 1f) floor(raw) else raw
            val margin = (available - cell * modules) / 2f
            return QrGeometry(side = available, cell = cell, inset = if (raw >= 1f) floor(margin) else margin)
        }
    }
}

/** One rect per horizontal run of dark modules, not one per module. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawModules(
    m: QrMatrix,
    dark: Color,
    left: Float,
    top: Float,
    cell: Float,
) {
    for (y in 0 until m.size) {
        var x = 0
        while (x < m.size) {
            if (!m[x, y]) { x++; continue }
            val start = x
            while (x < m.size && m[x, y]) x++
            drawRect(dark, Offset(left + start * cell, top + y * cell), Size((x - start) * cell, cell))
        }
    }
}
