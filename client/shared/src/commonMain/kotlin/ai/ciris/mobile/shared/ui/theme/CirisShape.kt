package ai.ciris.mobile.shared.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * SHAPE. Radius 5–6 (inputs 5, cards 6, sheets 8). No shadows anywhere, in
 * either ground — hairlines only. Nothing in the product passes an elevation.
 */
object CirisShape {
    val inputRadius = 5.dp
    val cardRadius = 6.dp
    val sheetRadius = 8.dp
    val hairlineWidth = 1.dp

    val input = RoundedCornerShape(inputRadius)
    val card = RoundedCornerShape(cardRadius)
    val chip = RoundedCornerShape(inputRadius)
    val sheet = RoundedCornerShape(topStart = sheetRadius, topEnd = sheetRadius)
}
