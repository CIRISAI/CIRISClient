package ai.ciris.mobile.shared.ui.shell

import ai.ciris.mobile.shared.ui.nav.LocalInsideShell
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A screen's own top bar — drawn only where the screen is the whole window.
 *
 * ONE TOP BAR. Inside the circles shell the frame already carries the circle,
 * its governance line, Stop everything, the tab strip, the card's name and the
 * one back arrow. A screen that draws a second bar in there repeats the title,
 * offers a second back with different behaviour, and pushes the content down by
 * 64dp for nothing — which is what "Settings has two top bars" looked like.
 *
 * So inside the shell this renders the screen's ACTIONS and nothing else. The
 * actions are not chrome: refresh, add, filter, the node switcher are the
 * screen's own verbs, they carry test tags the platform gates drive, and the
 * shell has no way to know them. With no actions the row has no height, because
 * its only padding is horizontal.
 *
 * Outside the shell — the pre-login flows, a full-window screen — it is
 * Material's `TopAppBar`, unchanged, with the same arguments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
) {
    if (LocalInsideShell.current) {
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
        return
    }
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = colors,
    )
}
