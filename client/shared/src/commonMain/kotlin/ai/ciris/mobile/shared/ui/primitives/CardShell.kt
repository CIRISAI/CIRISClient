package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * PRIMITIVE 1 · CardShell — one container, hairline, 6dp radius, optional
 * top accent. NEVER NESTS.
 *
 * "Nested cards become one shell plus ruled field rows" is most of wave 2, and
 * a card that still nests a container is wrong on sight. So nesting is
 * refused, not discouraged: in test mode (every gate, every QA leg) a nested
 * shell throws; in production it logs and renders, because a person's screen
 * must not crash over a layout rule.
 */
val LocalInsideCardShell = staticCompositionLocalOf { false }

@Composable
fun CardShell(
    modifier: Modifier = Modifier,
    tag: String? = null,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalInsideCardShell.current) {
        val msg = "CardShell never nests (inner tag=$tag)"
        if (TestAutomation.isEnabled()) error(msg) else PlatformLogger.e("CardShell", msg)
    }
    val t = CirisTheme.tokens
    val tagged = if (tag != null) modifier.testable(tag) else modifier
    Column(
        modifier = tagged
            .fillMaxWidth()
            .clip(CirisShape.card)
            .background(t.raised)
            .border(CirisShape.hairlineWidth, t.hairline, CirisShape.card),
    ) {
        if (accent != null) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(accent))
        }
        CompositionLocalProvider(LocalInsideCardShell provides true) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), content = content)
        }
    }
}
