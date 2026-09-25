package ai.ciris.mobile.shared.ui.shell

import ai.ciris.mobile.shared.ui.nav.LocalInsideShell
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Where a hosted screen's title goes.
 *
 * The shell knows which CARD is open; only the screen knows which STEP of
 * itself is showing ("Preview", "Security Report", "Step 2 — fetch"), and
 * several screens live under a surface that is not their own name at all —
 * every federation leaf is filed under the Global Commons hub, a chat under
 * Contacts, and the Add Federation ID flow under nothing. Titling from the
 * card was therefore wrong exactly where the name mattered most. The screen
 * keeps authoring its title; the shell draws it, once.
 */
val LocalShellCardTitle = staticCompositionLocalOf<MutableState<(@Composable () -> Unit)?>?> { null }

/** The shell's holder for whatever title the hosted screen publishes. */
@Composable
fun rememberShellCardTitleSlot(): MutableState<(@Composable () -> Unit)?> =
    remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

/**
 * A screen's own top bar — drawn in full only where the screen is the whole
 * window.
 *
 * ONE TOP BAR. Inside the circles shell the frame already carries the circle,
 * its governance line, Stop everything, the tab strip, the card's name and the
 * one back arrow. A screen that draws a second bar in there repeats the title,
 * offers a second back with different behaviour, and pushes the content down by
 * 64dp for nothing — which is what "Settings has two top bars" looked like.
 *
 * So inside the shell this renders what the shell CANNOT know:
 *
 *  - [actions] — refresh, add, filter, the node switcher: the screen's own
 *    verbs, carrying test tags the platform gates drive. They take the shell's
 *    ink as their content colour, because the primary-filled Material bar they
 *    were coloured against is not there any more.
 *  - [flowNavigationIcon] — a step back INSIDE this screen (Preview → Editor,
 *    fetch → peer picker). The shell's arrow leaves the screen altogether, so
 *    it cannot stand in for this one; a screen with such a step says so here
 *    and the control always renders.
 *  - [title] — published to the shell's card header, so the step's own name is
 *    what a person reads.
 *
 * [navigationIcon] is the screen's TOP-LEVEL back, and that one the shell owns.
 * It is dropped here, because two arrows meaning different things in the same
 * corner is the confusion this frame exists to end.
 *
 * Outside the shell — the pre-login flows, a full-window screen — this is
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
    flowNavigationIcon: (@Composable () -> Unit)? = null,
) {
    if (LocalInsideShell.current) {
        val slot = LocalShellCardTitle.current
        if (slot != null) {
            DisposableEffect(title) {
                slot.value = title
                onDispose { slot.value = null }
            }
        }
        CompositionLocalProvider(LocalContentColor provides CirisTheme.tokens.ink) {
            Row(
                modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                flowNavigationIcon?.invoke()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        return
    }
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = { flowNavigationIcon?.invoke() ?: navigationIcon() },
        actions = actions,
        colors = colors,
    )
}
