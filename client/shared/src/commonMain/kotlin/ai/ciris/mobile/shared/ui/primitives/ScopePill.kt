package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.nav.CohortScope
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

/**
 * PRIMITIVE 5 · ScopePill — circle colour + name. THE ONLY PLACE SCOPE IS
 * RENDERED. Nothing else reads `tokens.circle(...)`.
 */
@Composable
fun ScopePill(scope: CohortScope, modifier: Modifier = Modifier, tag: String? = null) {
    val t = CirisTheme.tokens
    val colour = t.circle(scope)
    val name = circleName(scope)
    val tagged = if (tag != null) modifier.testable(tag, name) else modifier
    Row(
        modifier = tagged
            .clip(CirisShape.chip)
            .background(colour.copy(alpha = 0.10f).compositeOver(t.raised))
            .border(CirisShape.hairlineWidth, colour.copy(alpha = 0.35f).compositeOver(t.raised), CirisShape.chip)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
        Text(name, style = CirisTheme.type.label, color = colour)
    }
}

/** The circle's plain name: Just me · Family · Neighbours · Communities and Businesses · Everyone. */
@Composable
fun circleName(scope: CohortScope): String = when (scope) {
    CohortScope.AGENT -> localizedString("nav.circle.agent")
    CohortScope.FAMILY -> localizedString("nav.circle.family")
    CohortScope.LOCAL_COMMUNITY -> localizedString("nav.circle.local_community")
    CohortScope.GLOBAL_COMMUNITIES -> localizedString("nav.circle.global_communities")
    CohortScope.GLOBAL_COMMONS -> localizedString("nav.circle.global_commons")
}
